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
	  'make build    Pull current branch, sign next APK and archive it' \
	  'make release   Prepare signed GitHub APK from version.properties' \
	  'make fdroid-candidate   Separately sign F-Droid verification APK' \
	  'make release-both   Convenience: release + fdroid-candidate' \
	  'make publish   Publish GitHub (with optional F-Droid asset)' \
	  'make verify-release  Check published APKs against local archive' \
	  'make install  adb install -r newest archived APK (no data wipe)' \
	  'make curl     Test S2 reverse SOCKS5 via Cloudflare trace' \
	  'make status   Show S2 reverse SOCKS5 listener' \
	  'make check    Verify newest archived APK signature/version' \
	  'make test     Run local Android unit tests' \
	  'make sync     Fast-forward current Git branch from origin' \
	  '' 'Local config: ~/.config/aap2/local.mk (Z, S2, PORT)' \
	  'Release version source: version.properties (both channels)'

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
	@./gradlew --no-daemon :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest

# GitHub publication must never depend on F-Droid availability or acceptance.
# The F-Droid candidate can be built and attached independently.
release: sync
	@set -eu; \
	  version="$(PUBLISHED_VERSION)"; code="$(PUBLISHED_CODE)"; prev="$(LATEST_CODE)"; \
	  printf '%s\n' "$$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || \
	    { echo 'Invalid version.properties versionName' >&2; exit 1; }; \
	  case "$$code" in ''|*[!0-9]*) echo 'Invalid versionCode' >&2; exit 1;; esac; \
	  [ "$$(git branch --show-current)" = master ] && \
	  [ -z "$$(git status --porcelain --untracked-files=no)" ] || \
	    { echo 'Release requires a clean master' >&2; exit 1; }; \
	  git fetch origin master; \
	  head=$$(git rev-parse HEAD); \
	  [ "$$head" = "$$(git rev-parse origin/master)" ] || \
	    { echo 'Local master differs from origin/master' >&2; exit 1; }; \
	  if [ -n "$$prev" ] && [ "$$code" -le "$$prev" ]; then \
	    echo "Release versionCode $$code must exceed archived $$prev" >&2; exit 1; \
	  fi; \
	  $(MAKE) --no-print-directory build VERSION_CODE="$$code" VERSION_NAME="$$version"; \
	  if [ -n "$$prev" ]; then \
	    sh scripts/aap2-spare-upgrade.sh check \
	      "$(RELEASE_DIR)/aap2-$$prev.apk" "$(RELEASE_DIR)/aap2-$$code.apk"; \
	  else \
	    echo 'No previous archived APK: signer continuity cannot be checked'; \
	  fi; \
	  printf '%s\n' "$$head" > "$(RELEASE_DIR)/aap2-$$code.commit"; \
	  echo "GitHub candidate ready: $(RELEASE_DIR)/aap2-$$code.apk (commit $$head)"

fdroid-candidate: sync
	@set -eu; \
	  version="$(PUBLISHED_VERSION)"; code="$(PUBLISHED_CODE)"; \
	  printf '%s\n' "$$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || exit 1; \
	  case "$$code" in ''|*[!0-9]*) exit 1;; esac; \
	  [ "$$(git branch --show-current)" = master ] && \
	  [ -z "$$(git status --porcelain --untracked-files=no)" ] || \
	    { echo 'F-Droid candidate requires a clean master' >&2; exit 1; }; \
	  git fetch origin master; \
	  head=$$(git rev-parse HEAD); \
	  [ "$$head" = "$$(git rev-parse origin/master)" ] || \
	    { echo 'Local master differs from origin/master' >&2; exit 1; }; \
	  fdapk="$(RELEASE_DIR)/aap2-$$code-fdroid.apk"; \
	  mkdir -p "$(RELEASE_DIR)"; \
	  [ ! -e "$$fdapk" ] || { echo "Already exists: $$fdapk" >&2; exit 1; }; \
	  AAP2_VERSION_CODE="$$code" AAP2_VERSION_NAME="$$version" \
	    sh scripts/build-signed-apk.sh fdroid; \
	  cp app/build/outputs/apk/local-release/aap2-fdroid.apk "$$fdapk"; \
	  printf '%s\n' "$$head" > "$(RELEASE_DIR)/aap2-$$code-fdroid.commit"; \
	  echo "F-Droid verification candidate ready: $$fdapk (commit $$head)"

# Convenient combined preparation, but GitHub release/publish remains independent.
release-both:
	@$(MAKE) --no-print-directory release
	@$(MAKE) --no-print-directory fdroid-candidate

