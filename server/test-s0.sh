#!/bin/sh
# S0 integration tests on a DISPOSABLE Debian VM or an ephemeral CI runner.
# Root required. Starts an isolated sshd on 127.0.0.1:22223.
# Never edits/reloads the system sshd. Never touches AAP2 production accounts.
set -eu

[ "$(id -u)" -eq 0 ] || { echo 'Run as root in the test VM only' >&2; exit 2; }
for cmd in ssh sshd ssh-keygen sshpass sftp python3 curl timeout openssl useradd userdel groupadd groupdel usermod chpasswd; do
    command -v "$cmd" >/dev/null || { echo "Missing test dependency: $cmd" >&2; exit 2; }
done

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CONF="$DIR/sshd-aap2-match.conf"
[ -f "$CONF" ] || { echo "Missing $CONF" >&2; exit 2; }

ENROLL=aap2s0enroll
PROXY=aap2s0proxy
PORT=22223
REMOTE_PORT=11909
TMP=$(mktemp -d /var/lib/aap2-s0.XXXXXXXX)
MADE_ENROLL=0
MADE_PROXY=0
MADE_G1=0
MADE_G2=0
SSHD_PID=
HTTP_PID=
CLIENT_CTL=

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
cleanup() {
    if [ -n "$CLIENT_CTL" ]; then
        ssh -F /dev/null -S "$CLIENT_CTL" -O exit -p "$PORT" "$PROXY@127.0.0.1" >/dev/null 2>&1 || :
    fi
    [ -z "$HTTP_PID" ] || { kill "$HTTP_PID" 2>/dev/null || :; wait "$HTTP_PID" 2>/dev/null || :; }
    [ -z "$SSHD_PID" ] || { kill "$SSHD_PID" 2>/dev/null || :; wait "$SSHD_PID" 2>/dev/null || :; }
    if [ "$MADE_PROXY" -eq 1 ]; then
        uid=$(id -u "$PROXY" 2>/dev/null || echo 0)
        [ "$uid" -lt 1001 ] || userdel "$PROXY"
    fi
    if [ "$MADE_ENROLL" -eq 1 ]; then
        uid=$(id -u "$ENROLL" 2>/dev/null || echo 0)
        [ "$uid" -lt 1001 ] || userdel "$ENROLL"
    fi
    [ "$MADE_G2" -eq 0 ] || groupdel aap2-proxy
    [ "$MADE_G1" -eq 0 ] || groupdel aap2-enroll
    rm -rf -- "$TMP"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

# Avoid adopting, altering or deleting pre-existing users/groups.
getent passwd "$ENROLL" >/dev/null && die "Existing account $ENROLL; refusing to touch it"
getent passwd "$PROXY" >/dev/null && die "Existing account $PROXY; refusing to touch it"
getent group aap2-enroll >/dev/null && die "Existing aap2-enroll group; use a fresh VM"
getent group aap2-proxy >/dev/null && die "Existing aap2-proxy group; use a fresh VM"

groupadd aap2-enroll; MADE_G1=1
groupadd aap2-proxy; MADE_G2=1
useradd -M -d / -s /usr/sbin/nologin -g aap2-enroll "$ENROLL"; MADE_ENROLL=1
useradd -M -d /nonexistent -s /usr/sbin/nologin -g aap2-proxy "$PROXY"; MADE_PROXY=1
[ "$(id -u "$ENROLL")" -ge 1001 ] || die 'Test enroll UID unsafe'
[ "$(id -u "$PROXY")" -ge 1001 ] || die 'Test proxy UID unsafe'

# Linux shadow "!" locked account may block pubkey in sshd; use NP, while
# permanent-user PasswordAuthentication is independently disabled.
usermod -p NP "$PROXY"
TEST_PIN=$(openssl rand -hex 8)
printf '%s:%s\n' "$ENROLL" "$TEST_PIN" | chpasswd

mkdir -p "$TMP/enroll/$ENROLL/inbox" "$TMP/authorized" "$TMP/web"
chmod 755 "$TMP/enroll" "$TMP/enroll/$ENROLL" "$TMP/enroll/$ENROLL/inbox" "$TMP/authorized"
touch "$TMP/enroll/$ENROLL/inbox/public_key"
chown "$ENROLL" "$TMP/enroll/$ENROLL/inbox/public_key"
chmod 600 "$TMP/enroll/$ENROLL/inbox/public_key"
printf 'S0 forward OK\n' > "$TMP/web/index.html"
ssh-keygen -q -t ed25519 -N '' -f "$TMP/hostkey"
ssh-keygen -q -t ecdsa -b 256 -N '' -f "$TMP/id"
ssh-keygen -q -t ecdsa -b 256 -N '' -f "$TMP/other"
printf 'restrict,port-forwarding,permitlisten="127.0.0.1:%s" %s\n' \
    "$REMOTE_PORT" "$(cat "$TMP/id.pub")" > "$TMP/authorized/$PROXY"
chmod 644 "$TMP/authorized/$PROXY"
printf '[127.0.0.1]:%s %s\n' "$PORT" "$(cat "$TMP/hostkey.pub")" > "$TMP/known_hosts"

# Separate daemon: no Include of /etc/ssh, no active server config changes.
cat > "$TMP/sshd_config" <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $TMP/hostkey
PidFile $TMP/sshd.pid
UsePAM no
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication no
HostbasedAuthentication no
AllowTcpForwarding no
AuthorizedKeysFile none
Subsystem sftp internal-sftp
EOF
sed -e "s@/var/lib/aap2/enroll/@$TMP/enroll/@g" \
    -e "s@/etc/ssh/aap2-authorized-keys/@$TMP/authorized/@g" \
    "$CONF" >> "$TMP/sshd_config"

# Needed for OpenSSH privilege separation on new CI images.
mkdir -p /run/sshd
chmod 755 /run/sshd
sshd -t -f "$TMP/sshd_config"
for spec in "$PROXY:maxsessions 0" "$PROXY:allowtcpforwarding remote" \
            "$PROXY:passwordauthentication no" "$ENROLL:forcecommand internal-sftp" \
            "$ENROLL:disableforwarding yes"; do
    user=${spec%%:*}
    expected=${spec#*:}
    sshd -T -f "$TMP/sshd_config" \
        -C "user=$user,addr=127.0.0.1,host=localhost,laddr=127.0.0.1,lport=$PORT" \
        | grep -Fx "$expected" >/dev/null || die "sshd -T: $user lacks [$expected]"
done

"$(command -v sshd)" -D -e -f "$TMP/sshd_config" 2>"$TMP/sshd.stderr" &
SSHD_PID=$!
python3 -m http.server 18887 --bind 127.0.0.1 --directory "$TMP/web" \
    >/dev/null 2>&1 &
HTTP_PID=$!
sleep 1
if ! kill -0 "$SSHD_PID" 2>/dev/null; then
    cat "$TMP/sshd.stderr" >&2
    die 'Isolated sshd could not start'
fi

# Helper commands; all SSH clients enforce the independent ephemeral host key.
ssh_perm() {
    if [ "${1:-}" = exec ]; then
        shift
        ssh -F /dev/null -p "$PORT" -T \
            -o UserKnownHostsFile="$TMP/known_hosts" -o GlobalKnownHostsFile=/dev/null \
            -o StrictHostKeyChecking=yes -o BatchMode=yes -o IdentitiesOnly=yes \
            -o ConnectTimeout=5 -o ExitOnForwardFailure=yes \
            -i "$TMP/id" "$PROXY@127.0.0.1" "$@"
    else
        ssh -F /dev/null -p "$PORT" -T \
            -o UserKnownHostsFile="$TMP/known_hosts" -o GlobalKnownHostsFile=/dev/null \
            -o StrictHostKeyChecking=yes -o BatchMode=yes -o IdentitiesOnly=yes \
            -o ConnectTimeout=5 -o ExitOnForwardFailure=yes \
            -i "$TMP/id" "$@" "$PROXY@127.0.0.1"
    fi
}
ssh_other() {
    ssh -F /dev/null -p "$PORT" -T \
        -o UserKnownHostsFile="$TMP/known_hosts" -o GlobalKnownHostsFile=/dev/null \
        -o StrictHostKeyChecking=yes -o BatchMode=yes -o IdentitiesOnly=yes \
        -o ConnectTimeout=5 -o ExitOnForwardFailure=yes \
        -i "$TMP/other" "$@" "$PROXY@127.0.0.1"
}
reject() {
    # All tests below have explicit connect/forward failure handling.
    # Do not run a shell function through coreutils timeout(1).
    if "$@" >/dev/null 2>&1; then
        die "Unexpected success: $1"
    fi
}

# Positive: exactly one loopback -R, traffic really reaches the client.
CLIENT_CTL="$TMP/control"
ssh_perm -M -S "$CLIENT_CTL" -fN \
    -R "127.0.0.1:$REMOTE_PORT:127.0.0.1:18887" || die 'Allowed -R rejected'
body=$(curl -fsS --max-time 5 "http://127.0.0.1:$REMOTE_PORT/" || :)
[ "$body" = "S0 forward OK" ] || die 'Allowed -R did not carry TCP traffic'
printf 'PASS: allowed -R and TCP data\n'

# Negative: mismatched port, wildcard, implicit "localhost" listener.
for listen in "127.0.0.1:11908" "0.0.0.0:$REMOTE_PORT" "$REMOTE_PORT"; do
    reject ssh_perm -fN -R "$listen:127.0.0.1:18887"
done
printf 'PASS: wrong port, wildcard and implicit localhost denied\n'

reject ssh_perm -W 127.0.0.1:18887
reject ssh_perm exec true
reject ssh_other -fN -R 127.0.0.1:11911:127.0.0.1:18887
if timeout 8 sftp -F /dev/null -P "$PORT" -b /dev/null \
    -o UserKnownHostsFile="$TMP/known_hosts" -o StrictHostKeyChecking=yes \
    -o BatchMode=yes -o IdentitiesOnly=yes -i "$TMP/id" \
    "$PROXY@127.0.0.1" >/dev/null 2>&1; then
    die 'Permanent account unexpectedly allowed SFTP'
fi
printf 'PASS: -W, shell/exec, SFTP and other publickey denied\n'

# Enrollment account: actual password login, SFTP upload to PRECREATED file only.
SSHPASS=$TEST_PIN
export SSHPASS
printf 'put %s inbox/public_key\nbye\n' "$TMP/other.pub" |
    timeout 12 sshpass -e sftp -F /dev/null -P "$PORT" \
        -o UserKnownHostsFile="$TMP/known_hosts" \
        -o GlobalKnownHostsFile=/dev/null -o StrictHostKeyChecking=yes \
        -o PubkeyAuthentication=no -o PreferredAuthentications=password \
        "$ENROLL@127.0.0.1" >/dev/null || die 'Enrollment SFTP upload failed'
cmp -s "$TMP/other.pub" "$TMP/enroll/$ENROLL/inbox/public_key" ||
    die 'Enrollment upload did not overwrite precreated file'

# ForceCommand internal-sftp must never run shell commands.
timeout 6 sshpass -e ssh -F /dev/null -T -p "$PORT" \
    -o UserKnownHostsFile="$TMP/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null -o StrictHostKeyChecking=yes \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password \
    "$ENROLL@127.0.0.1" "touch $TMP/enroll-shell-escaped" \
    >/dev/null 2>&1 || :
[ ! -e "$TMP/enroll-shell-escaped" ] || die 'Enrollment shell executed unexpectedly'
if timeout 8 sshpass -e ssh -F /dev/null -T -fN -p "$PORT" \
    -o UserKnownHostsFile="$TMP/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null -o StrictHostKeyChecking=yes \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password \
    -o ExitOnForwardFailure=yes \
    -R 127.0.0.1:11910:127.0.0.1:18887 \
    "$ENROLL@127.0.0.1" >/dev/null 2>&1; then
    die 'Enrollment account unexpectedly allowed forwarding'
fi
printf 'PASS: PIN SFTP-only enrollment, no exec and no forwarding\n'
printf 'S0 PASS: temporary daemon and users cleaned up on exit\n'
