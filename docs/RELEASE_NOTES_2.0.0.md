# Android as Proxy 2 — v2.0.0

First public AAP2 release: a self-hosted Android SOCKS5 exit over a restricted outbound SSH reverse tunnel.

## Highlights

- New Android package `net.isaeff.android.asproxy2`, installable alongside the original AAP1.
- Single 18-digit manual activation code; independently verified SSH server fingerprint; P-256 identity held in Android Keystore; explicit administrative approval.
- Debian 13 / OpenSSH server manager **`aap2ctl`** with `enroll`, `approve`, `list`, `revoke`, `doctor` and temporary-PIN cleanup.
- Per-device loopback-only reverse SOCKS5 TCP port; `aap2ctl list` marks current reverse listeners `(UP)`/`(DOWN)`, distinct from registration and proxy health.
- Persistent Android foreground service with reconnect, Direct Boot and post-APK-update restore that respects a manual Disconnect.
- User-initiated **Update** from the latest stable GitHub Release: validates package/version/certificate and uses the Android system installer. Android may require a one-time permission for installs from AAP2.
- Local release signing and maintainer Makefile shortcuts: `make build`, `make release VERSION=...`, explicit `make publish VERSION=...`, `make verify-release`, `make install` and `make curl`.

## Install

Start with the [README](https://github.com/burbilog/android-as-proxy-2#readme) and the [server setup guide](https://github.com/burbilog/android-as-proxy-2/blob/master/server/INSTALL.md).

Install the attached **`aap2.apk`** on Android 7.0+; do **not** install or update an enrolled device with an APK signed by a different certificate. The server installer does **not** modify or reload `sshd_config`; follow the manual SSH Match policy instructions.

To connect a new device:

```sh
aap2ctl enroll "MyPhone" --server ssh.example.org --manual-code
aap2ctl approve ID
aap2ctl list
```

Independently check the complete SSH host-key fingerprint shown on the phone before sending any activation code.

## Tested and limitations

Tested real SSH/SOCKS5 on local Android 11 Zebra TC21 and Android 14 Seuic Cruise2, release-signed APK in-place upgrades, and isolated Debian 13 OpenSSH/PAM/systemd integration in CI.

**Caveats:** OEM battery restrictions, Wi-Fi changes and extended unattended operation vary by device. The user-approved Android in-app APK installer flow is a separate first-release acceptance check on the *local* Zebra. An `(UP)` reverse listener means an SSH TCP listener exists, not proof of SOCKS5 HTTP reachability. Keep AAP1 running on any remote production phone until AAP2 is tested there.

## Integrity

The release provides `SHA256SUMS` for `aap2.apk`. Signing credentials remain with the maintainer, never in GitHub Actions or in the source repository.
