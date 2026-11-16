# Android as Proxy 2 (AAP2)

AAP2 turns a spare Android handset into a self-hosted SOCKS5 exit. The phone opens an **outbound**, server-host-key-pinned SSH connection to an OpenSSH server you control, requests a **loopback-only reverse port**, and forwards SOCKS5 traffic through the phone's Internet connection.

The server does not expose a public SOCKS5 port, and the Android app does not need a long-term SSH password. AAP2 can coexist with the original AAP1 app; its Android application ID is `net.isaeff.android.asproxy2`.

**Latest published stable APK:** v2.0.6. The source in `master` also contains preparations for v2.1.0 (About screen, F-Droid build variant and shared version metadata). Real Android 11 and Android 14 devices, disposable Debian 13 OpenSSH integration, and signed in-place updates have been tested for earlier releases. Long-term reliability across OEM battery policies and unattended networks is device-dependent and remains a separate acceptance test.

## Architecture

```text
Your laptop                    Your SSH server                    Android
    |                                |                              |
    |--- SSH local -L (optional) --->|<--- outbound pinned SSH ------|
    |                                |        restricted -R          |
    |--- SOCKS5 via local SSH ------>|==============================>|
    |                                |                         Internet exit
```

Each approved phone gets a distinct server-side SOCKS5 port listening **only on 127.0.0.1**, not on the public interface. The server uses existing OpenSSH; there is no separate AAP2 network daemon or inbound listener on the phone.

## Requirements

- Android 7.0+ (API 24), preferably a dedicated phone that can remain powered.
- A Debian 13 SSH server (documented S2 target), Python 3.9+, OpenSSH and systemd.
- Administrator access to the server and an **independent trusted copy** of its full SSH host-key fingerprint.

Android power-saving policies can stop foreground services on some handsets. Validate your particular device before remote unattended use. Keeping a phone plugged in continuously also requires attention to battery health.

## 1. Prepare the server

Review [server/INSTALL.md](server/INSTALL.md), then run on your server from a trusted checkout as root:

```sh
sh server/install.sh
aap2ctl doctor
```

