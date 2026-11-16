# A1 — verified-host Android enrollment

**Status:** integrated into `develop` for the first public AAP2 release. Android application ID: `net.isaeff.android.asproxy2`. The remote AAP1 installation is independent and untouched. Long-term OEM battery policy and live system-installer updates are separate physical tests.

The Android client derives from the tested P0 Keystore/Direct Boot/strict-host-key implementation. S2 server-side invitation/approval/revocation is integrated into the same repository release. No PIN, SSH password or private key is ever installed on the server as an Android client credential.

## Activation

1. On an unconfigured device, the user opens **Activate AAP2 using one-time PIN** and types the server host[:port] and exactly **one activation code**. The server issues it with `aap2ctl enroll ...` as **18 decimal digits grouped 4-4-4-4-2**. Android has one numeric field, inserts separators visually, and offers Show/Hide. Internally the first six digits select the temporary SSH username `aap2eNNNNNN`, the next ten are the actual random password/PIN, and the last two are a decimal MOD97 check. The user's screen deliberately does not distinguish the account and PIN. A failed checksum is rejected locally **before any SSH connection or PIN transfer**; it detects every single mistyped digit and all unequal adjacent digit swaps, but adds no authentication entropy. The code is never saved to disk, copied to saved instance state, or logged. The code stays in the in-memory form after SSH authentication failure so it can be corrected without retyping the whole form. Activation grants a **20-minute PIN lifetime**. Existing legacy enrollment records remain readable/revocable, but new invitations always use the single-code UI.
2. **Check server fingerprint** performs SSH KEX, captures the public host-key blob and **rejects user authentication**. The UI displays the **complete** `SHA256:` fingerprint, the key algorithm, host and port. The user must compare it with an administrator through an independent trusted channel. There is no TOFU, auto-accept or PIN transmission at this stage.
3. Only **Verified — send PIN and activate** can start a second SSH session with an exactly pinned host key. The PIN authenticates a temporary SFTP-only account; strictly bounded/schema-validated `config.json` supplies permanent username and assigned remote port. Android creates/reuses a permanent Android Keystore P-256 key, persists **non-secret** pending details, then uploads the **public** key only.
4. The administrator explicitly approves the uploaded key on S2. Android reads `status.json`, proves a separate permanent **publickey-only** SSH login and the exact loopback `-R 127.0.0.1:PORT`. Only then does it commit the device-protected `DirectBootTunnelProfile`, clear pending metadata and start the foreground SOCKS5 service.
5. The normal foreground service owns reconnects and Direct Boot. Enrollment does not start another permanent SSH manager.

The PIN is never put into saved instance state, preferences, Intent arguments or logs. CI sends temporary test PINs only into disposable Android private files and deletes them before connection. Private P-256 signing operations remain in Android Keystore.

## User-facing reconnect status (2026-11-14)

An SSH outage with the foreground service still running is now displayed
as **Reconnecting…**, not **Disconnected**. The state is set by the existing
SSH retry loop, after a failed/lost connection and before the existing
backoff/network-callback wait. **Connecting…** remains the label during
each actual SSH connection attempt; **Connected** means the exact
loopback reverse forward succeeded. **Disconnected** remains for a
stopped service. The same retry state is visible in the one-screen
**Details** view as **Reconnecting**.

On the main screen, **Disconnect** remains available during
**Connecting** and **Reconnecting**, so the user can stop an ongoing
automatic retry instead of being offered a redundant **Connect**
button. The explicit stop action retains the prior Direct Boot
`desired_running=false` behavior, preventing automatic boot restart
until the user next presses **Connect**. No extra service, wake lock,
Wi-Fi manipulation, network probe, log parsing, or background job is
introduced. Existing fingerprint/Keystore profiles are not changed.

The instrumentation test on an **already enrolled disposable device**
verifies the main-screen and Details labels and the visible Disconnect
control by temporarily changing only the in-process UI state, without
actually stopping the live SSH tunnel.

## Physical reconnect UI acceptance — Seuic Cruise2, 20005 (2026-11-14)

**PASS on spare hardware; no production Zebra or soak changes.** Local release build
`20005` from A1 commit `147d211` was signed with the same long-term certificate
as installed `20004`; `scripts/aap2-spare-upgrade.sh check` verified matching
signing-certificate SHA256 and strictly increasing versionCode. Guarded in-place
`adb install -r` on the existing enrolled Cruise2 (`484cf6e0`) succeeded,
without clearing app data, replacing the Android Keystore identity, or
re-enrolling.

The disposable Debian S2 container `aap2-a1-test` had one approved Cruise2
registration `840437` (permanent account `aap2p840437`, loopback SOCKS5
`127.0.0.1:11010`). First, terminating that account's SSH session recovered
very quickly: 2-second server polls stayed UP, but the Android log added
`Keystore SSH reverse forward ready` at 23:06:20 local time. **This did not
establish that the retry state was visible to a human.**

