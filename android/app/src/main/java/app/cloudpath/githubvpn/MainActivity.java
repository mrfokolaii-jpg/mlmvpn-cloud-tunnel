package app.cloudpath.githubvpn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native, Persian-first UI. GitHub login is always opened in the user's browser. */
public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION = 71;
    private static final int BG = Color.rgb(16, 16, 26), CARD = Color.rgb(30, 29, 43), BORDER = Color.rgb(65, 63, 83);
    private static final int TEXT = Color.rgb(246, 244, 255), MUTED = Color.rgb(167, 164, 186), PURPLE = Color.rgb(137, 120, 255), GREEN = Color.rgb(119, 227, 177);
    private Settings settings;
    private TokenVault vault;
    private RunStore runs;
    private boolean receiverRegistered;
    private final AtomicBoolean signingIn = new AtomicBoolean(false);
    private AlertDialog loginDialog;
    private ScrollView page;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this); vault = new TokenVault(this); runs = new RunStore(this);
        render();
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TunnelService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
        render();
    }
    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false; }
        super.onStop();
    }
    @Override protected void onDestroy() { signingIn.set(false); super.onDestroy(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); t.setLineSpacing(dp(3), 1f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private void addGap(LinearLayout root, int height) { View v = new View(this); root.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private TextView pill(String label, int color) {
        TextView t = text(label, 12, color, true); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(7), dp(12), dp(7)); t.setBackground(shape(Color.rgb(36, 35, 52), 40, 0)); return t;
    }
    private LinearLayout card() {
        LinearLayout c = vertical(); c.setPadding(dp(18), dp(17), dp(18), dp(17));
        c.setBackground(shape(CARD, 20, BORDER)); return c;
    }
    private TextView action(String title) {
        TextView t = text(title, 14, PURPLE, true); t.setPadding(0, dp(10), 0, dp(2));
        t.setFocusable(true); t.setClickable(true); return t;
    }
    private boolean active() { return !"stopped".equals(settings.phase()); }

    private void render() {
        if (isFinishing()) return;
        final int previousScrollY = page == null ? 0 : page.getScrollY();
        boolean running = active();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(23, 20, 37), BG, Color.rgb(12, 12, 20)});
        scroll.setBackground(backdrop);
        LinearLayout root = vertical(); root.setPadding(dp(22), dp(22), dp(22), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); scroll.addView(root);
        TextView overline = text("CLOUDPATH  /  GITHUB", 11, PURPLE, true); overline.setLetterSpacing(.12f); root.addView(overline);
        addGap(root, 7);
        root.addView(text("تونل من", 29, TEXT, true));
        addGap(root, 4);
        root.addView(text("اتصال آزمایشی با حساب گیت‌هاب", 14, MUTED, false));
        addGap(root, 24);

        FrameLayout outer = new FrameLayout(this);
        GradientDrawable outerShape = new GradientDrawable(); outerShape.setShape(GradientDrawable.OVAL);
        outerShape.setColor(Color.rgb(34, 29, 60)); outerShape.setStroke(dp(1), Color.rgb(73, 64, 119)); outer.setBackground(outerShape);
        LinearLayout dial = vertical(); dial.setGravity(Gravity.CENTER);
        GradientDrawable dialShape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                running ? new int[]{Color.rgb(91, 77, 190), Color.rgb(66, 54, 141)} : new int[]{Color.rgb(119, 103, 246), Color.rgb(76, 61, 172)});
        dialShape.setShape(GradientDrawable.OVAL); dial.setBackground(dialShape);
        TextView power = text("⏻", 40, Color.WHITE, false); power.setGravity(Gravity.CENTER); dial.addView(power);
        TextView dialLabel = text(running ? ("stopping".equals(settings.phase()) ? "در حال قطع" : "قطع") : "شروع", 19, Color.WHITE, true);
        dialLabel.setGravity(Gravity.CENTER); dial.addView(dialLabel);
        FrameLayout.LayoutParams dialParams = new FrameLayout.LayoutParams(dp(154), dp(154), Gravity.CENTER); outer.addView(dial, dialParams);
        LinearLayout.LayoutParams outerParams = new LinearLayout.LayoutParams(dp(190), dp(190)); outerParams.gravity = Gravity.CENTER; root.addView(outer, outerParams);
        outer.setContentDescription(running ? "قطع اتصال" : "شروع اتصال"); outer.setClickable(true); outer.setFocusable(true);
        outer.setOnClickListener(v -> { if (running) stopVpn(); else startVpn(); });
        addGap(root, 20);
        TextView statusTitle = text("running".equals(settings.phase()) ? "وضعیت اتصال" : "وضعیت", 13, MUTED, false); statusTitle.setGravity(Gravity.CENTER); root.addView(statusTitle);
        TextView status = text(settings.status(), 17, TEXT, true); status.setGravity(Gravity.CENTER); status.setPadding(0, dp(5), 0, dp(2)); root.addView(status);
        addGap(root, 16);
        LinearLayout chips = new LinearLayout(this); chips.setGravity(Gravity.CENTER); chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(pill(running ? "●  نشست فعال" : "○  آماده", running ? GREEN : MUTED));
        View gap = new View(this); chips.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        chips.addView(pill("تمدید خودکار آزمایشی", PURPLE)); root.addView(chips);
        addGap(root, 28);

        TextView section = text("مسیر خروج", 14, MUTED, true); root.addView(section); addGap(root, 9);
        LinearLayout location = card();
        location.addView(text("کشور اتصال  ‹", 17, TEXT, true)); addGap(location, 5);
        location.addView(text(countryLabel(settings.country()), 14, PURPLE, true));
        addGap(location, 6); location.addView(text("کشورها فقط در صورت وجود خروجیِ قابل‌دسترسی کار می‌کنند.", 12, MUTED, false));
        location.setOnClickListener(v -> { if (active()) toast("برای تغییر کشور، ابتدا اتصال را قطع کن"); else chooseCountry(); }); root.addView(location);
        addGap(root, 22);

        root.addView(text("حساب و مخزن", 14, MUTED, true)); addGap(root, 9);
        LinearLayout account = card();
        account.addView(text(settings.login().isEmpty() ? "به GitHub وارد نشده‌ای" : "●  @" + settings.login(), 17,
                settings.login().isEmpty() ? TEXT : GREEN, true));
        addGap(account, 6);
        account.addView(text(settings.repo().isEmpty() ? "مخزن هنوز تنظیم نشده است" : settings.repo(), 13, MUTED, false));
        if (settings.login().isEmpty()) {
            TextView login = action("ورود اصلی GitHub  ‹"); login.setOnClickListener(v -> loginSettings()); account.addView(login);
        } else {
            TextView configure = action("تنظیم مخزن و حساب  ‹"); configure.setOnClickListener(v -> accountSettings()); account.addView(configure);
        }
        root.addView(account);
        addGap(root, 22);
        LinearLayout notice = card(); notice.setBackground(shape(Color.rgb(31, 29, 42), 18, Color.rgb(73, 65, 83)));
        notice.addView(text("پیش از اتصال", 15, TEXT, true)); addGap(notice, 6);
        notice.addView(text("GitHub Actions سهمیه و سقف زمانی دارد. تمدید خودکار قطعی نیست و مصرف ممکن است هزینه داشته باشد. استفادهٔ دائمی به‌عنوان VPN می‌تواند با شرایط GitHub ناسازگار باشد.", 12, MUTED, false));
        root.addView(notice);
        addGap(root, 12);
        TextView safety = text("بعد از قطع VPN، اینترنت گوشی ممکن است مستقیم وصل شود. برای جلوگیری از نشت، گزینهٔ مسدودسازی بدون VPN را در تنظیمات اندروید فعال کن.", 11, MUTED, false);
        root.addView(safety);
        TextView licenses = action("مجوز کتابخانه‌های متن‌باز  ‹");
        licenses.setOnClickListener(v -> showLicenses());
        root.addView(licenses);
        setContentView(scroll);
        page = scroll;
        scroll.post(() -> scroll.scrollTo(0, previousScrollY));
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void showLicenses() {
        StringBuilder all = new StringBuilder();
        String[] names = {"libXray.txt", "Xray-core.txt", "hev-socks5-tunnel.txt", "BouncyCastle.md"};
        try {
            for (String name : names) {
                all.append("\n\n--- ").append(name).append(" ---\n");
                try (java.io.InputStream in = getAssets().open("licenses/" + name);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
                    all.append(bytes.toString("UTF-8"));
                }
            }
            new AlertDialog.Builder(this).setTitle("مجوز کتابخانه‌ها").setMessage(all.toString()).setPositiveButton("بستن", null).show();
        } catch (Exception e) { toast("خواندن مجوزها ناموفق بود"); }
    }
    private void startVpn() {
        if (settings.login().isEmpty()) { loginSettings(); return; }
        if (!SessionRules.validRepo(settings.repo())) { accountSettings(); return; }
        try { if (vault.load().isEmpty()) { loginSettings(); return; } }
        catch (Exception e) { toast("خواندن ورود ذخیره‌شده ناموفق بود؛ دوباره وارد شو"); return; }
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, VPN_PERMISSION); else launchVpn();
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION) {
            if (resultCode == RESULT_OK) launchVpn(); else toast("برای ایجاد VPN اجازهٔ اندروید لازم است");
        }
    }
    private void launchVpn() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 81);
        Intent start = new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
    }
    private void stopVpn() {
        if ("stopping".equals(settings.phase())) return;
        startService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_STOP));
    }
    private static String flag(String cc) {
        if (cc == null || !cc.matches("[A-Z]{2}")) return "◉";
        return new String(Character.toChars(0x1F1E6 + cc.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + cc.charAt(1) - 'A'));
    }
    private String countryLabel(String code) {
        if (SessionRules.DIRECT.equals(code)) return "◉  حداکثر سرعت · آدرس خود سرور";
        if (!SessionRules.validCountry(code)) return "انتخاب کشور";
        String name = new Locale("", code).getDisplayCountry(new Locale("fa"));
        return flag(code) + "  " + name + "  ·  " + code;
    }
    private void chooseCountry() {
        LinearLayout content = vertical(); content.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText search = new EditText(this); search.setSingleLine(); search.setHint("جست‌وجوی کشور یا کد آن"); content.addView(search);
        ListView list = new ListView(this); content.addView(list, new LinearLayout.LayoutParams(-1, dp(390)));
        List<String> codes = new ArrayList<>(); Collections.addAll(codes, Locale.getISOCountries());
        Collator collator = Collator.getInstance(new Locale("fa"));
        Collections.sort(codes, (a, b) -> collator.compare(countryLabel(a), countryLabel(b)));
        codes.add(0, SessionRules.DIRECT);
        List<String> visible = new ArrayList<>(codes), labels = new ArrayList<>();
        for (String code : visible) labels.add(countryLabel(code));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("انتخاب کشور خروجی").setView(content).setNegativeButton("انصراف", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            settings.setCountry(visible.get(position)); dialog.dismiss(); render();
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT); visible.clear(); labels.clear();
                for (String code : codes) {
                    String label = countryLabel(code);
                    if (q.isEmpty() || code.toLowerCase(Locale.ROOT).contains(q) || label.toLowerCase(Locale.ROOT).contains(q)) { visible.add(code); labels.add(label); }
                }
                adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e) { }
        });
        dialog.show();
    }
    private void loginSettings() {
        if (active()) { toast("ابتدا اتصال را قطع کن"); return; }
        LinearLayout body = vertical(); body.setPadding(dp(22), dp(8), dp(22), dp(4));
        body.addView(text("برای ورود از صفحهٔ رسمی GitHub، یک OAuth App با Device Flow بساز و فقط Client ID عمومی آن را اینجا وارد کن. گذرواژه یا Client Secret لازم نیست.", 14, TEXT, false));
        EditText id = new EditText(this); id.setSingleLine(); id.setHint("OAuth Client ID"); id.setText(settings.clientId());
        id.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); body.addView(id);
        TextView link = action("ساخت OAuth App در GitHub ↗");
        link.setOnClickListener(v -> openBrowser("https://github.com/settings/applications/new")); body.addView(link);
        body.addView(text("دسترسی repo برای دیدن مخزن خصوصی و اجرای workflow درخواست می‌شود؛ این مجوز به همهٔ مخازن خصوصی حساب دسترسی می‌دهد.", 12, MUTED, false));
        new AlertDialog.Builder(this).setTitle("ورود به GitHub").setView(body).setNegativeButton("انصراف", null)
                .setPositiveButton("ادامه", (dialog, which) -> {
                    String value = id.getText().toString().trim();
                    if (!value.matches("[A-Za-z0-9_.-]{8,100}")) { toast("Client ID معتبر وارد کن"); return; }
                    settings.setClientId(value); startDeviceLogin(value);
                }).show();
    }
    private void openBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("مرورگر پیدا نشد"); }
    }
    private void startDeviceLogin(String clientId) {
        if (!signingIn.compareAndSet(false, true)) { toast("ورود در حال انجام است"); return; }
        toast("در حال دریافت کد ورود…");
        new Thread(() -> {
            try {
                JSONObject grant = GithubApi.beginDevice(clientId);
                String deviceCode = grant.getString("device_code"), userCode = grant.getString("user_code");
                if (!userCode.matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) throw new IllegalStateException("پاسخ ورود معتبر نیست");
                int interval = Math.max(5, grant.optInt("interval", 5));
                long deadline = System.currentTimeMillis() + Math.min(900, Math.max(30, grant.optInt("expires_in", 900))) * 1000L;
                runOnUiThread(() -> {
                    if (!signingIn.get() || isFinishing()) return;
                    TextView body = text("کد زیر را فقط در github.com/login/device وارد کن:\n\n" + userCode + "\n\nاین کد را برای دیگران نفرست.", 18, TEXT, true);
                    body.setPadding(dp(22), dp(8), dp(22), dp(12)); body.setGravity(Gravity.CENTER);
                    loginDialog = new AlertDialog.Builder(this).setTitle("تأیید در GitHub").setView(body)
                            .setPositiveButton("باز کردن GitHub", null)
                            .setNegativeButton("لغو ورود", (dialog, which) -> signingIn.set(false)).create();
                    loginDialog.setOnCancelListener(dialog -> signingIn.set(false));
                    loginDialog.show();
                    // Keep the code visible when the user returns from the browser.
                    loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> openBrowser("https://github.com/login/device"));
                });
                while (signingIn.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L);
                    if (!signingIn.get()) return;
                    JSONObject answer = GithubApi.pollDevice(clientId, deviceCode);
                    if (!signingIn.get()) return;
                    String token = answer.optString("access_token", "");
                    if (!token.isEmpty()) {
                        String scope = answer.optString("scope", "");
                        if (!java.util.Arrays.asList(scope.split(",")).contains("repo")) throw new IllegalStateException("مجوز repo داده نشده است");
                        JSONObject user = new GithubApi(token).user();
                        String login = user.optString("login", "");
                        if (!login.matches("[A-Za-z0-9-]{1,39}")) throw new IllegalStateException("حساب GitHub شناسایی نشد");
                        vault.save(token); settings.setLogin(login);
                        if (settings.repo().isEmpty()) settings.setRepo(login + "/mlmvpn-cloud-tunnel");
                        signingIn.set(false);
                        runOnUiThread(() -> { if (loginDialog != null) loginDialog.dismiss(); render(); toast("حساب GitHub متصل شد"); });
                        return;
                    }
                    String error = answer.optString("error", "");
                    if ("authorization_pending".equals(error)) continue;
                    if ("slow_down".equals(error)) { interval = Math.max(interval + 5, answer.optInt("interval", interval + 5)); continue; }
                    if ("device_flow_disabled".equals(error)) throw new IllegalStateException("Device Flow در تنظیمات OAuth App فعال نیست");
                    throw new IllegalStateException("ورود تأیید نشد؛ دوباره تلاش کن");
                }
                if (signingIn.get()) throw new IllegalStateException("زمان تأیید ورود به پایان رسید");
            } catch (Exception e) {
                signingIn.set(false);
                String message = e.getMessage() == null ? "ورود ناموفق بود" : e.getMessage();
                runOnUiThread(() -> { if (loginDialog != null) loginDialog.dismiss(); toast(message); });
            }
        }, "github-device-login").start();
    }
    private void accountSettings() {
        if (active()) { toast("ابتدا اتصال را قطع کن"); return; }
        LinearLayout body = vertical(); body.setPadding(dp(22), dp(8), dp(22), dp(8));
        body.addView(text("مخزن باید شامل agent و workflow این پروژه روی شاخهٔ پیش‌فرض باشد.", 13, TEXT, false));
        EditText repo = new EditText(this); repo.setSingleLine(); repo.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        repo.setHint("owner/repository"); repo.setText(settings.repo()); body.addView(repo);
        TextView cleanup = action("بررسی و پایان نشست‌های قبلی"); cleanup.setOnClickListener(v -> cleanupRuns()); body.addView(cleanup);
        TextView logout = action("خروج از این حساب در دستگاه"); logout.setOnClickListener(v -> {
            if (!runs.all().isEmpty()) { toast("ابتدا پایان نشست‌های قبلی را بررسی کن"); return; }
            vault.clear(); settings.setLogin(""); render(); toast("اطلاعات ورود از دستگاه پاک شد");
        }); body.addView(logout);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("حساب GitHub").setView(body).setNegativeButton("بستن", null)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String value = repo.getText().toString().trim();
                    if (!SessionRules.validRepo(value)) { toast("نام مخزن باید به شکل owner/repository باشد"); return; }
                    settings.setRepo(value); render();
                }).create();
        logout.setOnClickListener(v -> {
            if (!runs.all().isEmpty()) { toast("ابتدا پایان نشست‌های قبلی را بررسی کن"); return; }
            vault.clear(); settings.setLogin(""); dialog.dismiss(); render(); toast("اطلاعات ورود از دستگاه پاک شد");
        });
        dialog.show();
    }
    private void cleanupRuns() {
        toast("در حال بررسی نشست‌های قبلی…");
        new Thread(() -> {
            try {
                GithubApi api = new GithubApi(vault.load());
                for (RunStore.Record r : runs.all()) if (api.cancelIfPresent(r.repo, r.sid)) runs.remove(r.sid);
                Thread.sleep(1800);
                for (RunStore.Record r : runs.all()) if (api.cancelIfPresent(r.repo, r.sid)) runs.remove(r.sid);
                runOnUiThread(() -> toast(runs.all().isEmpty() ? "همهٔ نشست‌ها پایان یافته‌اند" : "پایان همهٔ نشست‌ها هنوز تأیید نشده؛ بخش Actions را بررسی کن"));
            } catch (Exception e) { runOnUiThread(() -> toast("بررسی GitHub انجام نشد؛ دوباره تلاش کن")); }
        }, "github-cleanup").start();
    }
}
