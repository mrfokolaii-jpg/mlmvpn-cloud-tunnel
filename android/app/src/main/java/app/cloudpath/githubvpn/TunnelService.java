package app.cloudpath.githubvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import hev.htproxy.TProxyService;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/** Foreground Android VPN; the GitHub runner is renewed on a best-effort basis, not unlimited. */
public final class TunnelService extends VpnService {
    static final String ACTION_START = "app.cloudpath.githubvpn.START";
    static final String ACTION_STOP = "app.cloudpath.githubvpn.STOP";
    static final String ACTION_STATUS = "app.cloudpath.githubvpn.STATUS";
    private static final String CHANNEL = "vpn";
    private static final int NOTIFICATION = 17;
    private static final long BOOT_TIMEOUT_MS = 7 * 60_000L;
    private static final long RENEW_BEFORE_MS = 12 * 60_000L;
    private static final long CANCEL_BUDGET_MS = 45_000L;
    private final Object tunLock = new Object();
    private volatile boolean desired;
    private volatile Thread worker;
    // True while this process has a session or stop worker that is expected to keep writing
    // status. The UI uses it to tell a live session from a phase left behind by a dead process.
    private static volatile boolean working;
    static boolean isWorking() { return working; }
    private ParcelFileDescriptor tun;
    private boolean tunStarted;
    private XrayEngine engine;
    private Settings settings;
    private RunStore runs;

    private static final class Session {
        final String login, repo, branch, sid, country;
        final GithubApi api;
        final SessionCrypto crypto;
        JSONObject payload;
        Session(String login, String repo, String branch, String sid, String country, GithubApi api,
                SessionCrypto crypto, JSONObject payload) {
            this.login = login; this.repo = repo; this.branch = branch; this.sid = sid;
            this.country = country; this.api = api; this.crypto = crypto; this.payload = payload;
        }
    }

