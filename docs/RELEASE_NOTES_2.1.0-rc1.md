# Android as Proxy 2 — v2.1.0-rc1 (release candidate)

**Status:** candidate for deliberate testing, **not** a stable release.
This tag must be published as a GitHub **Pre-release** and must **not**
replace `v2.0.6` as Latest or appear in the ordinary in-app updater.

## Changes since v2.0.6

- Hardened unattended SSH: full pinned host-key blob and restricted host-key
  algorithm negotiation; explicit identification of rejected host keys without
  reliance on JSch's exception text. A wrong host key is never accepted,
  and neither the server pin nor Android Keystore identity is regenerated.
  Host-key rejection, authentication refusal and recoverable local errors
  are reported durably with hourly retry; network failures use bounded
  backoff and reconnect after a genuine default-network change.
- More resilient SOCKS5 CONNECT implementation: correct TCP half-close,
  stall recovery for one-sided streams, TCP keepalive, connection saturation
  reporting, and IPv4/IPv6 fallback within a TCP connect deadline. Fully
  open idle sessions and explicitly authorized access to Android localhost
  and LAN are preserved. No added SOCKS password or destination filter.
- Android 13+ Connect no longer depends on the notification permission.
  Foreground service is promoted promptly even when stored enrollment is
  invalid or missing. Excluded application state from backup/device transfer.
- Cleaner enrollment guidance, About page (version, distribution channel,
  source and support), proper application name, optional battery settings.
- Two source-aligned GitHub/F-Droid build flavors. GitHub retains manual
  in-app APK updates; F-Droid flavor excludes APK download/install and
  install-packages permission. **No F-Droid store publication is claimed.**
- Server metadata is written crash-durably, including directory fsync.
  Existing enrollment, restricted OpenSSH server API, phone signing identity,
  package ID and server-side reverse-forward port remain compatible.
- Guarded release workflow for RC and stable builds, with signer,
  version, source-commit, archive and publication checks.

## Verified before RC1

Prior to creating the official RC APK, the same application code was built
as a **locally signed** `2.1.0-local-20801` and exercised on two *spare*
devices against a separate test S2 at `a.isaeff.net`:

- **Zebra TC21, Android 11:** in-place update preserving enrollment,
  working real HTTPS through the SOCKS5 reverse forward, automatic SSH
  recovery after a 10-second Wi-Fi interruption and after device reboot.
- **SEUIC, Android 14:** in-place APK update, HTTPS through SOCKS5, Connect
  with `POST_NOTIFICATIONS` denied (including `USER_FIXED`), and
  automatic service/SSH recovery after reboot without manually opening
  the app. Following a Wi-Fi toggle, the SEUIC's Wi-Fi itself remained
  disconnected in sleep; waking the display (without unlocking) restored
  Wi-Fi and AAP2 automatically reconnected. This is a known OEM behavior,
  not evidence that AAP2 can recover network access when Android has none.
- CI: GitHub and F-Droid flavor builds/unit tests and disposable Android 11
  emulator plus genuine OpenSSH/PIN/SFTP/SSH reverse-forward/SOCKS E2E passed
  for the source changes from the Fable 5.2 review in PR #37.

These observations apply to the **pre-RC signed test build**, not yet to
the final signed RC1 artifact, and do not establish multi-week unattended
reliability. A full Direct Boot *before first unlock* guarantee is not
claimed based solely on these tests.

## RC1 acceptance checklist

Before publication, on the maintainer's local workstation:

1. Merge this reviewed version/notes commit to `master` and confirm CI is
   green; prepare using `make release TYPE=rc1` with the existing long-term
   signing key. Verify package `net.isaeff.android.asproxy2`,
   `versionName=2.1.0-rc1`, `versionCode=20901`, certificate continuity
   against `v2.0.6`, SHA256 and exact source commit.
2. Upgrade spare Zebra TC21 and SEUIC **in place** from `20801` without
   uninstall, reset or re-enrollment. Confirm preserved enrollment and
   Android Keystore identities, approved server reverse ports, SOCKS5 HTTPS,
   and reboot recovery. The SEUIC may need its display woken for Wi-Fi.
3. Publish only with explicit `make publish TYPE=rc1`; confirm GitHub
   **Pre-release / not Latest** and check downloaded bytes and hashes using
   `make verify-release TYPE=rc1`.

**Known limits / deferred:** the optional F-Droid channel is not yet
published; long-term OEM power management varies by device; an interrupted
server `enroll` may leave a fail-closed `creating` record requiring
administrator repair (tracked in issue #38). There is no automatic
remediation of uncertain Unix-account ownership. Never deploy test or RC
builds to the remote Japanese production phone/relay without a separate
maintainer decision.

The stable `v2.1.0` release, if approved later, must be a new source
commit with `versionCode=21000`, `versionName=2.1.0`, reviewed stable
notes and a separately verified release-signed APK.
