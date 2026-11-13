#!/bin/sh
# Real account/password/sshd lifecycle; EPHEMERAL GitHub-hosted runner ONLY.
# This modifies /etc/passwd, /etc/shadow and /etc/ssh/sshd_config on the
# THROWAWAY runner. NEVER run on aap2-test or production.
set -eu
[ "$(id -u)" -eq 0 ] || { echo 'root required' >&2; exit 2; }
[ "${GITHUB_ACTIONS:-}" = true ] && [ "${AAP2_S1_DISPOSABLE:-}" = YES ] ||
  { echo 'Refusing non-disposable machine; changes system sshd config' >&2; exit 2; }
[ ! -e /var/lib/aap2 ] && [ ! -e /etc/ssh/aap2-authorized-keys ] ||
  { echo 'Existing AAP2 server state: refusing to touch' >&2; exit 2; }
! getent group aap2-enroll >/dev/null && ! getent group aap2-proxy >/dev/null ||
  { echo 'Existing AAP2 groups: refusing to adopt' >&2; exit 2; }
for cmd in groupadd useradd usermod userdel sshd ssh-keygen sshpass sftp systemctl; do
  command -v "$cmd" >/dev/null || { echo "Missing $cmd" >&2; exit 2; }
done

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d /tmp/aap2-s1-integration.XXXXXXXX)
chmod 700 "$TMP"
DAEMON=
cleanup_test() {
    [ -z "$DAEMON" ] || kill "$DAEMON" 2>/dev/null || :
    rm -rf -- "$TMP"
}
trap cleanup_test EXIT
trap 'exit 1' HUP INT TERM

groupadd aap2-enroll
groupadd aap2-proxy
install -d -o root -g root -m 0755 /var/lib/aap2 /var/lib/aap2/enroll
install -d -o root -g root -m 0700 /var/lib/aap2/state
install -d -o root -g root -m 0755 /etc/ssh/aap2-authorized-keys
install -o root -g root -m 0755 "$HERE/aap2-setup" /usr/local/sbin/aap2-setup
install -o root -g root -m 0644 "$HERE/aap2-cleanup.service" /etc/systemd/system/
install -o root -g root -m 0644 "$HERE/aap2-cleanup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now aap2-cleanup.timer
systemctl is-active --quiet aap2-cleanup.timer

ssh-keygen -A
mkdir -p /run/sshd
# AAP2 CLI NEVER writes sshd_config. Only this deliberately destructive
# disposable-host test harness writes a dedicated sshd_config.
cat > /etc/ssh/sshd_config <<EOF
Port 22224
ListenAddress 127.0.0.1
HostKey /etc/ssh/ssh_host_ed25519_key
UsePAM yes
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication no
HostbasedAuthentication no
AllowTcpForwarding no
AuthorizedKeysFile none
Subsystem sftp internal-sftp
EOF
cat "$HERE/sshd-aap2-match.conf" >> /etc/ssh/sshd_config
sshd -t
SSHD=$(command -v sshd)
"$SSHD" -D -e 2>"$TMP/sshd.stderr" &
DAEMON=$!
sleep 1
kill -0 "$DAEMON" || { cat "$TMP/sshd.stderr"; exit 1; }

# AAP2 server-owned username and ephemeral PIN: NEVER echo credentials to CI.
aap2-setup enroll CI-TEST --server 127.0.0.1:22224 \
  --client-addr 127.0.0.1 --client-host localhost \
  --server-addr 127.0.0.1 --server-port 22224 > "$TMP/invite.txt"
E=$(sed -n 's/^Setup username: //p' "$TMP/invite.txt")
DISPLAY_PIN=$(sed -n 's/^One-time PIN: //p' "$TMP/invite.txt")
PIN=$(printf '%s' "$DISPLAY_PIN" | tr -d '-')
ID=$(sed -n 's/^Enrollment ID: //p' "$TMP/invite.txt")
[ "${#PIN}" -eq 10 ] && [ -n "$E" ] && [ -n "$ID" ]
case "$PIN" in *[!0-9]*) echo 'PIN not decimal' >&2; exit 1;; esac
P="aap2p$ID"
getent passwd "$E" >/dev/null
getent passwd "$P" >/dev/null
[ -f "/var/lib/aap2/enroll/$E/config.json" ]
[ -f "/etc/ssh/aap2-authorized-keys/$P" ]

# Real PIN-only SFTP overwrites the PRECREATED key file inside chroot.
ssh-keygen -q -t ecdsa -b 256 -N '' -f "$TMP/device"
ssh-keyscan -p 22224 127.0.0.1 > "$TMP/known_hosts" 2>/dev/null
export SSHPASS="$PIN"
printf 'put %s inbox/public_key\nbye\n' "$TMP/device.pub" |
  timeout 12 sshpass -e sftp -F /dev/null -P 22224 \
    -o UserKnownHostsFile="$TMP/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null -o StrictHostKeyChecking=yes \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password \
    "$E@127.0.0.1" >/dev/null
