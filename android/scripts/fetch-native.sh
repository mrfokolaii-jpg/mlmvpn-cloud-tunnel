#!/usr/bin/env bash
# Official upstream artifacts, pinned to release checksums. No tokens are needed.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p app/libs
curl --fail --location --retry 3 --output "$tmp/libxray-android.zip" \
  https://github.com/XTLS/libXray/releases/download/v26.10.21/libxray-android.zip
echo 'ddefb68bb784ec291f977f85200c1a89b0c0a3847412d14b0d0b84b864b0c7e7  '"$tmp/libxray-android.zip" | sha256sum -c -
unzip -p "$tmp/libxray-android.zip" android/libXray.aar > "$tmp/libXray.aar"
test -s "$tmp/libXray.aar"
curl --fail --location --retry 3 --output "$tmp/hev-socks5-tunnel.aar" \
  https://github.com/heiher/hev-socks5-tunnel/releases/download/2.19.3/hev-socks5-tunnel.aar
echo 'ab6c581e6157680cfe5687ee7bbdcf95405c006c2db3701879ec44b9e5f83836  '"$tmp/hev-socks5-tunnel.aar" | sha256sum -c -
cp "$tmp/libXray.aar" app/libs/libXray.aar
cp "$tmp/hev-socks5-tunnel.aar" app/libs/hev-socks5-tunnel.aar
printf 'Native libraries are ready.\n'
