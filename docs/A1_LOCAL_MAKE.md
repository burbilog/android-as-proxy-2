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

Overrides work per invocation, e.g.:

```zsh
make curl S2=your-ssh-server.example PORT=11011
make install Z=YOUR_LOCAL_ADB_SERIAL
```

Do not publish CI debug-signed APKs as releases. A signed APK built by this
Makefile still requires physical smoke testing and release approval before
being uploaded to GitHub Releases. No `make publish` target intentionally.
