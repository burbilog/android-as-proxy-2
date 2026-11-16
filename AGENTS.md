# AAP2 maintainer / coding-agent entry point

This repository is a self-hosted Android SOCKS5 exit via a pinned,
outbound SSH reverse forward to an OpenSSH server. The phone's long-term
SSH identity lives in Android Keystore, and changing or uninstalling the
app can make remote recovery expensive. Treat deployed enrollments and
the signing certificate as persistent user data.

## Read before modifying or publishing releases

**Authoritative project policy:** [docs/RELEASE_POLICY.md](docs/RELEASE_POLICY.md).

Also read [README.md](README.md),
[docs/A1_LOCAL_MAKE.md](docs/A1_LOCAL_MAKE.md),
[docs/P0_RELEASE_SIGNING.md](docs/P0_RELEASE_SIGNING.md),
and [docs/FDROID.md](docs/FDROID.md) before release-related work.

**Important invariants:**

- Current `master` and `version.properties` do not prove an APK was
  published; check GitHub Releases and actual tags each time.
- GitHub `Latest` is for field-tested stable APKs, not RC or debug
  builds. Use `make` for the side-effect-free release guide.
  Public operations require `TYPE=release` or `TYPE=rcN`; an RC
  must remain a GitHub Pre-release with `--latest=false`, and stable
  alone may become Latest. Check actual signed APKs and GitHub tags.
- GitHub and F-Droid flavors share stable versions and application ID,
  but F-Droid availability and signing verification are independent.
- Preserve the long-term local APK signing key and Android Keystore
  continuity. No private signing material or live credentials in Git,
  CI, documentation, issue bodies or assistant transcripts.
- No uninstall, app-data clearing, forced re-enrollment, server
  revocation or public release without explicit maintainer intent.
- Keep README, stable release notes, and the release policy in sync
  when the release procedure changes. Do not claim physical test
  results without evidence.

This file is a **discovery pointer**; release decisions and detailed
rules belong in `docs/RELEASE_POLICY.md`. Follow the current repository
code and actual release state instead of assuming its historical
snapshot is current.
