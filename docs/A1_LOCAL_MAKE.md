# AAP2 local Makefile (marduk)

**Release decisions are governed by [RELEASE_POLICY.md](RELEASE_POLICY.md).**
This page documents the currently implemented stable-only local tooling;
`make publish` creates a GitHub Latest release and is **not** a release-candidate publisher.

The root `Makefile` is a small maintainer shortcut for local **release-signed
GitHub-channel** APK builds and physical testing. Both GitHub and F-Droid
use a single checked-in `version.properties` for official versionCode and
versionName. The GitHub shortcut calls existing Gradle and
`scripts/build-signed-apk.sh`, preserving its interactive signing password
and local-only PKCS#12 keystore. It never generates or uploads a signing key.

## One-time local config

Run once on `marduk`; this file is **outside Git** and is not pushed to
the public repository:

```zsh
mkdir -p ~/.config/aap2
cat > ~/.config/aap2/local.mk <<'EOF'
Z = YOUR_LOCAL_ADB_SERIAL
S2 = your-ssh-server.example
PORT = 11011
EOF
chmod 600 ~/.config/aap2/local.mk
```

`Z` is the ADB serial of the **local** Zebra TC21, not the Japanese device.
`S2` is the SSH destination already in `~/.ssh/config`; `PORT`
is its approved *loopback* reverse SOCKS5 port. Change them locally
if you switch test servers or devices; never place these overrides in Git.
The Makefile only operates on AAP2 (`net.isaeff.android.asproxy2`), never AAP1.

## Day-to-day

From `~/aap2/a1-build`:

```zsh
make build
make install
make curl
make status
```

- `make build`: first `git pull --ff-only origin <current-branch>`,
  then build and sign with the existing local PKCS#12 (interactive password),
  and archive as `~/aap2-releases/aap2-VERSION_CODE.apk`.
  It chooses **one higher than the highest numbered archived APK**
  (initial floor `20010` → first `20011`), so failed builds do not
  consume numbers. An existing numbered APK is never overwritten.
  If the latest installed APK is not in that directory, set a higher
  number explicitly, e.g. `make build VERSION_CODE=20020`.
  Optionally set `VERSION_NAME=2.0.0-rc1-local`.
- `make install`: picks the newest archived signed APK and uses
  `adb -s "$Z" install -r`. Refuses an unconfigured/unreachable device
  or a device without already installed AAP2. No uninstall, `pm clear`,
  re-enrollment, or touching the Android Keystore. Android's
  `MY_PACKAGE_REPLACED` receiver restores an intentionally running
  SOCKS5 tunnel.
- `make curl`: `ssh` into the configured S2, then `curl` Cloudflare
  over `socks5h://127.0.0.1:PORT`. Cloudflare `ip=` should be the
  *phone's* network egress, not the S2 IP.
- `make status`: show the loopback SOCKS5 listener or `DISCONNECTED`.
- `make check`: verify the newest archived APK signature, display
  Android package/version and SHA256.
- `make test`: run `:app:testDebugUnitTest` locally.
- `make sync`: fast-forward the current Git branch (no forced resets).
- `make help`: compact command reference.
- `make release`/`publish`/`verify-release`: prepare and publish a stable GitHub release with explicit safeguards (see below).

Overrides work per invocation, e.g.:

```zsh
make curl S2=your-ssh-server.example PORT=11011
make install Z=YOUR_LOCAL_ADB_SERIAL
```

## Stable public releases (single shared version)

The current GitHub and F-Droid source variants use the **same**
`versionCode`/`versionName` from `version.properties`. Increase these
exactly once for a new public version. Both variants keep the existing
Android application ID. A local throwaway `make build` uses a higher
version code and a `-local-` version name; this is **not** suitable for
F-Droid or a stable GitHub release.

To make the GitHub APK **without depending on F-Droid**, signed locally
with the long-term certificate, for the version in the file:

```zsh
git switch master
make release
```

This target first checks that local `master` matches `origin/master`,
then reads `version.properties`, builds the GitHub variant with
exactly those values, compares signer continuity against the previous
archived signed APK and records the source commit. It refuses a public
versionCode that does not exceed the most recent local archived code.
No `VERSION_CODE` command-line override can change the official version.

Review and test the candidate, then publish via:

```zsh
make publish
make verify-release
```

`publish` also requires the source version and matching
`docs/RELEASE_NOTES_<versionName>.md`; it checks APK identity,
versionCode/versionName, signing certificate and source commit, then
uploads `aap2.apk` and `SHA256SUMS` to GitHub. If a matching
locally signed `aap2-fdroid.apk` candidate has also been prepared
from the **same commit**, it verifies both candidates' certificates
and includes that second asset. F-Droid failures do not block GitHub. The signer remains
local. If an APK is not ready, this workflow **never** uploads one.

The other channel builds independently without the maintainer's signing
secret:

```zsh
./gradlew --no-daemon :app:assembleFdroidRelease
```

This command gives an unsigned APK for the F-Droid build system.
`make fdroid-candidate` separately produces an **upstream-signed**
F-Droid flavor candidate using the local developer key, for future
reproducible verification. For convenience, `make release-both` performs
both sequentially; if F-Droid preparation fails, the GitHub candidate
remains available for `make publish`. F-Droid never receives that key. The source/versions remain
the same, but the F-Droid variant has no GitHub APK updater or
install-packages permission. See [F-Droid distribution](FDROID.md) for the signing
compatibility warning: a default F-Droid-signed APK cannot update a
GitHub-signed installation with the same application ID unless the
upstream signature is verified and adopted reproducibly.

The repo never auto-creates a public release just by running Gradle, and
never publishes debug-signed Android CI artifacts.
