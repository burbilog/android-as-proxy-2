# Android as Proxy 2 (AAP2)

AAP2 turns a spare Android handset into a self-hosted SOCKS5 exit. The phone opens an **outbound**, server-host-key-pinned SSH connection to an OpenSSH server you control, requests a **loopback-only reverse port**, and forwards SOCKS5 traffic through the phone's Internet connection.

The server does not expose a public SOCKS5 port, and the Android app does not need a long-term SSH password. AAP2 can coexist with the original AAP1 app; its Android application ID is `net.isaeff.android.asproxy2`.

**Maintainers and future coding agents:** follow the durable [release policy](docs/RELEASE_POLICY.md) before preparing any APK, release candidate, GitHub Latest release or F-Droid submission. See also [AGENTS.md](AGENTS.md) for a short handoff entry point.

**Published stable APK:** always check [GitHub Latest](https://github.com/burbilog/android-as-proxy-2/releases/latest); do not infer it from `master`. **Historical snapshot (2026-11-16):** v2.0.6 was Latest, while `master` contained the still-unreleased v2.1.0 work (About screen, F-Droid build variant and shared version metadata). Real Android 11 and Android 14 devices, disposable Debian 13 OpenSSH integration, and signed in-place updates have been tested for earlier releases. Long-term reliability across OEM battery policies and unattended networks is device-dependent and remains a separate acceptance test.

## Maintainer quick start: build and publish

**Run make with no arguments** from the repository root. It shows the
current checked-in version and a safe, self-contained guide for local
builds, release candidates, stable releases and optional F-Droid APKs.
It does **not** download, build or publish anything.

Public APKs have an explicit release type:

```sh
# Local throwaway APK (never a public release):
make build

# GitHub Pre-release: testers only, does NOT become Latest
make release TYPE=rc1
make publish TYPE=rc1
make verify-release TYPE=rc1

# GitHub stable Release: becomes Latest (in-app Update target)
make release TYPE=release
make publish TYPE=release
make verify-release TYPE=release

# Optional F-Droid-flavor candidate, same source/version:
make fdroid-candidate TYPE=release
```

**These are separate release cycles, not a single copy-paste script.**
First commit the exact matching `versionName`/`versionCode` in
[`version.properties`](version.properties) and reviewed
`docs/RELEASE_NOTES_<versionName>.md` on master, pass CI and test
the **locally release-signed** candidate on a spare physical Android.
`TYPE` must be `release` or `rc1`–`rc99`; absent/invalid TYPE
is rejected. Android versionCodes are reserved in blocks of 100:
e.g. `2.1.0-rc1=20901`, `2.1.0-rc2=20902`,
`2.1.0=21000`; next stable cycle uses `21100`.
Existing Git tags/releases, signature mismatches and version-code
downgrades are rejected. GitHub and F-Droid remain independent.
A private long-term APK signing key is required on the maintainer's
workstation; GitHub CI cannot publish these APKs.

**Full policy and walkthrough:** [RELEASE_POLICY.md](docs/RELEASE_POLICY.md)
and [A1_LOCAL_MAKE.md](docs/A1_LOCAL_MAKE.md). The current source
version is not necessarily the latest **published** APK; verify
[GitHub Releases](https://github.com/burbilog/android-as-proxy-2/releases).

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


## Using AAP2 across restrictive networks (Hysteria 2 relay)

If a direct SSH connection is blocked or unreliable in the user's country,
the user can reach an **intermediate VM in a network where Hysteria 2 works**,
then use the AAP2 reverse SSH tunnel from that VM to an Android phone in the
**desired Internet exit country**. The VM is a relay, **not** the Internet
exit: websites see the phone's network IP.

```text
Person / browser (restrictive network)
  |  SOCKS5 at 127.0.0.1:11010
  v
Hysteria 2 client on the person's computer
  |  encrypted Hysteria 2 connection (QUIC/UDP)
  |  -- crosses the restricted network boundary -->
  v
Transit VM in a reachable / less-restricted country
  |  Hysteria 2 server forwards TCP to 127.0.0.1:11010
  |  AAP2 reverse SSH listener (loopback ONLY)
  |  <==== existing SSH connection initiated by the phone ====
  v
Android phone running AAP2 in the desired exit country
  |  phone's Wi-Fi or mobile data connection
  v
Destination website (sees the Android phone's public IP)
```

The phone initiates **outbound SSH to the transit VM** and requests a
restricted reverse forward (`-R`). The user independently initiates a
Hysteria 2 connection **to the same VM**. Neither AAP2's SOCKS5 port
nor the phone needs a publicly accessible inbound port. This is useful
when the user and the desired exit country are different, or when the
direct path to the SSH server is filtered.

### Forward the AAP2 SOCKS5 port with Hysteria 2

First set up the AAP2 server and phone as described above. For example,
`aap2ctl list` on the transit VM might report an approved phone with
`127.0.0.1:11010 (UP)`. Confirm the port works **from the VM** before
configuring the relay.

Install and configure the Hysteria 2 server on that VM and an authenticated
Hysteria 2 client on the person's computer according to
[Hysteria 2's documentation](https://v2.hysteria.network/docs/).
Add this section to the **existing Hysteria 2 client YAML** (keep its
`server`, `auth`, TLS and other required settings):

```yaml
tcpForwarding:
  - listen: 127.0.0.1:11010
    remote: 127.0.0.1:11010
```

- `listen`: TCP socket on the **user's computer**, accepting local
  SOCKS5 connections. Keep it bound to loopback.
- `remote`: TCP destination **as seen by the Hysteria 2 server on the
  transit VM**. It is the VM's AAP2 SSH reverse-forward listener, not
  the Android phone's IP address.
- If local port `11010` is occupied, change **only** `listen` (for
  example, `127.0.0.1:11011`) and use that local port in applications.
  Leave `remote` at the AAP2-assigned port.

This uses the Hysteria 2 client's built-in
[TCP forwarding mode](https://v2.hysteria.network/docs/advanced/Full-Client-Config/#tcp-forwarding);
no extra `ssh -L` connection is required on the user's computer.
Reload or restart the **Hysteria 2 client** using the usual service
management for that installation. No AAP2 configuration change is needed.

Check from the user's computer:

```sh
curl -fsS --max-time 20 \
  --proxy socks5h://127.0.0.1:11010 \
  https://www.cloudflare.com/cdn-cgi/trace
```

The `ip=` / `loc=` fields should correspond to the **Android phone's
Internet connection**, not the transit VM. `socks5h` asks the SOCKS5
proxy to resolve destination hostnames remotely. A successful `(UP)`
listener alone does not guarantee end-to-end Internet access.

**Scope and security:** This example forwards **TCP connections to a
SOCKS5 service**; it is not an automatic whole-device VPN. Configure each
application to use the local SOCKS5 port (and check for DNS/WebRTC or
other traffic bypassing the proxy). SOCKS5 UDP ASSOCIATE is **not**
carried by this TCP-only forwarding rule. Hysteria 2 depends on a
working UDP/QUIC path and may itself be blocked on some networks.
Protect Hysteria 2 with authentication and proper TLS verification;
never expose the VM's AAP2 `11010` listener or the user's local
SOCKS5 port on a public interface. Only use networks and services
you are authorized to access.

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

On the maintainer's own workstation the [Makefile](docs/A1_LOCAL_MAKE.md) provides `make` for a no-side-effects workflow summary, `make build` for local testing, and explicit `make release TYPE=rcN|release` / `make publish TYPE=rcN|release` for public releases. Publication is never implicit; signing keys, device serials and private server configuration remain outside Git.

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
