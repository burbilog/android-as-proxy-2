# Local maintainer shortcuts. Never place signing keys, ADB serials or
# personal SSH server details in this tracked file.
# Configure them once in ~/.config/aap2/local.mk (see docs/A1_LOCAL_MAKE.md).
SHELL := /bin/sh
.DEFAULT_GOAL := help

RELEASE_DIR ?= $(HOME)/aap2-releases
PUBLISHED_CODE := $(shell sed -n 's/^versionCode=\([0-9][0-9]*\)$$/\1/p' version.properties)
PUBLISHED_VERSION := $(shell sed -n 's/^versionName=\(.*\)$$/\1/p' version.properties)
VERSION ?= $(PUBLISHED_VERSION)
Z ?=
S2 ?=
PORT ?=
-include $(HOME)/.config/aap2/local.mk

# Use the highest locally archived release number, not the Gradle debug
# default. An unsuccessful build does not consume a version number.
LATEST_CODE = $(shell find "$(RELEASE_DIR)" -maxdepth 1 -type f -name 'aap2-[0-9]*.apk' -printf '%f\n' 2>/dev/null | sed -n 's/^aap2-\([0-9][0-9]*\)\.apk$$/\1/p' | sort -n | tail -1)
# Local builds use a fresh number; public releases use the exact version.properties.
VERSION_CODE ?= $(shell printf '%s\n' '$(or $(LATEST_CODE),0)' '$(PUBLISHED_CODE)' | sort -n | tail -1 | awk '{print $$1 + 1}')
VERSION_NAME ?= $(PUBLISHED_VERSION)-local-$(VERSION_CODE)
CODE ?= $(PUBLISHED_CODE)
APK = $(RELEASE_DIR)/aap2-$(LATEST_CODE).apk

.PHONY: help sync build install curl status test check release fdroid-candidate release-both publish verify-release

help:
	@printf '%s\n' \
	  'AAP2 -- local development and guarded public releases' \
	  '' \
	  'Checked-in version: $(PUBLISHED_VERSION)  versionCode: $(PUBLISHED_CODE)' \
	  'This is SOURCE metadata, not proof of a published release.' \
	  'Release policy: docs/RELEASE_POLICY.md' \
	  '' \
	  'LOCAL TEST (disposable signed APKs, never public):' \
	  '  make build                  Build next local-numbered APK' \
	  '  make install                Upgrade existing spare Android via ADB' \
	  '  make curl                   Test remote SOCKS5 end to end' \
	  '  make status                 Show reverse TCP listener' \
	  '  make check                  Check latest local APK signature' \
	  '  make test                   Run local Android and release-tool tests' \
	  '' \
	  'RELEASE CANDIDATE (GitHub Pre-release, NEVER Latest):' \
	  '  1. In Git: versionName=X.Y.Z-rc1 and versionCode=(stableCode-100)+1' \
	  '  2. Commit matching docs/RELEASE_NOTES_X.Y.Z-rc1.md on master' \
	  '  3. make release TYPE=rc1' \
	  '  4. Test the signed APK on a physical spare phone' \
	  '  5. make publish TYPE=rc1' \
	  '  6. make verify-release TYPE=rc1' \
	  '' \
	  'STABLE RELEASE (GitHub Latest / in-app Update):' \
	  '  1. In Git: versionName=X.Y.Z and versionCode=stableCode (ends 00)' \
	  '  2. Commit matching docs/RELEASE_NOTES_X.Y.Z.md on master' \
	  '  3. make release TYPE=release' \
	  '  4. Complete CI, physical upgrade and signer checks' \
	  '  5. make publish TYPE=release' \
	  '  6. make verify-release TYPE=release' \
	  '' \
	  'OPTIONAL F-DROID (never blocks GitHub):' \
	  '  make fdroid-candidate TYPE=release  (or TYPE=rc1)' \
	  '  make release-both TYPE=release     (prepare both sequentially)' \
	  '' \
	  'SAFETY: TYPE is mandatory on all PUBLIC targets; publish without it fails.' \
	  'RC must never become Latest. Existing Git tags/APKs are not overwritten.' \
	  'Public candidates: $(RELEASE_DIR)/public/vX.Y.Z[-rcN]/' \
	  'Private signing key stays on this workstation.' \
	  '' \
	  'Full instructions: docs/A1_LOCAL_MAKE.md  docs/RELEASE_POLICY.md' \
	  'Local config: ~/.config/aap2/local.mk (Z, S2, PORT)'
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
	@python3 -m unittest discover -s scripts -p 'test_aap2_release.py' -v
	@./gradlew --no-daemon :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest

# One shared release backend for RC and stable, with the same signature,
# provenance and versionCode checks. The script checks TYPE before any work.
# Accept TYPE only from an explicit make command-line assignment, never
# an ambient shell variable or local.mk default. No shell interpolation.
ifeq ($(origin TYPE),command line)
export AAP2_RELEASE_TYPE := $(TYPE)
else
export AAP2_RELEASE_TYPE :=
endif
export AAP2_RELEASE_DIR := $(RELEASE_DIR)

release:
	@python3 scripts/aap2-release.py prepare

fdroid-candidate:
	@python3 scripts/aap2-release.py prepare-fdroid

release-both:
	@python3 scripts/aap2-release.py prepare
	@python3 scripts/aap2-release.py prepare-fdroid

publish:
	@python3 scripts/aap2-release.py publish

verify-release:
	@python3 scripts/aap2-release.py verify
