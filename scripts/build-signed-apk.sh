#!/bin/sh
# Build, zipalign and sign AAP2 locally. Signing credentials NEVER go to GitHub.
set -eu
umask 077

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Android/Sdk"}}
TOOLS=${AAP2_BUILD_TOOLS:-"$SDK/build-tools/35.0.0"}
KEYSTORE=${AAP2_KEYSTORE:-"$HOME/.local/share/aap2/aap2-release.p12"}
ALIAS=${AAP2_KEY_ALIAS:-aap2}
OUTPUT_DIR="$ROOT/app/build/outputs/apk/local-release"
UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$OUTPUT_DIR/aap2-aligned-unsigned.apk"
OUTPUT="$OUTPUT_DIR/aap2.apk"

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
./gradlew --no-daemon :app:assembleRelease
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
sha256sum "$OUTPUT"
printf '\nSigned APK: %s\n' "$OUTPUT"
