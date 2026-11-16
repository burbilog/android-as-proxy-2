# Android as Proxy 2 — v2.0.6

Usability fix for **Reset enrollment / Change server** on smaller Android
terminals.

Instead of an Android dialog with a TextField (which opened the system
keyboard, covered the destructive button, and sometimes closed the entire
dialog when Back was pressed), reset confirmation is now a **full-screen
page**. AAP2 provides its own compact, touch-friendly numeric keypad.

- Random four-digit code and entered digits always remain visible alongside
  the keypad and red **ERASE AAP2 ENROLLMENT** button, pinned to the bottom.
- **No system keyboard** is shown; pressing Android Back or Cancel returns
  to AAP2 without resetting anything.
- Incorrect or incomplete digits cannot unlock the destructive button;
  Delete allows editing the typed digits.
- The warning still explains that the current SSH identity is deleted,
  another activation will be needed, and the old server's account must
  separately be revoked with `aap2ctl revoke ID`.

No change to reset behavior, existing keys, Android SSH tunneling, S2,
APK signing or the updater. The release-signed APK can be installed in place
through AAP2's **Update** button.
