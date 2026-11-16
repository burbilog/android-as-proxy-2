# Local maintainer shortcuts. Never place signing keys, ADB serials or
# personal SSH server details in this tracked file.
# Configure them once in ~/.config/aap2/local.mk (see docs/A1_LOCAL_MAKE.md).
SHELL := /bin/sh
.DEFAULT_GOAL := help

RELEASE_DIR ?= $(HOME)/aap2-releases
PUBLISHED_CODE := $(shell sed -n 's/^versionCode=\([0-9][0-9]*\)$/\1/p' version.properties)
PUBLISHED_VERSION := $(shell sed -n 's/^versionName=\(.*\)$/\1/p' version.properties)
VERSION ?= $(PUBLISHED_VERSION)
Z ?=
S2 ?=
PORT ?=
-include $(HOME)/.config/aap2/local.mk

# Use the highest locally archived release number, not the Gradle debug
# default. An unsuccessful build does not consume a version number.
LATEST_CODE = $(shell find "$(RELEASE_DIR)" -maxdepth 1 -type f -name 'aap2-[0-9]*.apk' -printf '%f\n' 2>/dev/null | sed -n 's/^aap2-\([0-9][0-9]*\)\.apk$$/\1/p' | sort -n | tail -1)
# Local builds use a fresh number; public releases use the exact version.properties.
VERSION_CODE ?= $(shell printf '%s\n' '$(or $(LATEST_CODE),0)' '$(PUBLISHED_CODE)' | sort -n | tail -1 | awk '{print $1 + 1}')
VERSION_NAME ?= $(PUBLISHED_VERSION)-local-$(VERSION_CODE)
CODE ?= $(PUBLISHED_CODE)
APK = $(RELEASE_DIR)/aap2-$(LATEST_CODE).apk

.PHONY: help sync build install curl status test check release publish verify-release

help:
	@printf '%s\n' \
	  'make build    Pull current branch, sign next APK and archive it' \
	  'make release   Build + check signed stable APK from version.properties' \
	  'make publish   Publish archived APK + SHA256 on GitHub' \
	  'make verify-release         Compare GitHub latest APK with local archive' \
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
	@./gradlew --no-daemon :app:testGithubDebugUnitTest

# Publishing is deliberately separate from building. Only a clean,
# up-to-date master may produce a public stable version.
release: sync
	@set -eu; \
	  version="$(VERSION)"; \
	  [ "$version" = "$(PUBLISHED_VERSION)" ] || \
	    { echo 'VERSION must match version.properties' >&2; exit 1; }; \
	  printf '%s\n' "$$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || \
	    { echo 'Invalid version in version.properties' >&2; exit 1; }; \
	  [ "$$(git branch --show-current)" = master ] || \
	    { echo 'Release requires master branch' >&2; exit 1; }; \
	  [ -z "$$(git status --porcelain --untracked-files=no)" ] || \
	    { echo 'Commit tracked changes before release' >&2; exit 1; }; \
	  git fetch origin master; \
	  head=$$(git rev-parse HEAD); \
	  [ "$$head" = "$$(git rev-parse origin/master)" ] || \
	    { echo 'Local master differs from origin/master' >&2; exit 1; }; \
	  code="$(PUBLISHED_CODE)"; prev="$(LATEST_CODE)"; \
	  case "$$code" in ''|*[!0-9]*) echo 'Invalid VERSION_CODE' >&2; exit 1;; esac; \
	  if [ -n "$$prev" ] && [ "$$code" -le "$$prev" ]; then \
	    echo "VERSION_CODE must exceed archived $$prev" >&2; exit 1; \
	  fi; \
	  $(MAKE) --no-print-directory build VERSION_CODE="$$code" VERSION_NAME="$$version"; \
	  if [ -n "$$prev" ]; then \
	    sh scripts/aap2-spare-upgrade.sh check \
	      "$(RELEASE_DIR)/aap2-$$prev.apk" "$(RELEASE_DIR)/aap2-$$code.apk"; \
	  else \
	    echo 'No previous archived APK: signer continuity cannot be checked'; \
	  fi; \
	  printf '%s\n' "$$head" > "$(RELEASE_DIR)/aap2-$$code.commit"; \
	  echo "Release candidate ready: $(RELEASE_DIR)/aap2-$$code.apk (commit $$head)"; \
	  echo "After review: make publish VERSION=$$version"

