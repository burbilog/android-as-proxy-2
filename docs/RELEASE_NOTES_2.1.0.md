# Android as Proxy 2 — v2.1.0 (planned)

User-facing release work after stable v2.0.6:

- New About page with GitHub source, support, licensing, current version and distribution channel.
- Clearer activation guidance and a definition of what Connected means.
- Correct application name (remove the former Development label).
- GitHub and F-Droid variants build from the same source and version.properties; an independently signed F-Droid candidate can accompany GitHub releases without blocking them.
- GitHub builds keep explicit manual in-app APK updates, now available before enrollment.
- F-Droid builds contain no APK download/install implementation or install-packages permission. Updates come from the F-Droid client.
- No changes to server API, SSH host-key verification, enrollment identity, SOCKS5 or reset semantics.

Release-signed GitHub APK must be built on the maintainer's machine with the existing long-term signing key and tested with an in-place upgrade from v2.0.6 before publication. F-Droid review, reproducible build and screenshot capture are independent future gates.
