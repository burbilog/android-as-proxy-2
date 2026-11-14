# S2 Debian 13 gate — isolated real-SSH/PAM/systemd testing

This is a **disposable-container integration test**, not instructions to
configure the user's working SSH server.

CI workflow \`.github/workflows/server-s2-debian13.yml\` builds a fresh
\`debian:13\` image with OpenSSH, PAM, Python and systemd. It boots systemd
inside an isolated Docker container **on GitHub's ephemeral Ubuntu runner**,
then executes \`server/test-s1-live.sh\` with explicit S2 + Debian safety
flags. The test harness *deliberately replaces sshd_config and creates UNIX
accounts INSIDE THIS DISPOSABLE CONTAINER*. Do not run that harness on
\`aap2-test\` or on any production server.

The GitHub runner uses privileged Docker for systemd/cgroups. This is
acceptable only within the short-lived CI runner; it is **not** a required
production deployment mode. The test script refuses to run without
\`GITHUB_ACTIONS=true\`, \`AAP2_S1_DISPOSABLE=YES\` and no pre-existing AAP2
users/groups/state. These are protective friction, not a proof that a
different local machine is disposable.

## Evidence required

1. Debian 13 is verified via \`/etc/os-release\`; systemd is running.
2. \`enroll\` creates actual UNIX temporary/password and permanent/publickey
   accounts with UID>=1001, a root-owned chroot and an active 1-minute
   \`aap2-cleanup.timer\`. A ten-digit PIN authenticates to real OpenSSH/PAM,
   confined to precreated-file SFTP.
3. Invalid injected \`authorized_keys\` options are rejected without
   enabling a key. An exact ECDSA P-256 key snapshot is accepted by
   \`approve\` and activates only the assigned loopback \`-R\`.
4. Simulate an interruption **after key publication but before status and
   metadata publication**. Retrying \`approve\` must keep the original
   snapshot even if the temporary SFTP file was overwritten by another
   key while the client was still authorized.
5. HTTP reaches the client through a real approved \`-R\`; wrong port,
   shell, \`-W\`, and a second SSH key are rejected.
6. A test-only edit attempts \`revoke\` with forged **UID 0** metadata. It
   must fail without touching root, the authorized key, or the existing
   connection. A live NSS home-path mismatch must fail similarly.
   Malformed state JSON must make \`cleanup\` fail closed.
7. Manually expire the temporary invite metadata, start the **actual
   systemd** \`aap2-cleanup.service\` and verify the timer remains active.
   The temporary user/chroot disappears while the approved identity
   and active reverse tunnel remain.
8. Explicit \`revoke\` removes only the selected permanent UNIX identity
   and authorized key and closes the **already active** reverse listener.

An ephemeral integration run does **not** prove that another Debian server
has identical PAM policy, an unmodified running SSH daemon, or identical
systemd configuration. On production, the admin still reviews Match/Include
order, applies sshd config **manually**, tests daemon reload and real
connections independently. \`aap2-setup\` itself never alters system
sshd config or restarts/reloads sshd.

This gate also does not provide the Android enrollment UI, the pre-PIN
full host-key verification screen, Android SFTP upload, or the user's
confirmation flow. Those remain the next A1 implementation milestone.
