# 2026-11 code review: accepted decisions and follow-up

This is a **design decision record**, not a claim that a release has been
published or physically field-tested. Read the actual code and release
status before implementation. AAP2 is for deployments where the person
holding the Android device may have **no technical skills and little time**.

## Trust boundary and enrollment

- **Intended and retained:** the authenticated user of an AAP2 SOCKS5
  endpoint can connect by TCP not only to the public Internet but also
  to services on the Android device (for example, droidVNC-NG on
  `127.0.0.1`) and the Android-side LAN. This capability is essential
  for remote support. No blanket private-IP ban or password prompt on
  the phone-side SOCKS5 server. The server-side reverse listener remains
  bound to `127.0.0.1`; access to the relay VM and any Hysteria 2
  credentials must be controlled by the **server operator**. Do not
  expose SOCKS5 publicly by default.
- **Accepted residual enrollment risk:** a party knowing a short-lived
  activation PIN could attempt to replace the SFTP-uploaded client
  public key before the admin snapshots and approves it. Do **not**
  silently change the enrollment protocol or lock temporary password
  accounts the moment a file becomes nonempty: partial upload and
  reconnect/error recovery must not be broken. No extra fingerprint
  code or comparison step for the person holding the phone.
- Do not add required onboarding dialogs, SOCKS passwords, or
  destructive reset/re-enrollment as routine troubleshooting.

## Unattended reliability: implementation areas

- **Protect SSH identity:** pin the negotiated SSH host-key algorithm
  to the already verified key; a newly added server key must not
  disconnect existing phones. Mismatch must **not** silently re-pin.
- **Recover safely:** persist non-secret errors and understandable
  status/notifications. Retry pinned-host-key rejection and temporary
  local/Keystore failures at most hourly, without ever authenticating to
  a rejected host, re-pinning or replacing the phone identity. Because
  JSch may discard transport exceptions during userauth and emit an
  ambiguous Auth fail, allow one normal-backoff retry before throttling
  the second consecutive authentication failure hourly. A healthy
  tunnel or a non-auth failure resets the consecutive count.
  Ordinary network failures use bounded backoff. Explicit STOP/RESET
  seals the previous manager before clearing persistent issue state.
- **Avoid stale Android profiles:** exclude device-protected and
  credential-protected app state from Android 12+ device transfer
  and cloud backup. Keystore identities are not transferable.
- **Network switch:** detect *actual* default-network changes, not
  repeated callbacks for the same network.
- **SOCKS longevity:** preserve half-close and very long idle sessions,
  but reclaim stalled one-sided connections and report saturation;
  prefer sequential dual-stack fallback over unnecessary full
  Happy Eyeballs orchestration. Do not introduce a short global
  idle timeout for radio or other long sessions.
- **Battery controls:** provide only an optional settings shortcut,
  never an automatic forced exception request or default WifiLock.
  Users should not be asked to understand system networking internals.
- **Local SOCKS port:** keep the existing stable `11080` endpoint,
  declared in one place. Switching to a random port is not justified
  for a dedicated Android device yet.

## Maintenance and deferrals

- Remove misleading P0-era comments, redundant manifest declarations
  and unused starter tests when safe, but do not globally rename
  historical milestones or rewrite the ~400-line Compose screen
  into a ViewModel solely for style.
- Server metadata replacement must be crash-durable with file and
  directory `fsync`, **without** turning it into a separate daemon,
  log-parser or recovery protocol.
- Manage dependency drift through reviewable, tested, **non-automatic**
  dependency update PRs. AndroidX/JSch upgrades need regressions for
  pinned SSH host-key negotiation, Android 11 and end-to-end SOCKS.
- Do not implement automatic pruning of expired reserved ports.
  Reservation/revocation policy remains explicit and manual.
- Avoid mass-moving milestone documents into `docs/history`
  merely for cosmetic reasons; improve navigability gradually.

## Status of work on these decisions

- Code-review security/recovery group implemented in
  [PR #28](https://github.com/burbilog/android-as-proxy-2/pull/28).
- SOCKS longevity, DNS fallback and network-switch group implemented in
  [PR #29](https://github.com/burbilog/android-as-proxy-2/pull/29).
- Remaining optional battery shortcut, shared local port, maintainability
  cleanup and server state-durability work is performed in a separate
  follow-up. **None of these merges are evidence of completed physical
  soak testing**, nor permission to publish a stable APK.
- Physical acceptance on a spare Android and a real network interruption
  test remain release gates. Keep a remote enrolled phone undisturbed
  until those pass. See [release policy](RELEASE_POLICY.md).
