# AAP2 local Makefile (marduk)

**Release decisions are governed by [RELEASE_POLICY.md](RELEASE_POLICY.md).**
This page documents **both** GitHub release-candidate and stable publication
through the same guarded `TYPE` interface. Use `make` with no arguments
for a local, side-effect-free quick reference.

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
- `make test`: run release-tool Python guardrail tests and both Android
  flavor unit-test suites locally.
- `make sync`: fast-forward the current Git branch (no forced resets).
- `make help`: compact command reference.
- `make release TYPE=...` / `publish TYPE=...` /
  `verify-release TYPE=...`: one guarded pipeline for RC and stable
  GitHub releases (see below).

Overrides work per invocation, e.g.:

```zsh
make curl S2=your-ssh-server.example PORT=11011
make install Z=YOUR_LOCAL_ADB_SERIAL
```

## Public releases: shared GitHub + F-Droid version

The **sole checked-in source of public version metadata** is
[version.properties](../version.properties). A public Git tag must
contain the exact versionCode and versionName built into the APK;
GitHub and F-Droid flavors share them and the same application ID.
The tag is created only by publishing, after the reviewed version
change and corresponding release notes are merged to master.

**Before starting after a long gap, run make with no arguments.**
It displays local test, RC and stable commands; no download, build,
signing or network operation occurs. Then inspect live GitHub Releases,
the checked-in version, and the [release policy](RELEASE_POLICY.md).

### Reserved blocks of 100 versionCodes

Each new stable version receives the next multiple of 100, with
the 99 preceding values reserved for that version's RC1..RC99.
This is a **linear sequence of release cycles**, NOT a mathematical
encoding of major/minor/patch into versionCode. It ensures increasing
codes across RC and stable APKs even as semantic versions vary.

| Version | versionCode | TYPE |
| --- | ---: | --- |
| 2.1.0-rc1 | 20901 | rc1 |
| 2.1.0-rc2 | 20902 | rc2 |
| 2.1.0-rc99 | 20999 | rc99 |
| 2.1.0 | 21000 | release |
| 2.1.1-rc1 | 21001 | rc1 |
| 2.1.1 | 21100 | release |
| 2.2.0-rc1 | 21101 | rc1 |
| 2.2.0 | 21200 | release |

These values assume the existing 2.1.0 stable reservation at 21000.
Do not assign a new block until checking all previous **published**
release tags and actual versionCodes. The release script does both
before building or publishing. An RC with a later version number must
have a higher code than **all previous public APKs**, even if that RC
was never installed. No RC may be published after a newer RC or the
stable version for that cycle.

