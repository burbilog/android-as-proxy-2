# A1 — verified-host Android enrollment

**Status:** development, [draft PR #10](https://github.com/burbilog/android-as-proxy-2/pull/10). Working Zebra P0 APK, `aap2-test`, remote SSH users and the CSV soak monitor are untouched. Android application ID is `net.isaeff.android.asproxy2`.

The Android client derives from the tested P0 Keystore/Direct Boot/strict-host-key implementation. S2 server-side invitation/approval/revocation is implemented separately (PRs #8/#9). No PIN, SSH password or private key is ever installed on the server as an Android client credential.

## Activation

1. On an unconfigured device, the user opens **Activate AAP2 using one-time PIN** and enters the server host[:port], server-issued `aap2e...` setup user and ten-digit PIN (4-4-2 grouping; leading zeros preserved).
2. **Check server fingerprint** performs SSH KEX, captures the public host-key blob and **rejects user authentication**. The UI displays the **complete** `SHA256:` fingerprint, the key algorithm, host and port. The user must compare it with an administrator through an independent trusted channel. There is no TOFU, auto-accept or PIN transmission at this stage.
3. Only **Verified — send PIN and activate** can start a second SSH session with an exactly pinned host key. The PIN authenticates a temporary SFTP-only account; strictly bounded/schema-validated `config.json` supplies permanent username and assigned remote port. Android creates/reuses a permanent Android Keystore P-256 key, persists **non-secret** pending details, then uploads the **public** key only.
4. The administrator explicitly approves the uploaded key on S2. Android reads `status.json`, proves a separate permanent **publickey-only** SSH login and the exact loopback `-R 127.0.0.1:PORT`. Only then does it commit the device-protected `DirectBootTunnelProfile`, clear pending metadata and start the foreground SOCKS5 service.
5. The normal foreground service owns reconnects and Direct Boot. Enrollment does not start another permanent SSH manager.

The PIN is never put into saved instance state, preferences, Intent arguments or logs. CI sends temporary test PINs only into disposable Android private files and deletes them before connection. Private P-256 signing operations remain in Android Keystore.

## Failure recovery and migration

- A terminated SFTP session after upload preserves the original host pin, permanent username/port and Keystore key alias in pending state, with **no PIN**. **Resume approved enrollment** makes **one** pinned permanent publickey SSH / reverse-port attempt using the **existing** identity. No password or automatic enrollment retry loop.
- A different pending enrollment cannot silently overwrite the stored host/key; a configured Direct Boot profile cannot be replaced through the activation UI.
- Expired S2 temporary accounts, changed server host keys, invalid status/config JSON, mismatched reverse ports and missing permanent keys fail closed.
- Legacy password/TOFU input, boot fallback, service fallback and the former `SSHTunnelManager` implementation are removed. On normal MainActivity startup the obsolete credential-encrypted `aap_prefs` file (which older development APKs might have used for an SSH password) is deleted. The trusted **device-protected** SSH profile and Keystore identity are never cleared by this migration.

## Reproducible CI evidence — 2026-11-14

Code at commit `833d44b7c9f56f4b43118dda58fb17502ddb6acf`:

- [Android build and unit tests #43534858536](https://github.com/burbilog/android-as-proxy-2/actions/runs/43534858536) — **PASS** after removal of the legacy password SSH implementation.
- [Android 11 real OpenSSH S2 E2E #43534858498](https://github.com/burbilog/android-as-proxy-2/actions/runs/43534858498) — **PASS** on a fresh disposable Android 11/API 30 emulator with disposable OpenSSH/PAM/S2 on the GitHub runner.
- Real **Compose UI click-through**: three fields → fingerprint probe → full SHA256 compared with runner's independently obtained `ssh-keygen` result → confirm Verified. Server inbox is checked empty **before** that click. The public key is then uploaded and administratively approved, the actual Android foreground SSH -R/SOCKS5 proxy returns an HTTP body, and server revoke tears it down.
- Negative checks: changed SSH host pin rejected before authentication; malformed JSON and fractional/string integer fields rejected; expired setup PIN removed by **real S2 cleanup**; forcibly broken SFTP after key upload → explicit admin approval → successful **no-PIN Resume using same Keystore identity**; OpenSSH rejects the wrong `permitlisten` port.
- A separate enrollment on the same disposable emulator drives an in-place `adb install -r` from `versionCode 20000` to `20001` with the **same temporary debug signing certificate**. Existing public Keystore key, pinned SSH host key and real reverse-forward/SOCKS5 HTTP survive the APK update without uninstall.
- The file-based-encrypted Android emulator is PIN-locked and **cold-rebooted** with **no adb reverse**. CI requires user 0 to stay `RUNNING_LOCKED` while SOCKS5 HTTP succeeds through the restored SSH reverse tunnel. Server is reached via emulator's `10.0.2.2` host route; no instrumentation/user unlock occurs during the pre-unlock check.
- PINs, server private keys, release signing keys and client private keys are not emitted in CI logs.

All tests above exercise a disposable emulator and test signing certificate. They do **not** establish OEM device behavior or validate the user's permanent production release signing key.

## Remaining acceptance gates

- [x] Positive Compose UI enrollment, S2 approval, actual SOCKS5 HTTP and revoke on emulator.
- [x] Negative host/PIN/SFTP/JSON/`permitlisten` paths and no-PIN Resume.
- [x] Same-certificate in-place APK update and pre-first-unlock FBE reboot on emulator.
- [x] Legacy password/TOFU implementation removal and Android CI regression.
- [ ] Physical A1 enrollment, foreground-service lifetime and Direct Boot before first unlock on a **spare device** (or later in an explicitly planned maintenance window for Zebra).
- [ ] Update from one **release-signed** APK to another using the long-term key kept on `marduk`, preserving identity and verified host key. See `docs/P0_RELEASE_SIGNING.md`. The guarded local `scripts/aap2-spare-upgrade.sh` verifies both signatures and versions and requires an explicit spare-device ADB serial; it never uninstalls AAP2 or touches the working Zebra by default.

**Do not install the A1 development APK on the working Zebra just to close the remaining gates.** Leave PR #10 draft until physical and long-term-signing tests are explicitly accepted.
