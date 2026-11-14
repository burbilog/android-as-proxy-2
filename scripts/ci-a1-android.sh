#!/usr/bin/env bash
# Disposable Android emulator -> host loopback OpenSSH S2; no adb reverse.
# This modifies only an ephemeral CI runner, NEVER a real Android device.
set -euo pipefail
umask 077

[ "${GITHUB_ACTIONS:-}" = true ] && [ "${AAP2_A1_DISPOSABLE:-}" = YES ] ||
  { echo 'A1 test refuses non-disposable runner' >&2; exit 2; }

ROOT="$PWD"
SERVER="$ROOT/s2-fixture/server"
FIXTURE="$ROOT/scripts/ci-a1-server.sh"
PKG=net.isaeff.android.asproxy2
RUNNER="${PKG}.test/androidx.test.runner.AndroidJUnitRunner"
TEST=net.isaeff.android.asproxy.A1EnrollmentIntegrationTest
STATE=/tmp/aap2-a1-ci

sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" init
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
# Android emulator 10.0.2.2 reaches runner host loopback across cold boots.
[ "$(adb shell getprop ro.crypto.type | tr -d '\r')" = file ] ||
  { echo 'A1 Direct Boot requires an FBE emulator' >&2; exit 1; }

# Negative test uses an incorrect pinned host key and fake PIN;
# must not create pending enrollment or alter the Direct Boot profile.
run_test() {
  local method="$1"
  local output
  output=$(timeout 180 adb shell am instrument -w \
    -e class "${TEST}#${method}" "$RUNNER")
  printf '%s\n' "$output"
  grep -q 'OK (1 test)' <<<"$output" || {
    echo "A1 instrumentation FAILED: $method" >&2
    return 1
  }
}
TRUSTED_SHA256=$(sudo ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub -E sha256 | awk '{print $2}')
UI_TEST=net.isaeff.android.asproxy.A1EnrollmentUiTest
UI_OUTPUT=$(timeout 150 adb shell am instrument -w \
  -e class "${UI_TEST}#fingerprintMustBeDisplayedAndExplicitlyVerifiedBeforePinAuth" \
  -e trusted_sha256 "$TRUSTED_SHA256" "$RUNNER")
printf '%s\n' "$UI_OUTPUT"
grep -q 'OK (1 test)' <<< "$UI_OUTPUT" ||
  { echo 'A1 Compose fingerprint gate FAILED' >&2; exit 1; }
echo 'A1 PASS: Compose full host fingerprint and explicit PIN boundary'
run_test a1_0_malformedServerJsonRejectedBeforeProvisioning
run_test a1_1_wrongPinnedHostRejectedBeforeAuthenticationOrKeyCreation

# Exercise the real ten-minute TTL cleanup without waiting ten minutes:
# in the disposable fixture only, backdate the new invitation, invoke real
# S2 cleanup, then prove Android cannot enroll using its original PIN.
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" invite
SETUP_USER=$(sudo sed -n 's/^Setup username: //p' "$STATE/invite.txt")
DISPLAY_PIN=$(sudo sed -n 's/^One-time PIN: //p' "$STATE/invite.txt")
PIN="${DISPLAY_PIN//-/}"
FINGERPRINT=$(sudo ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub -E sha256 | awk '{print $2}')
[ "${#PIN}" = 10 ] && [ -n "$SETUP_USER" ] && [ -n "$FINGERPRINT" ] || exit 1
unset DISPLAY_PIN
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" expire
printf '%s\n%s\n%s\n' "$SETUP_USER" "$PIN" "$FINGERPRINT" |
  adb shell -T "run-as $PKG sh -c 'mkdir -p files && umask 077 && cat > files/a1-ci-expired-invitation && chmod 600 files/a1-ci-expired-invitation'"
unset PIN
run_test a1_1b_expiredPinCannotCreatePendingProfile

# Invitation is created AFTER emulator boots. PIN is kept only in a
# root-owned file and the debug application's temporary test sandbox.
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" invite

