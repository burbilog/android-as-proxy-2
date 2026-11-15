# Android as Proxy 2 — v2.0.1

Maintenance release fixing the **Update** button on Android 11 and other Android builds that do not return signing metadata from `PackageManager.getPackageArchiveInfo()` for an APK not yet installed.

## Fixed

- An otherwise valid, correctly release-signed newer APK could be rejected by AAP2 with `APK signing certificate differs from installed AAP2`. This was a **false positive** when Android returned `signingInfo = null` for the downloaded archive.
- AAP2 now checks the downloaded archive's **package name** and **strictly increasing versionCode**, then hands it to the normal **Android system installer**. Android verifies the actual APK signature and refuses to replace an installed package signed with an incompatible key.
- Added a real Android 11 instrumentation regression for a newer archive with no `signingInfo`, plus wrong-package rejection.

## Installation and upgrade

Download the release-signed `aap2.apk` from this GitHub Release. The installer requires explicit approval and may ask for permission to allow installation from AAP2.

**Important for v2.0.0 / versionCode 20012 and older:** their own Update button may exhibit the false-positive failure. Such existing installations need **one manual in-place APK update** (open the downloaded release APK in Android's system installer, or use `adb install -r` on a locally connected device). **Do not uninstall the app or clear its data**: that would discard enrollment information. Once v2.0.1 or newer is installed, future in-app updates use the corrected path.

Server-side `aap2ctl`, approved keys, SSH tunnel protocol and S2 deployment are unchanged. Keep the original AAP1 application untouched. Extended OEM-specific unattended background reliability remains a separate acceptance area.