publish:
	@set -eu; \
	  version="$(PUBLISHED_VERSION)"; code="$(PUBLISHED_CODE)"; \
	  printf '%s\n' "$$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || exit 1; \
	  case "$$code" in ''|*[!0-9]*) exit 1;; esac; \
	  [ "$$(git branch --show-current)" = master ] && \
	  [ -z "$$(git status --porcelain --untracked-files=no)" ] || \
	    { echo 'Publish requires a clean master' >&2; exit 1; }; \
	  git fetch origin master; \
	  head=$$(git rev-parse HEAD); \
	  [ "$$head" = "$$(git rev-parse origin/master)" ] || \
	    { echo 'Local master differs from origin/master' >&2; exit 1; }; \
	  apk="$(RELEASE_DIR)/aap2-$$code.apk"; \
	  fdapk="$(RELEASE_DIR)/aap2-$$code-fdroid.apk"; \
	  [ -s "$$apk" ] && [ -f "$(RELEASE_DIR)/aap2-$$code.commit" ] && \
	  [ "$$(cat "$(RELEASE_DIR)/aap2-$$code.commit")" = "$$head" ] || \
	    { echo 'Missing GitHub APK or wrong source commit: run make release' >&2; exit 1; }; \
	  notes="docs/RELEASE_NOTES_$$version.md"; \
	  [ -s "$$notes" ] || { echo "Missing $$notes" >&2; exit 1; }; \
	  sdk="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-$(HOME)/Android/Sdk}}"; \
	  tools="$${AAP2_BUILD_TOOLS:-$$sdk/build-tools/35.0.0}"; \
	  "$$tools/apksigner" verify "$$apk"; \
	  meta=$$("$$tools/aapt" dump badging "$$apk"); \
	  line=$$(printf '%s\n' "$$meta" | grep '^package:' | head -n 1); \
	  printf '%s\n' "$$line" | grep -F "name='net.isaeff.android.asproxy2'" >/dev/null; \
	  printf '%s\n' "$$line" | grep -F "versionCode='$$code'" >/dev/null; \
	  printf '%s\n' "$$line" | grep -F "versionName='$$version'" >/dev/null; \
	  ! printf '%s\n' "$$meta" | grep -q '^application-debuggable' || exit 1; \
	  with_fdroid=NO; \
	  if [ -s "$$fdapk" ]; then \
	    [ -f "$(RELEASE_DIR)/aap2-$$code-fdroid.commit" ] && \
	    [ "$$(cat "$(RELEASE_DIR)/aap2-$$code-fdroid.commit")" = "$$head" ] || \
	      { echo 'F-Droid candidate has a different source commit' >&2; exit 1; }; \
	    "$$tools/apksigner" verify "$$fdapk"; \
	    fdmeta=$$("$$tools/aapt" dump badging "$$fdapk"); \
	    fdline=$$(printf '%s\n' "$$fdmeta" | grep '^package:' | head -n 1); \
	    [ "$$line" = "$$fdline" ] || \
	      { echo 'Channel package/version mismatch' >&2; exit 1; }; \
	    ! printf '%s\n' "$$fdmeta" | grep -q '^application-debuggable' || exit 1; \
	    ! "$$tools/aapt" dump permissions "$$fdapk" | \
	      grep -q 'android.permission.REQUEST_INSTALL_PACKAGES' || \
	      { echo 'F-Droid candidate requests APK installer permission' >&2; exit 1; }; \
	    a=$$("$$tools/apksigner" verify --print-certs "$$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'); \
	    b=$$("$$tools/apksigner" verify --print-certs "$$fdapk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'); \
	    [ -n "$$a" ] && [ "$$a" = "$$b" ] || \
	      { echo 'Channel signing certificates differ' >&2; exit 1; }; \
	    with_fdroid=YES; \
	  fi; \
	  command -v gh >/dev/null && gh auth status >/dev/null || \
	    { echo 'GitHub CLI authentication required' >&2; exit 1; }; \
	  ! git ls-remote --exit-code --tags origin "refs/tags/v$$version" >/dev/null 2>&1 || \
	    { echo "Tag v$$version already exists" >&2; exit 1; }; \
	  tmp=$$(mktemp -d "$(RELEASE_DIR)/.aap2-publish.XXXXXX"); \
	  trap 'rm -f -- "$$tmp/aap2.apk" "$$tmp/aap2-fdroid.apk" "$$tmp/SHA256SUMS"; rmdir -- "$$tmp"' EXIT; \
	  cp -- "$$apk" "$$tmp/aap2.apk"; \
	  set -- "$$tmp/aap2.apk"; \
	  if [ "$$with_fdroid" = YES ]; then \
	    cp -- "$$fdapk" "$$tmp/aap2-fdroid.apk"; \
	    set -- "$$@" "$$tmp/aap2-fdroid.apk"; \
	    (cd "$$tmp" && sha256sum aap2.apk aap2-fdroid.apk > SHA256SUMS); \
	  else \
	    (cd "$$tmp" && sha256sum aap2.apk > SHA256SUMS); \
	  fi; \
	  gh release create "v$$version" "$$@" "$$tmp/SHA256SUMS" \
	    --repo burbilog/android-as-proxy-2 --target "$$head" \
	    --title "AAP2 v$$version" --notes-file "$$notes" --latest; \
	  echo 'Run make verify-release to check GitHub download'

verify-release:
	@set -eu; \
	  code="$(CODE)"; \
	  case "$$code" in ''|*[!0-9]*) echo 'No numbered APK to verify' >&2; exit 1;; esac; \
	  apk="$(RELEASE_DIR)/aap2-$$code.apk"; \
	  fdapk="$(RELEASE_DIR)/aap2-$$code-fdroid.apk"; \
	  [ -s "$$apk" ] || { echo "Missing $$apk" >&2; exit 1; }; \
	  tmp=$$(mktemp -d "$(RELEASE_DIR)/.aap2-verify.XXXXXX"); \
	  trap 'rm -f -- "$$tmp/aap2.apk" "$$tmp/aap2-fdroid.apk"; rmdir -- "$$tmp"' EXIT; \
	  curl -fsSL --retry 2 --max-time 120 -o "$$tmp/aap2.apk" \
	    https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk; \
	  cmp -- "$$apk" "$$tmp/aap2.apk" || { echo 'Published GitHub APK differs from archive' >&2; exit 1; }; \
	  if [ -s "$$fdapk" ]; then \
	    curl -fsSL --retry 2 --max-time 120 -o "$$tmp/aap2-fdroid.apk" \
	      https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2-fdroid.apk; \
	    cmp -- "$$fdapk" "$$tmp/aap2-fdroid.apk" || \
	      { echo 'Published F-Droid APK differs from archive' >&2; exit 1; }; \
	    sha256sum "$$tmp/aap2-fdroid.apk"; \
	  fi; \
	  sha256sum "$$tmp/aap2.apk"; \
	  echo 'RELEASE_OK: published signed channel APKs match local archives'
