#!/usr/bin/env bash
# GitHub Actions Android emulator -> adb reverse -> real disposable S2 sshd.
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
# Android emulator reaches the host loopback on 10.0.2.2, including after
# reboot. Do NOT use adb reverse: its routes disappear across cold boots.
[ "$(adb shell getprop ro.crypto.type | tr -d '\r')" = file ] ||
  { echo 'A1 Direct Boot requires a file-based-encrypted emulator' >&2; exit 1; }

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
# UI test uses a fake setup credential: the real OpenSSH server is contacted
# only for unauthenticated KEX. Fingerprint is supplied by host ssh-keygen.
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
  adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-release-test; touch files/a1-ci-release-upgrade-test'" \
    >/dev/null 2>&1 || true
  kill "$HTTP_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Keep instrumentation alive while the host issues SOCKS/HTTP requests.
# Otherwise Android's JUnit runner can terminate the target app process
# and its foreground service before this script has tested the tunnel.
run_test a1_2_realPinSftpApprovalAndPersistentKeystoreTunnel &
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

# CI host -> loopback S2 SSH -R -> Android SOCKS5 ->
# emulator 10.0.2.2 -> private CI HTTP server (no adb reverse).
SUCCESS=0
for i in $(seq 1 30); do
  BODY=$(curl --noproxy '' --silent --show-error --max-time 3 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://127.0.0.1:18887/ 2>/dev/null || true)
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
  echo 'Android adb reverse rules:' >&2
  adb shell am get-started-user-state 0 >&2 || true
  echo 'Remote SSH reverse listener:' >&2
  sudo ss -lntp | grep ":$PORT" >&2 || true
  echo 'Detailed proxied curl diagnostic:' >&2
  curl --noproxy '' -v --max-time 6 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://127.0.0.1:18887/ >&2 || true
  exit 1
}
echo "A1 PASS: emulator PIN→SFTP→approve→Keystore→-R→SOCKS5→HTTP"

# The same ephemeral debug signing certificate signs v20000 and v20001.
# This is a genuine in-place Android package update, NOT uninstall/reinstall.
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
adb install -r "$V2_APK" | grep -q '^Success
sleep 2
if curl --noproxy '' --silent --max-time 3 \
   --proxy "socks5h://127.0.0.1:$PORT" \
   http://127.0.0.1:18887/ >/dev/null 2>&1; then
  echo 'A1 ERROR: transport survived server revoke' >&2
  exit 1
fi
echo "A1 PASS: server revoke killed Android reverse SOCKS listener"

# Both instrumentation sessions already finished; server revoke was last.
 ||
  { echo 'A1 in-place APK upgrade failed' >&2; exit 1; }
echo 'A1 CI: installed versionCode 20001 over v20000 with same debug signing cert, no uninstall'

run_test a1_3_inPlaceSignedApkUpgradePreservesKeystoreAndPin &
TEST_PID=$!
SUCCESS=0
for i in $(seq 1 30); do
  BODY=$(curl --noproxy '' --silent --max-time 3 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://127.0.0.1:18887/ 2>/dev/null || true)
  if [ "$BODY" = "AAP2-A1-E2E-OK" ]; then SUCCESS=1; break; fi
  sleep 1
done
[ "$SUCCESS" = 1 ] || { echo 'A1 SSH/SOCKS failed after signed in-place APK update' >&2; exit 1; }
echo 'A1 PASS: in-place signed APK update retained Keystore/host pin and live SSH SOCKS5'
adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-release-upgrade-test'"
wait "$TEST_PID"

# True Direct Boot test: an Android 11 FBE emulator with lock PIN set,
# genuinely rebooted without Android user 0 ever being unlocked by CI.
# Do NOT restore adb reverse, call am start or launch instrumentation here.
adb shell locksettings set-pin --user 0 2468
adb reboot
timeout 180 adb wait-for-device
BOOTED=0
for i in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then
    BOOTED=1
    break
  fi
  sleep 1
done
[ "$BOOTED" = 1 ] || { echo 'Emulator did not complete cold boot' >&2; exit 1; }
USER_STATE=$(adb shell am get-started-user-state 0 | tr -d '\r')
[ "$USER_STATE" = RUNNING_LOCKED ] ||
  { echo "NOT a before-first-unlock test: user state=$USER_STATE" >&2; exit 1; }
echo "A1 CI: reboot complete, user 0 remains $USER_STATE; checking real SOCKS5"
SUCCESS=0
for i in $(seq 1 50); do
  USER_STATE=$(adb shell am get-started-user-state 0 | tr -d '\r')
  [ "$USER_STATE" = RUNNING_LOCKED ] ||
    { echo "User was unlocked during Direct Boot test: $USER_STATE" >&2; exit 1; }
  BODY=$(curl --noproxy '' --silent --max-time 4 \
    --proxy "socks5h://127.0.0.1:$PORT" \
    http://127.0.0.1:18887/ 2>/dev/null || true)
  if [ "$BODY" = "AAP2-A1-E2E-OK" ]; then SUCCESS=1; break; fi
  sleep 2
done
[ "$SUCCESS" = 1 ] ||
  { echo 'A1 Direct Boot SOCKS5 unavailable before first unlock' >&2; exit 1; }
echo 'A1 PASS: cold reboot → LOCKED_BOOT_COMPLETED → Keystore SSH -R → SOCKS5 HTTP while user locked'

sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" revoke
sleep 2
if curl --noproxy '' --silent --max-time 3 \
   --proxy "socks5h://127.0.0.1:$PORT" \
   http://127.0.0.1:18887/ >/dev/null 2>&1; then
  echo 'A1 ERROR: transport survived server revoke' >&2
  exit 1
fi
echo "A1 PASS: server revoke killed Android reverse SOCKS listener"

# Allow the instrumented process to finish only AFTER all network checks.
adb shell -T "run-as $PKG sh -c 'touch files/a1-ci-release-test'"
wait "$TEST_PID"
