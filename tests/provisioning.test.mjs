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
  assert.match(activity, /confirmRepositorySetup\(\)/);
});
