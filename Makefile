# Local maintainer shortcuts. Never place signing keys, ADB serials or
# personal SSH server details in this tracked file.
# Configure them once in ~/.config/aap2/local.mk (see docs/A1_LOCAL_MAKE.md).
SHELL := /bin/sh
.DEFAULT_GOAL := help

RELEASE_DIR ?= $(HOME)/aap2-releases
Z ?=
S2 ?=
PORT ?=
-include $(HOME)/.config/aap2/local.mk

# Use the highest locally archived release number, not the Gradle debug
# default. An unsuccessful build does not consume a version number.
LATEST_CODE = $(shell find "$(RELEASE_DIR)" -maxdepth 1 -type f -name 'aap2-[0-9]*.apk' -printf '%f\n' 2>/dev/null | sed -n 's/^aap2-\([0-9][0-9]*\)\.apk$$/\1/p' | sort -n | tail -1)
VERSION_CODE ?= $(shell expr $(or $(LATEST_CODE),20010) + 1)
VERSION_NAME ?= 2.0.0-local-$(VERSION_CODE)
APK = $(RELEASE_DIR)/aap2-$(LATEST_CODE).apk

.PHONY: help sync build install curl status test check

help:
	@printf '%s\n' \
	  'make build    Pull current branch, sign next APK and archive it' \
	  'make install  adb install -r newest archived APK (no data wipe)' \
	  'make curl     Test S2 reverse SOCKS5 via Cloudflare trace' \
	  'make status   Show S2 reverse SOCKS5 listener' \
	  'make check    Verify newest archived APK signature/version' \
	  'make test     Run local Android unit tests' \
	  'make sync     Fast-forward current Git branch from origin' \
	  '' 'Local config: ~/.config/aap2/local.mk (Z, S2, PORT)' \
	  'Override auto-version: make build VERSION_CODE=20020'

sync:
	@branch=$$(git branch --show-current); \
	  [ -n "$$branch" ] || { echo 'Detached HEAD: no automatic pull' >&2; exit 1; }; \
	  git pull --ff-only origin "$$branch"

build: sync
	@set -eu; \
	  code="$(VERSION_CODE)"; \
	  case "$$code" in ''|*[!0-9]*) echo 'Invalid VERSION_CODE' >&2; exit 1;; esac; \
	  mkdir -p "$(RELEASE_DIR)"; \
	  output="$(RELEASE_DIR)/aap2-$$code.apk"; \
	  [ ! -e "$$output" ] || { echo "Already exists: $$output" >&2; exit 1; }; \
	  echo "Signing AAP2 versionCode=$$code versionName=$(VERSION_NAME)"; \
	  AAP2_VERSION_CODE="$$code" AAP2_VERSION_NAME="$(VERSION_NAME)" \
	    sh scripts/build-signed-apk.sh; \
	  cp app/build/outputs/apk/local-release/aap2.apk "$$output"; \
	  echo "Archived: $$output"

install:
	@set -eu; \
	  [ -n "$(Z)" ] || { echo 'Set Z in ~/.config/aap2/local.mk' >&2; exit 1; }; \
	  [ -n "$(LATEST_CODE)" ] || { echo 'No archived APK; run make build' >&2; exit 1; }; \
	  apk="$(APK)"; \
	  [ -s "$$apk" ] || { echo "Missing APK: $$apk" >&2; exit 1; }; \
	  [ "$$(adb -s "$(Z)" get-state 2>/dev/null)" = device ] || \
	    { echo 'ADB device not connected/authorized' >&2; exit 1; }; \
	  installed=$$(adb -s "$(Z)" shell pm path net.isaeff.android.asproxy2); \
	  case "$$installed" in package:*) ;; *) echo 'AAP2 not installed: refusing first install' >&2; exit 1;; esac; \
	  echo "Device: $$(adb -s "$(Z)" shell getprop ro.product.model) ($(Z))"; \
	  echo "Upgrade using $$apk (keeps data and Keystore)"; \
	  adb -s "$(Z)" install -r "$$apk"

curl:
	@[ -n "$(S2)" ] && [ -n "$(PORT)" ] || \
	  { echo 'Set S2 and PORT in ~/.config/aap2/local.mk' >&2; exit 1; }
	@ssh "$(S2)" 'curl -fsS --max-time 15 --proxy socks5h://127.0.0.1:$(PORT) https://www.cloudflare.com/cdn-cgi/trace'

status:
	@[ -n "$(S2)" ] && [ -n "$(PORT)" ] || \
	  { echo 'Set S2 and PORT in ~/.config/aap2/local.mk' >&2; exit 1; }
	@ssh "$(S2)" 'ss -lnt | grep -F "127.0.0.1:$(PORT)" || echo DISCONNECTED'

check:
	@set -eu; \
	  [ -n "$(LATEST_CODE)" ] || { echo 'No archived APK; run make build' >&2; exit 1; }; \
	  sdk="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-$(HOME)/Android/Sdk}}"; \
	  tools="$${AAP2_BUILD_TOOLS:-$$sdk/build-tools/35.0.0}"; \
	  "$$tools/apksigner" verify --print-certs "$(APK)"; \
	  "$$tools/aapt" dump badging "$(APK)" | grep '^package:'; \
	  sha256sum "$(APK)"

test:
	@./gradlew --no-daemon :app:testDebugUnitTest
