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
adb reverse tcp:22224 tcp:22224
adb reverse tcp:18887 tcp:18887

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
run_test a1_1_wrongPinnedHostRejectedBeforeAuthenticationOrKeyCreation

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

# Ephemeral administrator approves a complete uploaded PUBLIC key.
sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" watch-approve \
  >/tmp/aap2-a1-approval-watch.log 2>&1 &
WATCH_PID=$!

mkdir -p /tmp/aap2-a1-http
printf 'AAP2-A1-E2E-OK\n' > /tmp/aap2-a1-http/index.html
python3 -m http.server 18887 --bind 127.0.0.1 \
  --directory /tmp/aap2-a1-http >/tmp/aap2-a1-http.log 2>&1 &
HTTP_PID=$!

cleanup() {
  kill "$HTTP_PID" >/dev/null 2>&1 || true
  kill "$WATCH_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if ! run_test a1_2_realPinSftpApprovalAndPersistentKeystoreTunnel; then
  cat /tmp/aap2-a1-approval-watch.log >&2
  exit 1
fi
wait "$WATCH_PID"
cat /tmp/aap2-a1-approval-watch.log

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

# CI host -> loopback S2 SSH -R -> Android foreground SOCKS5 ->
# emulator loopback via adb reverse -> private CI HTTP server.
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
  exit 1
}
echo "A1 PASS: emulator PIN→SFTP→approve→Keystore→-R→SOCKS5→HTTP"

sudo env GITHUB_ACTIONS=true AAP2_A1_DISPOSABLE=YES sh "$FIXTURE" "$SERVER" revoke
sleep 2
if curl --noproxy '' --silent --max-time 3 \
   --proxy "socks5h://127.0.0.1:$PORT" \
   http://127.0.0.1:18887/ >/dev/null 2>&1; then
  echo 'A1 ERROR: transport survived server revoke' >&2
  exit 1
fi
echo "A1 PASS: server revoke killed Android reverse SOCKS listener"
