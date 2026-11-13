# S1-A — aap2-setup (read-only server preflight)

This is **the first, intentionally non-mutating server CLI stage**.
It is **not** a functioning enrollment server yet: no PIN, no user creation,
no password activation, no approval or cleanup timer exists in this stage.

One Python 3.9+ script, stdlib only: \`server/aap2-setup\`.
No daemon, third-party Python packages, shell command strings, log parsing,
or system SSH changes.

## Commands

\`\`\`sh
python3 server/aap2-setup print-config
python3 server/aap2-setup doctor
\`\`\`

\`print-config\` prints the tested S0 template with a **manual-only** warning.
The administrator **must** place appropriate Match sections personally,
review existing Include/Match and AllowUsers/AllowGroups, check sshd -t,
then explicitly reload sshd. **AAP2 never edits/reloads sshd.**

\`doctor\` is **strictly read-only**, and currently checks:

- Python version and root EUID (identical for su, sudo and root shell).
- Existence of **both** exact AAP2 groups and fixed root-owned directories:
  \`/var/lib/aap2\` 0755, \`state\` 0700, \`enroll\` 0755, and
  \`/etc/ssh/aap2-authorized-keys\` 0755. Symlinks, wrong ownership,
  and unsafe modes are rejected.
- System \`sshd -t\` syntax **on disk**. It never reloads the running daemon.
- Optional *actual* temporary and permanent usernames (must exist in NSS,
  UID >=1001, a nologin shell, exactly their assigned AAP2 access role,
  no simultaneous membership in both roles). For these it validates chroot,
  precreated SFTP file, root-owned authorized key file, and exact
  effective \`sshd -T -C\` policy, including alternate auth sources,
  direct forwarding, reverse forwarding and shell limits.
- Warns if fail2ban is not available. Does not attempt to install it.
- Reports absence of the **still-unimplemented cleanup timer** explicitly.

For real Match checks, supply both actual AAP2 usernames and accurate
connection context, e.g.:

\`\`\`sh
python3 server/aap2-setup doctor \
  --enroll-user ACTUAL_TEMP_USER \
  --proxy-user ACTUAL_DEVICE_USER \
  --client-addr 192.0.2.10 \
  --client-host example.net \
  --server-addr 192.0.2.11 \
  --server-port 22
\`\`\`

\`--server-port\` is the SSH daemon's local port (22 inside the Incus VM),
not the Incus host-side 22222 forwarding port. \`--client-host\` denotes
the **remote connecting client hostname**, not the SSH server.

### Exit status

- **0**: all required checks passed, and no unresolved gates remain
  (cannot currently occur during S1-A).
- **1**: at least one required check failed.
- **2**: no failure but one or more checks remain **PENDING**.

In particular, running \`doctor\` without real AAP2 users is only a
**partial preflight**. It must NOT be mistaken for verification that Match
Group restrictions are effective. A running daemon may also differ from
the on-disk configuration; S0 real-SSH positive/negative integration
tests are still required.

## Safety and next step

**Do not install S1-A into a working server expecting \`enroll\` to work.**
It has no mutating subcommands, so it cannot create accounts or activate
passwords. Production integration is not requested by this PR; the
existing \`aapdev\` SSH transport and SOCKS listener remain untouched.

The next stage S1-B will add the crash-safe creation sequence:
reserve ID/port and write root-owned \`creating\` metadata **before**
\`useradd\`, verify NSS UID >=1001 and exact role/home before every
mutation, create root-owned chroot/inbox/status, and activate a random
10-digit PIN **last** only if all checks and cleanup timer are ready.
All destructive cleanup must additionally prove account ownership from
metadata; neither username prefix nor UID/group membership suffices.

## Validation

\`\`\`sh
python3 -m unittest discover -s server -p 'test_*.py' -v
python3 -m py_compile server/aap2-setup
\`\`\`

S0's \`test-s0.sh\` remains the separate positive/negative real-OpenSSH
integration test. No test in S1-A modifies system accounts or sshd.
