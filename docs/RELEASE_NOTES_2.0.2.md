# Android as Proxy 2 — v2.0.2

Adds an explicit, strongly confirmed **Reset enrollment / Change server**
action to the Android app.

- The main screen shows a deliberately secondary red reset action; its
  confirmation uses a prominent warning, displays the current server and
  Connection ID, and requires the user to type uppercase **RESET** exactly
  before the destructive button becomes available. Cancel does nothing.
- A confirmed reset shuts down the active SSH reverse tunnel and SOCKS5
  listener, disables Direct Boot/update auto-reconnect, clears pending
  enrollment, deletes the AAP2-only private key from Android Keystore,
  removes the pinned host profile and returns to **Activate AAP2**.
- The phone can then enroll against a different SSH server with a fresh
  18-digit activation code, verified SSH host key and administrator approval.
- Reset is local-only: **the old server administrator must separately use
  `aap2ctl revoke OLD_ID`** to revoke the old SSH public key. The old ID
  is shown in the confirmation. Other Android apps and the original AAP1
  app are not changed.
- Includes Android UI/end-to-end regression tests for cancel, typed
  confirmation, removal of the Keystore alias, disabled auto-restart and
  a new independent S2 enrollment.

As usual, install the release-signed `aap2.apk` in place. Do not uninstall
or clear application data merely to update the AAP2 app. See the project
README and server installation guide for activation and operational security.
