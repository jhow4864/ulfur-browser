#!/usr/bin/env bash
# Fill docs/RELEASE_NOTES_TEMPLATE.md for a built release APK and print the notes to stdout.
#
# usage: tools/release_notes.sh <version> <apk> [changes.md]
#   version     X.Y.Z (no leading "v"); the source link points at the tag vX.Y.Z
#   apk         the exact signed APK you will publish (Ulfur-<version>-arm64.apk)
#   changes.md  the "what's new" body. Default: docs/release-notes/<version>.md if it exists, otherwise the
#               "## <version>" section of CHANGELOG.md.
# env: RELEASE_DATE (default: today, e.g. "13 October 2026"), ANDROID_HOME (for apksigner, build-tools 36.0.0),
#      ULFUR_EXPECTED_CERT_SHA256 (default: the real Ulfur release certificate),
#      ULFUR_ALLOW_DRAFT=1 to fill a draft from a test/unsigned build (prints a DRAFT banner; never publish it).
#
# Fails if the APK isn't signed with the release certificate, or if GeckoView is still a beta/nightly build.
set -euo pipefail
cd "$(dirname "$0")/.."

[[ $# -ge 2 ]] || { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
VERSION="${1#v}"; APK="$2"; CHANGES="${3:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "ERROR: version must be X.Y.Z, got '$1'" >&2; exit 2; }
[[ -f "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }
EXPECTED="${ULFUR_EXPECTED_CERT_SHA256:-1f04b66b6d0bdf3c6e39640c23b95e517d7a144549385885e1789e3b92f6dd16}"
EXPECTED="$(tr 'A-F' 'a-f' <<<"${EXPECTED//:/}")"
DRAFT="${ULFUR_ALLOW_DRAFT:-0}"
problems=()

# --- versions from the source tree
GRADLE=app/build.gradle.kts
GV_VERSION="$(sed -nE 's/^val geckoviewVersion = "([^"]+)".*/\1/p' "$GRADLE")"
GV_CHANNEL="$(sed -nE 's/^val geckoviewChannel = "([^"]*)".*/\1/p' "$GRADLE")"
GV_LABEL="$GV_VERSION"
if [[ -n "$GV_CHANNEL" ]]; then
  GV_LABEL="$GV_VERSION (${GV_CHANNEL#-} channel)"
  problems+=("GeckoView is on the '${GV_CHANNEL#-}' channel ($GV_VERSION), not stable")
fi
UBO_VERSION="$(python3 -c 'import json;print(json.load(open("app/src/main/assets/extensions/ublock/manifest.json"))["version"])')"
APP_VERSION="$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' "$GRADLE")"
[[ "$APP_VERSION" == "$VERSION" ]] || problems+=("versionName in $GRADLE is $APP_VERSION, not $VERSION")

# --- the APK itself
APK_NAME="$(basename "$APK")"
APK_SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
APK_SIZE="$(python3 -c "import os,sys;b=os.path.getsize(sys.argv[1]);print(f'{b/1e6:.1f} MB, {b:,} bytes')" "$APK")"
[[ "$APK_NAME" == "Ulfur-$VERSION-arm64.apk" ]] || problems+=("APK is named $APK_NAME, expected Ulfur-$VERSION-arm64.apk")

APKSIGNER="$(command -v apksigner || true)"
[[ -n "$APKSIGNER" ]] || APKSIGNER="${ANDROID_HOME:-/home/box/android-tools/sdk}/build-tools/36.0.0/apksigner"
CERTS="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)" || CERTS=""
CERT_SHA256="$(sed -nE 's/^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$/\1/p' <<<"$CERTS" | head -1)"
CERT_DN="$(sed -nE 's/^Signer #1 certificate DN: (.*)$/\1/p' <<<"$CERTS" | head -1)"
if [[ -z "$CERT_SHA256" ]]; then
  problems+=("$APK_NAME is not signed or its signature doesn't verify")
  CERT_SHA256="(unsigned)"; CERT_DN="(unsigned)"
elif [[ "$CERT_SHA256" != "$EXPECTED" ]]; then
  problems+=("$APK_NAME is signed by $CERT_SHA256 ($CERT_DN), not the Ulfur release key $EXPECTED")
fi
CERT_COLON="$(tr 'a-f' 'A-F' <<<"$CERT_SHA256" | sed -E 's/(..)/\1:/g; s/:$//')"
[[ "$CERT_SHA256" == "(unsigned)" ]] && CERT_COLON="(unsigned)"

# --- changes
if [[ -z "$CHANGES" && -f "docs/release-notes/$VERSION.md" ]]; then CHANGES="docs/release-notes/$VERSION.md"; fi
if [[ -n "$CHANGES" ]]; then
  [[ -f "$CHANGES" ]] || { echo "ERROR: changes file not found: $CHANGES" >&2; exit 1; }
  CHANGES_TEXT="$(cat "$CHANGES")"
else
  CHANGES_TEXT="$(awk -v v="$VERSION" '$0 ~ "^## "v"( |$)" {f=1; next} f && /^## / {exit} f' CHANGELOG.md)"
  [[ -n "${CHANGES_TEXT//[[:space:]]/}" ]] || problems+=("no changes: pass a changes file or add a '## $VERSION' section to CHANGELOG.md")
fi

grep -q '\[check' <<<"$CHANGES_TEXT" && problems+=("the changes text still has [check …] markers")

if (( ${#problems[@]} )); then
  for p in "${problems[@]}"; do echo "PROBLEM: $p" >&2; done
  if [[ "$DRAFT" != 1 ]]; then echo "ERROR: not filling release notes (ULFUR_ALLOW_DRAFT=1 for a draft)." >&2; exit 1; fi
  echo "WARNING: filling a DRAFT. Do not publish it." >&2
fi

TAG="v$VERSION"
export VERSION TAG GV_LABEL UBO_VERSION APK_NAME APK_SHA256 APK_SIZE CERT_SHA256 CERT_COLON CERT_DN CHANGES_TEXT DRAFT
export DATE="${RELEASE_DATE:-$(date '+%-d %B %Y')}"
export SOURCE_URL="https://github.com/jhow4864/ulfur-browser/tree/$TAG"
export PROBLEMS="$(printf '%s\n' "${problems[@]:-}")"
python3 - docs/RELEASE_NOTES_TEMPLATE.md <<'PY'
import os, re, sys
t = open(sys.argv[1], encoding="utf-8").read()
t = re.sub(r"\A<!--.*?-->\n", "", t, flags=re.S)          # drop the instructions comment
e = os.environ
vals = {"VERSION": e["VERSION"], "DATE": e["DATE"], "CHANGES": re.sub(r"\A\s*<!--.*?-->\s*", "", e["CHANGES_TEXT"], flags=re.S).strip(), "APK_NAME": e["APK_NAME"],
        "APK_SHA256": e["APK_SHA256"], "APK_SIZE": e["APK_SIZE"], "CERT_SHA256": e["CERT_SHA256"],
        "CERT_SHA256_COLON": e["CERT_COLON"], "CERT_DN": e["CERT_DN"], "GECKOVIEW_VERSION": e["GV_LABEL"],
        "UBO_VERSION": e["UBO_VERSION"], "SOURCE_URL": e["SOURCE_URL"], "TAG": e["TAG"]}
t = re.sub(r"\{\{([A-Z0-9_]+)\}\}", lambda m: vals[m.group(1)], t)
if e["DRAFT"] == "1" and e["PROBLEMS"].strip():
    banner = "> **DRAFT – do not publish.**\n" + "".join(f"> * {p}\n" for p in e["PROBLEMS"].splitlines() if p)
    t = banner + "\n" + t
sys.stdout.write(t)
PY
