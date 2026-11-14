# AAP2 server (OpenSSH + Python 3.9+)

**Current scope:** S0 real OpenSSH policy tests; S1-A read-only doctor;
S1-B temporary PIN/SFTP invitation creation and TTL cleanup; **S2 server-side
manual key approval, restricted permanent SSH -R and explicit device revoke**.
Android's no-PIN-before-host-key-check UI and unattended registration are
still pending. Do not use these scripts to register a real remote phone.

- [S0_OPENSSH.md](S0_OPENSSH.md) — tested SSH Match policy, **manual** installation
- [S1_DOCTOR.md](S1_DOCTOR.md) — preflight, NSS/UID checks, effective `sshd -T -C`
- [S1_ENROLL.md](S1_ENROLL.md) — crash-safe reservation, PIN activation,
  1-minute cleanup timer, security contract and disposable-VM installation
- [S2_APPROVAL.md](S2_APPROVAL.md) — immutable candidate key, manual approval,
  loopback-only reverse forward and confirmed device revoke

Files: `aap2-setup` (single stdlib-only Python CLI),
`aap2-cleanup.service`/`.timer`, `sshd-aap2-match.conf` (**template only**).

**Never** auto-edit or reload `sshd_config`, change system PAM/firewall,
read authentication logs as machine state, or modify non-AAP2 accounts.
The existing AAP2 10909 test tunnel, `aapdev`, and Zebra must be left
alone until the disposable-VM tests are completed.

```sh
python3 server/aap2-setup print-config
python3 server/aap2-setup doctor
python3 -m unittest discover -s server -p 'test_*.py' -v
```
