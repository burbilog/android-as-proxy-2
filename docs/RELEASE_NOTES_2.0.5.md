# Android as Proxy 2 — v2.0.5

Android-only usability fix for the **Reset enrollment / Change server** dialog.

The previous confirmation placed a long explanation above the four-digit
challenge and entry field, requiring an unexpected scroll on smaller phones.
Now the **random four-digit code and numeric input are the first elements**,
visible immediately without scrolling. The warning is shorter and appears
**under** the input, but still explains that the reset disconnects the
tunnel, deletes the phone's SSH identity, requires fresh activation, and
**does not revoke the old server's account**.

The destructive confirmation remains disabled until the correct four digits
are entered. Cancel leaves everything unchanged. No change to server
enrollment, SSH, signing, APK update or the reset operation itself.

Upgrade in place through the app's **Update** action.
