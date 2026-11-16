# AAP2 release policy (GitHub and F-Droid)

**Status: maintainer policy, not a release announcement.** Adopted
2026-11-16. This document governs all future release candidates and
stable Android APK releases, regardless of which maintainer, human or
coding agent prepares them. Treat its rules as project policy until
explicitly amended in a reviewed commit. Do not infer the **current**
published version from the dated snapshot below.

## Start here when resuming maintenance

1. Read this policy, [README](../README.md),
   [local release workflow](A1_LOCAL_MAKE.md),
   [release signing](P0_RELEASE_SIGNING.md) and
   [F-Droid constraints](FDROID.md).
2. Inspect live [GitHub Releases](https://github.com/burbilog/android-as-proxy-2/releases),
   [latest stable release](https://github.com/burbilog/android-as-proxy-2/releases/latest),
   Git tags, `version.properties`, `Makefile`, relevant CI workflows and
   the newest release notes. **Never assume `master` is published.**
3. Check open issues, current test results and the actual Android
   package/version/signing certificate before proposing an upgrade.
   Distinguish documented plans, CI passing and field-proven behavior.
4. Do not publish a release, use a private signing key, reset a remote
   phone, revoke a server identity or switch distribution channels
   without deliberate maintainer authorization.

**Snapshot on 2026-11-16 (historical; recheck later):**

- `v2.0.6` is the latest regular GitHub Release: a release-signed
  `aap2.apk` and `SHA256SUMS`. The Android/SSH enrollment, reverse SOCKS5
  and end-to-end Japanese network exit have been field-tested.
- `master` is preparing `2.1.0` (`versionCode=21000` in
  `version.properties`), including the About page and separate
  `github`/`fdroid` flavors. This is **source state, not yet a
  published v2.1.0 APK**.
- No verified F-Droid reproducible build or F-Droid repository listing
  is claimed. See [F-Droid follow-up #21](https://github.com/burbilog/android-as-proxy-2/issues/21).
- Field success proves working connectivity, **not** weeks of reliable
  unattended operation across OEM battery policies, Wi-Fi loss and boot.

## What “stable” means

A **stable GitHub release** is a published release that is **not** a
Draft or Pre-release and is designated **Latest**. GitHub has no
independent `stable` checkbox. The AAP2 in-app updater intentionally
downloads from `releases/latest`; publishing a regular release as
Latest makes that build available to ordinary phones through **Update**.

`Stable` means the release has passed the gates below. It does **not**
mean “production reliability guaranteed,” “audited,” “no known bugs,”
or “long-term unattended operation proven.” A release may truthfully
be described as **field-tested**, with the actual devices and tests
listed in its notes. Never upgrade an untested candidate to Latest
merely to make it easy to download.

| Stage | Tag example | GitHub status | Intended use |
| --- | --- | --- | --- |
| Development | no release tag | no published APK | Ongoing changes on branches/`master` |
| Release candidate | `v2.1.0-rc1` | **Pre-release**, never Latest | Explicit opt-in testing on spare devices |
| Stable | `v2.1.0` | Normal Release, **Latest** | Regular installation and in-app Update |
| F-Droid distribution | same source version as stable | Independent F-Droid review/build | Optional second channel, never a gate for GitHub |

**The existing `v2.0.6` release can remain Latest and be described as
“Stable — field-tested.”** No new GitHub status or renamed tag is
required. Do not silently change its APK or pretend it has passed tests
it has not.

## Version numbers, source identity and channels

- Use `MAJOR.MINOR.PATCH` for stable `versionName` and
  `vMAJOR.MINOR.PATCH` for the stable Git tag. Increment **PATCH** for
  fixes, **MINOR** for backward-compatible features, **MAJOR** when an
  incompatible change or migration warrants it. If protocol,
  application ID or persisted identity changes, document the migration
  and rollback risks before any release.
- `version.properties` is the **canonical on-tag version source**,
  including RC tags.
  Both `github` and `fdroid` flavors use the **same**
  `versionName`, integer `versionCode`, application ID
  (`net.isaeff.android.asproxy2`) and source commit for a given release.
  They are different APK *contents* because of their update behavior.
  Do not maintain separate GitHub/F-Droid version branches or numbers.
- The Android `versionCode` must **increase** for each installable
  update, including same-signature upgrades between channels.
  Never recycle a versionCode for a *later release*; the two flavors
  of the **same** release intentionally share it despite different
  contents. Any pre-release test build must have a code lower than its eventual
  stable version **and** higher than the previous publicly installable
  version it must upgrade. Reserve codes and verify the whole chain
  before publishing; do not rely on lexical sorting of version names.
  **Reserve one consecutive block of 100 codes per stable release
  cycle.** The stable code ends in `00`; each `rcN` uses the
  preceding hundred block's code ending in `NN` (N=1..99).
  Thus `2.1.0-rc1=20901`, `2.1.0-rc2=20902`,
  `2.1.0=21000`, `2.1.1-rc1=21001`, `2.1.1=21100`.
  These codes are assigned by **release sequence**, not by a formula
  derived from semantic major/minor/patch numbers. Check all existing
  public releases before allocating the next block.
- Local `make build` APKs intentionally use higher, disposable version
  codes and `-local-` names. They are **not release candidates**.
  A phone running such a build might be unable to install a lower-code
  stable APK without a destructive downgrade. Plan RC testing on
  compatible test devices; never uninstall an enrolled phone to solve a
  versionCode collision.

**Implemented release workflow:** `make` without arguments prints a
side-effect-free release cheat sheet. Public operations take an explicit
`TYPE=release` or `TYPE=rc1` through `rc99`:

```sh
make release TYPE=rc1
make publish TYPE=rc1
make verify-release TYPE=rc1

make release TYPE=release
make publish TYPE=release
make verify-release TYPE=release
```

`TYPE` is validated **against the exact name and code in
version.properties before building**. Release candidates are
Pre-releases with `--latest=false`; stable releases are marked Latest.
The stable publication command never silently changes to RC or vice
versa. `make publish` without TYPE is an error, not a stable default.
The release backend validates that both tag and GitHub Release are unused
and that the version/RC order and versionCode increase, comparing the
actual APK from the previous published release. Public signed
candidates are archived separately from disposable local test APKs,
under `~/aap2-releases/public/vX.Y.Z[-rcN]/`.

**Historical exception:** v2.0.x predate the 100-code policy; the
next release checks the real earlier APK versionCode and certificate
rather than deriving them from old tags. For detailed instructions
and metadata paths, see [A1_LOCAL_MAKE.md](A1_LOCAL_MAKE.md).

## Signing and upgrades are invariants

- All publicly installable upstream APKs are signed **locally** with
  AAP2's existing long-term release key. Never upload that private
  key, its password or a plaintext backup to GitHub, GitHub Actions,
  F-Droid or the repository. Never ship CI debug-signed or unsigned APKs.
- Preserve `net.isaeff.android.asproxy2` and signing-certificate
  continuity; use `apksigner verify --print-certs` and compare the
  SHA-256 certificate fingerprint with earlier signed releases.
- Test an **in-place update** over the previous stable release on a
  physical spare Android (`adb install -r` or the regular updater)
  without uninstall, data clearing, Reset or re-enrollment. The
  Android Keystore private key, pinned server host key, enrolled
  public identity and approved reverse-forward port must survive.
- The installed Android app and S2 server have separate state.
  Uninstall/Reset can delete the local Keystore identity; the server
  then requires an explicitly authorized new enrollment and a
  separate revocation of the old account. Never tell a remote
  nontechnical user to uninstall or clear app data as a routine fix.
- A F-Droid-built APK signed by **F-Droid's own key** cannot update an
  already GitHub-signed APK with the same application ID. The
  preferred path is F-Droid **reproducible verification** against the
  separately built, upstream-signed `fdroid` flavor, and publication
  of that upstream-signed APK if accepted. This has **not** yet been
  demonstrated. Do not promise cross-channel in-place upgrades
  until both the published signing certificate and a real upgrade
  have been verified. See [FDROID.md](FDROID.md).
- If the original signing key is lost, **stop and document an explicit
  migration plan**; do not silently rotate to a new key or publish a
  “stable update” that existing phones cannot install.

## Gates before a stable release

1. **Review and provenance:** reviewed source/PRs, release notes
   `docs/RELEASE_NOTES_X.Y.Z.md`, correct canonical version,
   clean tagged commit, no secrets, tested migration/rollback assumptions.
   Include known limitations and relevant issue links.
2. **Automated:** required Android CI (both flavors, unit tests,
   instrumentation including Android 11), server tests, live disposable
   SSH/SFTP/SOCKS5 integration and manifest/permission checks pass.
   Verify the `fdroid` flavor has no in-app installer permission.
   If a workflow is unavailable or fails, investigate and record the
   decision; do not silently count it as passed.
3. **Physical acceptance:** test the **release-signed GitHub candidate**
   on a spare physical phone. Verify clean enrollment (if changed),
   host-key verification, administrator approval, SSH reconnect,
   restricted loopback reverse port, `socks5h` HTTP and correct exit
   country. Verify in-place upgrading from the previous stable,
   preserved enrollment/Android Keystore and any UI/behavior changed
   in this release. Test actual connection recovery after Wi-Fi loss
   or reboot when affected by changes; note which long-duration
   power-management behaviors remain unverified.
4. **Distribution:** verify original signer, package ID,
   monotonic `versionCode`, exact `versionName`, source commit
   correspondence and SHA256. Confirm a genuine stable decision
   before making the release Latest; keep one stable updater target.
5. **After publishing:** verify GitHub download bytes against the
   local archived candidate, check its release status/Latest target,
   release notes, hashes and basic upgrade path. Record actual test
   evidence, not just “works”; preserve the immutable release files.

An urgent security hotfix can use a shortened **relevant** test plan
if the maintainer explicitly approves and the release notes disclose
the exceptions and residual risks. Signing, version monotonicity,
package identity and non-destructive upgrades **never** become optional.

## Public GitHub publication procedure (RC and stable)

The maintainer's local signing environment is authoritative; an agent
with GitHub repository access cannot replace it. Follow
[A1_LOCAL_MAKE.md](A1_LOCAL_MAKE.md) for exact setup, requirements
and safeguards. Current implementation, **not permission to publish**:

```sh
# First commit the desired version.properties and matching
# docs/RELEASE_NOTES_X.Y.Z[-rcN].md, review and merge to master.
git switch master
git pull --ff-only origin master
make

# Example release candidate (versionName=2.1.0-rc1, code=20901)
make release TYPE=rc1
# Test the signed candidate and inspect its source/signing metadata.
make publish TYPE=rc1
make verify-release TYPE=rc1

# Later stable release (versionName=2.1.0, code=21000):
make release TYPE=release
# Complete field acceptance and deliberate stable approval.
make publish TYPE=release
make verify-release TYPE=release
```

These examples represent **two different committed versions** of
`version.properties`, not commands to run sequentially from the
same checkout without changing it. Both flavors share that file.
`make release-both TYPE=...` can prepare a same-commit F-Droid
candidate; GitHub does not depend on its success. The backend requires
clean, synchronized `master` for prepare/publish, signs locally,
compares the previous public certificate, and checks source/version
provenance. `verify-release` compares downloaded assets from the
**exact tag**, including RCs, to the locally archived candidate.
Review the current scripts before running them; they may have
changed since this policy was written.

Do **not** reuse an existing tag, overwrite or silently replace an
existing release APK, downgrade versionCode, or re-sign old assets.
An error in a published binary is fixed with a **new version/tag**.
Correcting an explanatory note is different from replacing a binary,
but changes should remain transparent.

## F-Droid is an independent channel

GitHub release cadence, testing and availability must **never** depend
on an F-Droid build, review, publication, policy change or account.
F-Droid metadata, reproducibility, screenshots, flavor restrictions
and store submission are separate work. It is acceptable for GitHub
`vX.Y.Z` to appear before the corresponding F-Droid release, or
for F-Droid not to carry that version at all. Do not create a
F-Droid-specific `versionName` or change release-signing policy to
satisfy a distributor. Never label `aap2-fdroid.apk` on GitHub as
“published on F-Droid” without an actual verified listing.

## Handoff checklist for future maintainers / AI agents

Before touching the release process, state the currently verified
**latest published stable**, whether a later source version remains
unreleased, and whether an RC and/or F-Droid distribution actually
exist. Inspect the implementation against this policy and note any
gaps. Work on a branch with reviewable changes; merge only after
applicable checks. A model may prepare docs, code and draft release
notes, but must **not imply it signed, published or field-tested an
APK unless it actually did so**. Private signing keys and live phone
credentials never leave the maintainer's own environment.

If policy changes become necessary, update this document, describe
the migration and its compatibility consequences in the PR, and
retain the reasoning rather than silently changing release behavior.
