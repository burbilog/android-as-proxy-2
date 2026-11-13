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
- The legacy password UI from the earlier development version remains
  for now. It is not used by this new PIN enrollment path and will be
  retired/isolated after end-to-end A1 acceptance.

## Gates before remotely provisioning the physical Zebra

- [ ] Android CI compilation and JVM input tests.
- [ ] Actual emulator/physical Android test against the server S2
  \`enroll/approve\` flow using a **new temporary account/remote port**.
- [ ] Demonstrate that the PIN never crosses SSH before Verified, and a
  changed host key fails closed before authentication.
- [ ] Verify an interrupted SFTP connection after approval can be resumed
  with the existing Keystore identity; test expired PIN, malformed JSON
  and wrong permitlisten.
- [ ] Verify after application restart, reboot-before-first-unlock and
  ordinary APK update, the newly enrolled device retains identity/pin.
- [ ] Remove the old freeform password/TOFU UI path before a public
  production release (or explicitly disable it from release variants).

**Do not install this development A1 APK on the working Zebra yet.** The
existing P0-build and ongoing remote SOCKS soak should remain untouched
until the end-to-end registration tests are planned separately.
