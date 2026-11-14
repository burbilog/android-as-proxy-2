#!/bin/sh
# Release-signing acceptance on a SPARE physical Android only.
# Does not sign APKs, enroll, touch server accounts, uninstall, or clear data.
set -eu

usage() {
    echo 'Usage: sh scripts/aap2-spare-upgrade.sh {check|first|upgrade} OLD.apk NEW.apk' >&2
    echo 'first/upgrade require AAP2_SPARE_SERIAL (explicit ADB device serial).' >&2
    exit 2
}
fail() { echo "AAP2 spare upgrade: $*" >&2; exit 1; }
[ "$#" -eq 3 ] || usage
ACTION=$1
OLD=$2
NEW=$3
case "$ACTION" in check|first|upgrade) ;; *) usage ;; esac

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
TOOLS=${AAP2_BUILD_TOOLS:-"$SDK/build-tools/35.0.0"}
AAPT="$TOOLS/aapt"
SIGNER="$TOOLS/apksigner"
PACKAGE=net.isaeff.android.asproxy2
[ -x "$AAPT" ] && [ -x "$SIGNER" ] ||
    fail "Missing aapt/apksigner in $TOOLS"
[ -s "$OLD" ] && [ -s "$NEW" ] || fail 'Both APK paths must exist and be nonempty'

# A signed APK, correct package, matching certificate and increasing version
# are mandatory even for the dry-run check.
inspect() {
    apk=$1
    "$SIGNER" verify --verbose --print-certs "$apk" >/dev/null ||
        fail "APK signature verification failed: $apk"
    meta=$("$AAPT" dump badging "$apk") || fail "Cannot read APK manifest: $apk"
    pkg=$(printf '%s\n' "$meta" |
        sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" | head -n 1)
    code=$(printf '%s\n' "$meta" |
        sed -n "s/^package: .*versionCode='\\([0-9][0-9]*\\)'.*/\\1/p" | head -n 1)
    [ "$pkg" = "$PACKAGE" ] || fail "Wrong Android applicationId in $apk: $pkg"
    case "$code" in ''|*[!0-9]*) fail "Invalid versionCode in $apk" ;; esac
    if printf '%s\n' "$meta" | grep -q '^application-debuggable'; then
        fail "Debuggable APK is not a release acceptance candidate: $apk"
    fi
    printf '%s\n' "$code"
}

OLD_CODE=$(inspect "$OLD")
NEW_CODE=$(inspect "$NEW")
[ "$NEW_CODE" -gt "$OLD_CODE" ] ||
    fail "Upgrade must increase versionCode ($OLD_CODE -> $NEW_CODE)"
cert() {
    "$SIGNER" verify --print-certs "$1" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}
OLD_CERT=$(cert "$OLD")
NEW_CERT=$(cert "$NEW")
[ -n "$OLD_CERT" ] && [ "$OLD_CERT" = "$NEW_CERT" ] ||
    fail 'Signing certificates differ; Android cannot upgrade in place'
printf 'Package: %s\nVersion: %s -> %s\nSigner SHA256: %s\n' \
    "$PACKAGE" "$OLD_CODE" "$NEW_CODE" "$OLD_CERT"
[ "$ACTION" != check ] || exit 0

command -v adb >/dev/null 2>&1 || fail 'adb is not installed'
SERIAL=${AAP2_SPARE_SERIAL:-}
case "$SERIAL" in ''|*[!A-Za-z0-9._:-]*) fail 'Set a single explicit AAP2_SPARE_SERIAL' ;; esac
adb_device() { adb -s "$SERIAL" "$@"; }
[ "$(adb_device get-state 2>/dev/null | tr -d '\r')" = device ] ||
    fail "Device $SERIAL is not connected and authorized"
MODEL=$(adb_device shell getprop ro.product.model | tr -d '\r')
[ -n "$MODEL" ] || fail "Cannot identify $SERIAL"
printf 'ADB serial: %s\nModel: %s\n' "$SERIAL" "$MODEL"

# Neither action ever uninstalls AAP2, touches its data or touches AAP1.
# The FIRST action is intentionally blocked for already installed AAP2.
installed=$(adb_device shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' || :)
case "$installed" in
    package:*) ;;
    '') ;;
    *) fail "Unexpected pm path response for $PACKAGE: $installed" ;;
esac
case "$ACTION" in
    first)
        [ -z "$installed" ] ||
            fail 'AAP2 is already installed; refusing to replace or reset it'
        echo 'This must be a SPARE phone. AAP1 and existing devices are untouched.'
        ;;
    upgrade)
        [ -n "$installed" ] ||
            fail 'AAP2 is missing. Install the first APK and enroll BEFORE upgrading'
        installed_code=$(adb_device shell dumpsys package "$PACKAGE" |
            sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
        [ "$installed_code" = "$OLD_CODE" ] ||
            fail "Installed versionCode is $installed_code; expected $OLD_CODE"
        echo 'Check that A1 enrollment and SOCKS5 worked BEFORE this update.'
        ;;
esac
printf 'Type SPARE %s to %s AAP2 on this device: ' "$SERIAL" "$ACTION"
IFS= read -r answer || fail 'Confirmation not received'
[ "$answer" = "SPARE $SERIAL" ] || fail 'Cancelled'

case "$ACTION" in
    first) adb_device install "$OLD" || fail 'Initial installation failed' ;;
    upgrade) adb_device install -r "$NEW" || fail 'In-place update failed (nothing uninstalled)' ;;
esac

expected=$OLD_CODE
[ "$ACTION" = upgrade ] && expected=$NEW_CODE
actual=$(adb_device shell dumpsys package "$PACKAGE" |
    sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
[ "$actual" = "$expected" ] ||
    fail "Installed versionCode $actual differs from expected $expected"
echo "Installation verified: $PACKAGE versionCode=$actual"
if [ "$ACTION" = first ]; then
    echo 'NEXT: perform real A1 UI enrollment on disposable S2, approve, record client public-key fingerprint and test SOCKS5 HTTP; only then run upgrade.'
else
    echo 'NEXT: verify SAME client public-key fingerprint, host pin, SSH -R and HTTP SOCKS5 WITHOUT new PIN; test cold boot before unlock.'
fi
