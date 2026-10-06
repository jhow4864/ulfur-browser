#!/usr/bin/env bash
# Size-optimised arm64 APK: R8 release -> lossless zopfli repack -> zipalign -> apksigner (Beast release key).
# usage: tools/build_small_apk.sh [out.apk]
# needs: pip install zopfli; ANDROID_HOME; keystore.properties (default ../beast-keys/keystore.properties,
#        override with BEAST_KEYSTORE_PROPERTIES=/path). Without it the APK is signed with ~/.android/debug.keystore.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-BeastBrowser-arm64.apk}"
BT="$ANDROID_HOME/build-tools/36.0.0"
PROPS="${BEAST_KEYSTORE_PROPERTIES:-../beast-keys/keystore.properties}"
prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }

./gradlew assembleRelease -Pbeast.keystoreProperties="$(realpath -m "$PROPS")"
python3 tools/repack_apk.py app/build/outputs/apk/release/app-release.apk /tmp/beast-repacked.apk
"$BT/zipalign" -f -p 4 /tmp/beast-repacked.apk /tmp/beast-aligned.apk

if [[ -f "$PROPS" ]]; then
  export BEAST_KS_PASS="$(prop storePassword)" BEAST_KEY_PASS="$(prop keyPassword)"
  "$BT/apksigner" sign --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
    --ks-pass env:BEAST_KS_PASS --key-pass env:BEAST_KEY_PASS --out "$OUT" /tmp/beast-aligned.apk
  unset BEAST_KS_PASS BEAST_KEY_PASS
else
  echo "WARNING: $PROPS not found - signing with the debug key" >&2
  "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android --out "$OUT" /tmp/beast-aligned.apk
fi
rm -f "$OUT.idsig"
"$BT/apksigner" verify -v --print-certs "$OUT"
