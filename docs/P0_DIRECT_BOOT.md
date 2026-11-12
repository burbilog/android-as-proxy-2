# P0-A Direct Boot: unattended SSH reverse SOCKS on Zebra TC21

**Scope:** isolated P0 proof, **not** production enrollment. The app remains
`net.isaeff.android.asproxy2`; AAP1 is untouched. No SSH password or PIN is
stored in device-protected storage. The permanent P-256 key stays in Android
Keystore and is **never silently regenerated**.

This implementation starts the same foreground SOCKS5 service after
`LOCKED_BOOT_COMPLETED`. For the Keystore profile, `BootCompletedReceiver`
and `SocksForegroundService` are `directBootAware`. An explicitly provisioned
profile contains only hostname/SSH port, username, remote loopback port and the
independently verified SSH server host-key blob. A missing/invalid profile does
not enable the new path. The pre-existing password path is not used before unlock.

## Update the APKs

Download both artifacts from the newest successful PR #2 Actions run:
`aap2-debug-apk` and `aap2-p0-androidTest-apk`.
They must come from the **same** run. Extract both.

**Important signing trap:** GitHub Actions Android debug signing key is not
persisted across fresh runners yet. If `adb install -r` fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, do not attempt to bypass signature
verification. A one-time uninstall/reinstall of **AAP2 only** is required for
this proof; it **deletes its private Keystore identity**, so re-run
`keystorePublicKeyAndPersistence` and replace the AAP2 key in
`aap2-test:/home/aapdev/.ssh/authorized_keys`. AAP1 is a different
application ID and must never be removed.

```sh
adb install -r app-debug.apk
adb install -r app-debug-androidTest.apk
```

If the signature error occurs:

```sh
adb uninstall net.isaeff.android.asproxy2.test
adb uninstall net.isaeff.android.asproxy2
adb install app-debug.apk
adb install app-debug-androidTest.apk
```

Follow `docs/P0_SSH_PROOF.md` to regenerate and authorize the new P-256
public key. The permanent APK signing scheme is a separate P0-B gate.

## One-time Direct Boot profile provisioning

On `marduk` with Zebra attached to ADB:

```sh
TEST_RUNNER=net.isaeff.android.asproxy2.test/androidx.test.runner.AndroidJUnitRunner
TEST_CLASS=net.isaeff.android.asproxy.P0SshIntegrationTest

# Actual IPv4 of marduk reachable by Zebra; NOT 127.0.0.1.
HOST_IP=192.0.2.10

# Both values come from the trusted VM console, NOT from a network TOFU probe.
HOST_KEY_B64=$(incus exec aap2-test -- awk '{print $2}' /etc/ssh/ssh_host_ecdsa_key.pub)
HOST_SHA256=$(incus exec aap2-test -- ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub -E sha256 | awk '{print $2}')

adb logcat -c
adb shell am instrument -w \
  -e class "${TEST_CLASS}#provisionDirectBootTunnel" \
  -e host "$HOST_IP" \
  -e port 22222 \
  -e user aapdev \
  -e host_key_base64 "$HOST_KEY_B64" \
  -e trusted_sha256 "$HOST_SHA256" \
  -e remote_port 10909 \
  "$TEST_RUNNER"

adb logcat -d -s AAP2-P0:I '*:S'
```

Expected: `OK (1 test)` and `BOOT_PROFILE_CONFIGURED port=10909`.
If `provisionDirectBootTunnel` fails, do not proceed to reboot.
This checks the network host key against the independently verified key
**before storing anything**. No secret is transferred through ADB args.

## Reboot without first unlock

```sh
adb logcat -c
adb reboot
adb wait-for-device
```

**Do not unlock Zebra.** From `marduk`, use the server-side check:

```sh
incus exec aap2-test -- ss -lnt
incus exec aap2-test -- curl --max-time 25 \
  --proxy socks5h://127.0.0.1:10909 \
  https://example.org/
```

Expected: `127.0.0.1:10909 LISTEN` and HTTP 200.
The debug receiver also checks that Keystore signing works before first
unlock. Logcat, if ADB works while locked:

```sh
adb logcat -d -s AAP2-P0:I '*:S'
```

Look for `DIRECT_BOOT_KEYSTORE_OK`, `BOOT_RECEIVER_START`,
`BOOT_TUNNEL_STARTED`, `BOOT_FORWARD_READY 127.0.0.1:10909`.
On a cold boot, Wi-Fi can come up late; retry/backoff occurs inside the
foreground service without restarting it. A missing Keystore key,
authentication rejection or host-key mismatch must fail closed.
Force-stop and OEM battery restrictions remain outside these guarantees.

## Negative SSH host-key test (no changes to sshd)

This instrumentation test connects to the existing **test** SSH server using a
deliberately wrong local host-key pin. It first checks the live host key against
the independently trusted VM key, so a missing/wrong server cannot produce a
false success. It uses the existing P-256 identity to exercise the same JSch
authentication path, but **no password, PIN, remote forwarding or server-side
SSH changes**. The incorrect pin exists only in test memory and is not stored.

**Stop AAP2 normally using its notification Stop** before running the
instrumentation test, so the test cannot interrupt a live SOCKS tunnel. Then:

