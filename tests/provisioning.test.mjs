import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { basename } from 'node:path';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');

test('per-account installer packages the actual agent files and installs the workflow last', () => {
  const installer = read('android/app/src/main/java/app/cloudpath/githubvpn/ManagedProvisioner.java');
  const gradle = read('android/app/build.gradle');
  const entries = [...installer.matchAll(/new FileSpec\("([^"]+)",\s*"([^"]+)"\)/g)].map(match => [match[1], match[2]]);
  assert.equal(entries.length, 6);
  assert.deepEqual(entries.at(-1), ['.github/workflows/github-vpn-session.yml', 'tunnel/github-vpn-session.yml']);
  for (const [repositoryPath, assetPath] of entries) {
    assert.ok(existsSync(new URL(`../${repositoryPath}`, import.meta.url)), `${repositoryPath} must exist`);
    assert.ok(gradle.includes(basename(repositoryPath)), `${repositoryPath} must be part of the Gradle assets task`);
    assert.ok(assetPath.startsWith('tunnel/'));
  }
  assert.match(gradle, /assets\.srcDir\(tunnelAssets\)/);
  assert.match(gradle, /dependsOn prepareTunnelAssets/);
  assert.match(read('.github/workflows/github-vpn-session.yml'), /agent\/gt-agent\.mjs/);
});

test('Android minimum version honors the bundled native tunnel library', () => {
  const gradle = read('android/app/build.gradle');
  const minSdk = gradle.match(/\bminSdk\s+(\d+)/);
  assert.ok(minSdk && Number(minSdk[1]) >= 29, 'hev-socks5-tunnel requires Android API 29 or higher');
});

test('account sign-in does not hard-code the author or reuse the original default repo', () => {
  const activity = read('android/app/src/main/java/app/cloudpath/githubvpn/MainActivity.java');
  assert.doesNotMatch(activity, /mrfokolaii-jpg|login\s*\+\s*"\/mlmvpn-cloud-tunnel"/i);
  assert.match(read('android/app/src/main/java/app/cloudpath/githubvpn/GithubApi.java'), /"scope", "repo workflow"/);
  assert.match(activity, /confirmRepositorySetup\(startAfterLogin\)/);
  assert.match(activity, /OnboardingRules\.clientId\(BuildConfig\.GITHUB_OAUTH_CLIENT_ID, settings\.clientId\(\)\)/);
  assert.match(activity, /showMissingOAuthConfiguration\(\)/);
  assert.doesNotMatch(activity, /setHint\("OAuth Client ID"\)/);
});

test('builds without the distributor OAuth ID are clearly marked as unconfigured', () => {
  const workflow = read('.github/workflows/build-android.yml');
  assert.match(workflow, /vars\.CLOUDPATH_GITHUB_CLIENT_ID/);
  assert.match(workflow, /artifact_suffix=-unconfigured/);
  assert.match(workflow, /cloudpath-vpn-debug\$\{\{ steps\.oauth\.outputs\.artifact_suffix \}\}/);
  assert.match(workflow, /https:\/\/github\.com\/login\/device\/code/);
  assert.match(workflow, /device_flow_disabled/);
  assert.doesNotMatch(workflow, /echo\s+["']?\$response/);
});

test('login offers sensitive code copying, browser choice and system bar insets', () => {
  const activity = read('android/app/src/main/java/app/cloudpath/githubvpn/MainActivity.java');
  assert.match(activity, /ClipData\.newPlainText\("GitHub device code", userCode\)/);
  assert.match(activity, /ClipDescription\.EXTRA_IS_SENSITIVE/);
  assert.match(activity, /Intent\.createChooser\(browser/);
  assert.match(activity, /WindowInsets\.Type\.systemBars\(\)/);
  assert.match(activity, /view\.setPadding\(0, top, 0, bottom\)/);
});

test('Android launcher has a VPN name and adaptive icon', () => {
  const manifest = read('android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /android:label="@string\/app_name"/);
  assert.match(manifest, /android:icon="@mipmap\/ic_launcher"/);
  assert.match(read('android/app/src/main/res/values/identity.xml'), /<string name="app_name">VPN<\/string>/);
  assert.match(read('android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml'), /@drawable\/ic_vpn_foreground/);
});
