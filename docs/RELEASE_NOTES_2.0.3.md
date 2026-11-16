# Android as Proxy 2 — v2.0.3

Small Android user-interface update. The enrollment protocol, SSH server,
configuration storage and signing key are unchanged.

- **Reset enrollment / Change server** is now in its own separated footer,
  pinned to the **bottom** of the main screen rather than next to the
  everyday Connect, Details and Update controls. The intentionally strict
  typed `RESET` confirmation is unchanged.
- After Reset followed by successful activation against a server, AAP2
  now clears the obsolete **“Local enrollment erased”** message. It also
  clears other old transient status only when an unconfigured phone becomes
  configured; unrelated resumes do not dismiss Update errors.
- UI instrumentation checks that the Reset link is visible below Update
  and continues to require typed confirmation before deleting the local
  SSH identity.

Install the release-signed `aap2.apk` through **Update** or Android's
normal package installer. The existing enrollment and Android Keystore
key are preserved by an in-place update; do not uninstall or clear app data.
