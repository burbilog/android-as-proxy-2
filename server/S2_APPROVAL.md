# S2 — SFTP key approval, restricted -R and device revocation

**Deployment status:** S2 ships in the AAP2 v2.0.6 stable release. Disposable Debian 13/Ubuntu OpenSSH integration passed; individual unattended Android deployments still require field validation.
Use [INSTALL.md](INSTALL.md) for installation; SSH Match remains manual. Do not copy this configuration into the existing
\`aap2-test\` VM: it carries the working Zebra tunnel on port 10909. The S0
Match configuration must be installed MANUALLY, independently of this CLI;
\`aap2ctl\` never edits or reloads sshd.

## Manual typing on a phone (no clipboard, no QR)

Every `aap2ctl enroll` invitation now displays **one Phone activation code**,
formatted in 4-4-4-4-2 blocks. Android has one entry field and inserts
separators automatically;
users type **only 18 decimal digits**. The code consists of a six-digit
temporary account selector, ten random PIN digits and two MOD97 check
digits, all invisible as separate parts to the user. Leading zeros matter.
A failed local checksum is a typing mistake and MUST NOT open SSH or send PIN.
Checksum digits do **not** increase authentication entropy; only the ten
random PIN digits are secret. Existing 12-hex device records can still
be listed and revoked; the server no longer creates that invitation format.
The server address and port are still typed separately.

Example (fill in real addresses):

```sh
aap2ctl enroll SpareAndroid --server ssh.example.net:22225

# For unusual SSH Match rules, the full context is still available:
aap2ctl enroll --help-all
aap2ctl enroll SpareAndroid \
  --server ssh.example.net:22225 \
  --client-addr 192.0.2.5 --client-host phone.example.net \
  --server-addr 192.0.2.10 --server-port 22
