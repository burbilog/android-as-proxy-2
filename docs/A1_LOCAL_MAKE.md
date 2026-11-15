# AAP2 local Makefile (marduk)

The root `Makefile` is a small maintainer shortcut for local **release-signed**
APK builds and physical testing. It calls existing Gradle and
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

## Stable public releases

A stable release is deliberately a two-step operation; an ordinary `make
build` **never** publishes anything. First switch to `master` (once) and
prepare the signed APK with a numeric version (requires the same interactive
private keystore password as `make build`):

```zsh
git switch master
make release VERSION=2.0.0
```

The `release` target fast-forwards the current branch, insists on a clean
`master` matching `origin/master`, builds with the next archived
`versionCode` and exact `versionName=2.0.0`, then uses
`scripts/aap2-spare-upgrade.sh check` to compare the old and new **signed**
APKs (package, increasing version and signing certificate). It also writes
`aap2-VERSION_CODE.commit` next to the archive so the eventual publication
is pinned to the exact source commit. To specify a version code instead of
automatic increment:

```zsh
make release VERSION=2.0.0 VERSION_CODE=20012
```

Inspect and test the candidate as appropriate. **Do not** `make install` on
the locally enrolled Zebra if the aim is to test its in-app Update button
against the older installed release.

Publish only after acceptance, using a locally authenticated GitHub CLI
(`gh auth status`) and a committed release-notes document named
`docs/RELEASE_NOTES_2.0.0.md`:

```zsh
make publish VERSION=2.0.0
make verify-release
```

`publish` re-verifies that the APK's package name, exact Android
`versionCode`/`versionName`, signature, and recorded source commit match
the current clean/up-to-date `master`. It refuses an existing release tag,
then uploads the APK under the **fixed asset name `aap2.apk`** and a
`SHA256SUMS` to the stable GitHub Release `v2.0.0`. No signing material
is uploaded; temporary staging files are deleted afterward. No Android
installation or SSH server mutation is triggered.

`verify-release` downloads
`https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk`
and compares every byte with the archived signed APK. The next stable
version, e.g. `2.0.1`, repeats the same sequence with its own release notes.
If the latest numbered archive is not the candidate, pass `CODE=20012`
to `make publish` and `make verify-release`.

The repo never auto-creates a public release just by running Gradle, and
never publishes debug-signed Android CI artifacts.
