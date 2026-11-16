# Android as Proxy 2 — v2.0.4

Small Android-only usability improvement to **Reset enrollment / Change server**.

The destructive confirmation no longer requires typing the English word
`RESET`. Instead, the red warning dialog displays a **new random four-digit
confirmation code** whenever it opens. The user enters those four digits
using the phone's numeric keyboard. **ERASE AAP2 ENROLLMENT** stays disabled
until the entered digits exactly match the displayed code; incomplete or
incorrect codes do nothing. Cancel closes the dialog without changes.

The reset action remains in its separated bottom footer, away from Connect
and Update. Actual reset behavior, Keystore private-key deletion, SSH/SOCKS
shutdown, fresh enrollment and the need for **server-side**
`aap2ctl revoke OLD_ID` are unchanged.

A fresh code is shown only to the person holding the phone; it is a safety
confirmation, not an SSH authentication credential.

Install the release-signed `aap2.apk` using the app's **Update** control.
An in-place APK update preserves the existing enrollment and SSH identity.
