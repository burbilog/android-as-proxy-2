# AAP 2 – networking milestone (development build)

This document describes the current **test-only** development state. Do **not** deploy this build to the unattended phone in Japan.

## Changes from AAP 1

- Android package ID: `net.isaeff.android.asproxy2`. It installs beside v1 without overwriting it.
- Local SOCKS5 listener: `127.0.0.1:11080` (v1 uses 1080). SOCKS5 CONNECT only; no UDP/BIND/SOCKS4.
- SSH library: `com.github.mwiede:jsch:2.28.8`.
- Remote SSH port forwarding: **only** `127.0.0.1:<remote_port> -> 127.0.0.1:11080`.
- No SSH exec channels, remote `kill`, `fuser`, `pkill`, or fallback bind to all interfaces.
- One long-running SSH reconnect loop inside the Android foreground service. No retry alarms, broadcasts or Intent extras with the SSH password.
- Bounded in-memory log (1000 entries); removed plaintext SMTP report sender.
- Android backup disabled for this application.

## Remaining limitations – before any remote deployment

**Security:**
- The first SSH connection still uses trust-on-first-use *without interactive verification* when no key has been pinned. Subsequent sessions pin the first key. The config import wizard must require an independently verified SSH known_hosts key or fingerprint before production deployment.
- The SSH password is still stored in plain application-private SharedPreferences. Next stage: Android Keystore-backed secrets and SSH private-key authentication.
- This is a debug build, not a release signed with your permanent APK signing key.

**Product:**
- The HTTPS URL configuration import wizard has not been implemented.
- The user must configure the v2 SSH credentials manually for local testing.
- Remote SSH port must **differ** from the v1 remote port if both are running against the same SSH server.
- No test on a physical Zebra has been completed yet. The automated tests only cover JVM-local SOCKS protocol and transfer behaviour.

## Local Zebra trial

1. Download `aap2-debug-apk` from the successful GitHub Actions run on the `develop` PR.
2. Install on the **local** test Zebra only. The APK package ID differs from v1.
3. Configure a *dedicated* remote port, e.g. 10801 (assuming v1 is not using it).
4. Start AAP2 and verify the SSH server listens on **loopback only**. On SSH server:

   ```sh
   ss -ltnp | grep 10801
   ```

   Expected local address: `127.0.0.1:10801`. Never `0.0.0.0` or `[::]`.
5. On that SSH server test through proxy:

   ```sh
   curl --proxy socks5h://127.0.0.1:10801 https://ifconfig.me
   curl --proxy socks5h://127.0.0.1:10801 -o /dev/null -w '%{speed_download} B/s\n' 'https://speed.cloudflare.com/__down?bytes=30000000'
   ```

6. Repeat with four simultaneous curl processes and compare total throughput with AAP1.
7. Disable Wi-Fi for 30 seconds and re-enable it; confirm automatic reconnect with the same remote port.
8. Restart the SSH server and confirm automatic reconnect **without any kill on the server**.
9. Restart the Zebra and confirm boot-autoconnect behaviour if enabled.

No absolute throughput can be guaranteed by CI: it depends on the device, Wi-Fi, SSH CPU and international network path. The test asserts correctness and large binary transfer, not Japanese upstream speed.

## CI

GitHub Actions runs `./gradlew :app:assembleDebug :app:testDebugUnitTest` with API 35 and uploads `aap2-debug-apk`. PR builds supersede older runs to conserve minutes.

## APK signing note

GitHub-hosted Actions runners can use a *different debug signing key on different runs*. For a future in-place update path, configure a persistent release signing key under the repository owner's control, not inside Git.