```

The human-facing `enroll` result is organized into three short sections,
without colors or terminal dependencies:

- **PHONE:** the public server address and **one** bearer activation code.
- **VERIFY SERVER:** complete SHA256 fingerprints returned by the local
  `ssh-keygen`, for every installed SSH host-key algorithm. Compare the
  **fingerprint and key type** shown on the phone; never accept an
  unverified server key or suppress this section merely to shorten output.
- **ADMIN:** the enrollment ID and the exact command
  `aap2ctl approve ID` to use **after** the phone uploads its public key.
  The admin-only setup username is also shown here for diagnostics.

The stable machine-readable `Server:`, `Phone activation code ...:`,
`Setup username:` and `Enrollment ID:` lines are preserved because the
disposable integration checks extract them. CLI never writes the activation
code to an on-disk metadata file; avoid pasting it into tickets or logs.

The phone may be behind one or more NATs. The enrollment server does **not**
require the phone's LAN IP or public NAT IP, and SSH authorization is **not**
bound to either. To keep simple enrollment usable, the `sshd -T -C`
security probe defaults to the documentation source address
`198.51.100.1`, client hostname `unknown`, loopback local SSH address
`127.0.0.1` and local port `22`. These are **SSH Match probe inputs**,
not the phone's public endpoint, and do not change listening settings.
The CLI warns about the sample client and loopback server addresses;
JSON `invitation.warnings[]` carries those warnings as structured data.

For a server whose `Match Address`, `Match Host`, `Match LocalAddress`
or `Match LocalPort` differs by connection context, explicitly supply
the actual addresses and local SSH port via the advanced options shown by
`enroll --help-all`. A successful sample-context `sshd -T -C` does **not**
prove every possible real connection context; review such conditional
rules independently before inviting the phone. The strict group and
key restrictions for the sampled context are still checked before
any temporary PIN is activated.

The first six digits are **not authentication secrets**: they select a
short-lived Unix account. The ten-digit PIN, verified SSH host-key
fingerprint and explicit administrator approval remain unchanged. The
manual-entry PIN lasts **20 minutes**.
The temporary password still expires automatically via the cleanup timer;
do not treat the public six-digit code as an authentication secret. Issue
invitations only when the user is ready. Existing 12-hex enrollment IDs,
approved accounts and stored Android profiles remain valid. The default
format is now always 18 digits; no extra option or legacy client mode.

## Administrator workflow

After the current server setup from [INSTALL.md](INSTALL.md), with a working
\`aap2-cleanup.timer\` and verified SSH policy:

\`\`\`sh
aap2ctl enroll TC21 --server ssh.example.net:22222 \
  --client-addr 192.0.2.5 --client-host phone.example.net \
  --server-addr 192.0.2.10 --server-port 22

# The phone independently verifies the exact SSH host fingerprint BEFORE
# authenticating with the 10-digit PIN.
# It opens SFTP, downloads config.json, uploads ONLY its P-256 PUBLIC key
# into inbox/public_key, and saves its permanent identity locally.

aap2ctl approve <enrollment-id>
# Review the complete server-computed fingerprint of the immutable
# root-owned candidate.pub snapshot, then type y.
aap2ctl list

# Explicitly revoke a single AAP2 device, never a generic Unix username:
aap2ctl revoke <enrollment-id>
# Type REVOKE to confirm.
\`\`\`

### CLI output and list semantics

`enroll` prints three short sections in order: **PHONE** (endpoint and
one-time activation code), **VERIFY SERVER**
(full SHA256 host-key fingerprints and key types), and **ADMIN**
(enrollment ID and the exact `approve` command). The one-time
activation code is displayed **once**; the PIN is not written into
persistent state or Android logs. The CLI remains usable over plain
SSH and in scripts; no TUI or third-party Python dependencies.

`aap2ctl list` is a compact read-only view of current devices,
hiding `REVOKED` historical entries by default. Use `list --all`
to include them (the same filter applies to `list -j` and
`list -j --all`). Other states, including `EXPIRED` and
`REVOKING`, remain visible so a failed or incomplete cleanup cannot
be silently hidden. Each row shows the root-owned enrollment state
and its loopback SOCKS5 port. For `REVOKED` history rows,
the port is labeled **former**, not reserved: it can be reused.
`APPROVED` means its public key is authorized; it does **not** mean
the phone is online. Only `APPROVED` rows additionally show `(UP)`,
`(DOWN)` or `(UNKNOWN)` at the end. `UP` means the *server kernel* has
a TCP LISTEN socket bound to that device's reserved
`127.0.0.1:remote_port` (**reverse SSH forwarding present**).
`DOWN` means no such listener; `UNKNOWN` means the kernel socket table
could not be read. This is a passive `/proc/net/tcp` snapshot, **no logs,
network probes, SSH process inspection or daemon**. It cannot prove the
SOCKS5 path to the Internet works; check actual traffic with curl.
Historical `REVOKED` rows are never marked UP, even if their old port
was assigned to another device. The temporary PIN expiry is shown for
`ACTIVE` invitations only, never as an expiry date for an approved
permanent key. An empty list explains how to display `enroll --help`.

### Reserved SOCKS5 ports: manual reclamation policy

**Intentional S2 policy (including JSON/API consumers):** each enrollment
reserves the first available port in `127.0.0.1:11010..11509` (500 ports).
The allocator skips ports currently bound on loopback **and** ports belonging
to *every* metadata record whose state is not `revoked`. This includes
`active`, `expired`, `approved`, `creating` and `revoking`.
Port numbers are not monotonic counters and a disconnected phone does not
release its reservation.

After the 20-minute PIN expires, the cleanup timer removes the temporary
SFTP/login account, **not** the unapproved permanent placeholder, its
metadata, or its reserved port. This is deliberate fail-closed behavior:
automatic cleanup must never guess that an enrollment is safe to revoke,
especially if a device has already been approved. Expired, abandoned
invitations therefore consume capacity until an administrator explicitly
reclaims them. An exhausted pool causes future enrollment to fail, rather
than stealing a reserved port.

Inspect the records and device names with:

```sh
aap2ctl list --all
aap2ctl list --all -j
```

For an identified abandoned invitation, the administrator can run
`aap2ctl revoke ID`, inspect the displayed identity/port, and type the
literal `REVOKE` to confirm. This validates the stored identities and
removes the corresponding account/key safely. After the record becomes
`revoked`, its former port can be allocated to a **new** enrollment if it
is not currently bound by any listener. Do **not** revoke an approved,
still-needed device merely because its listener is down. Ambiguous or
tampered metadata fail closed and require manual inspection, not a
force-clean or deletion of state files.

**For integrations using `-j`/`--json`:** `aap2ctl list --all -j`
returns `devices[]` with a lowercase `state` and numeric `remote_port`.
Treat **every state other than `revoked` as a reserved port**, particularly
`expired`. Neither `temporary_pin_expires_at`, `listener_up:false`
nor `listener_up:null` means that the port is free.
`aap2ctl cleanup -j` expires temporary logins but does **not** reclaim
device reservations. Track abandoned invitations in your own inventory and
surface them for deliberate administrator review; never auto-revoke them
or reuse their ports based only on elapsed time or a JSON field.
`revoke -j` still requires the same interactive `REVOKE` confirmation
on stdin (prompt on stderr); JSON mode is **not** an unattended deletion API.
Code should check both the process exit status and JSON `ok`/`status`.

### JSON output for scripts

Use `-j` or `--json` either before or after a subcommand:

```sh
aap2ctl -j list | jq -r '.devices[] | select(.state == "approved") | .id'
aap2ctl list --all --json | jq '.devices'
aap2ctl doctor -j | jq '.checks[] | select(.level != "ok")'
```

The format is **JSON schema 1**, Python 3.9 standard-library only; no
YAML/third-party dependency. Each JSON invocation writes exactly one
JSON object on stdout, with `schema`, `command`, `ok`, and typed
command-specific fields. Parse failures return a JSON error and exit
status 2; operational failures return a JSON error or `ok:false` with
a nonzero exit status; doctor retains exit status 2 for pending
verification gates. The `--help` display remains human-facing.

- `list`: `devices[]` with `id`, `name`, `state`, integer
  `remote_port` and `listen_address`. For `approved` records only,
  `listener_up` is a JSON boolean from the live kernel socket table
  (`true`/ `false`), or `null` if it was unreadable. Do not interpret
  `listener_up` as end-to-end Internet connectivity or device identity.
  Only an `active` invitation has integer Unix
  `temporary_pin_expires_at`.
- `enroll`: `invitation` with the endpoint, usernames, port, TTL,
  host-key SHA256 fingerprint/type records and `approve_command`.
  The **single 18-digit secret** is supplied once as
  `invitation.activation_code`. It is not written to server state,
  but **stdout contains a bearer secret**: do not archive, log or
  publish the JSON response.
- `doctor`: `checks[]` (`level`, `message`), `failures`,
  `pending` and `status`. `print-config`: the raw manual-only
  SSH Match `template` as a string. `cleanup`: `expired_ids[]`
  and `errors[]`. `approve` and `revoke`: resulting `status`,
  enrollment ID and relevant approved identity fields.

**No auto-approval or auto-revocation:** JSON only changes formatting,
not security or the interactive decision. For `approve -j`, the
public-key fingerprint and the administrator prompt are printed to
**stderr** for review, while **stdout stays JSON-only**. For
`revoke -j`, the literal `REVOKE` confirmation is still mandatory.
A cancelled confirmation returns `ok:false`, `status:"cancelled"`
and a nonzero exit code. If invoking non-interactively, do not mistake
`--json` for consent to make changes.

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
- The temporary PIN stays active until its 20-minute TTL. On expiry the
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
python3 -m py_compile server/aap2ctl
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