The installer creates dedicated restricted accounts/groups, installs the Python standard-library CLI `aap2ctl` and enables the PIN cleanup timer. **It does not edit or reload sshd_config, PAM or firewall rules.** Review and apply the provided [SSH Match policy](server/sshd-aap2-match.conf) manually. **After editing the Match rules, run `/usr/sbin/sshd -t && systemctl reload ssh` yourself**, keeping the current administrative SSH session open; verify that a *new* SSH login works before closing it. `sshd -T -C` and `aap2ctl doctor` only check the configuration on disk, not whether the running sshd has reloaded it. See the [SFTP troubleshooting note](server/INSTALL.md#troubleshooting-password-accepted-but-sftp-immediately-closes) if activation authenticates but fails immediately.

## 2. Enroll the phone

On the server, issue a single manual activation code:

```sh
aap2ctl enroll "MyPhone" --server ssh.example.org
```

The command displays an 18-digit activation code, the server endpoint and its SSH host-key fingerprints. The PIN is temporary. Share the code privately; never put it into a log, issue, screenshot or published command output.

Install the latest **release-signed** APK from [GitHub Releases](https://github.com/burbilog/android-as-proxy-2/releases). CI debug APKs use a different signing certificate and are **not** suitable for upgrading an existing release installation.

On the phone:

1. Open **Activate AAP2**; enter the server and the 18-digit activation code (visually grouped 4-4-4-4-2).
2. Tap **Check server fingerprint**, verify its complete SHA256 fingerprint **through an independent channel**, then confirm the match.
3. The phone generates its private identity in Android Keystore and uploads only the public SSH key using the verified SFTP connection.
4. Wait for the administrator's approval. On the server:

```sh
aap2ctl list
aap2ctl approve ID
aap2ctl list
```

The approved key is restricted to that device's allocated reverse port. No new PIN is required for reconnects or in-place APK updates. `aap2ctl list` marks an approved device as `(UP)` when its reverse TCP listener exists, or `(DOWN)` if absent; this is **not** an end-to-end connectivity test. Use `aap2ctl revoke ID` to revoke a device.

## 3. Use the SOCKS5 proxy

For example, if S2 allocated the server's loopback port `11010`, use a trusted SSH connection to forward it to your computer:

```sh
ssh -N -L 11010:127.0.0.1:11010 ssh.example.org
```

In another terminal:

```sh
curl --proxy socks5h://127.0.0.1:11010 https://www.cloudflare.com/cdn-cgi/trace
```

The reported Internet egress address should be the **phone's** network IP, not the SSH server's. `socks5h` also resolves destination names through the phone-side proxy.

## Updating Android

The **GitHub build** offers **Update** on its main screen, even before activation. This downloads `aap2.apk` directly from the latest **stable** GitHub Release, checks the Android package name and increasing `versionCode`, then opens Android's standard package installer, which cryptographically verifies the APK signature and signer compatibility. Android will ask for installation confirmation and may first ask you to allow installations from AAP2.

There is **no automatic polling, background download or silent installation**. If the user wanted the connection running before the update, AAP2 attempts to restore it after `MY_PACKAGE_REPLACED`. An explicit **Disconnect** remains off after updating.

GitHub's `releases/latest` endpoint intentionally does not include pre-releases; release-candidate builds should be installed and tested separately. The **F-Droid build** does not download or install APKs; updates are handled by the F-Droid client. See [distribution channels](docs/FDROID.md). A future release can also provide an
`aap2-fdroid.apk` signed with the same developer key for F-Droid
reproducibility verification; this is **not an F-Droid listing** and
cross-channel updates remain subject to certificate compatibility.

## Switching servers / resetting enrollment

On an already-enrolled phone, tap **Reset enrollment / Change server** in its
own bottom footer, away from the everyday Connect/Details/Update controls.
A full-screen confirmation displays the current server and connection ID,
plus a **random four-digit code**. The phone's system keyboard does **not**
appear: digits are entered on AAP2's own compact keypad. The red **ERASE AAP2
ENROLLMENT** button remains pinned below the keypad and is disabled until all
four digits match. **Cancel** or Android Back returns to the main screen
without changing the connection. A new code is generated on every opening.

After confirmation, AAP2 disables boot/update auto-reconnect, closes the
current reverse SSH tunnel and SOCKS5 listener, and permanently deletes only
its own local pinned host key, profile, pending enrollment and Android
Keystore SSH identity. It then returns to **Activate AAP2**, where a *new*
18-digit code can enroll against another server. No root, Android Settings,
ADB, uninstall or `pm clear` is needed. AAP1 and other apps are untouched.

**Critical:** local reset does NOT revoke the old server's SSH public key or
delete its account. The old server administrator must separately run
`aap2ctl revoke OLD_ID` (the reset warning shows the current ID). Do this
when retiring a phone/server connection.

## Building and testing

For developers:

```sh
./gradlew --no-daemon :app:assembleGithubDebug :app:testGithubDebugUnitTest
./gradlew --no-daemon :app:assembleFdroidRelease :app:testFdroidDebugUnitTest
python3 -m unittest discover -s server -p 'test_*.py'
```

The Android project uses JDK 17 and Android SDK 35. Both channels read the same `version.properties` versionCode/versionName, and retain the existing Android package ID. The F-Droid variant excludes the in-app APK installer and its permission. GitHub releases can be published independently of any F-Droid build or listing; `make release-both` optionally prepares both signed variants from the same version. See [F-Droid preparation](docs/FDROID.md). A distributable APK **must** be signed with one long-term local key, never a CI debug key. See [release-signing guidance](docs/P0_RELEASE_SIGNING.md).

On the maintainer's own workstation the [Makefile](docs/A1_LOCAL_MAKE.md) provides short commands: `make build`, `make install`, `make status`, `make curl` and `make check`. Signing keys, device serials and private server configuration remain outside Git.

## About and support

The app's **About** screen is available before activation and links to the
[source repository](https://github.com/burbilog/android-as-proxy-2),
[GNU GPLv3 license](LICENSE.md), [setup guide](README.md), and
[issue tracker](https://github.com/burbilog/android-as-proxy-2/issues).
It identifies the installed version and GitHub/F-Droid channel, and
explains why an OpenSSH server and administrator are required.

## Security notes

- A one-time code is **not** a substitute for independently verifying the SSH host key. All permanent keys require explicit administrator approval.
- The permanent Android private key stays in Android Keystore; the server permits only loopback reverse forwarding on the device's assigned port.
- Server account cleanup and explicit device revocation are separate. The cleanup timer never revokes approved keys.
- Expired, **unapproved** invitations still reserve a server-side SOCKS5 port until the administrator explicitly revokes their enrollment ID. JSON (`-j`) integrations must treat every state other than `revoked` as reserved; see [manual S2 port policy](server/S2_APPROVAL.md#reserved-socks5-ports-manual-reclamation-policy).
- The app is intended for authorized use on networks and services you are permitted to access. Do not treat an active reverse listener as proof that phone-side Internet connectivity is healthy.

See [server/README.md](server/README.md), [S2 approval](server/S2_APPROVAL.md) and [A1 Android enrollment](docs/A1_ANDROID_ENROLLMENT.md) for the detailed security and test contracts.