To test the screen deliberately, the operator temporarily stopped S2 SSH
service/socket and terminated the disposable account session, with restoration
after a bounded 40-second outage. The physical phone visibly displayed
**Reconnecting…** during the outage. In a repeat test, the operator pressed
**Disconnect** while reconnecting. After the S2 SSH service returned, AAP2
**remained disconnected**, as explicitly requested. Pressing **Connect**
manually restored the tunnel, and the isolated S2 host successfully fetched
Cloudflare HTTPS via the same server-side SOCKS5 listener.

**Accepted:** real service-to-Compose retry indication, manual cancellation
during retry, persistence of desired stopped state after connectivity returns,
and manual recovery with working SOCKS5. CI build/unit
[#43633853165](https://github.com/burbilog/android-as-proxy-2/actions/runs/43633853165)
and full Android 11/real OpenSSH S2 E2E
[#43633853171](https://github.com/burbilog/android-as-proxy-2/actions/runs/43633853171)
also PASS on `147d211` (the E2E passed on the second attempt after the
first attempt's emulator process disappeared). This does **not** close the
separate long-term OEM/Doze Wi-Fi auto-reassociation gate. No forced Wi-Fi
scan, wake lock, separate monitor, or log parsing was added.

## On-device confirmed reset / move to another server

An enrolled AAP2 phone now shows **Reset enrollment / Change server** at the
bottom of the main screen. This is a destructive operation and is intentionally
not a one-tap button:

- The red error-container confirmation puts the **four-digit challenge and
  numeric entry at the very top without scrolling**. Compact warnings below
  identify the current server and Connection ID, explain that the local
  Keystore SSH identity is destroyed, and require separate server revocation.
- **ERASE AAP2 ENROLLMENT** is disabled until the user enters the
  randomly generated **four-digit code displayed in that dialog**, using the
  numeric keyboard. Each reopening gets a fresh code; a wrong or incomplete
  code does nothing. Cancel/Back makes no changes.
- Reset is dispatched to `SocksForegroundService`, which persists
  `desired_running=false`, tears down the running SSH session and local
  SOCKS5 listener, then clears pending state, deletes the **specific**
  `aap2-ssh-ecdsa-p256-v1` Android Keystore key and its creation marker,
  and finally clears the device-protected SSH profile. An interrupted reset
  fails closed; it does not silently resume the former tunnel.
- The main screen returns to **Activate AAP2** for a new independent
  fingerprint verification, activation code and administrator approval.
  No app uninstall, Android settings, `adb pm clear` or AAP1 changes.

**Local reset never revokes server-side access.** The operator must use
`aap2ctl revoke OLD_ID` on the old S2; otherwise that server still contains
the old authorized SSH public key. The dialog displays the old ID to assist
with this task.

Disposable Android E2E tests check cancellation, incorrect/complete four-digit confirmation, actual removal of the former Keystore alias, disabled
`MY_PACKAGE_REPLACED` auto-restore, and a second independent S2 enrollment.

## Connection identification and one-screen support diagnostics

After activation, MainActivity always displays **Connection ID** (the suffix
of the approved permanent SSH user, e.g. `692103` from `aap2p692103`).
It matches the first column of `aap2ctl list` on S2. It stays visible
while disconnected, not just when the SSH tunnel is up. No server lookup is
needed and no phone enrollment nickname is invented: S2 owns device labels.

The **Details** button opens a single, non-scrollable support screen designed
to be photographed or shown on a video call. It displays live connection
state and traffic, ID, exact permanent SSH account, SSH server endpoint,
server-side and phone-side loopback SOCKS5 ports, auto-connect setting,
phone model/Android version, APK version/build, and **both full SHA256
fingerprints**: the previously pinned server host key and the existing
Android Keystore SSH **public** key. The latter matches the fingerprint
an administrator sees at approval. It obtains information locally from
the committed Direct Boot profile and public key; **no SSH probing,
log parsing, network test, PIN exposure, key generation or storage mutation**
is performed by the details screen. If the Keystore is unavailable,
the page says so rather than attempting to replace the identity.

The Details page is a view only: Close/Android Back returns to the same
MainActivity without changing the connection. Existing Zebra, its
production server, and its separate soak monitor are unaffected.

**Connection diagnostics CI:** on commit `ca46bdac0b8712cbfb531c8e5b19be8cfa02559c`,
[Android build/unit workflow #43595391452](https://github.com/burbilog/android-as-proxy-2/actions/runs/43595391452)
and [full Android 11/S2 E2E #43595391397](https://github.com/burbilog/android-as-proxy-2/actions/runs/43595391397)
both **PASS**. The latter launches the real MainActivity after an actual
administrator-approved enrollment and confirms that the main connection ID,
full host/phone key fingerprints, server endpoints, ports and diagnostics
footer are visible together in the same phone viewport, with a working
Close action. Full PIN/SFTP/approval/SOCKS HTTP, APK update, Direct Boot and
revoke regression gates also passed.

## Physical diagnostics-screen acceptance — Seuic Cruise2 (2026-11-14)

- Same local long-term signing certificate; in-place ADB upgrade of the
  already-enrolled **physical Seuic Cruise2** from `20003` to `20004`:
  `scripts/aap2-spare-upgrade.sh upgrade` reported **Success** and verified
  installed `versionCode=20004`. No uninstall/re-enrollment.
- Immediately afterward the isolated S2 host successfully fetched
  Cloudflare trace via `socks5h://127.0.0.1:11010` and the existing
  SSH reverse tunnel. The remote exit IP was `195.91.221.147`.
  Thus the signed in-place update preserved a functioning proxy.
- Human review of the **Details** screen on the actual Seuic accepted
  its compact typography. It is intentionally small so that support
  information remains on a **single screen**, including on devices with
  less display space. Do not add complexity or increase the font by
  default without a concrete reported clipping problem.
- This test does not establish cold-reboot locked-user behavior or
  long-term OEM background survival of the `20004` build; those
  remain separate acceptance tests. Working Zebra, its server and the
  soak monitor were not altered.

## Physical Direct Boot acceptance — release-signed 20004 (2026-11-14)

The Seuic Cruise2 was rebooted via ADB with the screen left untouched.
After boot, `sys.boot_completed=1` and `am get-started-user-state 0`
reported `RUNNING_LOCKED`: the phone had **not** been unlocked since reboot.
At that time the isolated S2 container successfully fetched Cloudflare
HTTPS through `socks5h://127.0.0.1:11010`, with the same remote exit IP
`195.91.221.147` as before reboot. **Physical Direct Boot on signed
`20004`: PASS.** This shows a usable tunnel before first unlock; it
does not establish the exact time after reboot when connectivity returned.

Immediately after `adb wait-for-device`, `am` briefly reported
`Can't find service: activity`; this means Android services were not
ready yet, not that Direct Boot failed. Wait for `sys.boot_completed=1`
before querying the activity service. Continue remaining recovery/
background tests on the disposable Seuic, not on the working Zebra.

## Physical Wi-Fi toggle under PIN-locked screen — Seuic 20004 (2026-11-14)

**Direct Boot: PASS.** After an ADB reboot with a configured lock-screen
PIN, Android reported `RUNNING_LOCKED`, the AAP2 foreground service had
started from `LOCKED_BOOT_COMPLETED`, and `cmd wifi status` showed the
saved WLAN `O3` connected. Cloudflare HTTPS passed through the existing
server-side SOCKS5 `127.0.0.1:11010` without unlocking.

**Artificial Wi-Fi toggle while screen asleep:** ADB `svc wifi disable`,
then `enable`, left Android's Wi-Fi setting enabled but unassociated
(`Wifi is not connected`) for several minutes; the remote reverse
listener was absent. The phone was not online; this alone cannot be
classified as an AAP2 reconnect defect.

**Controlled wake-only reproduction: PASS.** The user repeated the
toggle after another locked reboot; Wi-Fi again remained enabled but
disconnected while the screen slept. At 17:03, a single
`adb shell input keyevent KEYCODE_WAKEUP` (NO PIN entry) was sufficient
for the saved `O3` connection to return. Android STILL reported
`RUNNING_LOCKED`; at 17:04 Cloudflare HTTPS again succeeded over the
**same** S2 reverse SOCKS5 port and exit IP `195.91.221.147`.
There was no manual app restart, re-enrollment or credential unlock.

**Conclusion:** AAP2 restored the SSH reverse tunnel once Wi-Fi came
back, independently of the first PIN unlock. On this Seuic, explicitly
turning the Wi-Fi radio off/on while the display sleeps may defer
association until the screen wakes. This is an ADB/admin-initiated
radio toggle, **not a representative natural Wi-Fi outage**. Do not
work around it with forced Wi-Fi control, wake locks or screen wakeups
in AAP2. To test realistic network interruption, disrupt the **AP or
upstream network** without changing the Android Wi-Fi enabled state;
or separately test server-side SSH-session loss/retry. All such tests
remain restricted to the disposable Seuic and S2, not production Zebra.

## Physical AP disappearance test: Wi-Fi doze wake dependency — Seuic 20004 (2026-11-14)

This is a **real AP-disappearance** test, not `svc wifi disable/enable`.
The O3 AP runs in a separate VM. The user powered that VM off, then
brought it back up. Another phone was able to join O3, but the Seuic
remained `RUNNING_UNLOCKED` with `Wifi is enabled` and
`Wifi is not connected`; the display was `mWakefulness=Dozing`.

The server-side 30-second curl monitor (`/tmp/aap2-o3.log`) showed
`rc=0 http=200` at 14:15:09 and 14:15:39 UTC; `rc=28` at 14:16:09;
then `rc=7 http=000` from 14:16:44 through 14:27:46 UTC, with no
listener at `127.0.0.1:11010`. After one
`adb shell input keyevent KEYCODE_WAKEUP`, requests resumed as
`rc=0 http=200` by 14:28:16 UTC and continued at ~0.12–0.15 s per
request. AAP2 was **not restarted** and no re-enrollment occurred.

**Interpretation:** The Seuic's Wi-Fi re-association after real AP loss
appears gated on display wake, even after the primary user was unlocked
earlier in this boot. SSH automatic reconnection on network restoration:
**PASS**. Unattended Wi-Fi recovery while dozing on this firmware:
**NOT PASS**. This should not be generalized to all Android OEMs;
Zebra's earlier recovery observations differ. Do not implement an
unconditional AAP2 wake lock, forced scans or display wakeups based on
this device-specific observation. Future realistic resilience testing
needs either another OEM or a network path that can be disrupted without
losing Wi-Fi association, and should isolate AAP2's own SSH retry behavior.

## Physical server-side SSH termination test — Seuic 20004 (2026-11-14)

A deliberately isolated test on the disposable S2 server terminated the
existing AAP2 device user's SSH session with `pkill -TERM -u` on the
dedicated permanent account. No Wi-Fi changes, phone restart, enrollment
change, revocation, or `sshd` restart were involved.

A two-second poll of the reserved loopback listener on S2 reported:
`14:41:53Z` through `14:41:59Z UP`; `14:42:01Z`,
`14:42:03Z` and `14:42:05Z DOWN`; from `14:42:07Z`
onward `UP`. An immediate HTTPS request through the original
`socks5h://127.0.0.1:11010` again succeeded, with the unchanged
exit IP `195.91.221.147`. **Physical SSH reconnect after server-side
session termination: PASS.**

The polling interval constrains the outage measurement: listener
absence was observed over approximately six seconds between the first
DOWN and first following UP sample. No claim is made about exact SSH reconnection latency. A subsequent
per-session blackhole test is documented separately below. This validates AAP2's reconnect loop with the phone network
available, independently of the Seuic firmware's dozing Wi-Fi issue.

## Physical per-session TCP blackhole — Seuic 20004 (2026-11-14)

**PASS:** A controlled 90-second, session-specific bidirectional nftables
DROP was applied in the disposable S2 container, matching only the
existing AAP2 SSH 4-tuple (server `172.16.172.92:22`, peer
`192.168.0.165:46388`). This silently discarded packets on the old
connection without FIN/RST, while leaving other and **new** SSH
connections (different source port) eligible. The AP/Wi-Fi, Android
application, S2 sshd and device credentials were not touched.

Observed UTC timestamps:

- `14:57:21`: BLACKHOLE START.
- `14:57:28`: HTTP via SOCKS timed out, `rc=28 http=000`
  after 5 seconds, although the port was still initially listening.
- `14:57:58`: first server-side listener DOWN; polls at 2-second
  intervals stayed DOWN through `14:58:04`.
- `14:58:06`: listener UP again, **before** rule removal.
- `14:58:33`: 30-second HTTP monitor confirms `rc=0 http=200`.
- `14:58:51`: BLACKHOLE END; subsequent HTTP 200s continued.
- Final independent Cloudflare HTTPS via `socks5h://127.0.0.1:11010`
  also succeeded, with original exit IP `195.91.221.147`.

The listener returned roughly 45 seconds after blackholing began,
with approximately eight seconds between the first DOWN and following
UP samples. Because the server had `ClientAliveInterval 15` /
`ClientAliveCountMax 2` and the phone also has SSH keepalives,
this establishes recovery of the **combined** failure detection and
reconnect mechanism, not which side detected the silence first. The
DROP affected only the old SSH 4-tuple: recovery during DROP means
a fresh TCP connection could bypass that particular rule. This is
not equivalent to an outage blocking all outbound SSH traffic.
On-device network remained available and the test does not address
the Seuic dozing Wi-Fi re-association limitation.

## Failure recovery and migration

- A terminated SFTP session after upload preserves the original host pin, permanent username/port and Keystore key alias in pending state, with **no PIN**. **Resume approved enrollment** makes **one** pinned permanent publickey SSH / reverse-port attempt using the **existing** identity. No password or automatic enrollment retry loop.
- A different pending enrollment cannot silently overwrite the stored host/key; a configured Direct Boot profile cannot be replaced through the activation UI.
- Expired S2 temporary accounts, changed server host keys, invalid status/config JSON, mismatched reverse ports and missing permanent keys fail closed.
- Legacy password/TOFU input, boot fallback, service fallback and the former `SSHTunnelManager` implementation are removed. On normal MainActivity startup the obsolete credential-encrypted `aap_prefs` file (which older development APKs might have used for an SSH password) is deleted. The trusted **device-protected** SSH profile and Keystore identity are never cleared by this migration.

## Reproducible CI evidence — 2026-11-14

Code at commit `833d44b7c9f56f4b43118dda58fb17502ddb6acf`:

- [Android build and unit tests #43534858536](https://github.com/burbilog/android-as-proxy-2/actions/runs/43534858536) — **PASS** after removal of the legacy password SSH implementation.
- [Android 11 real OpenSSH S2 E2E #43534858498](https://github.com/burbilog/android-as-proxy-2/actions/runs/43534858498) — **PASS** on a fresh disposable Android 11/API 30 emulator with disposable OpenSSH/PAM/S2 on the GitHub runner.
- Historical tested **Compose UI click-through** (commit `833d44b`): three fields → fingerprint probe → full SHA256 compared with runner's independently obtained `ssh-keygen` result → confirm Verified. Server inbox is checked empty **before** that click. The public key is then uploaded and administratively approved, the actual Android foreground SSH -R/SOCKS5 proxy returns an HTTP body, and server revoke tears it down.
- Negative checks: changed SSH host pin rejected before authentication; malformed JSON and fractional/string integer fields rejected; expired setup PIN removed by **real S2 cleanup**; forcibly broken SFTP after key upload → explicit admin approval → successful **no-PIN Resume using same Keystore identity**; OpenSSH rejects the wrong `permitlisten` port.
- A separate enrollment on the same disposable emulator drives an in-place `adb install -r` from `versionCode 20000` to `20001` with the **same temporary debug signing certificate**. Existing public Keystore key, pinned SSH host key and real reverse-forward/SOCKS5 HTTP survive the APK update without uninstall.
- The file-based-encrypted Android emulator is PIN-locked and **cold-rebooted** with **no adb reverse**. CI requires user 0 to stay `RUNNING_LOCKED` while SOCKS5 HTTP succeeds through the restored SSH reverse tunnel. Server is reached via emulator's `10.0.2.2` host route; no instrumentation/user unlock occurs during the pre-unlock check.
- PINs, server private keys, release signing keys and client private keys are not emitted in CI logs.

All tests above exercise a disposable emulator and test signing certificate. They do **not** establish OEM device behavior or validate the user's permanent production release signing key.


## Physical acceptance — Seuic Cruise2, Android 14 (2026-11-14)

- Installed permanent-release-signed `20000` on a previously unconfigured
  spare Android 14/API 34 phone; manually enrolled into isolated Debian 13
  S2 container (`aap2-a1-test`, external SSH port `22225`). Admin approved
  the phone's Android Keystore P-256 public key. Loopback-only reverse SSH
  forward `127.0.0.1:11010` and SOCKS5 DNS/HTTPS to Cloudflare succeeded.
- Upgraded `20000 → 20001` using the same release signing certificate and
  guarded `adb install -r` (no uninstall, no `pm clear`). AAP2 reported
  an already verified server identity and showed `Connect`, not new-enrollment
  prompts. After one explicit `Connect` press the SAME server-approved
  account/forward worked for real SOCKS5 HTTPS, without PIN or reapproval.
- Rebooted the phone; once Android completed boot, `dumpsys user` reported
  **`RUNNING_LOCKED`** before first user unlock. Without unlocking or manually
  launching AAP2, SOCKS5 HTTPS on the existing `11010` reverse port returned
  a valid Cloudflare response. This demonstrates true physical Direct Boot
  SSH/Keystore/reverse-forward/foreground-service start.
- Initial `dumpsys user` immediately after `adb wait-for-device` briefly
  returned `Can't find service: user`: Android's framework was still starting;
  retry yielded a valid `RUNNING_LOCKED` state. A separate earlier reboot was
  `RUNNING_UNLOCKED` and was **not** counted as pre-unlock evidence.
- After manual Wi-Fi disable/enable on Cruise2, a repeat request from isolated S2 through `--socks5-hostname 127.0.0.1:11010` returned Cloudflare trace and public IP `195.91.221.147` again. This confirms basic network recovery; it does not alone establish long-term background reliability or repeated Wi-Fi cycling.
- No production Zebra, existing `aap2-test`, or original soak CSV was modified.
  Hardware trial uses the earlier pre-short-code A1 release APKs; it does not
  validate the later six-digit manual-typing enrollment changes.

## Updated short-code CI acceptance — 2026-11-14

Commit `8531166ec940ddb46cbfd8260c986105e5d9f7a1`:
[Android build/unit tests](https://github.com/burbilog/android-as-proxy-2/actions/runs/43581141232)
and [real Android 11 emulator/S2 E2E](https://github.com/burbilog/android-as-proxy-2/actions/runs/43581141257)
both **PASS**. The actual Compose test types only the six-digit setup
code (not the Unix prefix), verifies the independently derived full SSH
host fingerprint, and confirms the generated permanent S2 SSH account is
exactly the matching `aap2pNNNNNN`. The real server approves the Keystore
public key and the test reaches HTTP through the restricted reverse SOCKS.
The same run also passes signed test APK update, locked pre-unlock reboot,
and server revoke.

The short-code user experience is therefore accepted in emulator CI.
A physical *fresh enrollment* with the new user interface is a separate
test. **Seuic Cruise2 is a reusable, disposable A1 test phone**: after
recording the successful 20000 → 20001 update / Direct Boot results,
it is acceptable to revoke its S2 test account, uninstall AAP2,
install a freshly release-signed higher-version APK, and enroll again.
Uninstall deletes the previous AAP2 profile/Keystore identity by design;
new enrollment must receive a new key and server account. A factory reset
of the whole phone is not required. **Never** apply this procedure to
Zebra, the separate `aap2-test` VM, or the soak monitor.

## Single-code UX revision — in development, 2026-11-14

Physical Seuic test of the earlier six-digit+PIN UI passed: code `692103`,
new permanent SSH user `aap2p692103`, verified phone key approved by S2,
HTTPS via `127.0.0.1:11010` succeeded. A real manual-entry mistake
(`792103` instead of `692103`) caused `JSchException`, and the
two-field interface obscured the exact cause. New UX deliberately collapses
account selector and password into one checksum-protected entry.

- Server S2 `--manual-code`: prints one 18-digit code with two MOD97 check
  digits, grouped 4-4-4-4-2. Legacy non-manual CLI still displays a distinct
  ten-digit PIN, to avoid altering older disposable SSH tests.
- Android: one masked numeric entry with Show/Hide and automatic visual
  grouping; code can be checked before SSH. Its value stays in RAM on
  retry, but is never written into saved-state/preferences/logcat.
  Full host fingerprint still requires explicit independent confirmation;
  one code does **not** combine or bypass host-key verification.
- The internal PIN remains ten random digits; check digits detect typos,
  **not** malicious guesses. S2 policy still limits PIN lifetime and
  requires manual admin approval and restricted publickey forwarding.
- CI and physical acceptance of this *new single-code* UI are separate from
  the previously passed two-field physical and CI tests. Do not alter
  running Seuic or working Zebra until the revised build passes CI.

## Unified activation-code CI acceptance — 2026-11-14

On commit `d8bb0bd5e55caa4feb0fb13ce2f6fd03dd87fd48`, both
[Android build/unit workflow #43587742958](https://github.com/burbilog/android-as-proxy-2/actions/runs/43587742958)
and [full Android 11/S2 E2E #43587742934](https://github.com/burbilog/android-as-proxy-2/actions/runs/43587742934)
**PASS**. The E2E explicitly confirms that one mistyped activation-code
digit is rejected **before any SSH authentication**, that the real Compose
single-field form completes fingerprint confirmation → SFTP → key approval →
restricted reverse SOCKS5 HTTP, and that APK update, locked Direct Boot
and server revoke still work. The earlier two-field physical Seuic acceptance
remains valid but **does not** replace a new physical trial of the unified code.

## Physical unified-code acceptance — Seuic Cruise2, Android 14 (2026-11-14)

The disposable Seuic Cruise2 was reinstalled with locally release-signed
`versionCode 20003` (same long-term signing certificate as `20002`).
The isolated `aap2-a1-test` server had S2 `--manual-code` with the
new **single 18-digit activation code** and MOD97 checksum.

The operator manually entered the activation code on the phone; Android
accepted it, verified the server identity, uploaded the Keystore public
key and waited for administrator approval. After approval, SOCKS5 HTTPS
from the isolated S2 container to Cloudflare succeeded over the device's
reverse SSH tunnel. **Physical single-code activation E2E: PASS.**

The code and PIN are never collected in this document. This test does not
establish prolonged OEM background resilience, battery optimization behavior
or repeated Wi-Fi-loss recovery for the revised build. Preserve the
operational Zebra and its independent soak.

## Physical rotation bug and A1 fix (2026-11-15)

On the operator-held Zebra TC21, release-signed `20006` lost the server
address, 18-digit activation entry and pre-auth host-fingerprint step when the
device was rotated after tapping **Check server fingerprint**. The previous
Compose `remember` values belonged to a recreated `EnrollmentActivity`.
This is a real release blocker for manual activation.

A1 now handles only orientation/screen-size/keyboard-hidden configuration
changes in `EnrollmentActivity` itself, preserving the existing transient
Compose screen, the candidate SSH host key and any ongoing network coroutine.
**No PIN, activation code or candidate host key is added to saved-instance
state, ViewModel SavedStateHandle, preferences, logs or storage.** A process
death or explicit exit still drops the uncommitted code; the separately
documented pending enrollment remains non-secret and resumable.

A real Compose instrumentation regression now rotates to landscape and back
after completing unauthenticated SSH host-key probing, asserting that the
same fingerprint, masked 18-digit code and explicit approval button survive.
It then cancels fingerprint verification and asserts the original server
address remains present. Physical acceptance and latest Android CI are
separate gates before issuing any new release candidate; use a new
`versionCode` (at least `20007`), never silently replace the previously
built `20006` binary with different bytes.

## APK replacement: preserve desired tunnel state (2026-11-15)

**Physical observation on LOCAL Zebra TC21, not the remote Japanese AAP1
phone:** in-place release-signed `20007 → 20008` with `adb install -r`
preserved Android Keystore, host pin and S2 registration ID `552310`, but
killed the foreground service. Public Debian 13 S2 SOCKS5 on loopback
`11011` went away until the administrator manually opened AAP2 and tapped
Connect. After manual reconnect, SOCKS5 HTTPS worked through the same
account and port, without PIN or re-enrollment. This was a missing
post-upgrade trigger, **not** loss of identity.

New `BootCompletedReceiver` manifest registration handles the system-only
`android.intent.action.MY_PACKAGE_REPLACED` in addition to both existing
boot broadcasts. It reuses the existing privileged foreground service and
device-protected `DirectBootTunnelProfile.wantsConnection` gate. Update
restart has its own service action, treated like boot/restart: **never**
switch `desired_running` to true when a user has disconnected; **never**
enroll or create credentials. No polling, new daemon, permissions or
periodic alarms.

CI acceptance now includes:
- Real debug-cert **in-place APK update** on Android 11; before starting ANY
  Activity or instrumentation, public SSH reverse SOCKS5 HTTP must
  automatically work again, proving `MY_PACKAGE_REPLACED` recovery.
- After explicit Disconnect, directly invoking the update receiver must
  leave the tunnel stopped and leave the persisted `desired_running` false;
  an explicit Connect restores the original Keystore account and tunnel.
- Existing pre-first-unlock Direct Boot and restricted OpenSSH E2E tests
  still run. These gates also need physical confirmation on the LOCAL
  Zebra with the next release-signed `20009` build. **Do not use the old
  `20008` binary as if it included this fix.**

## Physical upgrade auto-resume and intentional-stop acceptance — PASS (2026-11-15)

Operator-tested LOCAL Zebra TC21, ADB serial `20210523020324`,
permanent registered S2 device ID `552310` and loopback reverse SOCKS5 port
`11011` on public Debian 13 `a.isaeff.net`. The physical signing key and
existing enrolled identity were retained throughout.

- `adb install -r` with signed `20009` over `20008`: returned Success.
  Without opening AAP2, SSH/SOCKS5 HTTPS from S2 succeeded immediately,
  Cloudflare observed the local home ISP IP `195.91.221.147`.
- After tapping **Disconnect**, server reverse listener disappeared. Replacing
  the same signed `20009` APK again **did not** reconnect, including after a
  further delay. Explicitly tapping **Connect** restored the original SOCKS5
  without PIN or re-enrollment.
- Final commit `8aeb080e0a7831caaf441868367c023c729238fa`:
  Android debug build/unit [PASS #43735707744](https://github.com/burbilog/android-as-proxy-2/actions/runs/43735707744)
  and real Android 11/S2 E2E
  [PASS #43735707756](https://github.com/burbilog/android-as-proxy-2/actions/runs/43735707756).
  E2E verifies automatic SOCKS5 after actual APK replacement **before any
  Activity/instrumentation starts**, plus manual Disconnect/restart controls,
  code/fingerprint trust boundary, approved SSH, SOCKS5 and cold boot.

This confirms the package-replaced automatic reconnect feature on local
physical Android 11 and in disposable emulator CI. It does not establish
long-duration resilience on the remote Japanese Zebra; do not touch AAP1
there until an explicitly approved pilot.

## User-initiated APK update from Main screen (2026-11-15)

The approved operator requested a simple **Update** button immediately beside
**Details** on the configured AAP2 main screen. No auto-checks, background
download jobs, GitHub credentials, own installer service or silent install.

**Tap Update → download `https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk` over HTTPS** → GitHub redirects to its most recent
**stable** published Release asset (pre-releases are intentionally excluded) →
require HTTPS on every redirect, allow only `github.com` and
`*.githubusercontent.com`, enforce a 100 MiB cap → inspect the downloaded APK
with Android PackageManager: same `applicationId`, same current signing
certificate, and a strictly greater `versionCode` → share the **app-private
cached APK only** via `FileProvider` → launch the Android system package
installer. Android independently verifies the actual APK signature and always
asks for user installation confirmation.

On Android 8+, if AAP2 is not allowed to request installs yet, the button
opens **Allow from this source** for this application's package. Upon returning
with permission granted, it proceeds to the standard Android installer prompt.
A denied permission leaves the running SSH/SOCKS5 service untouched. The update
button is disabled only while this foreground UI download is active.

On successful install, Android kills the old APK process and sends
`MY_PACKAGE_REPLACED`; the previously verified, physically tested
`desired_running` restore gate reconnects only when the user wanted it.
Neither the updater nor the installer clears the Android Keystore identity,
host pin, existing S2 approval, or disabled/Disconnect state.

**Release prerequisite:** GitHub must have a published **non-prerelease**
Release whose asset is named exactly `aap2.apk`. The project's public
`https://isaeff.net/a` URL remains an optional convenience for first-time
human installation and is **not used by the Android app**. This deliberately
keeps the updater independent of the author's personal domain. The updater deliberately
refuses nonexistent, same-version, downgraded, wrong-package or
differently-signed APKs. Android UI/unit CI exercises button presence and
URI policy without attempting an accidental live app upgrade. Physical test
of the update UI and Android installer on local Zebra is a separate gate.
The first APK with this UI must have a fresh versionCode, at least `20010`.

## Remaining acceptance gates

- [x] Six-digit manual-typing Compose enrollment with disposable S2 `--manual-code` — full [Android 11 E2E run #43581141257](https://github.com/burbilog/android-as-proxy-2/actions/runs/43581141257) **PASS** (2026-11-14), including Compose text entry, PIN/SFTP/approval/SSH `-R`/SOCKS5 HTTP, signed in-place test upgrade, pre-unlock Direct Boot and revoke.
- [x] Positive Compose UI enrollment, S2 approval, actual SOCKS5 HTTP and revoke on emulator.
- [x] Negative host/PIN/SFTP/JSON/`permitlisten` paths and no-PIN Resume.
- [x] Same-certificate in-place APK update and pre-first-unlock FBE reboot on emulator.
- [x] Legacy password/TOFU implementation removal and Android CI regression.
- [x] Physical A1 enrollment and Direct Boot before first unlock on a **spare device** (Seuic Cruise2, Android 14/API 34, 2026-11-14).
- [x] Basic physical Wi-Fi off/on recovery: after disabling and re-enabling Wi-Fi on Seuic Cruise2, SOCKS5 HTTPS again succeeded via the same S2 reverse port `11010` (2026-11-14).
- [x] Physical single 18-digit activation-code enrollment on Seuic Cruise2 with release-signed `20003`, administrative approval, and actual SOCKS5 HTTPS via Cloudflare (2026-11-14).
- [x] Physical reconnect UI on release-signed `20005`: visible Reconnecting state, Disconnect while retrying remains stopped after SSH restoration, manual Connect returns working SOCKS5 (Seuic Cruise2; isolated S2, 2026-11-14).
- [ ] Physical Zebra TC21 rotation test of the revised pre-auth fingerprint screen (must preserve server address and 18-digit code in RAM); Android CI with real rotation must pass before release.
- [x] Physical in-place update `20008 → 20009` automatically restored LOCAL Zebra SOCKS5 through public S2 (`a.isaeff.net`, port `11011`) without opening the app. Subsequent manual Disconnect → reinstall same signed `20009` → still Disconnected, and explicit Connect restored the same SOCKS5 (2026-11-15).
- [ ] Physical AAP2 Update button: download a newer stable signed APK directly from GitHub Releases, Android unknown-source permission and installer confirmation, preserve profile and auto-restore, on LOCAL Zebra (do not touch remote AAP1).
- [ ] Long-running foreground-service reliability and OEM battery restrictions under extended soak; repeated network-loss/reconnect cycles remain a separate gate.
- [x] Update from one **release-signed** APK to another using the long-term key kept on `marduk`, preserving identity and verified host key, confirmed with real SSH and SOCKS5 HTTP on Seuic Cruise2, 2026-11-14. See `docs/P0_RELEASE_SIGNING.md`. The guarded local `scripts/aap2-spare-upgrade.sh` verifies both signatures and versions and requires an explicit spare-device ADB serial; it never uninstalls AAP2 or touches the working Zebra by default.

**Release caution:** never install CI debug-signed APKs over an existing release-signed AAP2. Test in-place signed updates on the local Zebra only, and keep the remote Japanese AAP1 phone untouched. Long-running reliability and device-specific restrictions remain a separate pilot gate.
