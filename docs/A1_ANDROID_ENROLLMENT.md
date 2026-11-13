# A1 — Android enrollment v1 (development integration)

**Base:** the already tested P0 Android Keystore + Direct Boot + strict SSH
host-key code in \`feature/p0-local-deploy\`. This is **not** based on the
obsolete password-auth/TOFU code on the separate server S2 branch. The
server-side S2 implementation is independently verified in PRs #8/#9.

## User flow

When AAP2 has **no active Direct Boot profile**, its main screen now
offers **Activate AAP2 using one-time PIN**. This opens a separate Activity
with exactly three fields:

- \`Server\` (host or host:port; bracket IPv6 before :port);
- server-generated \`Setup username\` (\`aap2e...\`);
- the ten-digit \`PIN\` (4-4-2 reading groups; no secret persistence).

**Step 1 — NO AUTHENTICATION:** the user taps Check server fingerprint.
\`VerifiedSshHostKey.probe\` performs authenticated SSH KEX and captures the
public host-key blob, while rejecting further userauth. The full
\`SHA256:\` fingerprint and a grouped 4-character display are shown,
including host:port and key algorithm.

**Step 2 — EXPLICIT HUMAN VERIFICATION:** the user compares the ENTIRE
fingerprint independently with the admin. Nothing about the SSH server is
trusted based on a DNS lookup, configuration file or first-seen TOFU. Only
a tap on **Verified — send PIN and activate** permits password auth.

**Step 3 — PIN-only SFTP:** a NEW JSch session enforces the exact verified
host blob before PIN auth. It downloads strictly size-limited
\`config.json\` (schema 1, permanent username, reserved port), creates or
opens the persistent Android Keystore P-256 key and stores only non-secret
**pending** login details BEFORE uploading the public key into
\`inbox/public_key\`. It waits for \`status.json\` to become \`approved\`.
The PIN is held only in Activity memory / JSch's live session and never
written to preferences, logs, Intent extras or Android saved-state.

**Step 4 — permanent SSH:** after approval, a new SSH session enforces
the same pinned host blob, uses the **existing** Android Keystore identity
and verifies a real \`-R 127.0.0.1:PORT\` to local SOCKS port 11080.
Only then does it persist \`DirectBootTunnelProfile\`, clear the pending
record and start the existing foreground SOCKS service.

The normal foreground service still owns all ongoing SSH reconnect and
Direct Boot boot/recovery logic. Enrollment never creates a second
permanently running SSH manager.

## Recovery and protections

- On failure after SFTP upload, the verified host blob, permanent
  username/port and SAME Keystore alias remain in app-private pending
  storage. **No PIN** is stored. The user can click **Resume approved
  enrollment** after the admin approves the key. Resume performs exactly
  one strict pinned publickey SSH+R attempt, no password or retry loop.
- A different pending enrollment cannot silently overwrite the existing
  verified host/key. The user must explicitly discard pending metadata.
  This does not delete or regenerate the Keystore identity.
- If a Direct Boot profile exists, **the enrollment screen refuses to
  replace it**. Opening the screen cannot damage the working Zebra
  profile or its authorized client key.
- The old password/TOFU provisioning UI and password SSH service fallback have
  been removed on this A1 development branch. Without a verified Direct Boot
  profile the foreground service fails closed, even if old preferences exist.

## Gates before remotely provisioning the physical Zebra

- [x] Android CI compilation and JVM input tests — [run #43497695021](https://github.com/burbilog/android-as-proxy-2/actions/runs/43497695021) PASS.
- [ ] Actual emulator/physical Android test against the server S2
  \`enroll/approve\` flow using a **new temporary account/remote port**.
- [x] Client negative pre-auth host-key pin test PASS (`OK (1 test)`): a deliberately changed local pin is rejected before PIN auth, Keystore provisioning or pending-profile persistence. The Compose button ordering is implemented but UI tap-by-tap verification is still pending.
- [ ] Verify an interrupted SFTP connection after approval can be resumed with the existing Keystore identity; test expired PIN, malformed JSON and wrong permitlisten on Android.
- [ ] Verify after application restart, reboot-before-first-unlock and
  ordinary APK update, the newly enrolled device retains identity/pin.
- [x] Remove legacy password/TOFU provisioning UI **and** fallback SSH
  service startup. Still requires Android CI and emulator regression checks
  on the removal commits before release.

**Do not install this development A1 APK on the working Zebra yet.** The
existing P0-build and ongoing remote SOCKS soak should remain untouched
until the end-to-end registration tests are planned separately.

## Emulator-to-server acceptance — 2026-11-13

[GitHub Actions #43499869896](https://github.com/burbilog/android-as-proxy-2/actions/runs/43499869896) passed on Android 11 / API 30 x86_64 emulator using a *real* ephemeral S2 OpenSSH, PAM, and Python enrollment server on GitHub's disposable Ubuntu runner (not the production server).

- `a1_1_wrongPinnedHostRejectedBeforeAuthenticationOrKeyCreation` returned `OK (1 test)` (0.171s); wrong host pin refused before authentication or state creation.
- Server created a fresh ten-digit PIN; its private test value was streamed into app's private temporary test file, not `am instrument` args/logs. The first complete uploaded public P-256 key was approved via `aap2-setup approve`.
- `a1_2_realPinSftpApprovalAndPersistentKeystoreTunnel` returned `OK (1 test)` (8.712s) after full independent host fingerprint comparison, password-only SFTP, key publication, permanent Keystore publickey authentication, exact reverse listener verification and start of Android foreground SOCKS.
- Host **actually fetched HTTP through the Android SOCKS5 reverse SSH tunnel**: `A1 PASS: emulator PIN→SFTP→approve→Keystore→-R→SOCKS5→HTTP`. HTTP server was CI-local; no public internet dependency.
- Explicit `aap2-setup revoke` terminated the approved account's active reverse session: `A1 PASS: server revoke killed Android reverse SOCKS listener`.
- The instrumentation test deliberately stayed alive during host HTTP/revoke checks because the Android test runner can terminate its process (and foreground service) after a test returns. That is an **emulator test harness detail**, not an AAP2 service lifetime change.

**Scope:** This proves core Android↔S2 transport and fail-closed host-pin behavior on a disposable emulator. It does not yet prove the Compose UI user-action flow, reboot-before-first-unlock, interrupted SFTP recovery, malformed server JSON, or preserving identity on physical Zebra after enrolling this way. This is still a **draft development branch**; the existing P0 APK on Zebra is untouched.

## Legacy path retirement (2026-11-14)

The A1 branch now exposes only verified PIN enrollment on the main screen.
The foreground service refuses to start SSH unless a validated Direct Boot
profile exists; leftover legacy password settings cannot silently reconnect
on boot or after an APK upgrade. Existing P0 Keystore/Direct Boot profiles
still use the original tunnel implementation and are not rewritten. This
change is **not yet regression-tested** on the emulator; PR stays draft.
