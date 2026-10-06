#!/usr/bin/env bash
# Size-optimised arm64 APK: R8 release -> lossless zopfli repack -> zipalign -> apksigner (Beast release key).
# usage: tools/build_small_apk.sh [out.apk]
# needs: pip install zopfli; ANDROID_HOME; keystore.properties (default ../beast-keys/keystore.properties,
#        override with BEAST_KEYSTORE_PROPERTIES=/path). There is NO debug-key fallback: without the release key
#        the script fails. The result must match ULFUR_EXPECTED_CERT_SHA256 (default: the real release cert).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-BeastBrowser-arm64.apk}"
BT="$ANDROID_HOME/build-tools/36.0.0"
PROPS="${BEAST_KEYSTORE_PROPERTIES:-../beast-keys/keystore.properties}"
EXPECTED_SHA256="${ULFUR_EXPECTED_CERT_SHA256:-1f04b66b6d0bdf3c6e39640c23b95e517d7a144549385885e1789e3b92f6dd16}"
if [[ ! -f "$PROPS" ]]; then
  echo "ERROR: release keystore properties not found: $PROPS (release builds never use the debug key)" >&2
  exit 1
fi
prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }

./gradlew assembleRelease -Pbeast.keystoreProperties="$(realpath -m "$PROPS")"
python3 tools/repack_apk.py app/build/outputs/apk/release/app-release.apk /tmp/beast-repacked.apk
"$BT/zipalign" -f -p 4 /tmp/beast-repacked.apk /tmp/beast-aligned.apk

export BEAST_KS_PASS="$(prop storePassword)" BEAST_KEY_PASS="$(prop keyPassword)"
"$BT/apksigner" sign --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
  --ks-pass env:BEAST_KS_PASS --key-pass env:BEAST_KEY_PASS --out "$OUT" /tmp/beast-aligned.apk
unset BEAST_KS_PASS BEAST_KEY_PASS
rm -f "$OUT.idsig"
CERTS="$("$BT/apksigner" verify -v --print-certs "$OUT")"
echo "$CERTS"
if grep -q "CN=Android Debug" <<<"$CERTS"; then
  echo "ERROR: $OUT is signed with the DEBUG certificate" >&2; rm -f "$OUT"; exit 1
fi
if [[ -n "$EXPECTED_SHA256" ]] && ! grep -qi "certificate SHA-256 digest: $EXPECTED_SHA256" <<<"$CERTS"; then
  echo "ERROR: $OUT signer does not match the expected release certificate $EXPECTED_SHA256" >&2; rm -f "$OUT"; exit 1
fi
