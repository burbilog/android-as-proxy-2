# AAP2 server (OpenSSH + Python 3.9+)

S2 provides temporary PIN/SFTP device enrollment, explicit administrator
key approval, restricted permanent loopback-only reverse SSH forwarding
and per-device revocation. Android A1 enrollment and physical spare-device
integration have passed their own gates. AAP2 v2.0.6 is a published
stable release; test unattended connectivity and OEM power behavior on
each intended physical phone before relying on it remotely.

## Install

On an existing Debian 13 SSH server, from a reviewed checkout of `master` or a stable release tag,
as root:

```sh
sh server/install.sh
```

The installer provisions AAP2-owned groups/directories, the Python CLI and
the one-minute cleanup systemd timer. Repeat runs preserve device state.
It **never** edits/reloads `sshd_config`, PAM or the firewall. The only
manual SSH integration is to review and append the provided
`sshd-aap2-match.conf` template at the correct position, verify the
on-disk policy and **explicitly reload the running sshd** with
`/usr/sbin/sshd -t && systemctl reload ssh`. Keep the original
administrative SSH session open; verify a fresh login before closing it.
**Neither `sshd -T -C` nor `aap2ctl doctor` proves that the live daemon
loaded the new Match policy.** Without reload, SFTP enrollment can fail
after successful password authentication because sshd starts
`/usr/sbin/nologin` rather than `internal-sftp`. See the
[installation and SFTP troubleshooting guide](INSTALL.md#troubleshooting-password-accepted-but-sftp-immediately-closes).

## Expired enrollment reservations

The one-minute timer retires expired temporary PIN/SFTP access, **not**
the enrollment's reserved port or permanent placeholder. All states
except `revoked`, including `expired`, reserve the port. Inspect
`aap2ctl list --all` and explicitly `aap2ctl revoke ID` (interactive
`REVOKE` confirmation) when retiring an abandoned invitation. For JSON
integration using `-j`, do not treat an expired PIN, an absent TCP
listener or `cleanup` as permission to reuse a port or auto-revoke a
device. See [S2 approval policy](S2_APPROVAL.md#reserved-socks5-ports-manual-reclamation-policy).

## Reference

- [INSTALL.md](INSTALL.md) — install, idempotent updates, manual SSH Match setup
- [S0_OPENSSH.md](S0_OPENSSH.md) — tested SSH Match policy
- [S1_DOCTOR.md](S1_DOCTOR.md) — read-only preflight, NSS/UID checks
- [S1_ENROLL.md](S1_ENROLL.md) — PIN/SFTP lifecycle, timer and safety contract
- [S2_APPROVAL.md](S2_APPROVAL.md) — approval, restricted forwarding, revoke

Files: `aap2ctl` (stdlib-only Python CLI),
`aap2-cleanup.service`/`.timer`, `sshd-aap2-match.conf` (manual template).

**Never** run `test-s1-live.sh` on a real server: it destructively
replaces SSH configuration in disposable CI only. Never infer live
connections from log parsing or an APPROVED registration status.

```sh
aap2ctl doctor
python3 -m unittest discover -s server -p 'test_*.py' -v
```