publish:
	@set -eu; \
	  version="$(VERSION)"; code="$(CODE)"; \
	  [ "$version" = "$(PUBLISHED_VERSION)" ] && [ "$code" = "$(PUBLISHED_CODE)" ] || \
	    { echo 'VERSION/CODE must match version.properties' >&2; exit 1; }; \
	  printf '%s\n' "$$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || \
	    { echo 'Invalid version in version.properties' >&2; exit 1; }; \
	  case "$$code" in ''|*[!0-9]*) echo 'No numbered APK to publish' >&2; exit 1;; esac; \
	  [ "$$(git branch --show-current)" = master ] || \
	    { echo 'Publish requires master branch' >&2; exit 1; }; \
	  [ -z "$$(git status --porcelain --untracked-files=no)" ] || \
	    { echo 'Commit tracked changes before publication' >&2; exit 1; }; \
	  git fetch origin master; \
	  head=$$(git rev-parse HEAD); \
	  [ "$$head" = "$$(git rev-parse origin/master)" ] || \
	    { echo 'Local master differs from origin/master' >&2; exit 1; }; \
	  apk="$(RELEASE_DIR)/aap2-$$code.apk"; \
	  [ -s "$$apk" ] || { echo "Missing $$apk" >&2; exit 1; }; \
	  [ -f "$(RELEASE_DIR)/aap2-$$code.commit" ] && \
	  [ "$$(cat "$(RELEASE_DIR)/aap2-$$code.commit")" = "$$head" ] || \
	    { echo 'APK was not prepared from this master: run make release' >&2; exit 1; }; \
	  notes="docs/RELEASE_NOTES_$$version.md"; \
	  [ -s "$$notes" ] || { echo "Missing $$notes" >&2; exit 1; }; \
	  sdk="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-$(HOME)/Android/Sdk}}"; \
	  tools="$${AAP2_BUILD_TOOLS:-$$sdk/build-tools/35.0.0}"; \
	  "$$tools/apksigner" verify "$$apk"; \
	  meta=$$("$$tools/aapt" dump badging "$$apk"); \
	  line=$$(printf '%s\n' "$$meta" | grep '^package:' | head -n 1); \
	  printf '%s\n' "$$line" | grep -F "name='net.isaeff.android.asproxy2'" >/dev/null; \
	  printf '%s\n' "$$line" | grep -F "versionCode='$$code'" >/dev/null; \
	  printf '%s\n' "$$line" | grep -F "versionName='$$version'" >/dev/null || \
	    { echo 'APK version does not match requested release' >&2; exit 1; }; \
	  ! printf '%s\n' "$$meta" | grep -q '^application-debuggable' || \
	    { echo 'Refusing debuggable APK' >&2; exit 1; }; \
	  command -v gh >/dev/null || { echo 'Install GitHub CLI (gh)' >&2; exit 1; }; \
	  gh auth status >/dev/null; \
	  ! git ls-remote --exit-code --tags origin "refs/tags/v$$version" >/dev/null 2>&1 || \
	    { echo "Tag v$$version already exists" >&2; exit 1; }; \
	  tmp=$$(mktemp -d "$(RELEASE_DIR)/.aap2-publish.XXXXXX"); \
	  trap 'rm -f -- "$$tmp/aap2.apk" "$$tmp/SHA256SUMS"; rmdir -- "$$tmp"' EXIT; \
	  cp -- "$$apk" "$$tmp/aap2.apk"; \
	  (cd "$$tmp" && sha256sum aap2.apk > SHA256SUMS); \
	  gh release create "v$$version" "$$tmp/aap2.apk" "$$tmp/SHA256SUMS" \
	    --repo burbilog/android-as-proxy-2 --target "$$head" \
	    --title "AAP2 v$$version" --notes-file "$$notes" --latest; \
	  echo 'Run make verify-release to check GitHub download'

verify-release:
	@set -eu; \
	  code="$(CODE)"; \
	  case "$$code" in ''|*[!0-9]*) echo 'No numbered APK to verify' >&2; exit 1;; esac; \
	  apk="$(RELEASE_DIR)/aap2-$$code.apk"; \
	  [ -s "$$apk" ] || { echo "Missing $$apk" >&2; exit 1; }; \
	  tmp=$$(mktemp); \
	  trap 'rm -f -- "$$tmp"' EXIT; \
	  curl -fsSL --retry 2 --max-time 120 -o "$$tmp" \
	    https://github.com/burbilog/android-as-proxy-2/releases/latest/download/aap2.apk; \
	  cmp -- "$$apk" "$$tmp" || { echo 'Published APK differs from local archive' >&2; exit 1; }; \
	  sha256sum "$$tmp"; \
	  echo 'RELEASE_OK: latest GitHub APK matches local signed archive'
