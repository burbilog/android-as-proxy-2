#!/bin/sh
# Build, zipalign and sign one AAP2 distribution flavor locally.
# Signing credentials NEVER go to GitHub or F-Droid CI.
set -eu
umask 077

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CHANNEL=${1:-github}
case "$CHANNEL" in
    github) APK_NAME=aap2.apk; TASK=assembleGithubRelease ;;
    fdroid) APK_NAME=aap2-fdroid.apk; TASK=assembleFdroidRelease ;;
    *) echo 'Usage: build-signed-apk.sh [github|fdroid]' >&2; exit 2 ;;
esac
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
TOOLS=${AAP2_BUILD_TOOLS:-"$SDK/build-tools/35.0.0"}
KEYSTORE=${AAP2_KEYSTORE:-"$HOME/aap2/aap2-release.p12"}
ALIAS=${AAP2_KEY_ALIAS:-aap2}
OUTPUT_DIR="$ROOT/app/build/outputs/apk/local-release"
UNSIGNED="$ROOT/app/build/outputs/apk/$CHANNEL/release/app-$CHANNEL-release-unsigned.apk"
ALIGNED="$OUTPUT_DIR/aap2-$CHANNEL-aligned-unsigned.apk"
OUTPUT="$OUTPUT_DIR/$APK_NAME"

if [ ! -f "$KEYSTORE" ]; then
    printf 'Missing release keystore: %s\n' "$KEYSTORE" >&2
    printf 'See docs/P0_RELEASE_SIGNING.md (generate once on trusted marduk).\n' >&2
    exit 1
fi
for tool in zipalign apksigner; do
    if [ ! -x "$TOOLS/$tool" ]; then
        printf 'Missing Android build tool: %s/%s\n' "$TOOLS" "$tool" >&2
        exit 1
    fi
done

cd "$ROOT"
./gradlew --no-daemon ":app:$TASK"
if [ ! -f "$UNSIGNED" ]; then
    printf 'Unsigned release not found: %s\n' "$UNSIGNED" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
"$TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"
# Never leave a previously signed APK behind after a failed new signing attempt.
rm -f -- "$OUTPUT"
"$TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --out "$OUTPUT" "$ALIGNED"
"$TOOLS/apksigner" verify --verbose --print-certs "$OUTPUT"
if [ "$CHANNEL" = fdroid ]; then
    if "$TOOLS/aapt" dump permissions "$OUTPUT" |
        grep -q 'android.permission.REQUEST_INSTALL_PACKAGES'; then
        echo 'F-Droid APK unexpectedly requests install-packages permission' >&2
        rm -f -- "$OUTPUT"
        exit 1
    fi
fi
sha256sum "$OUTPUT"
printf '\nSigned APK: %s\n' "$OUTPUT"
