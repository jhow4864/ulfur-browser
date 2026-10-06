![Ulfur](docs/readme-header.png)

# Ulfur (formerly Beast Browser)

Ulfur is an Android browser with an Opera GX-style UI, built on Mozilla GeckoView, with uBlock Origin bundled as a
built-in extension. Highlights: Realms (separate Play / Work / Ghost cookie jars and tab lists), Tab DNA (parent/child
tab lineage), Ulfur Shields, Reader view, a password vault, and a download manager with HLS support.

The app was called **Beast Browser** up to 2.3.8. Version 2.4.0 renamed what users see (app name, icons, in-app text).
It upgrades 2.3.x in place: the internal identifiers stay "beast", namely the package `com.jamhowman.beastbrowser`,
class names, pref keys, `beast.db`, the realm context ids, the built-in extension ids and native port names, so tabs,
logins, settings and downloads carry over. The brand string lives in one place: the `brand` entity at the top of
`app/src/main/res/values/strings.xml`.

* Package: `com.jamhowman.beastbrowser` (unchanged for upgrades). minSdk 26, target/compile SDK 37 (37.1). Version 2.4.0 (versionCode 14)
* Engine: `org.mozilla.geckoview:geckoview-<abi>:157.0.20260924084938` from https://maven.mozilla.org/maven2/
* Ad blocking: uBlock Origin 1.75.0 (official AMO XPI, unpacked into `app/src/main/assets/extensions/ublock/`),
  installed as a built-in extension. Also uses GeckoView Enhanced Tracking Protection (Strict),
  Total Cookie Protection, HTTPS-Only mode, Global Privacy Control and fingerprinting protection.

## Additions to the built-in extensions
* `assets/extensions/ublock/js/beast-bridge.js` (GPLv3, loaded from uBO's `background.html`, plus the
  `nativeMessaging`/`geckoViewAddons` permissions in uBO's manifest). It exposes uBO's per-site switch
  (`µb.toggleNetFilteringSwitch`, the trusted-site list) to the app over the native port `beast_ubo`, and
  reports changes made in uBO's own panel. The Shields switch drives uBO and the ETP site exception together.
* `assets/extensions/beast-helper/`: a tiny built-in extension with a privileged experiment API (`beastHttps`).
  "Continue to HTTP site" adds Gecko's own `https-only-load-insecure` permission for that one host only, for the
  rest of the session (private and normal browsing are kept separate), the same mechanism desktop Firefox uses.
  If the helper is unavailable, the app falls back to allowing HTTP for that single page load.
* The helper extension (shown as "Ulfur Helper") also provides **Reader view**. A content script runs Readability's `isProbablyReaderable`, and the
  reader icon appears in the address bar when a page qualifies. Tapping it extracts the article (Readability.js is
  injected only then) and shows it in the helper's `reader/reader.html` page. That page has a dark default, your
  accent colour, text size −/+, sans/serif, and dark/sepia/light themes. **Save** stores title, byline, HTML, URL and
  date in a local `reading_list.db`. Menu → **Reading list** opens saved articles offline. Nothing is saved without
  an explicit tap, including in private tabs. Code: `reader/ReaderMode.kt`, `reader/ReadingListDb.kt`,
  `ui/ReadingListActivity.kt`, `browser/HelperSessions.kt` (per-session native app `beast_tab`).
* **Media sniffer (extension side).** `beast-helper/background.js` + `media/sniffer-core.js` watch responses with
  `webRequest` for progressive MP4/WebM (≥ 200 KB) and clear HLS. HLS master playlists are parsed (via
  `filterResponseData`, passed through unchanged), and each quality becomes its own entry. The list goes to the
  app's `MediaSniffer.publish(session, items)`. DRM is never offered:
  * a host block list, kept in sync with `MediaSniffer` by a unit test
  * SAMPLE-AES / Widevine / PlayReady / FairPlay HLS
  * any page using EME
  See `docs/media-sniffer.md`. JS tests: `node --test app/src/test/js/` (also run by `ExtensionJsTest`).
* Built-in extensions are reinstalled in place once after each app update (`installBuiltIn`) so these changes apply.

## Downloads
Ulfur's own download manager (`downloads/DownloadCenter.kt`) handles page downloads and context-menu downloads:
* live progress (StateFlow), speed and ETA
* pause/resume via HTTP Range requests through GeckoWebExecutor; restarts if the server ignores Range
* cancel, retry, open, share, delete, and clear completed
* a foreground-service notification (tap opens the Downloads screen)
* a progress ring on the menu button
* private downloads are never persisted, and leave the list when private browsing ends

New files are saved to Downloads/Ulfur. Files downloaded before the rename stay in Downloads/Beast, and their
records keep working: each record stores its MediaStore URI or absolute path.

## Build
Needs JDK 17+ and Android SDK platform 37.1. Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties`
containing `sdk.dir=...`. That file is git-ignored and must not be committed.

    ./gradlew assembleDebug                         # arm64-v8a (default)
    ./gradlew assembleDebug -Pbeast.abi=x86_64      # emulator build
    ./gradlew assembleDebug -Pbeast.abi=armeabi-v7a # older 32-bit phones
    ./gradlew testDebugUnitTest                     # unit tests and UI preview renders (../beast-browser-screens)

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. The Gradle property names (`beast.abi`,
`beast.keystoreProperties`) keep their original names.

    # Size-optimised installable APK (~95 MB): R8 + shrinkResources release, lossless zopfli recompression,
    # zipalign and apksigner with the release key (needs `pip install zopfli`)
    tools/build_small_apk.sh /path/to/Ulfur-arm64.apk

### Release signing
Signing material (keystore, passwords, `keystore.properties`) lives **outside this repository** and must never be
committed. `.gitignore` blocks `*.jks`, `*.keystore`, `keystore.properties` and `beast-keys/`. The release key is
**not** part of this source tree. `app/build.gradle.kts` and `tools/build_small_apk.sh` read
`../beast-keys/keystore.properties`. Override it with `-Pbeast.keystoreProperties=` or `BEAST_KEYSTORE_PROPERTIES=`.
The file contains:

    storeFile=/absolute/path/beast-release.jks
    storePassword=...
    keyAlias=beast
    keyPassword=...

Without it, release builds fall back to the local debug key. A release-signed APK cannot be installed over a
debug-signed one (or the reverse); uninstall first.

## Licences
* uBlock Origin is GPL-3.0 (c) Raymond Hill and contributors (https://github.com/gorhill/uBlock). Ulfur's
  `beast-bridge.js` addition is GPL-3.0 as well. If you distribute an APK that contains uBO, you must follow the GPLv3
  for that component, which includes offering its source (included here).
* GeckoView is MPL-2.0.
* Readability.js 0.6.0 (Mozilla, https://github.com/mozilla/readability) is Apache-2.0. It is bundled unmodified in
  `assets/extensions/beast-helper/reader/`, with its licence in `LICENSE-Readability.txt`.
