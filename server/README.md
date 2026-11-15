# AAP2 server (OpenSSH + Python 3.9+)

S2 provides temporary PIN/SFTP device enrollment, explicit administrator
key approval, restricted permanent loopback-only reverse SSH forwarding
and per-device revocation. Android A1 enrollment and physical spare-device
integration have passed their own gates. Treat remote deployment as a
separate, deliberate pilot until the full release is published.

## Install

On an existing Debian 13 SSH server, from a reviewed checkout of this branch,
as root:

```sh
sh server/install.sh
```

The installer provisions AAP2-owned groups/directories, the Python CLI and
the one-minute cleanup systemd timer. Repeat runs preserve device state.
It **never** edits/reloads `sshd_config`, PAM or the firewall. The only
manual SSH integration is to review and append the provided
`sshd-aap2-match.conf` template at the correct position, check the
effective policy and confirm independent administrator access after a
manual sshd reload, while keeping the original SSH session open. Follow [INSTALL.md](INSTALL.md) for the short procedure.

## Reference

- [INSTALL.md](INSTALL.md) — install, idempotent updates, manual SSH Match setup
- [S0_OPENSSH.md](S0_OPENSSH.md) — tested SSH Match policy
- [S1_DOCTOR.md](S1_DOCTOR.md) — read-only preflight, NSS/UID checks
- [S1_ENROLL.md](S1_ENROLL.md) — PIN/SFTP lifecycle, timer and safety contract
- [S2_APPROVAL.md](S2_APPROVAL.md) — approval, restricted forwarding, revoke

Files: `aap2-setup` (stdlib-only Python CLI),
`aap2-cleanup.service`/`.timer`, `sshd-aap2-match.conf` (manual template).

**Never** run `test-s1-live.sh` on a real server: it destructively
replaces SSH configuration in disposable CI only. Never infer live
connections from log parsing or an APPROVED registration status.

```sh
aap2-setup doctor
python3 -m unittest discover -s server -p 'test_*.py' -v
```
