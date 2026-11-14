# S2 — SFTP key approval, restricted -R and device revocation

**Development gate:** S2 runs only on a disposable Debian/Ubuntu test SSH
host until explicit review. Do not copy this configuration into the existing
\`aap2-test\` VM: it carries the working Zebra tunnel on port 10909. The S0
Match configuration must be installed MANUALLY, independently of this CLI;
\`aap2-setup\` never edits or reloads sshd.

## Manual typing on a phone (no clipboard, no QR)

For **new** S2 invitations, use `--manual-code` with the ordinary
`enroll` arguments. The CLI then displays **Phone setup code** as
**exactly six decimal digits**, including leading zeros. In the A1
Android screen, the user types **only those six digits**, not the
`aap2e` Unix username prefix. The phone constructs the exact SSH
username locally. The one-time PIN remains **ten digits**; printed
hyphens are separators and must not be entered on the phone.
The server address and port are still typed separately.

Example (fill in real addresses):

```sh
aap2-setup enroll SpareAndroid --manual-code \
  --server ssh.example.net:22225 \
  --server-addr 192.0.2.10 --server-port 22
```

The phone may be behind one or more NATs. The enrollment server does **not**
require the phone's LAN IP or public NAT IP, and SSH authorization is **not**
bound to either. `--client-addr` is optional: it is passed **only** to
`sshd -T -C` as a sample source address to inspect effective `Match`
policy. By default the CLI uses documentation address `198.51.100.1`,
prints a warning, and still checks the role-specific `Match Group`
restrictions on both generated accounts. On servers with source-sensitive
`Match Address` or `Match Host` conditions, an administrator must
independently verify policy for the real connection; no one sample IP can
prove those policies for arbitrary NAT clients.

Six-digit codes are **not authentication secrets**: they only select a
short-lived Unix account. The ten-digit PIN, the verified SSH host-key
fingerprint and explicit administrator approval are unchanged. The
manual-entry PIN lasts **20 minutes** (10 minutes in the legacy mode).
The temporary password still expires automatically via the cleanup timer;
do not treat the public six-digit code as an authentication secret. Issue
invitations only when the user is ready. Existing 12-hex enrollment IDs,
approved accounts and stored
Android profiles remain valid. The new option does **not** migrate or
alter existing accounts, or silently change the default enroll format.

## Administrator workflow

After the server setup from [S1_ENROLL.md](S1_ENROLL.md), with a working
\`aap2-cleanup.timer\` and verified SSH policy:

\`\`\`sh
aap2-setup enroll TC21 --server ssh.example.net:22222 \
  --client-addr 192.0.2.5 --client-host phone.example.net \
  --server-addr 192.0.2.10 --server-port 22

# The phone independently verifies the exact SSH host fingerprint BEFORE
# authenticating with the 10-digit PIN.
# It opens SFTP, downloads config.json, uploads ONLY its P-256 PUBLIC key
# into inbox/public_key, and saves its permanent identity locally.

aap2-setup approve <enrollment-id>
# Review the complete server-computed fingerprint of the immutable
# root-owned candidate.pub snapshot, then type y.
aap2-setup list

# Explicitly revoke a single AAP2 device, never a generic Unix username:
aap2-setup revoke <enrollment-id>
# Type REVOKE to confirm.
\`\`\`

### Approval security

- Only one \`ecdsa-sha2-nistp256\` key line (max 1024 bytes, optional
  restricted ASCII comment) is accepted. No injected \`authorized_keys\`
  options, extra lines, whitespace tricks, alternative curves or
  noncanonical SSH wire/base64 encoding.
- The SFTP upload is opened by fixed path with \`O_NOFOLLOW\` and
  \`O_NONBLOCK\`. Numeric owner/group, link count, regular-file type,
  mode 0600 and size are checked by \`fstat\`; 1025 bytes is the maximum
  read. Any observed change during reading rejects the candidate.
  There is **no filesystem quota**; a PIN-holder can still upload large
  data, an accepted residual DoS risk.
- The first valid key is copied as \`state/<id>/candidate.pub\`
  (root:root 0600). Later SFTP overwrites **cannot** replace that
  snapshot. OpenSSH \`ssh-keygen\` validates the candidate and prints its
  complete SHA256 fingerprint for the administrator.
- No state lock is held while the administrator reads/verifies the
  fingerprint and answers \`y\`. On approval the lock is reacquired;
  current TTL, state, account ownership and exact snapshot are checked
  again. An expired/cancelled invitation cannot be approved.
- A *single*, root-owned \`authorized_keys\` line is installed for the
  permanent device:
  \`restrict,port-forwarding,permitlisten="127.0.0.1:<port>" ecdsa-sha2-nistp256 ...\`
  with the port reserved uniquely in metadata. It cannot grant public
  TCP listening, unrestricted forwarding, shell/exec or SFTP. All
  server-side restrictions still require the manually installed Match
  policy from S0.
- Status \`approved\` is published to the SFTP client **after** the key
  is installed. If the CLI crashes after key installation but before
  status publication, retrying \`approve\` with the same snapshot is
  safe. Android can attempt the permanent key even when its SFTP
  connection was interrupted; no need to delete/regenerate that key.
- The temporary PIN stays active until the 10-minute TTL. On expiry the
  systemd cleanup removes only temporary access and its chroot; the
  permanent key stays approved, including across a cleanup run.

### Revocation security

\`revoke\` accepts the root-owned enrollment **ID**, never a Unix
username, UID or untrusted filename. It prompts for the literal
\`REVOKE\`. Under the state lock, it first validates root-owned
metadata, the fixed key file and the live NSS identity/UID/GID/groups
(UID **must be >=1001**). An unexpected \`authorized_keys\` line
causes refusal.

It marks state \`revoking\`, atomically clears the authorized key so
new SSH logins cannot occur, terminates only the device UID's existing
sessions, and deletes only that verified permanent Unix account
(\`userdel\` without \`-r\`). If the temporary enrollment still exists,
it is also disabled and cleaned. The reserved account's key file is
removed, state becomes \`revoked\`. A second \`revoke\` is harmless.

If a crash/tampering/UID reuse makes ownership ambiguous, **fail closed
and require manual operator inspection** rather than force deletion.
Cleanup never removes approved permanent accounts or keys.

### Tests and limitations

\`server/test_setup.py\` includes isolated unit tests for canonical P-256
keys, injected options, malformed SSH wire formats, TTL preservation and
no-revoke-without-confirmation. \`server/test-s1-live.sh\` in explicit
S2-only mode runs a complete real OpenSSH disposable-runner workflow:
PIN/SFTP upload, negative injected upload, fingerprint approval,
restricted publickey \`-R\` carrying HTTP, denied exec/direct-tcpip and
other port, PIN cleanup with the permanent connection still alive,
then explicit revocation and confirmation that the listener closes.
The standalone S0/S1 regressions still run.

\`\`\`sh
python3 -m py_compile server/aap2-setup
python3 -m unittest discover -s server -p 'test_*.py' -v
\`\`\`

**Not included yet:** the Android three-field enrollment UI, the
independent pre-PIN host-key verification screen, native phone-side SFTP
upload and automatic permanent login after approval, server-reachable
multi-device demo on real Debian 13. The current Zebra uses existing
P0 test provisioning; this PR does not modify its running connection.

**Known limitation:** \`sshd -T -C\` verifies disk configuration only;
the administrator must separately confirm that the running sshd loaded
it. A separate disposable Debian 13 VM integration gate is required
before live server deployment.
