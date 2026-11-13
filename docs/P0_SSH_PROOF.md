# P0-A: Android Keystore / JSch / Debian SSH proof

**Status:** Test harness implemented; real Zebra TC21 verification pending. This is **not**
the enrollment wizard and does not change AAP2's existing password-based UI.
Use the isolated Incus VM `aap2-test` only. AAP1 is untouched.

## What this branch implements

- `VerifiedSshHostKey.probe()`: SSH KEX verifies the host-key signature, captures the
  exact SSH public-key blob, and **always rejects the host** before user authentication.
  It does not send passwords/PINs and never pins an unverified key.
- `VerifiedSshHostKey.pinnedJsch()`: subsequent authentication accepts only an
  **independently verified exact host-key blob**; a changed key fails closed.
- `AndroidKeystoreIdentity`: stable Android Keystore `secp256r1` key,
  `SHA256withECDSA`, mwiede/JSch `Identity` adapter and SSH RFC 5656 formatting.
  No private-key export, no silent re-creation after recorded loss.
- Android instrumentation: public-key export, KEX probe, publickey SSH authentication
  and loopback reverse forward using the existing SOCKS5 relay.
- Debug-only `LOCKED_BOOT_COMPLETED` receiver reports whether signing works
  before first unlock. It does **not** start a network connection or regenerate keys.

This proof does **not** claim Direct Boot reverse forwarding yet. That remains the
P0-A real-device gate together with end-to-end enrollment.

## 1. Build and install on Zebra

From this PR's branch (not `develop` until merged):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The AndroidTest APK is uploaded as a separate GitHub Actions artifact on PRs to
`develop`. Release signing is **not** configured by this proof.

On `marduk`, set `HOST_IP` to the actual IPv4 address on which Incus publishes
port 22222, reachable by the Zebra (do not use `127.0.0.1`):

```sh
HOST_IP=192.0.2.10
TEST_RUNNER=net.isaeff.android.asproxy2.test/androidx.test.runner.AndroidJUnitRunner
TEST_CLASS=net.isaeff.android.asproxy.P0SshIntegrationTest
```

Replace `192.0.2.10` with your real Incus-host address.

## 2. Produce the phone's persistent public key

```sh
adb logcat -c
adb shell am instrument -w -e class "$TEST_CLASS#keystorePublicKeyAndPersistence" "$TEST_RUNNER"
adb logcat -d -s AAP2-P0:I '*:S'
```

Copy the complete line after `PUBLIC_KEY` (starts with `ecdsa-sha2-nistp256`).
The **public** key is safe to copy; the private key stays in Android Keystore.
Run this test twice and compare key content; it must not change.

Inside `aap2-test` as root, add that one key to
`/home/aapdev/.ssh/authorized_keys` using `vi`; permissions:

```sh
install -d -m 700 -o aapdev -g aapdev /home/aapdev/.ssh
chown aapdev:aapdev /home/aapdev/.ssh/authorized_keys
chmod 600 /home/aapdev/.ssh/authorized_keys
```

Do not replace the existing administrator key; append this test key.

## 3. Compare host fingerprint before allowing authentication

Read the fingerprint from inside your trusted Incus guest (not from the
untrusted network alone):

```sh
incus exec aap2-test -- ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Get the raw verified host-key blob:

```sh
HOST_KEY_B64=$(incus exec aap2-test -- awk '{print $2}' /etc/ssh/ssh_host_ed25519_key.pub)
```

Trigger a **pre-auth only** probe on Zebra:

```sh
adb logcat -c
adb shell am instrument -w -e class "$TEST_CLASS#probeHostKeyBeforeAuthentication" -e host "$HOST_IP" -e port 22222 "$TEST_RUNNER"
adb logcat -d -s AAP2-P0:I '*:S'
```

The `HOST_KEY SHA256:...` line must **exactly** match the trusted
`ssh-keygen` output. If it does not, stop. Never use a first-seen key as
the trusted one just because an SSH connection completed.

## 4. Prove publickey auth and remote -R (60 s window)

```sh
adb logcat -c
adb shell am instrument -w -e class "$TEST_CLASS#publickeyAuthAndLoopbackReverseForward" -e host "$HOST_IP" -e port 22222 -e user aapdev -e host_key_base64 "$HOST_KEY_B64" -e remote_port 10909 "$TEST_RUNNER"
```

While that test is running, in another terminal **inside aap2-test**:

```sh
ss -lnt | grep 10909
curl --max-time 15 --proxy socks5h://127.0.0.1:10909 https://ifconfig.me/ip
```

The remote listener must bind only `127.0.0.1:10909`, and HTTP traffic
must go through the phone's SOCKS5 relay. The test closes the SSH tunnel
after 60 seconds; repeat if needed. Never use `0.0.0.0` for the remote
listener. SSH failure or a host-key mismatch is a **test failure**.

## 5. Cold boot without unlocking

After the public-key test, keep the **debug** APK installed, reboot Zebra,
and do not unlock it yet:

```sh
adb reboot
adb logcat -d -s AAP2-P0:I '*:S'
```

Look for `DIRECT_BOOT_KEYSTORE_OK` or
`DIRECT_BOOT_KEYSTORE_FAILED`. It proves only whether the existing
Keystore key can sign in Direct Boot. It does **not** prove SSH networking
or foreground-service survival before the first unlock. On some phones
USB debugging/logcat may not be available until unlock; inspect logs
after unlock if retained.

## Acceptance and limitations

- [ ] Public key remains identical across app restart and normal reboot.
- [ ] Probe's full SHA256 fingerprint equals `ssh-keygen -E sha256` on the guest.
- [ ] Wrong host-key blob is rejected before publickey auth.
- [ ] JSch authenticates with Android Keystore P-256 and installs a loopback `-R`.
- [ ] `curl` through the forwarded SOCKS5 port actually succeeds.
- [ ] Cold boot **before first unlock**: Keystore signature works or a clear limitation is recorded.
- [ ] Full cold-boot `-R` with FGS and device-protected config (later implementation).

No task in this file is checked until verified on the real Zebra.