SETUP_USER=$(sudo sed -n 's/^Setup username: //p' "$STATE/invite.txt")
DISPLAY_PIN=$(sudo sed -n 's/^One-time PIN: //p' "$STATE/invite.txt")
PIN="${DISPLAY_PIN//-/}"
FINGERPRINT=$(sudo ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub -E sha256 | awk '{print $2}')
[ "${#PIN}" = 10 ] && [ -n "$SETUP_USER" ] && [ -n "$FINGERPRINT" ] || exit 1
unset DISPLAY_PIN

# No test PIN in adb instrumentation command-line arguments or CI logs.
printf '%s\n%s\n%s\n' "$SETUP_USER" "$PIN" "$FINGERPRINT" |
  adb shell -T "run-as $PKG sh -c 'mkdir -p files && umask 077 && cat > files/a1-ci-invitation && chmod 600 files/a1-ci-invitation'"
unset PIN

mkdir -p /tmp/aap2-a1-http
printf 'AAP2-A1-E2E-OK\n' > /tmp/aap2-a1-http/index.html
python3 -m http.server 18887 --bind 127.0.0.1 \
  --directory /tmp/aap2-a1-http >/tmp/aap2-a1-http.log 2>&1 &
HTTP_PID=$!

cleanup() {
  # Release either instrumentation process on earlier failure.
  adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-ui-verified-go; touch files/a1-ci-ui-release; touch files/a1-ci-release-test; touch files/a1-ci-release-upgrade-test'" \
    >/dev/null 2>&1 || true
  if [ -n "${WATCH_PID:-}" ]; then kill "$WATCH_PID" >/dev/null 2>&1 || true; fi
  kill "$HTTP_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Keep instrumentation alive while the host issues SOCKS/HTTP requests.
# Otherwise Android's JUnit runner can terminate the target app process
# and its foreground service before this script has tested the tunnel.
# The existing invitation first drives a complete positive Compose UI
# enrollment. The temporary PIN remains only in the app-private fixture.
adb shell -T "run-as $PKG mv files/a1-ci-invitation files/a1-ci-ui-invitation"
run_ui_positive() {
  local output
  output=$(timeout 210 adb shell am instrument -w \
    -e class "${UI_TEST}#realComposeVerifiedClickEnrollsAndStartsSocks" "$RUNNER")
  printf '%s\n' "$output"
  grep -q 'OK (1 test)' <<< "$output" ||
    { echo 'A1 complete Compose UI enrollment FAILED' >&2; return 1; }
}
run_ui_positive &
TEST_PID=$!

# Test callback publishes this file ONLY after the public-key SFTP put has
# completed. Kill the disposable setup-account session while it polls status.
# Do not touch the publickey account, any real host, or the emulator service.
wait_app_marker() {
  local marker="$1"
  for i in $(seq 1 240); do
    if adb shell -T "run-as $PKG test -f files/$marker" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$TEST_PID" 2>/dev/null; then
      echo "Android instrumentation exited before $marker" >&2
      wait "$TEST_PID" || true
      return 1
    fi
    sleep 0.5
  done
  echo "Android marker not reached: $marker" >&2
  return 1
}
wait_app_marker a1-ci-ui-fingerprint-ready
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES \
  sh "$FIXTURE" "$SERVER" assert-not-uploaded
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES \
  sh "$FIXTURE" "$SERVER" watch-approve >/tmp/aap2-a1-ui-admin.log 2>&1 &
WATCH_PID=$!
adb shell -T "run-as $PKG touch files/a1-ci-ui-verified-go"
wait "$WATCH_PID"
WATCH_PID=
cat /tmp/aap2-a1-ui-admin.log

# The app's own EnrollmentActivity must have started the production foreground
# service. Independently check its assigned SSH -R using real HTTP/SOCKS.
UI_ID=$(sudo cat "$STATE/id")
UI_PORT=$(sudo python3 - "$UI_ID" <<'PY'
import json
from pathlib import Path
import sys
print(json.loads((Path('/var/lib/aap2/state') / sys.argv[1] /
                  'metadata.json').read_text())['remote_port'])
PY
)
SUCCESS=0
for i in $(seq 1 35); do
  BODY=$(curl --noproxy '' --silent --max-time 3 \
    --proxy "socks5h://127.0.0.1:$UI_PORT" \
    http://10.0.2.2:18887/ 2>/dev/null || true)
  if [ "$BODY" = "AAP2-A1-E2E-OK" ]; then SUCCESS=1; break; fi
  sleep 1
done
[ "$SUCCESS" = 1 ] || { echo 'No real HTTP through Compose-enrolled SOCKS5' >&2; exit 1; }
echo 'A1 PASS: real Compose Verified click -> PIN/SFTP -> approve -> SSH -R -> SOCKS5 HTTP'
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES \
  sh "$FIXTURE" "$SERVER" revoke
sleep 2
if curl --noproxy '' --silent --max-time 3 \
   --proxy "socks5h://127.0.0.1:$UI_PORT" \
   http://10.0.2.2:18887/ >/dev/null 2>&1; then
  echo 'Compose-enrolled reverse listener survived revoke' >&2
  exit 1
fi
adb shell -T "run-as $PKG touch files/a1-ci-ui-release"
wait "$TEST_PID"
run_test a1_1c_resetRevokedUiFixtureWithoutDeletingKeystore

# Keep the original interrupted-SFTP/Resume, signed-update and Direct Boot
# regression suite, using another independent disposable S2 invitation.
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES \
  sh "$FIXTURE" "$SERVER" invite
SETUP_USER=$(sudo sed -n 's/^Setup username: //p' "$STATE/invite.txt")
DISPLAY_PIN=$(sudo sed -n 's/^One-time PIN: //p' "$STATE/invite.txt")
PIN="${DISPLAY_PIN//-/}"
FINGERPRINT=$(sudo ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub -E sha256 | awk '{print $2}')
[ "${#PIN}" = 10 ] && [ -n "$SETUP_USER" ] && [ -n "$FINGERPRINT" ] || exit 1
unset DISPLAY_PIN
printf '%s\n%s\n%s\n' "$SETUP_USER" "$PIN" "$FINGERPRINT" |
  adb shell -T "run-as $PKG sh -c 'umask 077 && cat > files/a1-ci-invitation && chmod 600 files/a1-ci-invitation'"
unset PIN
run_test a1_2_realPinSftpApprovalAndPersistentKeystoreTunnel &
TEST_PID=$!
wait_app_marker a1-ci-key-uploaded
sudo pkill -KILL -u "$SETUP_USER" ||
  { echo 'No disposable SFTP session to interrupt' >&2; exit 1; }
echo 'A1 CI: disconnected disposable setup SSH session after completed key upload'
wait_app_marker a1-ci-awaiting-approval

# Approval is intentionally later than the failed PIN/SFTP session. Android
# has stored only non-secret pending state and must use resume() without PIN.
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" approve
adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-approval-done'"
echo 'A1 CI: approved existing public key; Android must resume with original identity'

ID=$(sudo cat "$STATE/id")
PORT=$(sudo python3 - "$ID" <<'PY'
import json
from pathlib import Path
import sys
record = Path('/var/lib/aap2/state') / sys.argv[1] / 'metadata.json'
data = json.loads(record.read_text())
print(data['remote_port'])
PY
)

# CI host -> SSH -R -> Android SOCKS5 -> emulator 10.0.2.2 -> CI HTTP.
SUCCESS=0
for i in $(seq 1 30); do
  BODY=$(curl --noproxy '' --silent --show-error --max-time 3 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://10.0.2.2:18887/ 2>/dev/null || true)
  if [ "$BODY" = "AAP2-A1-E2E-OK" ]; then
    SUCCESS=1
    break
  fi
  sleep 1
done
[ "$SUCCESS" = 1 ] || {
  echo "No real HTTP over A1 SSH -R/SOCKS5 (port $PORT)" >&2
  echo 'Host direct HTTP:' >&2
  curl --noproxy '' -fsS --max-time 4 http://127.0.0.1:18887/ >&2 || true
  echo 'Android user state:' >&2
  adb shell am get-started-user-state 0 >&2 || true
  echo 'Remote SSH reverse listener:' >&2
  sudo ss -lntp | grep ":$PORT" >&2 || true
  echo 'Detailed proxied curl diagnostic:' >&2
  curl --noproxy '' -v --max-time 6 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://10.0.2.2:18887/ >&2 || true
  exit 1
}
echo "A1 PASS: emulator PIN→SFTP→approve→Keystore→-R→SOCKS5→HTTP"

# Test signed APK update WITHOUT ever uninstalling AAP2.
adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-release-test'"
wait "$TEST_PID"
cp app/build/outputs/apk/debug/app-debug.apk /tmp/aap2-a1-v20000.apk
AAP2_VERSION_CODE=20001 AAP2_VERSION_NAME=2.0.1-ci-upgrade \
  ./gradlew --no-daemon :app:assembleDebug
V2_APK=app/build/outputs/apk/debug/app-debug.apk
SIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
[ -x "$SIGNER" ] || { echo 'Android apksigner missing' >&2; exit 1; }
CERT1=$("$SIGNER" verify --print-certs /tmp/aap2-a1-v20000.apk |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
CERT2=$("$SIGNER" verify --print-certs "$V2_APK" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ -n "$CERT1" ] && [ "$CERT1" = "$CERT2" ] ||
  { echo 'A1 APK update certificate changed' >&2; exit 1; }
adb install -r "$V2_APK" | grep -q '^Success$' ||
  { echo 'A1 in-place APK upgrade failed' >&2; exit 1; }
echo 'A1 CI: in-place signed APK update v20000 to v20001, matching certificate'

run_test a1_3_inPlaceSignedApkUpgradePreservesKeystoreAndPin &
TEST_PID=$!
SUCCESS=0
for i in $(seq 1 30); do
  BODY=$(curl --noproxy '' --silent --max-time 3 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://10.0.2.2:18887/ 2>/dev/null || true)
  if [ "$BODY" = "AAP2-A1-E2E-OK" ]; then SUCCESS=1; break; fi
  sleep 1
done
[ "$SUCCESS" = 1 ] || { echo 'A1 SOCKS5 failed after signed APK update' >&2; exit 1; }
echo 'A1 PASS: signed in-place APK update kept Keystore, host pin, SSH and SOCKS5'
adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-release-upgrade-test'"
wait "$TEST_PID"

# A real FBE cold reboot: test no user unlock and no adb reverse repair.
adb shell locksettings set-pin --user 0 2468
adb reboot
timeout 180 adb wait-for-device
BOOTED=0
for i in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then BOOTED=1; break; fi
  sleep 1
done
[ "$BOOTED" = 1 ] || { echo 'Android cold boot did not finish' >&2; exit 1; }
USER_STATE=$(adb shell am get-started-user-state 0 | tr -d '\r')
[ "$USER_STATE" = RUNNING_LOCKED ] ||
  { echo "User not locked before first unlock: $USER_STATE" >&2; exit 1; }
SUCCESS=0
for i in $(seq 1 50); do
  USER_STATE=$(adb shell am get-started-user-state 0 | tr -d '\r')
  [ "$USER_STATE" = RUNNING_LOCKED ] ||
    { echo "User unlocked during Direct Boot test: $USER_STATE" >&2; exit 1; }
  BODY=$(curl --noproxy '' --silent --max-time 4 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://10.0.2.2:18887/ 2>/dev/null || true)
  if [ "$BODY" = "AAP2-A1-E2E-OK" ]; then SUCCESS=1; break; fi
  sleep 2
done
[ "$SUCCESS" = 1 ] || { echo 'A1 SOCKS5 failed before first unlock' >&2; exit 1; }
echo 'A1 PASS: cold reboot pre-unlock Keystore SSH -R / SOCKS5 / HTTP'

sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" revoke
sleep 2
if curl --noproxy '' --silent --max-time 3 \
   --proxy "socks5h://127.0.0.1:$PORT" \
   http://10.0.2.2:18887/ >/dev/null 2>&1; then
  echo 'A1 ERROR: transport survived server revoke' >&2
  exit 1
fi
echo "A1 PASS: server revoke killed Android reverse SOCKS listener"

# Both instrumentation sessions already finished; revoke tested last.
