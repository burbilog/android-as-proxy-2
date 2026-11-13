#!/bin/sh
# A1 emulator-only OpenSSH S2 fixture. THIS CHANGES /etc/ssh and creates
# Unix accounts, so it is intentionally restricted to a fresh GitHub runner.
# NEVER run this on aap2-test, marduk or any persistent machine.
set -eu
umask 077

[ "$(id -u)" -eq 0 ] && [ "$GITHUB_ACTIONS" = true ] &&
  [ "$AAP2_A1_DISPOSABLE" = YES ] ||
  { echo 'Refusing: a disposable GitHub-hosted root runner is required' >&2; exit 2; }

SERVER="$1"
ACTION="$2"
STATE=/tmp/aap2-a1-ci
INVITE=$STATE/invite.txt
IDENTIFIER=$STATE/id

case "$ACTION" in
  init)
    [ ! -e /var/lib/aap2 ] && [ ! -e /etc/ssh/aap2-authorized-keys ] ||
      { echo 'Existing AAP2 state: refusing to install' >&2; exit 2; }
    ! getent group aap2-enroll >/dev/null &&
      ! getent group aap2-proxy >/dev/null ||
      { echo 'Existing AAP2 groups: refusing to adopt' >&2; exit 2; }

    install -d -m 0700 "$STATE"
    groupadd aap2-enroll
    groupadd aap2-proxy
    install -d -o root -g root -m 0755 /var/lib/aap2 /var/lib/aap2/enroll
    install -d -o root -g root -m 0700 /var/lib/aap2/state
    install -d -o root -g root -m 0755 /etc/ssh/aap2-authorized-keys
    install -o root -g root -m 0755 "$SERVER/aap2-setup" /usr/local/sbin/aap2-setup
    install -o root -g root -m 0644 "$SERVER/aap2-cleanup.service" /etc/systemd/system/
    install -o root -g root -m 0644 "$SERVER/aap2-cleanup.timer" /etc/systemd/system/
    systemctl daemon-reload
    systemctl enable --now aap2-cleanup.timer
    systemctl is-active --quiet aap2-cleanup.timer

    ssh-keygen -A
    install -d -m 0755 /run/sshd
    # The CLI never edits sshd config. This fixture edits only the throwaway
    # runner's sshd_config, binding to loopback instead of an exposed IP.
    cat > /etc/ssh/sshd_config <<EOF
Port 22224
ListenAddress 127.0.0.1
HostKey /etc/ssh/ssh_host_ecdsa_key
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
    cat "$SERVER/sshd-aap2-match.conf" >> /etc/ssh/sshd_config
    /usr/sbin/sshd -t
    # Ubuntu's socket-activated ssh can keep its original port 22 until
    # the socket is stopped, regardless of this disposable sshd_config.
    systemctl disable --now ssh.socket >/dev/null 2>&1 || true
    systemctl restart ssh
    systemctl is-active --quiet ssh
    ssh-keyscan -T 3 -p 22224 127.0.0.1 > "$STATE/known_hosts" 2>/dev/null
    test -s "$STATE/known_hosts"
    printf 'A1 ephemeral OpenSSH S2 server started at 127.0.0.1:22224\n'
    ;;
  invite)
    [ -d "$STATE" ] || { echo 'Fixture not initialized' >&2; exit 2; }
    # The PIN is retained ONLY in a root-only fixture file, never CI log.
    aap2-setup enroll A1-CI --server 127.0.0.1:22224 \
      --client-addr 127.0.0.1 --client-host localhost \
      --server-addr 127.0.0.1 --server-port 22224 > "$INVITE"
    sed -n 's/^Enrollment ID: //p' "$INVITE" > "$IDENTIFIER"
    test -s "$IDENTIFIER"
    test -s "$INVITE"
    printf 'A1 ephemeral invitation created (PIN redacted)\n'
    ;;
  watch-approve)
    ID=$(cat "$IDENTIFIER")
    USERNAME=$(sed -n 's/^Setup username: //p' "$INVITE")
    FILE="/var/lib/aap2/enroll/$USERNAME/inbox/public_key"
    # Poll for a complete valid uploaded public key. No log scraping,
    # no monitoring of SSH authentication logs and no private keys.
    for i in $(seq 1 120); do
      if [ -s "$FILE" ] &&
         [ "$(tail -c 1 "$FILE" | od -An -tu1 | tr -d ' \n')" = 10 ] &&
         ssh-keygen -lf "$FILE" -E sha256 >/dev/null 2>&1; then
        printf 'y\n' | aap2-setup approve "$ID" > "$STATE/approve-output"
        grep -q 'APPROVED:' "$STATE/approve-output"
        printf 'A1 ephemeral admin approved the immutable uploaded key\n'
        exit 0
      fi
      sleep 1
    done
    echo 'Timed out waiting for a valid public-key upload' >&2
    exit 1
    ;;
  revoke)
    ID=$(cat "$IDENTIFIER")
    printf 'REVOKE\n' | aap2-setup revoke "$ID" > "$STATE/revoke-output"
    grep -q 'REVOKED:' "$STATE/revoke-output"
    printf 'A1 ephemeral approved device revoked\n'
    ;;
  *)
    echo 'Usage: ci-a1-server.sh SERVER_DIR {init|invite|watch-approve|revoke}' >&2
    exit 2
    ;;
esac
