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

During development, checkout `feature/s2-debian13-gate`; do not assume the
default branch already contains the installer. For example:

```sh
git clone --branch feature/s2-debian13-gate --single-branch \
  https://github.com/burbilog/android-as-proxy-2.git /opt/aap2-s2
sh /opt/aap2-s2/server/install.sh
```

The script creates the two dedicated groups and root-owned directories,
installs `/usr/local/sbin/aap2-setup`, the cleanup systemd service/timer,
enables the timer, and verifies that it is active. It requires an existing
valid OpenSSH installation; it deliberately does not run apt, rewrite SSH
configuration, modify PAM or the firewall, or reload/restart SSH.

Running it again is supported: it re-installs the CLI and units, preserves
`/var/lib/aap2/state/`, approved accounts, uploaded keys and registrations,
and leaves SSH daemon configuration untouched. Suspicious non-root-owned
directories, unusual target files or symlinks cause refusal. The script
does not try to repair ambiguous existing AAP2 state.

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
/usr/local/sbin/aap2-setup doctor
```

`doctor` is read-only. Exit code 2 (PENDING) is expected before real
AAP2 users exist: it is not a substitute for effective `sshd -T -C`
checks with actual account and connection context after enrollment.
Verify administrative access in a **second, new SSH session** before
manually reloading `ssh.service`; keep the first session open.

Never run `server/test-s1-live.sh` on a real VM: it destroys and replaces
SSH configuration and belongs exclusively to ephemeral CI containers.

## Enroll a phone (after SSH policy passes)

```sh
aap2-setup enroll MyPhone --manual-code --server ssh.example.org:22
aap2-setup approve ID
aap2-setup list
```

Only create the invitation when the phone operator is available:
`--manual-code` prints a one-time 18-digit code with 20-minute lifetime.
The Android app compares the complete SSH host fingerprint *before* it
sends the PIN. Approve the uploaded client public key explicitly; the
allocated SOCKS5 listening port remains bound to server loopback.
Do not publish the activation code or capture its output in shared logs.
See [S2_APPROVAL.md](S2_APPROVAL.md) for the full operator workflow,
restricted forwarding checks and revocation.

## Update server files

From the same reviewed checkout:

```sh
git pull --ff-only
sh server/install.sh
```

This updates the server CLI and timer units but never touches SSH policies
or enrollment state. Review changes and run `aap2-setup doctor` after an
update; do not merge incompatible development branches on a production VM.