\`TYPE\` must be **exactly** \`release\` or \`rc1\` through \`rc99\`,
without leading zeroes. Missing TYPE and invalid values fail before
running GitHub operations or invoking a signing tool. The checked-in
versionName must be exactly \`X.Y.Z\` for release, or \`X.Y.Z-rcN\`
for a matching RC. versionCode must end with \`00\` for release or
with \`NN\` matching the RC number. No implicit version bump.

### Example: prepare and publish v2.1.0-rc1

On a reviewed branch, change version.properties to:

\`\`\`properties
versionCode=20901
versionName=2.1.0-rc1
\`\`\`

Prepare \`docs/RELEASE_NOTES_2.1.0-rc1.md\` describing what needs
testing, limitations and incompatibilities. Merge those files and
the implementation into master; wait for applicable CI to pass.
On the maintainer workstation with the established, unchanged
long-term signing key:

\`\`\`zsh
git switch master
git pull --ff-only origin master
make

make release TYPE=rc1
# Inspect the locally signed APK and test a non-production Android.
make publish TYPE=rc1
make verify-release TYPE=rc1
\`\`\`

The **RC** GitHub Release is marked Pre-release and explicitly
\`--latest=false\`. Ordinary AAP2 in-app Update remains pointed at
the previous stable Latest. RC installation requires an explicit
tester action, never automatic promotion.

For RC2, commit \`2.1.0-rc2\` / \`20902\` and fresh matching release
notes before building \`TYPE=rc2\`. Do not reuse an RC tag or overwrite
its APK. An RC's versionCode must be **lower** than the final stable
code so Android can upgrade it in place.

### Promote to stable v2.1.0

After physical acceptance of the release-signed candidate, change
version.properties to the **reserved stable version**:

\`\`\`properties
versionCode=21000
versionName=2.1.0
\`\`\`

Prepare and commit \`docs/RELEASE_NOTES_2.1.0.md\`, run required CI,
and merge to master. Then:

\`\`\`zsh
git switch master
git pull --ff-only origin master
make release TYPE=release
# Verify installation over previous stable/RC on a spare physical Android.
make publish TYPE=release
make verify-release TYPE=release
\`\`\`

The **stable** release is published as normal GitHub Release with
\`--latest\`, so it becomes available to AAP2's manual Update button.
This is a deliberate release decision: \`make publish TYPE=release\`
must never be run merely to obtain a testing URL.

### Optional F-Droid candidate

Independent of the GitHub distribution:

\`\`\`zsh
make fdroid-candidate TYPE=release
# Or prepare both sequentially:
make release-both TYPE=release
\`\`\`

The same source commit and version.properties are used for both
flavors; both are signed with the original local developer key.
The F-Droid flavor contains no in-app installer permission or
GitHub updater. It can be built without a GitHub APK, but publication
on GitHub still requires its own signed GitHub flavor. An optional
\`aap2-fdroid.apk\` is attached to the GitHub Release only if it is
ready before publication, passes verification, and has the same
source commit/version/signing certificate. F-Droid store acceptance
and reproducibility verification are **separate**, never required
to publish GitHub. See [FDROID.md](FDROID.md).

### Where files go, and the safety checks

Local disposable \`make build\` archives stay under
\`~/aap2-releases/aap2-VERSION_CODE.apk\`. Public candidates are
stored **separately**, without overwriting files:

\`\`\`text
~/aap2-releases/public/v2.1.0-rc1/
  aap2.apk
  aap2-fdroid.apk        (optional)
  release.json           (tag, type, exact code/name, source SHA, hashes, signer)
\`\`\`

The public release backend is \`scripts/aap2-release.py\`.
All public actions require a clean, synchronized master branch
(except verification). Before signing and before publishing it
checks that no GitHub Release or remote Git tag already uses the
requested name, that the proposed semver/RC sequence is strictly
later than all existing published AAP2 versions, and that its
versionCode is above the **actual previous public signed APK**.
It checks the previous public signing certificate rather than
mistaking the highest **local disposable** build for the previous
public release.

After building, it checks the APK package, exact versionCode/name,
non-debuggability, signer continuity and SHA256. It stores the
exact source commit and does not overwrite an archived candidate.
At publication it rechecks the absence of the tag and source
provenance, then uploads \`aap2.apk\`, optional \`aap2-fdroid.apk\`,
and \`SHA256SUMS\` with the correct GitHub status. Verification
downloads assets by **exact tag**, not \`releases/latest\`, and
compares them to the local archive. All GitHub operations require
working network access and the \`gh\` CLI (publishing requires
authenticated access).

\`make publish\` (without TYPE) **always fails**. It never
defaults to a stable release. \`make\` / \`make help\` remain
read-only. No helper edits tags or pushes files before the
explicit publish action.

GitHub's create-release API is not atomic: if publication stops
after creating a tag or draft but before finishing assets,
the script will refuse a repeated tag. Investigate manually;
**do not delete/reuse tags or silently replace assets** to
hide a partial publication. Fixes to an already published binary
require a new version and tag.

### Extra care for remote enrolled phones

Use a spare physical Android for acceptance. Never uninstall
AAP2, clear app data, force a downgrade or change signing
certificates on a working remotely enrolled phone just to
install an RC. Its Android Keystore SSH identity and host-key
pin are persistent state. See [P0_RELEASE_SIGNING.md](P0_RELEASE_SIGNING.md).

The repo never auto-creates a public release merely by running
Gradle, \`make\`, or a debug CI workflow, and never publishes
debug-signed artifacts.