unset SSHPASS PIN DISPLAY_PIN
cmp "$TMP/device.pub" "/var/lib/aap2/enroll/$E/inbox/public_key"
echo 'S1-B PASS: real PIN SFTP-only upload into precreated file'

# S2 path: verify real server key approval, loopback -R, PIN expiry, revoke.
# Uses the exact same disposable runner setup as S1, but exercises
# permanent access. No actual phone or production sshd is involved.
if [ "${AAP2_S2_INTEGRATION:-}" = YES ]; then
  PORT=$(python3 - "$ID" <<'PY'
import json
from pathlib import Path
import sys
metadata = Path('/var/lib/aap2/state') / sys.argv[1] / 'metadata.json'
print(json.loads(metadata.read_text())['remote_port'])
PY
)
  [ "$PORT" -ge 11010 ] && [ "$PORT" -le 11509 ]

  # Malicious authorized_keys options must not pass upload validation.
  printf 'command="id" %s\n' "$(cat "$TMP/device.pub")" \
    > "/var/lib/aap2/enroll/$E/inbox/public_key"
  if printf 'y\n' | aap2-setup approve "$ID" >"$TMP/rejected-approve.out" 2>&1; then
    echo 'S2 ERROR: injected key options approved' >&2
    exit 1
  fi
  [ ! -s "/etc/ssh/aap2-authorized-keys/$P" ] ||
    { echo 'S2 ERROR: unauthorized key was installed' >&2; exit 1; }
  echo 'S2 PASS: injected key rejected before approval'
  cp "$TMP/device.pub" "/var/lib/aap2/enroll/$E/inbox/public_key"

  # Real SSH key fingerprint and explicit administrator approval; no PIN
  # used to authenticate the permanent account.
  printf 'y\n' | aap2-setup approve "$ID" > "$TMP/approve.out"
  if ! grep -q 'APPROVED:' "$TMP/approve.out"; then
    echo 'S2 ERROR: approval did not report APPROVED:' >&2
    cat "$TMP/approve.out" >&2
    exit 1
  fi
  if ! grep -q '^restrict,port-forwarding,permitlisten="127.0.0.1:' \
      "/etc/ssh/aap2-authorized-keys/$P"; then
    echo 'S2 ERROR: restricted authorized key missing' >&2; exit 1
  fi
  if ! grep -q '"status":"approved"' "/var/lib/aap2/enroll/$E/status.json"; then
    echo 'S2 ERROR: status.json not approved' >&2; exit 1
  fi
  echo 'S2 PASS: approved snapshot published to restricted key and status'

  # Later PIN/SFTP uploads cannot substitute the key already approved.
  cp "/etc/ssh/aap2-authorized-keys/$P" "$TMP/original-authorized"
  ssh-keygen -q -t ecdsa -b 256 -N '' -f "$TMP/other"
  cp "$TMP/other.pub" "/var/lib/aap2/enroll/$E/inbox/public_key"
  aap2-setup approve "$ID" > "$TMP/reapprove.out"
  grep -q 'Already approved:' "$TMP/reapprove.out"
  cmp "$TMP/original-authorized" "/etc/ssh/aap2-authorized-keys/$P"
  echo 'S2 PASS: subsequent SFTP upload cannot replace approved snapshot'

  if [ "${AAP2_S2_DEBIAN_GATES:-}" = YES ]; then
    # Crash after key install but before status/metadata publication.
    # Later upload must not supplant the original immutable candidate.
    python3 - "$ID" "$E" <<'PY'
