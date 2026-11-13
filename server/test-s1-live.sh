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