```zsh
TEST_RUNNER=net.isaeff.android.asproxy2.test/androidx.test.runner.AndroidJUnitRunner
TEST_CLASS=net.isaeff.android.asproxy.P0SshIntegrationTest
HOST_IP=192.168.0.242
HOST_KEY_B64=$(incus exec aap2-test -- awk '{print $2}' /etc/ssh/ssh_host_ecdsa_key.pub)

adb logcat -c
adb shell am instrument -w \
  -e class "${TEST_CLASS}#changedHostKeyRejectedBeforePublickeyAuth" \
  -e host "$HOST_IP" \
  -e port 22222 \
  -e user aapdev \
  -e host_key_base64 "$HOST_KEY_B64" \
  "$TEST_RUNNER"
adb logcat -d -s AAP2-P0:I '*:S'
```

Expected: `OK (1 test)` and
`HOST_KEY_MISMATCH_REJECTED_PREAUTH`.
This proves the incorrect pin stops SSH prior to authentication and that the
stored device profile and Keystore key are unchanged. No remote port is
requested by this test. **Do not** change or delete the actual SSH host keys
in `aap2-test`.

## Wi-Fi recovery with network-triggered backoff wake

The existing exponential backoff remains 5/10/20/40/80/120 seconds while
unreachable. The running foreground service now registers a **default-network
callback**, and `onAvailable` interrupts an ongoing retry delay: no second
connection thread, no broadcast receiver, and no new service start.
The callback is unregistered on service destruction. If network callbacks are
unavailable, timer-based retry continues. No manual restart should be needed.

After installing the latest **matching** debug APK/test APK, re-provision
the Direct Boot profile if the old CI debug signature required uninstall.
No P0-B release signing material is involved in this test.

```zsh
adb logcat -c
adb shell svc wifi disable
sleep 60
adb shell svc wifi enable
sleep 30

adb logcat -d -s AAP2-P0:I '*:S'
incus exec aap2-test -- /usr/bin/ss -lnt | grep 10909 || echo 'No listener'
incus exec aap2-test -- curl -sS --max-time 25 \
  --proxy socks5h://127.0.0.1:10909 \
  -o /dev/null -w '%{http_code}\\n' https://example.org/
```

Look for `BOOT_NETWORK_AVAILABLE`, `BOOT_RETRY_NETWORK_WAKE`
(if a backoff sleep was interrupted), then `BOOT_FORWARD_READY`,
and `200`. A new default network may become available during an
in-progress SSH connect timeout; the callback will wake the next retry
after that attempt fails. If no network changes occur, normal backoff applies.
The already observed ~80-second recovery on 2026-11-12 is the baseline;
the improved result needs a new real-device test before acceptance.

## Confirm that Stop prevents a boot reconnect

Use the AAP2 foreground notification's **Stop** action, and then reboot
again without unlocking. There must be **no** `10909` listener.

For P0 test cleanup, after unlocking:

```sh
adb shell am instrument -w \
  -e class "${TEST_CLASS}#disableDirectBootTunnel" \
  "$TEST_RUNNER"
```

This clears the DE profile but **does not delete the Keystore key**.
Legacy password-mode settings remain separate in credential-protected
storage. This is a debug instrumentation provisioning tool, not a user UI.

## Acceptance

- [x] Provisioning test passed (`OK (1 test)`), pinned host key matched independently obtained fingerprint.
- [x] After `adb reboot`, **before first unlock**, `ss` showed `127.0.0.1:10909` on `aap2-test`.
- [x] SOCKS5 `curl` via the VM succeeded before first unlock (`https://example.org/`, HTTP/2 200, TLS 1.3, DNS via socks5h).
- [ ] Keystore remains stable; no password, PIN or SSH private key exists in DE storage.
- [x] Notification **Stop** persisted device-protected `desired_running=false`; after `adb reboot` `ss` showed `No listener` on the VM.
- [ ] Mismatched host key rejected pre-auth (new non-destructive test; awaiting Zebra run). Missing Keystore key remains a separate negative test.
- [x] Wi-Fi off (60 seconds) / on: SSH reconnected by itself, `BOOT_FORWARD_READY` 21:41:25 and subsequent SOCKS5 HTTPS request returned HTTP 200. Baseline reconnect PASS, but exponential backoff caused a long recovery delay.
- [ ] Network-callback fast reconnect (new code): verify the next Wi-Fi off/on test wakes an in-progress backoff.
- [ ] Observe full **physical power cycle** and extended unattended idle separately; an `adb reboot` or Wi-Fi toggle does not prove these cases.

The boot and Stop tests were performed on Zebra TC21 on 2026-11-12 (Android 11); observed: `DIRECT_BOOT_KEYSTORE_OK` 20:29:18.543, `BOOT_RECEIVER_START` 20:29:18.570, `BOOT_TUNNEL_STARTED` 20:29:18.782, `BOOT_FORWARD_READY` 20:29:19.291. Remaining unchecked checklist items are unverified. Note: stopping the application from Android system settings is not the same as AAP2 notification Stop and does not call `ACTION_STOP`.
