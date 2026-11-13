# One-command local AAP2 build, signing and deployment

This is a **development/test** workflow. Both APKs are compiled as Android
**debug** variants (including instrumentation) and then signed **locally**
with the same permanent `~/aap2/aap2-release.p12` key used by P0-B. The
private key never leaves marduk; CI builds remain unsigned or ephemeral
debug-signed, never distributed as the permanent-signed app. Do not publish
the debug APK as a production release.

The source branch `feature/p0-local-deploy` integrates the P0-A Keystore/
Direct Boot/negative test code with the P0-B versionCode override and signing
configuration. The script clones/updates a **dedicated** source folder
`~/aap2/p0-local-build`. It does not touch `~/aap2/x`,
`~/aap2/p0b-build` or the signing key, nor the legacy AAP1 package.

## One-time bootstrap on marduk

```zsh
curl -fsSL \
  https://raw.githubusercontent.com/burbilog/android-as-proxy-2/feature/p0-local-deploy/scripts/aap2-local.sh \
  -o ~/aap2/aap2.sh
sh -n ~/aap2/aap2.sh
```

No extra download of CI artifacts. Requires existing local JDK, Git, Gradle
wrapper, Android SDK Build Tools 35, ADB, signing PKCS12 and its password.
`ANDROID_HOME` is set/exported from `~/Android/Sdk` automatically if not
already exported (addresses the Gradle SDK-not-found issue).

## Regular use

```zsh
sh ~/aap2/aap2.sh         # build, sign 2 APKs and adb install -r both
sh ~/aap2/aap2.sh build   # build and sign, leave phone untouched
sh ~/aap2/aap2.sh install # install last signed APKs without rebuilding
```

Artifacts:

```text
~/aap2/local-apk/app.apk
~/aap2/local-apk/tests.apk
```

The command prompts interactively for the signing password **twice**, once
per APK (no password in CLI arguments, environment or GitHub). Signing is
verified with `apksigner verify`. By default, versionCode is read from the already-installed AAP2 package (or `20003` if no AAP2/device is found). This avoids an accidental downgrade on repeat deployments; Android permits reinstalling a new debug APK with the **same** versionCode and certificate. To intentionally test a version increase, set `AAP2_VERSION_CODE` explicitly. Default versionName is `2.0.0-p0-local`, and can be overridden with `AAP2_VERSION_NAME`. Reinstall with the same
versionCode and the same certificate is supported for testing.

If multiple Android devices are connected, set `ANDROID_SERIAL` to select
the intended device. No automatic `adb uninstall` is ever done in
`deploy` or `install`.

## One-time CI debug-signature migration

On the first run, `adb install -r` may fail
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the old GitHub Actions debug key is
different from your durable signing key. **Nothing is deleted automatically.**

To explicitly reset only AAP2 and its test package (never AAP1) and install
the already-built, newly signed pair:

```zsh
sh ~/aap2/aap2.sh reset
```

The script requires typing `RESET-AAP2` in the terminal. This
**deletes the old AAP2 Android Keystore SSH identity**, direct-boot profile
and preferences. Recreate its P-256 key, re-authorize the **new** public key
in `aap2-test:/home/aapdev/.ssh/authorized_keys`, and re-provision Direct
Boot with the existing `~/aap2/x` helper. Do not just run `sh x` without
authorizing the new identity. AAP1 is never uninstalled.

If *only* the instrumentation test APK has a signature mismatch, remove
`net.isaeff.android.asproxy2.test` alone and rerun `install`, without
uninstalling the main AAP2 package and without destroying its identity.

Once the app is installed with the permanent signature, subsequent
`deploy` actions use `adb install -r` and preserve the Android Keystore
identity. A real on-device update test is still required to confirm this
for Zebra; a successfully signed build alone is not sufficient.

## Run P0-A negative tests

After a successful install **and** restoring the real identity/profile,
stop AAP2 through the notification **Stop** before the auth-failure test.
Then use the test invocations from `docs/P0_DIRECT_BOOT.md`:

- `missingKeystoreIdentityFailsClosed`: look up absent *test* alias without
  altering real Keystore identity.
- `rejectedPublickeyAuthenticationStopsRetryLoop`: real pinned sshd, random
  nonexistent user, fatal SSH rejection, no repeated login.

No SSHD configuration, keys, or AAP2 saved profile are changed by these
tests. Use `sh x` afterward to restore `desired_running=true`, reboot if
checking Direct Boot, then verify HTTP 200 through the reverse SOCKS5.

## Safety

- Never commit, upload, or print `aap2-release.p12` or its password.
- Do not use local debug APK for public distribution.
- Existing `~/aap2/p0-local-build` source is updated via
  `git merge --ff-only`, not `reset --hard`.
- `reset` is opt-in and cannot run silently. The script only removes the
  two AAP2 package names, never legacy AAP1.

## P0 acceptance on real Zebra TC21 — 2026-11-13

- Original ephemeral CI-debug signed AAP2 rejected permanent-signed APK
  with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; deliberate `reset`
  removed **AAP2 and its instrumentation package only**. Both APKs
  installed successfully using permanent certificate SHA-256
  `574d67d31d15c2d7b6babfad13e7a65893ef94af706338671a8927291854f1e1`.
- New P-256 Android Keystore key created and authorized on
  `aap2-test`; Direct Boot profile provisioned by `sh ~/aap2/x`.
  Both negative tests passed: missing alias (0.123s) and rejected SSH
  public key login (6.824s).
- An in-place version 20003 deployment installed both locally signed
  APKs with `adb install -r`; the persisted Keystore public key before
  and after upgrade was byte-identical (`cmp` PASS).
- Separate repeat `sh ~/aap2/aap2.sh install` gave two `Success`;
  immediately followed by `adb reboot`, `adb wait-for-device`,
  30s wait and SOCKS5 `curl` via `127.0.0.1:10909`, which returned
  **HTTP 200**. **No `sh x` was invoked** between install and reboot:
  the Direct Boot profile continued to work without reprovisioning.

**Result:** P0-A/P0-B device acceptance PASS. Debug/test APKs remain
development-only; normal production UX and server rollout are separate gates.

### Local script refresh

If `~/aap2/aap2.sh` was downloaded before 2026-11-13 version-code
fix, refresh it once. Recent script defaults to reading the installed
`versionCode` to avoid downgrade on future bare `deploy` calls.

```zsh
curl -fsSL https://raw.githubusercontent.com/burbilog/android-as-proxy-2/feature/p0-local-deploy/scripts/aap2-local.sh -o ~/aap2/aap2.sh
sh -n ~/aap2/aap2.sh
```
