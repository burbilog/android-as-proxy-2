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

- [ ] Profiling test passes and pinned host key matches independently obtained fingerprint.
- [ ] After actual cold reboot, before first unlock, `ss` shows `127.0.0.1:10909`.
- [ ] SOCKS5 `curl` via the VM succeeds before first unlock.
- [ ] Keystore remains stable; no password, PIN or SSH private key exists in DE storage.
- [ ] Stop disables new boot reconnect, including after a further reboot.
- [ ] Missing/changed host key or missing Keystore identity fails closed.
- [ ] Observe Wi-Fi reconnect behavior across a real power cycle, not only `adb reboot`.

None of these checks are considered passed until performed on the real Zebra.