    /** Only a definite 403 from the dispatch endpoint is safe to rotate away from. */
    private static final class QuotaExhaustedException extends IOException {
        QuotaExhaustedException(GithubApi.ApiException cause) { super(cause.getMessage(), cause); }
    }
    @Override public void onCreate() {
        super.onCreate();
        settings = new Settings(this); runs = new RunStore(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "اتصال VPN", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) { requestStop(); return START_NOT_STICKY; }
        boolean systemRestart = intent == null || SERVICE_INTERFACE.equals(action);
        if (!ACTION_START.equals(action) && !(systemRestart && settings.desired())) { stopSelf(); return START_NOT_STICKY; }
        if (worker != null && worker.isAlive()) return START_STICKY;
        if (VpnService.prepare(this) != null) {
            settings.setDesired(false); settings.setStatus("stopped", "اجازهٔ VPN لازم است");
            sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
            stopSelf(); return START_NOT_STICKY;
        }
        desired = true; settings.setDesired(true);
        startForeground(NOTIFICATION, notification("در حال آماده‌سازی…"));
        state("starting", "در حال بررسی GitHub…");
        worker = new Thread(this::runLoop, "vpn-session");
        working = true;
        worker.start();
        return START_STICKY;
    }
    @Override public void onRevoke() { requestStop(); super.onRevoke(); }
    @Override public void onDestroy() {
        // OS revocation or a normal stop; a process kill may skip onDestroy altogether, which is
        // why the UI reconciles a stale phase on its own as well.
        desired = false;
        working = false;
        Thread t = worker; if (t != null) t.interrupt();
        // Never leave "running"/"stopping" persisted: after this point the UI must offer Start.
        settings.setDesired(false);
        if (!"stopped".equals(settings.phase())) settings.setStatus("stopped", "اتصال قطع شد");
        try { new Thread(this::stopTun, "vpn-teardown").start(); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }
    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TunnelService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("تونل من").setContentText(text).setContentIntent(content).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "قطع اتصال", stopIntent).build()).build();
    }
    private void state(String phase, String text) {
        settings.setStatus(phase, text);
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
        try { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text)); } catch (RuntimeException ignored) { }
    }
    private void requestStop() {
        desired = false; settings.setDesired(false);
        state("stopping", "در حال قطع اتصال و پایان نشست GitHub…");
        Thread t = worker;
        if (t != null) t.interrupt();
        // Close the TUN promptly without blocking Android's main thread on native shutdown.
        new Thread(this::stopTun, "vpn-stop-local").start();
        if (t == null || !t.isAlive()) {
            if (runs.all().isEmpty()) {
                // Nothing on GitHub to cancel: finish at once so the app can be started again
                // instead of sitting in "stopping" while a network call fails.
                state("stopped", "اتصال قطع شد");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            // The process may have died while its GitHub run kept going. Stop must still try to
            // cancel tracked runs, even when Android has just recreated the service for this intent.
            startForeground(NOTIFICATION, notification("در حال پایان نشست GitHub…"));
            worker = new Thread(() -> {
                String message = "اتصال قطع شد";
                try {
                    // Bounded: a slow or filtered network must not hold the UI in "stopping".
                    // Each tracked run is cancelled with the token belonging to its owner account.
                    long deadline = System.currentTimeMillis() + CANCEL_BUDGET_MS;
                    cancelAll(deadline);
                    if (!runs.all().isEmpty()) { Thread.sleep(2000); cancelAll(deadline); }
                } catch (Exception ignored) { }
                if (!runs.all().isEmpty()) message += "؛ پایان نشست GitHub هنوز تأیید نشده است";
                state("stopped", message);
                working = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }, "vpn-cancel-orphaned-runs");
            working = true;
            worker.start();
        }
    }
    private void checkDesired() throws InterruptedException {
        if (!desired || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    private static void delay(long millis) throws InterruptedException { Thread.sleep(millis); }
    private static String sid() {
        byte[] bytes = new byte[18]; new SecureRandom().nextBytes(bytes); return SessionCrypto.b64(bytes);
    }
    private String dnsServer() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network active = cm.getActiveNetwork();
            LinkProperties properties = active == null ? null : cm.getLinkProperties(active);
            if (properties != null) for (InetAddress address : properties.getDnsServers()) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isAnyLocalAddress())
                    return address.getHostAddress() + ":53";
            }
        } catch (Exception ignored) { }
        return "1.1.1.1:53";
    }
    private void startTun() throws Exception {
        synchronized (tunLock) {
            checkDesired();
            Builder b = new Builder().setSession("تونل من").setMtu(1500)
                    .addAddress("172.31.255.2", 30).addRoute("0.0.0.0", 0)
                    .addAddress("fd12:3456:789a::2", 126).addRoute("::", 0)
                    .addDnsServer("1.1.1.1").addDisallowedApplication(getPackageName());
            // Keep Android's default metering behaviour; a mobile-data VPN is not necessarily unmetered.
            tun = b.establish();
            if (tun == null) throw new IOException("اندروید اجازهٔ ساخت رابط VPN را نداد");
            String yaml = "tunnel:\n  mtu: 1500\n  ipv4: 172.31.255.2\n  ipv6: 'fd12:3456:789a::2'\n" +
                    "socks5:\n  port: " + engine.socksPort + "\n  address: 127.0.0.1\n  udp: 'udp'\n" +
                    "  username: '" + engine.socksUser + "'\n  password: '" + engine.socksPass + "'\n" +
                    "misc:\n  log-level: error\n";
            File f = new File(getFilesDir(), "tunnel.yaml");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(yaml.getBytes(StandardCharsets.UTF_8)); }
            if (!TProxyService.TProxyStartService(f.getAbsolutePath(), tun.getFd())) throw new IOException("موتور شبکه شروع نشد");
            tunStarted = true;
            // Native startup is asynchronous; do not claim connected if it immediately fails.
            delay(300);
            if (!TProxyService.TProxyIsRunning()) throw new IOException("موتور شبکه متوقف شد");
            checkDesired();
        }
    }
    private void stopTun() {
        synchronized (tunLock) {
            if (tunStarted) { try { TProxyService.TProxyStopService(); } catch (Throwable ignored) { } tunStarted = false; }
            if (tun != null) { try { tun.close(); } catch (IOException ignored) { } tun = null; }
            new File(getFilesDir(), "tunnel.yaml").delete();
        }
    }
    private void cancelRecord(RunStore.Record record) {
        try {
            GithubApi api = new TokenVault(this, record.login).api();
            if (api.cancelIfPresent(record.repo, record.sid)) runs.remove(record.sid);
        } catch (Exception ignored) { /* retry on next launch; do not silently forget the run */ }
    }
    private void cancelAll() { cancelAll(Long.MAX_VALUE); }
    private void cancelAll(long deadline) {
        for (RunStore.Record r : runs.all()) {
            if (System.currentTimeMillis() >= deadline) return;
            cancelRecord(r);
        }
    }
    private void cancelSid(String sid) {
        for (RunStore.Record r : runs.all()) if (r.sid.equals(sid)) { cancelRecord(r); break; }
    }

    /** Use the selected account first, then other healthy accounts; switch only for explicit auth/quota failures. */
    private Session prepareFromAccountPool(String country, boolean renewing) throws Exception {
        List<String> accounts = settings.accountLogins();
        String selected = settings.login();
        if (!selected.isEmpty()) {
            accounts.removeIf(value -> value.equalsIgnoreCase(selected));
            accounts.add(0, selected);
        }
        int quotaFailures = 0, authFailures = 0;
        boolean configured = false;
        for (String login : accounts) {
            checkDesired();
            String health = settings.accountHealth(login);
            if ("EXHAUSTED".equals(health)) { quotaFailures++; continue; }
            if ("AUTH_REQUIRED".equals(health)) { authFailures++; continue; }
            String repo = settings.repoFor(login);
            if (!SessionRules.validRepo(repo)) continue;
            configured = true;
            TokenVault vault = new TokenVault(this, login);
            String token;
            try { token = vault.load(); }
            catch (Exception e) { throw new IOException("ورود ذخیره‌شدهٔ @" + login + " قابل خواندن نیست؛ دوباره وارد شو", e); }
            if (token.isEmpty()) { settings.setAccountHealth(login, "AUTH_REQUIRED"); authFailures++; continue; }
            GithubApi api = vault.api();
            String branch;
            try {
                branch = api.defaultBranch(repo);
                api.ensureWorkflow(repo);
            } catch (GithubApi.ApiException e) {
                if (e.status == 401) {
                    settings.setAccountHealth(login, "AUTH_REQUIRED");
                    throw new IOException("ورود حساب @" + login + " نیاز به تأیید دوباره دارد؛ دوباره وارد GitHub شو", e);
                }
                throw e;
            }
            Session session;
            try { session = prepareSession(api, login, repo, branch, country, renewing); }
            catch (QuotaExhaustedException exhausted) {
                settings.setAccountHealth(login, "EXHAUSTED"); quotaFailures++;
                state(renewing ? "running" : "starting", "سهمیهٔ @" + login + " تمام شده؛ بررسی حساب بعدی…");
                continue;
            } catch (GithubApi.ApiException e) {
                if (e.status == 401) {
                    settings.setAccountHealth(login, "AUTH_REQUIRED");
                    throw new IOException("ورود حساب @" + login + " نیاز به تأیید دوباره دارد؛ نشست پیگیری می‌شود", e);
                }
                throw e;
            }
            settings.setAccountHealth(login, "OK");
            settings.activateAccount(login);
            return session;
        }
        if (quotaFailures > 0)
            throw new IOException("سهمیهٔ Actions حساب‌های آماده تمام شده است؛ حساب دیگری اضافه کن یا پس از بازنشانی سهمیه دوباره امتحان کن");
        if (authFailures > 0) throw new IOException("ورود همهٔ حساب‌های آماده نیاز به تأیید دوباره دارد؛ دوباره وارد GitHub شو");
        if (!configured) throw new IOException("برای هیچ حسابی مخزن خصوصی و workflow آماده نیست؛ ابتدا حساب و مخزن را تنظیم کن");
        throw new IOException("حساب GitHub آماده‌ای برای شروع پیدا نشد");
    }

    private Session prepareSession(GithubApi api, String login, String repo, String branch, String country, boolean renewing) throws Exception {
        checkDesired();
        String id = sid(); SessionCrypto crypto = new SessionCrypto();
        runs.add(login, repo, id); // persist owner before dispatch, including an ambiguous network failure
        try { api.dispatch(repo, branch, id, crypto.publicKey(), country); }
        catch (GithubApi.ApiException e) {
            // A definite 4xx rejection means GitHub did not create a run. Network timeouts are
            // ambiguous and must remain tracked so a later Stop can still find the run.
            if (e.status >= 400 && e.status < 500) runs.remove(id);
            if (e.quotaExhausted()) throw new QuotaExhaustedException(e);
            throw e;
        }
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        long nextRunCheck = 0;
        int transientFailures = 0;
        while (System.currentTimeMillis() < deadline) {
            checkDesired();
            try {
                JSONObject payload = api.session(repo, id, crypto);
                transientFailures = 0;
                if (payload != null) {
                    if ("failed".equals(payload.optString("phase"))) throw new IOException("ساخت نشست روی GitHub ناموفق بود");
                    String err = SessionRules.countryError(payload, country);
                    if (!err.isEmpty()) throw new IOException("خروجی این کشور آماده نشد: " + err);
                    if ("ready".equals(payload.optString("phase")) && SessionRules.websocketPath(payload) != null &&
                            SessionRules.selectedUuid(payload, country) != null && !SessionRules.endpoints(payload).isEmpty()) {
                        if (payload.optLong("endsAt", 0) <= System.currentTimeMillis() + 60_000) throw new IOException("نشست بسیار نزدیک به پایان است");
                        return new Session(login, repo, branch, id, country, api, crypto, payload);
                    }
                }
                if (System.currentTimeMillis() >= nextRunCheck) {
                    nextRunCheck = System.currentTimeMillis() + 30_000;
                    JSONObject run = api.findRun(repo, id);
                    if (run != null && "completed".equals(run.optString("status")))
                        throw new IOException("اجرای GitHub پیش از آماده‌شدن تونل پایان یافت؛ سهمیه و Actions را بررسی کن");
                }
            } catch (GithubApi.ApiException e) { throw e; }
            catch (IOException e) {
                // Explicit server/country errors are fatal; transient network errors get a short retry.
                if (e.getMessage() != null && (e.getMessage().startsWith("خروجی") || e.getMessage().startsWith("ساخت") ||
                        e.getMessage().startsWith("اجرای") || e.getMessage().startsWith("نشست بسیار") || e.getMessage().startsWith("رمزگشایی"))) throw e;
                if (++transientFailures >= 4) throw e;
            }
            state(renewing ? "running" : "starting", renewing ? "در حال آماده‌سازی نشست بعدی…" :
                    (SessionRules.DIRECT.equals(country) ? "در حال ساخت نشست GitHub…" : "در حال آماده‌سازی کشور انتخاب‌شده…"));
            delay(8000);
        }
        throw new IOException("نشست آماده نشد؛ سهمیه، صف Actions و دسترسی شبکه را بررسی کن");
    }
    private void runLoop() {
        String finalMessage = "اتصال قطع شد";
        try {
            String country = settings.country();
            if (!SessionRules.validCountry(country)) throw new IOException("کشور معتبر نیست");
            // Reconcile previous runs with their own account tokens before spending another quota.
            long oldRunsDeadline = System.currentTimeMillis() + CANCEL_BUDGET_MS;
            cancelAll(oldRunsDeadline);
            if (!runs.all().isEmpty()) throw new IOException("پایان نشست‌های قبلی هنوز تأیید نشده؛ آن‌ها را بررسی کن و دوباره تلاش کن");
            checkDesired();
            Session active = prepareFromAccountPool(country, false);
            checkDesired();
            engine = new XrayEngine(this);
            engine.initialize(dnsServer());
            engine.activate(active.payload, country, active.sid, active.crypto);
            checkDesired(); startTun();
            state("running", SessionRules.DIRECT.equals(country) ? "متصل · خروجی سرور · @" + active.login : "متصل · " + country + " · @" + active.login);
            int misses = 0;
            while (desired) {
                delay(30_000); checkDesired();
                try {
                    if (!TProxyService.TProxyIsRunning()) throw new IOException("رابط VPN متوقف شد");
                    JSONObject live = engine.probe(active.sid, active.crypto);
                    if (!"ready".equals(live.optString("phase"))) throw new IOException("سرور آماده نیست");
                    active.payload = live;
                    if (SessionRules.selectedUuid(live, country) == null) {
                        engine.stopCore(); // keep the TUN, do not fall back to another country
                        state("running", "خروجی کشور موقتاً آماده نیست؛ تلاش دوباره…");
                        misses = 2;
                    } else { misses = 0; }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) throw e;
                    if (++misses >= 2) {
                        state("running", "در حال بازیابی مسیر ارتباطی…");
                        try {
                            JSONObject fresh = active.api.session(active.repo, active.sid, active.crypto);
                            checkDesired();
                            if (fresh != null) { engine.activate(fresh, country, active.sid, active.crypto); active.payload = fresh; misses = 0; }
                        } catch (InterruptedException interrupted) { throw interrupted; }
                        catch (Exception ignored) { engine.stopCore(); }
                    }
                }
                long endsAt = active.payload.optLong("endsAt", 0);
                if (endsAt <= 0 || System.currentTimeMillis() >= endsAt) throw new IOException("نشست GitHub پایان یافت و جایگزین آماده نشد");
                if (active.payload.optBoolean("ending") || System.currentTimeMillis() >= endsAt - RENEW_BEFORE_MS) {
                    Session old = active;
                    try {
                        Session replacement = prepareFromAccountPool(country, true);
                        checkDesired();
                        try { engine.activate(replacement.payload, country, replacement.sid, replacement.crypto); }
                        catch (Exception failure) {
                            // Restore the previous session if switching failed; no direct fallback.
                            try { engine.activate(old.payload, country, old.sid, old.crypto); } catch (Exception ignored) { }
                            throw failure;
                        }
                        active = replacement;
                        cancelSid(old.sid);
                        state("running", SessionRules.DIRECT.equals(country) ? "متصل · خروجی سرور · @" + active.login : "متصل · " + country + " · @" + active.login);
                    } catch (InterruptedException e) { throw e; }
                    catch (Exception e) {
                        // Cancel unsuccessful replacement(s) with their owners' tokens; retain the old runner.
                        for (RunStore.Record r : runs.all()) if (!r.sid.equals(old.sid)) cancelRecord(r);
                        state("running", "تمدید ناموفق بود؛ بررسی سهمیه و تلاش دوباره…");
                        delay(60_000);
                    }
                }
            }
        } catch (InterruptedException e) {
            // Stop requested or Android is reclaiming the service.
        } catch (Throwable e) {
            String text = e.getMessage();
            finalMessage = text == null || text.isEmpty() ? "اتصال برقرار نشد" : text;
        } finally {
            desired = false; settings.setDesired(false);
            stopTun();
            if (engine != null) { try { engine.close(); } catch (Throwable ignored) { } engine = null; }
            // Clear the interrupt so best-effort cancellation can actually do network I/O.
            Thread.interrupted();
            long deadline = System.currentTimeMillis() + CANCEL_BUDGET_MS;
            cancelAll(deadline);
            if (!runs.all().isEmpty()) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                cancelAll(deadline);
            }
            if (!runs.all().isEmpty()) finalMessage += "؛ پایان نشست GitHub هنوز تأیید نشده است";
            state("stopped", finalMessage);
            working = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
}