import json
from pathlib import Path
import sys
ident, username = sys.argv[1:]
metadata = Path('/var/lib/aap2/state') / ident / 'metadata.json'
data = json.loads(metadata.read_text())
data['state'] = 'active'
metadata.write_text(json.dumps(data) + '\n')
status = Path('/var/lib/aap2/enroll') / username / 'status.json'
status.write_text('{"schema":1,"status":"pending"}\n')
PY
    printf 'y\n' | aap2-setup approve "$ID" > "$TMP/crash-reapprove.out"
    grep -q 'APPROVED:' "$TMP/crash-reapprove.out"
    cmp "$TMP/original-authorized" "/etc/ssh/aap2-authorized-keys/$P"
    grep -q '"state":"approved"' "/var/lib/aap2/state/$ID/metadata.json"
    grep -q '"status":"approved"' "/var/lib/aap2/enroll/$E/status.json"
    echo 'S2 Debian 13 PASS: interrupted approval replays the original key only'
  fi

  if [ "${AAP2_S2_DEBIAN_GATES:-}" = YES ]; then
    # Debian usermod will not change an account with an active SSH
    # session. Test NSS-home mismatch before opening the permanent -R.
    usermod -d /tmp/aap2-foreign-home "$P"
    if printf 'REVOKE\n' | aap2-setup revoke "$ID" > "$TMP/nss-tamper.out" 2>&1; then
      echo 'S2 ERROR: mismatched NSS home accepted by revoke' >&2
      exit 1
    fi
    usermod -d /nonexistent "$P"
    cmp "$TMP/original-authorized" "/etc/ssh/aap2-authorized-keys/$P"
    echo 'S2 Debian 13 PASS: foreign NSS home refused without deleting device'
  fi

  mkdir -p "$TMP/web"
  printf 'S2 forwarding works\n' > "$TMP/web/index.html"
  python3 -m http.server 18887 --bind 127.0.0.1 --directory "$TMP/web" >/dev/null 2>&1 &
  HTTP_PID=$!
  sleep 1
  ssh -F /dev/null -p 22224 -T -M -S "$TMP/control" -fN \
    -i "$TMP/device" \
    -o UserKnownHostsFile="$TMP/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null \
    -o StrictHostKeyChecking=yes -o BatchMode=yes \
    -o IdentitiesOnly=yes -o ExitOnForwardFailure=yes \
    -R "127.0.0.1:$PORT:127.0.0.1:18887" "$P@127.0.0.1"
  [ "$(curl -fsS --max-time 6 "http://127.0.0.1:$PORT/")" = 'S2 forwarding works' ] ||
    { echo 'S2 ERROR: authorized -R did not carry TCP' >&2; exit 1; }
  echo 'S2 PASS: approved restricted permanent key opens actual -R and carries TCP'

  if [ "${AAP2_S2_DEBIAN_GATES:-}" = YES ]; then
    # UID=0 metadata forgery must never let revoke touch root.
    # A live NSS home mismatch must also fail even when name/groups match.
    cp -p "/var/lib/aap2/state/$ID/metadata.json" "$TMP/pristine-metadata"
    python3 - "$ID" <<'PY'
import json
from pathlib import Path
import sys
p = Path('/var/lib/aap2/state') / sys.argv[1] / 'metadata.json'
data = json.loads(p.read_text())
data['users']['aap2-proxy']['uid'] = 0
p.write_text(json.dumps(data) + '\n')
PY
    if printf 'REVOKE\n' | aap2-setup revoke "$ID" > "$TMP/uid-tamper.out" 2>&1; then
      echo 'S2 ERROR: UID=0 metadata forgery accepted by revoke' >&2
      exit 1
    fi
    getent passwd root >/dev/null
    cmp "$TMP/original-authorized" "/etc/ssh/aap2-authorized-keys/$P"
    cp -p "$TMP/pristine-metadata" "/var/lib/aap2/state/$ID/metadata.json"

    cmp "$TMP/original-authorized" "/etc/ssh/aap2-authorized-keys/$P"
    [ "$(curl -fsS --max-time 6 "http://127.0.0.1:$PORT/")" = 'S2 forwarding works' ] ||
      { echo 'S2 ERROR: refused UID tamper disconnected approved device' >&2; exit 1; }
    echo 'S2 Debian 13 PASS: UID=0 metadata refused without changing active -R'

    printf 'not-json\n' > "/var/lib/aap2/state/$ID/metadata.json"
    if aap2-setup cleanup > "$TMP/corrupt.out" 2>&1; then
      echo 'S2 ERROR: corrupt metadata silently accepted by cleanup' >&2
      exit 1
    fi
    cmp "$TMP/original-authorized" "/etc/ssh/aap2-authorized-keys/$P"
    cp -p "$TMP/pristine-metadata" "/var/lib/aap2/state/$ID/metadata.json"
    echo 'S2 Debian 13 PASS: corrupt state fails closed; approved key survives'
  fi

  # Reject other bind addresses/ports and all session/direct-tcpip access.
  if ssh -F /dev/null -p 22224 -T -fN -i "$TMP/device" \
    -o UserKnownHostsFile="$TMP/known_hosts" \
    -o StrictHostKeyChecking=yes -o BatchMode=yes \
    -o IdentitiesOnly=yes -o ExitOnForwardFailure=yes \
    -R "127.0.0.1:11999:127.0.0.1:18887" "$P@127.0.0.1" >/dev/null 2>&1; then
    echo 'S2 ERROR: wrong -R port accepted' >&2; exit 1
  fi
  if ssh -F /dev/null -p 22224 -T -i "$TMP/device" \
    -o UserKnownHostsFile="$TMP/known_hosts" -o StrictHostKeyChecking=yes \
    -o BatchMode=yes -o IdentitiesOnly=yes "$P@127.0.0.1" true >/dev/null 2>&1; then
    echo 'S2 ERROR: shell/exec accepted' >&2; exit 1
  fi
  if ssh -F /dev/null -p 22224 -T -i "$TMP/device" \
    -o UserKnownHostsFile="$TMP/known_hosts" -o StrictHostKeyChecking=yes \
    -o BatchMode=yes -o IdentitiesOnly=yes \
    -W 127.0.0.1:18887 "$P@127.0.0.1" >/dev/null 2>&1; then
    echo 'S2 ERROR: direct TCP accepted' >&2; exit 1
  fi
  echo 'S2 PASS: wrong -R, exec, direct-tcpip rejected'

  # Approval must survive expiry of the TEMP account. Permanent -R lives.
  python3 - "$ID" <<'PY'
import json
from pathlib import Path
import sys
p = Path('/var/lib/aap2/state') / sys.argv[1] / 'metadata.json'
data = json.loads(p.read_text())
data['expires_at'] = 0
p.write_text(json.dumps(data) + '\n')
PY
  if [ "${AAP2_S2_DEBIAN_GATES:-}" = YES ]; then
    systemctl start aap2-cleanup.service
    systemctl is-active --quiet aap2-cleanup.timer
  else
    aap2-setup cleanup
  fi
  ! getent passwd "$E" >/dev/null
  grep -q '"state":"approved"' "/var/lib/aap2/state/$ID/metadata.json"
  [ "$(curl -fsS --max-time 6 "http://127.0.0.1:$PORT/")" = 'S2 forwarding works' ] ||
    { echo 'S2 ERROR: cleanup broke approved SSH' >&2; exit 1; }
  echo 'S2 PASS: PIN expiry preserves approved SSH and -R'

  # A different key must never replace the immutable approved snapshot.
  if [ -f "/var/lib/aap2/enroll/$E/inbox/public_key" ]; then
    echo 'S2 ERROR: temporary chroot survived expiry' >&2
    exit 1
  fi
  # A wrong key must be refused at authentication, not merely because
  # it asked for an unauthorized listener (a misleading false-positive).
  if timeout 4 ssh -F /dev/null -p 22224 -T -N -i "$TMP/other" \
    -o UserKnownHostsFile="$TMP/known_hosts" -o StrictHostKeyChecking=yes \
    -o BatchMode=yes -o IdentitiesOnly=yes -o ExitOnForwardFailure=no \
    -R "127.0.0.1:$PORT:127.0.0.1:18887" "$P@127.0.0.1" >/dev/null 2>&1; then
    echo 'S2 ERROR: unknown public key allowed' >&2; exit 1
  else
    rc=$?
    [ "$rc" -eq 255 ] || {
      echo "S2 ERROR: unknown-key auth unexpectedly stayed connected (rc=$rc)" >&2
      exit 1
    }
  fi

  # Explicit confirmation and active-session termination.
  printf 'REVOKE\n' | aap2-setup revoke "$ID"
  ! getent passwd "$P" >/dev/null ||
    { echo 'S2 ERROR: revoked account still exists' >&2; exit 1; }
  [ ! -e "/etc/ssh/aap2-authorized-keys/$P" ] ||
    { echo 'S2 ERROR: revoked key file remains' >&2; exit 1; }
  if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then
    echo 'S2 ERROR: revoked -R still carries TCP' >&2; exit 1
  fi
  grep -q '"state":"revoked"' "/var/lib/aap2/state/$ID/metadata.json"
  echo 'S2 PASS: permanent account/key revoked; active -R closed'
  exit 0
fi


# Force expiry in the fixture instead of waiting 10 minutes.
# This edit is TEST-ONLY on a disposable runner, not an AAP2 CLI feature.
python3 - "$ID" <<'PY'
import json
from pathlib import Path
import sys
p = Path('/var/lib/aap2/state') / sys.argv[1] / 'metadata.json'
data = json.loads(p.read_text())
data['expires_at'] = 0
p.write_text(json.dumps(data) + '\n')
PY

aap2-setup cleanup
if getent passwd "$E" >/dev/null; then
  echo 'Expired temporary user survived cleanup' >&2
  exit 1
fi
getent passwd "$P" >/dev/null ||
  { echo 'Permanent account was incorrectly deleted' >&2; exit 1; }
[ -f "/etc/ssh/aap2-authorized-keys/$P" ] ||
  { echo 'Permanent authorized key file was removed' >&2; exit 1; }
[ ! -e "/var/lib/aap2/enroll/$E" ] ||
  { echo 'Expired temporary chroot survived cleanup' >&2; exit 1; }
aap2-setup cleanup
echo 'S1-B PASS: expired PIN account removed, permanent account preserved, idempotent cleanup'
