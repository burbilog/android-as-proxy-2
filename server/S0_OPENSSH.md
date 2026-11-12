# S0 — OpenSSH restrictions (manual integration)

This is a **tested design under development**, not an installer. No project
program may change `sshd_config`, `sshd_config.d`, the host keys or reload
the running sshd. Do not apply the blocks to a working SSH server before all
tests pass on a **disposable** Debian VM and the admin has checked other
`Match`, `Include`, `AllowUsers`/`AllowGroups`, PAM and key sources.

## Intended production policy

See [sshd-aap2-match.conf](sshd-aap2-match.conf). The two groups are:

- `aap2-enroll`: temporary PIN/password **only**, forced
  `internal-sftp` in root-owned `/var/lib/aap2/enroll/%u` chroot;
  no SSH shell/exec, TTY or forwarding. Precreated writable
  `inbox/public_key` is inside a non-writable directory.
- `aap2-proxy`: permanent **publickey only**, keys exclusively in
  root-owned `/etc/ssh/aap2-authorized-keys/%u`, **remote** TCP forwarding,
  no SSH sessions (`MaxSessions 0`), no shell/exec/SFTP/TTY,
  no local `-L` / `-W`, no agent/X11 forwarding. An individual key
  requires `restrict,port-forwarding,permitlisten="127.0.0.1:10909"`
  (substitute the allocated **per-device** port). This is not a policy
  allowing every account the same port.

The explicit `127.0.0.1:PORT` listen form is deliberate:
`-R PORT:dest:port` asks for `localhost` and is **not the same
host string** as `127.0.0.1`. `GatewayPorts no` restricts
external exposure, but the **per-key permitlisten** enforces the
exact endpoint. `MaxSessions 0` blocks shell/session channels, not
TCP forwarding. This is why actual handshake tests are essential.

## Quick safe tests on `aap2-test`

Tests use their own `sshd -D` bound to **127.0.0.1:22223** inside the VM,
their own SSH host key, two disposable UNIX accounts named
`aap2s0enroll`/`aap2s0proxy`, private keys for the test alone,
temporary root-owned directories under `/var/lib/aap2-s0.*` and a local
HTTP server on `127.0.0.1:18887`. Exit/interrupt removes only users and
groups the script itself created (UID must be >=1001), test processes and
temporary files. If either account/group already exists, the script
**refuses to run rather than adopting it**.

The test script deliberately does not touch `aapdev`,
the running `sshd`, `/etc/ssh/sshd_config`, `/etc/ssh/aap2-*`,
port `22`, Incus host port `22222` or existing AAP2 forward `10909`
in the VM. The isolated S0 test uses **11909**, not AAP2's **10909**, for
its remote forward. The separate sshd is bound to `127.0.0.1:22223` and
needs no external firewall or Incus proxy changes. Stop the test if these
local test ports are already occupied.

On a fresh Debian 13 VM or a disposable snapshot:

```zsh
incus exec aap2-test -- apt-get install -y openssh-server openssh-client sshpass python3 curl openssl
incus exec aap2-test -- runuser -u aapdev -- git clone --branch feature/s0-openssh-isolated-test --depth 1 \
  https://github.com/burbilog/android-as-proxy-2.git /tmp/aap2-s0-source
incus exec aap2-test -- sh -n /tmp/aap2-s0-source/server/test-s0.sh
incus exec aap2-test -- sh /tmp/aap2-s0-source/server/test-s0.sh
```

No production config is installed; a failed check exits non-zero. GitHub
Actions runs the same script on an ephemeral Ubuntu runner to catch errors
before the VM exercise.

**Expected minimum checks:**

1. `sshd -t` accepts isolated config; `sshd -T -C` resolves both Match groups.
2. Permanent account: permitted `-R 127.0.0.1:11909` carries HTTP traffic.
3. Wrong port, wildcard and implicit `localhost` listener are denied.
4. `-W` / local direct-TCP, shell/exec, SFTP and unknown key are denied.
5. Temporary PIN user can overwrite its **precreated** SFTP file but cannot
   execute shell commands or request forwarding.

**Important limitation:** this S0 harness uses a separate daemon and **does
not validate that the installed production sshd has reloaded the desired
configuration**, or that its active PAM/SELinux policy is compatible.
A production check must always include its own `sshd -T -C` with real client
addresses, and a connection against the running daemon. Test
`lport=22` for the VM's sshd listening on 22; `22222` is an Incus
**host-side** forwarded port, not the guest's sshd local port.

## Installing on a real SSH server later

The administrator places the Match blocks **manually**, after reviewing
any pre-existing `Match` and `Include` directives. A separate
`Match all` is **not a reset to global context**. Do not paste into an
arbitrary include that might be processed in an unexpected Match context.
Create `/etc/ssh/aap2-authorized-keys` root-owned and verify it is not
writable by group/others. Create temporary chroots as root-owned parents
with writable files only inside their controlled subdirectories.

```sh
sshd -t
sshd -T -C user=REAL_PROXY_USER,addr=REAL_CLIENT_IP,host=REAL_CLIENT_HOST,laddr=SERVER_IP,lport=22
sshd -T -C user=REAL_ENROLL_USER,addr=REAL_CLIENT_IP,host=REAL_CLIENT_HOST,laddr=SERVER_IP,lport=22
```

These read on-disk configuration; they are not proof the live server
has loaded it. Only after independent review, perform **manual**
`systemctl reload ssh` and a real SSH integration test while retaining
an existing admin login for recovery.

### Account state caution

OpenSSH can reject an account whose shadow password is locked with `!`
**even for publickey** (varies with distro/PAM). Use a password-disabled
but accessible account state confirmed by actual `-R` login, such as
a non-hash shadow marker on the tested distribution, and deny password
authentication through `Match`. Do not assume `passwd -l` is safe.

The server-side `aap2-setup doctor` is a future **strictly read-only**
component. It must not edit SSH config, parse auth logs or treat a passed
`sshd -T` check as complete behavioral proof.
