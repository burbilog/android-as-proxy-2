# AAP2 documentation index

The [root README](../README.md) is the short introduction and user setup path.
These docs are grouped by audience. Milestone names (P0/A1/S0/S1/S2)
are historical phase identifiers, **not** current application modes.

## Phone user and remote assistant

- [README: activation and SOCKS5 use](../README.md#2-enroll-the-phone)
- [Access model and nontechnical phone user](ACCESS_MODEL.md)
- [Server-side step-by-step installation](../server/INSTALL.md)

A person holding the remote phone should only need the activation code,
an independently verified fingerprint and short administrator guidance.
Do not ask them to enter SSH keys, clear Android app data or debug logs.

## Server administrator

- [Server installation and OpenSSH Match policy](../server/INSTALL.md)
- [Server operator reference](../server/README.md)
- [S2 approvals, revocation and port reservation](../server/S2_APPROVAL.md)
- [Access and LAN/VNC trust boundary](ACCESS_MODEL.md)

## Maintainer / future coding agent

- [AGENTS.md (entry point)](../AGENTS.md)
- [Release policy](RELEASE_POLICY.md)
- [Local Makefile and signing workflow](A1_LOCAL_MAKE.md)
- [Original APK signing key continuity](P0_RELEASE_SIGNING.md)
- [F-Droid build and signing separation](FDROID.md)

## Historical design and test milestones

Earlier P0/A1/S0/S1/S2 files remain in their original locations to
preserve source links and test traceability. They describe decisions
and validation at a point in time, **not** an instruction to bypass
current release gates or to reset deployed phones. Use the links above
for current operations and the live GitHub Releases page for current
published versions.
