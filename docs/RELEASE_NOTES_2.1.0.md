# Android as Proxy 2 — v2.1.0 (planned)

## What changes since v2.0.6

- **Unattended SSH reliability:** the already pinned server host key also
  restricts host-key algorithm negotiation. A rejected server key never
  causes TOFU, automatic re-pinning or authentication to the wrong host.
  Pinned-key rejections, authentication problems and recoverable local errors
  leave a persistent problem status and are retried at most hourly;
  ordinary network failures use bounded backoff.
- **Network recovery:** reconnect when Android's default network actually
  changes or disappears, without the phone operator touching the app.
- **SOCKS5 stability:** TCP keepalive, bounded cleanup of stalled half-closed
  streams, observable connection-slot saturation, and IPv4/IPv6 address
  fallback with an overall TCP-connect deadline. Fully open idle sessions
  remain supported, including remote support via phone-local VNC and LAN.
- **Android safety:** exclude enrollment/Keystore-dependent app state from
  Android cloud backup and phone-to-phone transfer. Start the foreground
  service promptly even when the stored profile is invalid or missing.
  Android 13+ notification permission is optional and no longer blocks
  the Connect button.
- **Usability:** About page with source, support, license, version and
  distribution; clearer activation and Connected-state guidance; correct
  application label; optional battery settings shortcut.
- **Distribution:** GitHub and F-Droid variants use the same source and
  checked-in version. GitHub keeps manual in-app updates (also available
  before enrollment). F-Droid excludes the APK downloader, install permission
  and update button; any store submission and reproducible-build validation
  are separate future work.
- **Server:** crash-durable metadata replacement now syncs both the file
  and its parent directory. Existing OpenSSH protocol, enrollment records,
  identities and reverse-port restrictions remain compatible.
- **Release tooling:** guarded local signing and explicit RC/stable publication
  with signer, version and provenance checks.

There is **no enrollment-protocol migration or new authentication prompt**.
The existing verified host key, Android Keystore identity, application ID,
SOCKS5 access model and reset semantics are retained. An in-place update
must never require re-enrollment. GitHub-signed and independently F-Droid-signed
APKs are **not interchangeable** unless Android verifies a compatible signing
certificate.

## Acceptance before publication

Local Android 11 Zebra TC21: a maintainer-signed 2.1.0-local-20800 upgraded
v2.0.6 in place while preserving enrollment. A real SOCKS5 HTTPS request worked
after APK replacement, a ten-second total Wi-Fi outage and a phone reboot
without manual interaction. These are physical smoke tests, **not** a completed
long-duration soak test of the final candidate.

Before a public RC or stable release, pass current CI, build the exact candidate
with the existing long-term key, verify signatures, and repeat the physical
network/reboot tests after the final code changes. Use a spare device; do not
experiment on the remotely deployed Japanese phone or its relay VM. F-Droid
submission and screenshots are independent and do not block GitHub publication.
