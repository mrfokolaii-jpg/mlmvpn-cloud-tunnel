# Third-party components

The build downloads and bundles:

- [libXray v26.10.21](https://github.com/XTLS/libXray), MIT. It includes [Xray-core](https://github.com/XTLS/Xray-core), MPL-2.0.
- [hev-socks5-tunnel 2.19.3](https://github.com/heiher/hev-socks5-tunnel), MIT.
- [Bouncy Castle bcprov-jdk18on 1.80](https://github.com/bcgit/bc-java), Bouncy Castle licence.

Their licence texts are in `app/src/main/assets/licenses/` and are included in the APK. The build retrieves the native release artifacts from their official projects and checks their SHA-256 digests. All marks and project names belong to their respective owners.
