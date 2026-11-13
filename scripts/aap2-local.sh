#!/bin/sh
# AAP2 local test deploy: source -> debug + androidTest APKs -> zipalign -> sign.
# Both APKs use the SAME persistent local key; never put secrets in GitHub.
set -eu
umask 077

BASE="$HOME/aap2"
SRC="$BASE/p0-local-build"
OUT="$BASE/local-apk"
KEY="${AAP2_KEYSTORE:-$BASE/aap2-release.p12}"
ALIAS="${AAP2_KEY_ALIAS:-aap2}"
BRANCH="feature/p0-local-deploy"
REPO="https://github.com/burbilog/android-as-proxy-2.git"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
TOOLS="${AAP2_BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
ACTION="${1:-deploy}"

adb_run() {
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        adb -s "$ANDROID_SERIAL" "$@"
    else
        adb "$@"
    fi
}

usage() {
    printf 'Usage: sh ~/aap2/aap2.sh [deploy|build|install|reset]\n' >&2
    printf '  deploy: build + sign both APKs, then adb install -r (default)\n' >&2
    printf '  build:  build + sign without touching the Android device\n' >&2
    printf '  install: install previously signed APKs\n' >&2
    printf '  reset:  EXPLICITLY uninstall only AAP2 and its test app, then install\n' >&2
}

check_apks() {
    [ -s "$OUT/app.apk" ] && [ -s "$OUT/tests.apk" ] || {
        echo "Missing signed APKs; run: sh ~/aap2/aap2.sh build" >&2
        exit 1
    }
}

build() {
    [ -f "$KEY" ] || { echo "Missing local signing key: $KEY" >&2; exit 1; }
    for tool in zipalign apksigner; do
        [ -x "$TOOLS/$tool" ] || {
            echo "Missing Android Build Tools: $TOOLS/$tool" >&2
            exit 1
        }
    done

    # Gradle needs exported ANDROID_HOME even if the user configured a shell-only variable.
    export ANDROID_HOME="$SDK"
    export ANDROID_SDK_ROOT="$SDK"
    export AAP2_VERSION_CODE="${AAP2_VERSION_CODE:-20002}"
    export AAP2_VERSION_NAME="${AAP2_VERSION_NAME:-2.0.0-p0-local}"

    if [ ! -d "$SRC/.git" ]; then
        [ ! -e "$SRC" ] || { echo "Refusing to reuse non-git directory: $SRC" >&2; exit 1; }
        git clone --depth 1 --branch "$BRANCH" "$REPO" "$SRC"
    else
        [ "$(git -C "$SRC" remote get-url origin)" = "$REPO" ] || {
            echo "Unexpected git remote at $SRC; refusing to overwrite anything" >&2
            exit 1
        }
        git -C "$SRC" fetch --depth 1 origin "$BRANCH"
        git -C "$SRC" merge --ff-only FETCH_HEAD
    fi

    echo "Building $BRANCH at $(git -C "$SRC" rev-parse --short HEAD)"
    ( cd "$SRC" && sh ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest )

    app="$SRC/app/build/outputs/apk/debug/app-debug.apk"
    tests="$SRC/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    [ -s "$app" ] && [ -s "$tests" ] || {
        echo "Missing app or instrumentation APK after build" >&2; exit 1;
    }

    mkdir -p "$OUT"
    tmp=$(mktemp -d "$BASE/.aap2-sign.XXXXXXXX")
    trap 'rm -rf -- "$tmp"' EXIT HUP INT TERM

    "$TOOLS/zipalign" -f -p 4 "$app" "$tmp/app-aligned.apk"
    "$TOOLS/zipalign" -f -p 4 "$tests" "$tmp/tests-aligned.apk"

    echo 'Sign app APK (password prompt):'
    "$TOOLS/apksigner" sign --ks "$KEY" --ks-key-alias "$ALIAS" \
        --out "$tmp/app.apk" "$tmp/app-aligned.apk"
    echo 'Sign instrumentation APK using same key (password prompt):'
    "$TOOLS/apksigner" sign --ks "$KEY" --ks-key-alias "$ALIAS" \
        --out "$tmp/tests.apk" "$tmp/tests-aligned.apk"

    "$TOOLS/apksigner" verify --verbose --print-certs "$tmp/app.apk"
    "$TOOLS/apksigner" verify --verbose --print-certs "$tmp/tests.apk"

    mv -f "$tmp/app.apk" "$OUT/app.apk"
    mv -f "$tmp/tests.apk" "$OUT/tests.apk"
    echo "Signed debug APKs ready in $OUT"
}

install_apks() {
    check_apks
    if ! adb_run install -r "$OUT/app.apk"; then
        echo 'App install failed. A signature mismatch with old CI debug APK is expected.' >&2
        echo 'NOTHING was uninstalled. To accept a ONE-TIME loss of AAP2 Keystore' >&2
        echo 'identity, explicitly run: sh ~/aap2/aap2.sh reset' >&2
        return 1
    fi
    if ! adb_run install -r "$OUT/tests.apk"; then
        echo 'Test APK install failed. AAP2 data has NOT been cleared.' >&2
        echo 'If only the old test package has a mismatched signature, run:' >&2
        echo 'adb uninstall net.isaeff.android.asproxy2.test' >&2
        echo 'sh ~/aap2/aap2.sh install' >&2
        return 1
    fi
    echo 'Installed both locally signed APKs; AAP1 was not touched.'
}

reset_apks() {
    check_apks
    echo 'WARNING: removes ONLY net.isaeff.android.asproxy2(.test).'
    echo 'AAP2 Keystore SSH identity, saved profile and local AAP2 data WILL BE LOST.'
    echo 'The legacy AAP1 package net.isaeff.android.asproxy will not be touched.'
    printf 'Type RESET-AAP2 to proceed: '
    IFS= read -r answer
    [ "$answer" = RESET-AAP2 ] || { echo 'Cancelled.'; exit 1; }

    adb_run uninstall net.isaeff.android.asproxy2.test || :
    adb_run uninstall net.isaeff.android.asproxy2 || :
    adb_run install "$OUT/app.apk"
    adb_run install "$OUT/tests.apk"
    echo 'Install complete; re-create Keystore identity, authorize NEW public key'
    echo 'in aap2-test, then re-provision Direct Boot (existing sh x workflow).'
}

case "$ACTION" in
    build) build ;;
    deploy) build; install_apks ;;
    install) install_apks ;;
    reset) reset_apks ;;
    *) usage; exit 2 ;;
esac
