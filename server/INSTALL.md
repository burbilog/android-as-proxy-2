# Install AAP2 S2 on a Debian SSH server

Supported baseline: Debian 13, Python 3.9+, OpenSSH, systemd. The
administrator needs a root shell (or sudo) and an already working SSH
login. AAP2 has no independent network daemon, database, pip packages or
background agent. It uses the existing OpenSSH server.

## One-command server prerequisites

Clone a reviewed release of this repository and, **as root**, run:

```sh
sh server/install.sh
```

The installer is included in the published stable AAP2 release. Use the
current `master` branch (or an explicitly reviewed release tag):

```sh
git clone --branch master --single-branch \
  https://github.com/burbilog/android-as-proxy-2.git /opt/aap2-s2
sh /opt/aap2-s2/server/install.sh
```

The script creates the two dedicated groups and root-owned directories,
installs `/usr/local/sbin/aap2ctl`, installs the cleanup systemd service/timer,
enables the timer, and verifies that it is active. It requires an existing
valid OpenSSH installation; it deliberately does not run apt, rewrite SSH
configuration, modify PAM or the firewall, or reload/restart SSH.

Running it again is supported: it re-installs the CLI and units, preserves
`/var/lib/aap2/state/`, approved accounts, uploaded keys and registrations,
and leaves SSH daemon configuration untouched. Suspicious non-root-owned
directories, unusual target files or symlinks cause refusal. The script
does not try to repair ambiguous existing AAP2 state. The CLI has a single installed name: `aap2ctl`.

## Only manual system step: integrate the SSH Match policy

Read `server/sshd-aap2-match.conf`. Add its **two Match Group blocks**
manually to an appropriate place in `/etc/ssh/sshd_config`, **after all
global directives**. On default Debian 13, `sshd_config` includes
`/etc/ssh/sshd_config.d/*.conf` near the top; adding Match blocks to
an early-included `.conf` can unintentionally place subsequent global
directives inside a Match block. Never blindly copy the template to
`sshd_config.d/`.

Back up the original SSH config, review Include/Match order and existing
AllowUsers/AllowGroups/AuthenticationMethods, then validate the result:

```sh
/usr/sbin/sshd -t
/usr/local/sbin/aap2ctl doctor
```

`doctor` is read-only. Exit code 2 (PENDING) is expected before real
AAP2 users exist: it is not a substitute for effective `sshd -T -C`
checks with actual account and connection context after enrollment.

**Critical: apply the SSH policy to the running daemon before issuing a PIN.**
Both `sshd -t` and `sshd -T -C` examine the **configuration on disk**.
A successful check does **not** mean the already-running sshd has loaded
the new `Match Group aap2-enroll` blocks. With a validated config, keep
the existing administrative SSH session open and explicitly reload:

```sh
/usr/sbin/sshd -t && systemctl reload ssh
```

Open a **new** administrative SSH connection and verify that it still works
before closing the original one. The installer deliberately does **not**
reload SSH automatically; its repeatable execution must not affect existing
SSH sessions or custom daemon policies. After any future manual SSH Match
change, repeat the syntax check, reload, and new-session verification.

Never run `server/test-s1-live.sh` on a real VM: it destroys and replaces
SSH configuration and belongs exclusively to ephemeral CI containers.

## Enroll a phone (after SSH policy passes)

```sh
aap2ctl enroll MyPhone --server ssh.example.org:22
aap2ctl approve ID
aap2ctl list
```

Only create the invitation when the phone operator is available:
`enroll` always prints a single 18-digit code with a 20-minute lifetime.
The Android app compares the complete SSH host fingerprint *before* it
sends the PIN. Approve the uploaded client public key explicitly; the
allocated SOCKS5 listening port remains bound to server loopback.
Do not publish the activation code or capture its output in shared logs.
See [S2_APPROVAL.md](S2_APPROVAL.md) for the full operator workflow,
restricted forwarding checks and revocation.

## Troubleshooting: password accepted, but SFTP immediately closes

If the phone reports only `Enrollment incomplete: JSchException`, the
temporary account's password authentication may still have succeeded.
For example, sshd can log `Accepted password for aap2e... ` followed
immediately by `session closed`, while the phone never uploads its public
key. **Successful SSH authentication is not proof that SFTP started.**

A strong indicator is the local SFTP client failing with:

```text
Received message too long 1416128883
```

`1416128883` is `0x54686973` (ASCII `This`), the first four bytes
of `This account is currently not available.` printed by
`/usr/sbin/nologin`. That means the client received shell text
instead of the binary SFTP protocol. AAP2 deliberately gives temporary
accounts the `/usr/sbin/nologin` shell: **do not change it**. Their
access must be forced into `internal-sftp` by the live daemon's Match
policy.

First check that the disk policy is correct, then reload the live daemon
as described above. If a still-valid invitation exists, test the
actual SFTP service (enter the *ten-digit PIN* when prompted, not in the
command line):

```sh
# Replace the example account with the active "Setup username".
sftp -o PubkeyAuthentication=no aap2e123456@127.0.0.1
```

A working session reaches `sftp>` and can run:

```text
ls
get config.json /tmp/aap2-test-config.json
bye
```

Do not publish the PIN or unredacted `sftp -vvv` logs. If SFTP works
locally, retry the phone's verified activation while the invitation is
valid. If the PIN has expired, revoke the abandoned invitation before
issuing a new one. The on-disk `sshd -T -C` check alone cannot
substitute for this live test.

The pre-auth `JSchChangedHostKeyException` reported by sshd during
the phone's **Check server fingerprint** step is usually **expected**:
AAP2 intentionally rejects the probe after reading the host key, before
sending any PIN. Do not delete known host keys or disable host-key
verification merely because this appears in server logs.

## Expired invitations and port reservations

The cleanup timer removes expired temporary PIN/SFTP access but **does not**
automatically release an enrollment's reserved SOCKS5 port. Even unapproved
`expired` records hold their port until explicit administrator
`aap2ctl revoke ID` (interactive confirmation). Inspect
`aap2ctl list --all` before reclaiming abandoned registrations.
If you automate via `-j`/`--json`, treat all states other than
`revoked` as reserved; do not infer availability from expired PINs or
absent listeners. See [S2_APPROVAL.md](S2_APPROVAL.md#reserved-socks5-ports-manual-reclamation-policy).

## Update server files

From the same reviewed checkout:

```sh
git pull --ff-only
sh server/install.sh
```

This updates the server CLI and timer units but never touches SSH policies
or enrollment state. Review changes and run `aap2ctl doctor` after an
update; do not merge incompatible development branches on a production VM.
