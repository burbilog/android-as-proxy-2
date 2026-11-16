# F-Droid preparation and independent distribution channels

**Status: preparation only.** As of this change, GitHub v2.0.6 is the most
recent public stable APK; no F-Droid inclusion or reproducibility verification
is claimed. Never replace a proven GitHub installation with an unverified APK.

## Two products from one source

Both variants share AAP2's exact SSH, enrollment, SOCKS5 and Keystore code,
application ID `net.isaeff.android.asproxy2` and the version declared in
[version.properties](../version.properties). A published version should be
tagged `v<versionName>` and built from that commit. Update
`version.properties` only once per *public version*; never carry a separate
F-Droid version number or a second code branch.

- **GitHub** (`github`): includes the explicitly user-initiated
  `Update` button, HTTPS APK downloader, `REQUEST_INSTALL_PACKAGES` and
  a private FileProvider for the cached APK. It is built and signed on the
  maintainer's own machine, with the original long-term private key, and
  published independently to GitHub Releases as `aap2.apk`.
- **F-Droid** (`fdroid`): has **no** GitHub APK updater implementation
  (only a non-networking compile-time stub), no `Update` button,
  `REQUEST_INSTALL_PACKAGES` permission or update FileProvider. F-Droid
  provides its own app update workflow, independent of GitHub availability.
  Neither CI nor the F-Droid build receives the maintainer's private key.

Build examples with JDK 17 / Android SDK 35:

```sh
./gradlew --no-daemon :app:assembleGithubDebug :app:testGithubDebugUnitTest
./gradlew --no-daemon :app:assembleGithubRelease
./gradlew --no-daemon :app:assembleFdroidRelease :app:testFdroidDebugUnitTest
```

The GitHub release signer uses `scripts/build-signed-apk.sh` and builds
`:app:assembleGithubRelease`, aligns and signs the unsigned APK with
the existing PKCS12. Do not use the debug key or publish unsigned APKs.
The official release flow is still `make release`, `make publish`,
`make verify-release`, now reading the same source version.

Local throwaway testing may override `AAP2_VERSION_CODE` and
`AAP2_VERSION_NAME`; **official releases must not**. The Gradle defaults
read the version file directly so an independent F-Droid builder does
not need the maintainer's environment variables. The Fastlane
changelog filename is the integer `versionCode` (for example
`fastlane/metadata/android/en-US/changelogs/21000.txt`).

## Signing and migration: important

The two variant APKs have the **same application ID**. Android requires
identical compatible signing certificates for an in-place update. Standard
F-Droid-signed builds therefore **cannot replace an existing GitHub-signed
AAP2** (and vice versa) merely because their versions match. Never try
uninstall/reinstall on a remote enrolled phone: that removes its local
Keystore identity and requires new S2 enrollment and separate revocation
of the old server-side account.

Preferred long-term goal: F-Droid reproducible-build verification of the
GitHub release APK with the maintainer's original signature, so both
channels can publish update-compatible packages when verification succeeds.
This is **not yet proven**: Gradle, ZIP alignment, aapt/apksigner, build-tools
versions and all inputs must be fixed and compared. F-Droid may instead
sign itself; in that case switching channels necessarily requires
re-enrollment. Do not claim interoperability before comparison of actual
certificate SHA256 fingerprints and a real in-place update test.

The `fdroid` variant can be built and maintained separately even if
F-Droid rejects, delays or removes an application. GitHub releases are
not gated on F-Droid availability.

## Repository listing metadata

Text and the existing 512px project launcher artwork are in
`fastlane/metadata/android/en-US/`, with `changelogs/<versionCode>.txt`.
**Authentic screenshots from the actual app UI are still needed**, under
`images/phoneScreenshots/`; do not use speculative/mock screens showing
secrets or credentials. Prefer sanitized screens: unconfigured Home,
fingerprint verification with a *test-only* key, About and Details with
test accounts. Never publish a live activation code or account endpoint.

Before submitting to F-Droid:

1. Produce a tagged stable GitHub version and confirm the long-term
   signing certificate and in-place update from v2.0.6.
2. Verify `assembleFdroidRelease` contains no APK updater permission,
   update FileProvider or executable downloader; inspect manifest and APK.
3. Create an F-Droid build recipe using the `fdroid` flavor, pinned
   dependencies and the static version from `version.properties`.
   Keep the recipe separate from the GitHub signing/release workflow.
4. Test a reproducible binary match against the signed GitHub APK if
   upstream developer signing is desired; otherwise communicate signer
   incompatibility clearly to users.
5. Add real screenshots and submit through F-Droid's standard
   review process. The 3-month development guidance has exceptions for
   apps with proven users; AAP1's >1-year history can support a
   lineage statement, but does not automatically make AAP2 eligible.

The archived AAP1 repository can remain archived. Its creation date
and commit history are preserved.

Official references:
- https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Inclusion_Policy/
- https://fdroid.gitlab.io/jekyll-fdroid/docs/Reproducible_Builds/
- https://fdroid.gitlab.io/jekyll-fdroid/docs/Submitting_to_F-Droid_Quick_Start_Guide/
