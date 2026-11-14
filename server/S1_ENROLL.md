# AAP2 S1-B — temporary invitation and expiry (work in progress)

This is the **server-side invitation lifecycle**, **not end-to-end enrollment**.
Android SFTP upload, admin \`Approve\`, authorized-key publication and
permanent \`-R\` activation belong to **S2**. Do not give an S1-B PIN to a
remote user yet: no approval path is available in this milestone.

One executable \`server/aap2-setup\`: Python >=3.9, standard library only.
No network daemon, no \`pip\`, no custom auth protocol, no log parsing.

## Safety contract

- The script **never writes** \`/etc/ssh/sshd_config*\`, host keys, PAM,
  fail2ban or firewall configuration, and NEVER reloads/restarts sshd.
- \`print-config\` and \`doctor\` are read-only. \`enroll\` and \`cleanup\`
  are explicit root-only account operations, constrained to root-owned
  AAP2 state, account roles and verified UID >=1001.
- Names are generated from CSPRNG IDs (\`aap2e...\` and \`aap2p...\`).
  Existing NSS usernames always cause refusal: never adopt accounts.
- Before **any** \`useradd\`: the lock is acquired, the random name, UID,
  role, home and loopback remote port are reserved in a root-owned 0600
  \`state/ID/metadata.json\` record with state \`creating\`.
- Each account is created by \`useradd -M -u ...\` with exact primary
  AAP2 group, nologin shell, fixed home and explicit password marker.
  The temporary password begins **disabled**; the permanent user has
  the non-hash \`NP\` marker (must be tested with local OpenSSH/PAM before
  asserting production compatibility).
- After each \`useradd\`, NSS UID/GID/home/groups/shell are independently
  checked before \`verified=true\` is persisted. If the process crashes
  between \`useradd\` and that write, cleanup **must not infer ownership
  from a username prefix**. It leaves an explicit manual-repair error.
- Only after chroot, SFTP inbox, \`status.json\`, empty root-owned
  \`authorized_keys\`, and effective \`sshd -T -C\` for both real accounts
  are verified, the script commits \`active\` metadata and calls
  \`chpasswd\` via **stdin**. The temporary 10-digit PIN is never saved
  in files, logged or used in command-line arguments; leading zeros are
  retained and the admin display groups digits 4-4-2.
- An **active** \`aap2-cleanup.timer\` is a hard precondition before and
  immediately before PIN activation. If it is missing, no PIN is issued.
- \`cleanup\` under the state flock checks recorded UID >=1001, GID,
  group memberships, nologin shell and home against live NSS immediately
  before **each** mutation; then locks the temporary account's password,
  terminates that temporary UID's sessions and deletes **only** its user
  and exact chroot files. Permanent account and root-owned authorized
  key are **never** deleted by the expiry timer, including if later
  approved. No \`userdel -r\` or recursive cleanup is used.
- One damaged state record doesn't stop cleanup of other expired PINs.
  Corrupt/ambiguous account state fails closed without destructive repair.
- It uses the real OpenSSH CLI for policy checks, never \`journalctl\` or
  parsing \`auth.log\` to infer whether a login occurred.

## Files and fixed paths

\`\`\`text
/usr/local/sbin/aap2-setup                  root:root 0755
/var/lib/aap2/                             root:root 0755
/var/lib/aap2/state/                       root:root 0700
/var/lib/aap2/state/.lock                  root:root 0600
/var/lib/aap2/state/<id>/metadata.json     root:root 0600
/var/lib/aap2/enroll/                      root:root 0755
/var/lib/aap2/enroll/<temp-user>/          root:root 0755 (SFTP chroot)
  config.json, status.json                 root:root 0644
  inbox/                                   root:root 0755
    public_key                             temp-UID:GID 0600 (precreated)
/etc/ssh/aap2-authorized-keys/             root:root 0755
  <permanent-user>                         root:root 0644 (empty until S2)
\`\`\`

Port allocation reserves the first unused loopback TCP port in
\`11010..11509\`, skipping all ports already present in AAP2 metadata and
currently bound on \`127.0.0.1\`. This range deliberately doesn't include
the existing AAP2 test tunnel \`10909\` or legacy AAP1 \`10808\`.

SFTP \`config.json\` (schema=1) contains only permanent username and port;
it is **not** a trusted source of host-key pin. \`status.json\` initially
contains \`schema=1\`, \`status=pending\`, \`expires_at\` (Unix epoch seconds).

## Installation on a **disposable** Debian test VM (NOT NOW on production)

**IMPORTANT:** the current \`aap2-test\` VM has a working Zebra tunnel
and existing \`aapdev\` account. Do not apply these steps to it until the
real-VM isolation review and backup/snapshot are complete. The commands
below are an installation reference, **not an automatic installer**.

The administrator separately installs the \`Match Group\` template
\`sshd-aap2-match.conf\` in the correct place, checks existing
\`Include\`/\`Match\`/\`AllowUsers\`/\`AllowGroups\` and tests a new admin
SSH login before manually reloading sshd. AAP2 never does this.

Example prerequisites on a clean disposable Debian VM:

\`\`\`sh
groupadd aap2-enroll
groupadd aap2-proxy
install -d -o root -g root -m 0755 /var/lib/aap2 /var/lib/aap2/enroll
install -d -o root -g root -m 0700 /var/lib/aap2/state
install -d -o root -g root -m 0755 /etc/ssh/aap2-authorized-keys
install -o root -g root -m 0755 server/aap2-setup /usr/local/sbin/aap2-setup
install -o root -g root -m 0644 server/aap2-cleanup.service /etc/systemd/system/
install -o root -g root -m 0644 server/aap2-cleanup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now aap2-cleanup.timer
\`\`\`

**Only after** manual SSH Match integration and testing:

\`\`\`sh
aap2-setup print-config
aap2-setup doctor
aap2-setup enroll TC21-test --server ssh.example.net:22222 \
  --client-addr 192.0.2.10 --client-host phone.example.net \
  --server-addr 192.0.2.20 --server-port 22
aap2-setup list
aap2-setup cleanup
\`\`\`

\`--server-addr\` and \`--server-port\` refer to **the sshd process itself**
(e.g. Incus guest port 22), not the host's forwarded port 22222.
\`--client-addr\` and \`--client-host\` describe the *connecting* client.
The administrator must provide real context because global/source-address
Match blocks can change the effective policy.

For \`doctor\` without actual AAP2 accounts, exit **2** means PENDING, not
a green light for enrollment. A previous account audit alone is **never**
sufficient proof of ownership for cleanup: NSS/metadata are rechecked
immediately before each destructive operation.

## TTL and operational limitations

- PIN expires after **600 seconds** in metadata; actual disabling occurs
  at the next timer sweep, normally within about one minute of expiry.
  Metadata alone cannot revoke a password in \`sshd\`.
- \`OnBootSec=1min\` makes cleanup run after reboot.
- If the timer stops, an already-issued PIN might outlive TTL until
  an administrator runs cleanup or repairs the timer; the CLI refuses
  **new** invitations while the timer is inactive. S2 can add stricter
  operational safeguards if a real pilot demonstrates the need.
- \`creating\` records whose user UID wasn't independently verified stay
  for **manual** repair; this is intentional fail-closed behavior.
- S1-B does **not** implement \`approve\` or \`revoke\`. An empty
  permanent account/key file remains after temporary PIN expiry;
  don't use this phase for a real rollout. Permanent key cleanup will
  require explicit \`revoke\` in S2, never the automatic timer.
- DNS/host-key fingerprint confirmation on Android is future work:
  when S2 is implemented, the PIN must not be sent until the independent
  full server-host-key fingerprint comparison succeeds.
- No tmpfs or filesystem quotas: a valid PIN-holder may fill the SFTP
  precreated file; this accepted residual DoS risk is documented.
- **CI Ubuntu 24.04 / real OpenSSH + PAM lifecycle passed**: throwaway runner
  ran the actual `enroll`, password SFTP upload, forced expiry, `cleanup`,
  repeated cleanup, and verified that the permanent account/key file survived.
  Real Debian 13/aap2-test verification and fail-closed tamper/crash cases
  on disposable users remain separate gates before any live use. S0 tests
  are isolated and do **not** establish the live server's policy.

## Tests

\`\`\`sh
python3 -m py_compile server/aap2-setup
python3 -m unittest discover -s server -p 'test_*.py' -v
\`\`\`

Unit tests inject failures at account reservation/useradd, immediately
before PIN activation and during cleanup, and confirm protected UID/home
checks. S0 continues to exercise real OpenSSH/SFTP restrictions separately.

### CI evidence — 2026-11-13

- Python 3.9 and 3.13 stdlib tests (19 cases each): [CI 43476214083](https://github.com/burbilog/android-as-proxy-2/actions/runs/43476214083) PASS.
- Isolated S0 real OpenSSH restrictions: [CI 43476214037](https://github.com/burbilog/android-as-proxy-2/actions/runs/43476214037) PASS.
- S1-B **real Ubuntu 24.04 hosted runner**: [CI 43476214075](https://github.com/burbilog/android-as-proxy-2/actions/runs/43476214075) PASS: full root account/PIN/chroot/SFTP lifecycle, manual test-only expiry, cleanup without `userdel -f`, permanent account and key preserved, second cleanup idempotent. No PIN echoed to CI logs.

A real SFTP session briefly leaves a PAM/sshd worker after its channel closes;
expiry cleanup now makes up to four bounded `userdel` attempts (one-second
spacing, retrying only the known busy exit code 8), re-validating ownership
on every attempt. It never force-deletes an in-use account.
