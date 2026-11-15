#!/bin/sh
# AAP2 S2 server prerequisites. Deliberately does NOT modify or reload SSH.
set -eu

fail() {
    printf 'AAP2 install: ERROR: %s\n' "$*" >&2
    exit 1
}

[ "$(id -u)" -eq 0 ] || fail 'run as root (e.g. sudo sh server/install.sh)'
[ -d /run/systemd/system ] || fail 'systemd must be running'
command -v python3 >/dev/null 2>&1 || fail 'python3 is missing'
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' || fail 'Python 3.9+ is required'
[ -x /usr/sbin/sshd ] || fail 'openssh-server (/usr/sbin/sshd) is missing'
/usr/sbin/sshd -t || fail 'existing sshd configuration is invalid; refusing to install'
command -v systemctl >/dev/null 2>&1 || fail 'systemctl is missing'
command -v getent >/dev/null 2>&1 || fail 'getent is missing'
command -v groupadd >/dev/null 2>&1 || fail 'groupadd is missing'

src_dir=$(CDPATH= cd "$(dirname "$0")" && pwd -P) || fail 'cannot locate server source directory'
for name in aap2-setup aap2-cleanup.service aap2-cleanup.timer; do
    [ -f "$src_dir/$name" ] || fail "missing $src_dir/$name"
done

ensure_group() {
    name=$1
    if getent group "$name" >/dev/null; then
        gid=$(getent group "$name" | cut -d: -f3)
        case $gid in
            ''|*[!0-9]*) fail "invalid GID for $name" ;;
        esac
        [ "$gid" -ge 1000 ] || fail "existing group $name has system GID $gid"
    else
        groupadd "$name"
    fi
}

ensure_dir() {
    path=$1
    mode=$2
    if [ -e "$path" ] || [ -L "$path" ]; then
        [ -d "$path" ] && [ ! -L "$path" ] || fail "not a real directory: $path"
        [ "$(stat -c %u "$path")" -eq 0 ] || fail "not root-owned: $path"
    fi
    install -d -o root -g root -m "$mode" "$path"
}

install_file() {
    from=$1
    to=$2
    mode=$3
    if [ -e "$to" ] || [ -L "$to" ]; then
        [ -f "$to" ] && [ ! -L "$to" ] || fail "not a regular file: $to"
        [ "$(stat -c %u "$to")" -eq 0 ] || fail "not root-owned: $to"
    fi
    install -o root -g root -m "$mode" "$from" "$to"
}

ensure_group aap2-enroll
ensure_group aap2-proxy
ensure_dir /var/lib/aap2 0755
ensure_dir /var/lib/aap2/enroll 0755
ensure_dir /var/lib/aap2/state 0700
ensure_dir /etc/ssh/aap2-authorized-keys 0755

install_file "$src_dir/aap2-setup" /usr/local/sbin/aap2-setup 0755
install_file "$src_dir/aap2-cleanup.service" /etc/systemd/system/aap2-cleanup.service 0644
install_file "$src_dir/aap2-cleanup.timer" /etc/systemd/system/aap2-cleanup.timer 0644

systemctl daemon-reload
systemctl enable --now aap2-cleanup.timer
systemctl is-active --quiet aap2-cleanup.timer || fail 'cleanup timer did not become active'

printf '\nAAP2 server files and cleanup timer installed. Existing device state preserved.\n'
printf 'SSH configuration has NOT been changed or reloaded.\n'
printf 'NEXT: review and manually append the two Match blocks from:\n'
printf '  %s/sshd-aap2-match.conf\n' "$src_dir"
printf 'Run /usr/sbin/sshd -t, inspect sshd -T -C, then reload ssh.service\n'
printf 'ONLY after confirming a second admin SSH session still works.\n'
printf 'Then run: /usr/local/sbin/aap2-setup doctor\n'