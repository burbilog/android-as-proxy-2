# Android as Proxy 2 — v2.1.0-rc2 (release candidate)

**Status:** source candidate pending CI and spare-device acceptance;
not a stable release or claim that a signed APK has been published.
The RC must be a GitHub **Pre-release**, never Latest. See
[RC1 notes](RELEASE_NOTES_2.1.0-rc1.md) for the complete 2.1 changes.

## Changes since RC1 (c1a644f)

- F-01: JSch 2.28.8 can discard an I/O exception during userauth and
  report an ambiguous authentication failure. One normal network-backoff
  retry is allowed before throttling the second consecutive auth failure
  hourly. Other failures or a healthy forward break the streak. A wrong
  host key and local/Keystore failures still throttle immediately;
  neither the approved pin nor identity is replaced.
- F-02: STOP/RESET and manager destruction discard late connection
  state/problem callbacks. Cancel the old manager before clearing
  persistent issues.
- F-06: contain nonfatal exceptions in SOCKS client workers, including
  a response-pump submission racing executor shutdown; release sockets,
  connection counters and semaphore slots. Existing access policy,
  limits and idle/handshake timeouts are unchanged.
- F-10: public prepare/publish reject untracked build inputs (including
  ignored files under app/src); verification resolves the actual remote
  tag, including annotated tags, against archived sourceCommit before
  comparing published APK bytes and checksums.
- Regression coverage: disposable Android 11 / real OpenSSH TCP loss
  during signed userauth, repeat auth failure throttling, late failure
  on manager cancellation, rejected SOCKS response pump, active-close,
  incomplete handshake, IPv6 literal, backup-exclusion checks and
  release guardrails. CI results must be checked on the final commit.

## Acceptance (not yet completed by this source change)

1. Green Android GitHub/F-Droid builds and unit tests; disposable
   real OpenSSH/Android 11 E2E and relevant server CI.
2. Prepare the same-signer local APK on the maintainer's workstation
   from the reviewed, synchronized master using `make release TYPE=rc2`.
   Verify package `net.isaeff.android.asproxy2`, versionName
   `2.1.0-rc2`, versionCode `20902`, certificate, SHA256 and source commit.
3. In-place upgrade spare Zebra TC21 (Android 11) and SEUIC (Android 14)
   from RC1. Confirm same enrollment and Keystore identity, real
   SOCKS5 HTTPS, Wi-Fi interruption/recovery and reboot recovery.
   On SEUIC explicitly test crash/recovery with
   `adb -s SERIAL shell am crash net.isaeff.android.asproxy2` while
   connected (not force-stop). Document actual recovery behavior (F-03).
4. Publish only with a separate maintainer decision and
   `make publish TYPE=rc2`; verify using `make verify-release TYPE=rc2`.

Do not update the remote Japanese phone or relay for this test.
Do not reset, uninstall or clear existing enrollments. Stable v2.1.0
remains a separate release decision with versionCode 21000. Server issue
#38 and other deferred findings are unchanged.
