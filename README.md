![Ulfur](docs/readme-header.png)

# Ulfur

**Fast. Private. Feral.** Ulfur is a privacy-first Android browser with a gaming-style look. It runs on Mozilla's
GeckoView engine (the same engine as Firefox) and has uBlock Origin built in, so ads and trackers are blocked from the
first page you open.

Ulfur was called **Beast Browser** up to version 2.3.8.

Ulfur is an independent project. It embeds Mozilla's open source GeckoView library and is not affiliated with, endorsed by, or maintained by Mozilla. Firefox is a trademark of the Mozilla Foundation. Ulfur does not use Mozilla trademarks or logos as its own branding.

## Features

**Privacy and blocking**
* uBlock Origin built in, plus Firefox's Enhanced Tracking Protection (Strict), Total Cookie Protection,
  HTTPS-Only mode, Global Privacy Control and fingerprinting protection
* **Shields**: one switch per site that turns blocking on or off
* **Realms**: three separate browsing identities, each with its own cookies and tabs
  * **Play**, the everyday realm
  * **Work**, with its own logins and a one-tap wipe
  * **Ghost**, always private and burned when the last Ghost tab closes

**Tabs**
* Tab groups, tab search and **Tab DNA**, which links tabs to the tab they were opened from and lets you close a whole branch at once
* A Speed Dial home-screen widget

**Passwords**
* A fingerprint-locked password vault with autofill and a password generator
* **Encrypted backup and restore** of passwords, bookmarks, Speed Dial, the reading list and settings, protected by
  your own passphrase. It also imports password exports from Chrome and Firefox.

**Reading and media**
* Reader view with an offline reading list
* On-device page translation, so pages aren't sent to a translation server
* A download manager with pause and resume, plus downloads of HLS video streams
* A **video download button** in the address bar (on by default; can be turned off in Settings), for plain progressive MP4/WebM and unencrypted HLS. It does not offer or save AES-128 HLS, SAMPLE-AES, or EME (Widevine, PlayReady, FairPlay). It does not list major streaming hosts (YouTube, Netflix, Disney+, and the rest of the host block). It does not bypass DRM.

**Look and feel**
* Dark-first design, a choice of accent colours (each realm can have its own) and an icon that follows your
  wallpaper colours on Android 13 and later
* **Ulfur Monitor**, which shows the app's live CPU, memory and data use

## Install
Ulfur is released for **64-bit ARM (arm64-v8a) phones only**, which covers almost every Android phone from the last several years. There is no 32-bit or x86 release.

1. Download the newest `Ulfur-<version>-arm64.apk` from [Releases](../../releases/latest).
2. Open it on your phone and allow installs from your browser or file manager if Android asks.

Ulfur needs Android 8.0 or later on a 64-bit ARM phone, which covers almost every phone from the last several years.

**Coming from Beast Browser?**
* **2.3.3 or earlier**: install Ulfur straight over it. Your tabs, logins and settings carry over.
* **2.3.4 to 2.3.8, or the "1-3" build**: these were signed with a temporary key by mistake, so Android won't install
  Ulfur over them. Uninstall Beast Browser first, then install Ulfur. Uninstalling deletes the old app's data, so
  write down any saved passwords you'll need first. (Those versions have no export option.)

## Check your download

Every Ulfur release is signed with the same release key. Its certificate SHA-256 fingerprint is:

    1F:04:B6:6B:6D:0B:DF:3C:6E:39:64:0C:23:B9:5E:51:7D:7A:14:45:49:38:58:85:E1:78:9E:3B:92:F6:DD:16

On a computer with the Android SDK build tools, run `apksigner verify --print-certs Ulfur-<version>-arm64.apk` and
check that the `SHA-256 digest` line matches (apksigner prints it in lower case without colons:
`1f04b66b6d0bdf3c6e39640c23b95e517d7a144549385885e1789e3b92f6dd16`). On a phone, an app such as AppVerifier can
compare it for you. If it doesn't match, don't install the APK, and please open an issue.

## Updates

Ulfur checks this repo's releases about once a day and offers to download and install new versions. You can also tap
**Settings → Check for updates**. Before installing, it checks that the download is intact, is a newer version and is
signed with the same release key as the app you already have.

## Privacy

Ulfur has no accounts, analytics or telemetry. Your history, passwords, bookmarks and reading list stay on your phone.
Apart from the pages you visit, the only background connections are:
* the daily update check against this repo's GitHub releases
* uBlock Origin's filter-list updates
* Safe Browsing list updates, which warn about dangerous sites (you can turn this off in Settings)
* translation model downloads, the first time you translate a language

## For developers

* Package: `com.jamhowman.beastbrowser` (unchanged for upgrades). minSdk 26, target SDK 37, compile SDK 37.2. Version 2.7.1 (versionCode 19)
* Engine: `org.mozilla.geckoview:geckoview-beta-<abi>:158.0.20261007115609` (158 beta until stable 158 ships on 13 Oct 2026; releases must use stable `geckoview-<abi>:158.0.<buildid>`) from https://maven.mozilla.org/maven2/
* Ad blocking: uBlock Origin 1.75.0 (official AMO XPI, unpacked into `app/src/main/assets/extensions/ublock/`),
  installed as a built-in extension. Also uses GeckoView Enhanced Tracking Protection (Strict),
  Total Cookie Protection, HTTPS-Only mode, Global Privacy Control and fingerprinting protection.

### Additions to the built-in extensions
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

### Downloads
Ulfur's own download manager (`downloads/DownloadCenter.kt`) handles page downloads and context-menu downloads:
* live progress (StateFlow), speed and ETA
* pause/resume via HTTP Range requests through GeckoWebExecutor; restarts if the server ignores Range
* cancel, retry, open, share, delete, and clear completed
* a foreground-service notification (tap opens the Downloads screen)
* a progress ring on the menu button
* private downloads are never persisted, and leave the list when private browsing ends

New files are saved to Downloads/Ulfur. Files downloaded before the rename stay in Downloads/Beast, and their
records keep working: each record stores its MediaStore URI or absolute path.

### Build
Needs JDK 17+ and Android SDK platform 37.2. Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties`
containing `sdk.dir=...`. That file is git-ignored and must not be committed.

    ./gradlew assembleDebug                         # arm64-v8a (default, the only released build)
    ./gradlew assembleDebug -Pbeast.abi=x86_64      # emulator build, for development only
    ./gradlew testDebugUnitTest                     # unit tests and UI preview renders (../beast-browser-screens)

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. The Gradle property names (`beast.abi`,
`beast.keystoreProperties`) keep their original names.

    # Size-optimised installable APK (~95 MB): R8 + shrinkResources release, lossless zopfli recompression,
    # zipalign and apksigner with the release key (needs `pip install zopfli`)
    tools/build_small_apk.sh /path/to/Ulfur-arm64.apk

### Continuous integration
`.github/workflows/ci.yml` runs on every pull request and every push to `main`: `assembleDebug`, `testDebugUnitTest` and
`lintDebug` (lint errors fail the build, warnings don't). The debug APK and the test and lint reports are attached to
each run for 14 days. CI never signs release builds.

### Releasing
Follow [docs/release-checklist.md](docs/release-checklist.md) for every release.

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

**Release builds never fall back to the debug key.** (2.3.4 to 2.3.8 were accidentally debug-signed that way, which
forced a one-time reinstall.)
* If keystore.properties or the keystore is missing, incomplete or won't open, `assembleRelease` / `bundleRelease`
  stop at once in `:app:validateReleaseSigning`. The error names the file or the missing keys, never a password.
* Afterwards, `:app:verifyReleaseApkSigner` / `:app:verifyReleaseBundleSigner` check the packaged APK/AAB. They fail
  if it is signed with `CN=Android Debug`, or if its certificate SHA-256 differs from `ulfur.expectedCertSha256`
  (default: the real release certificate, `1f04b66b…f6dd16`; pass `-Pulfur.expectedCertSha256=` to skip the pin).
* `tools/build_small_apk.sh` applies the same rules (`ULFUR_EXPECTED_CERT_SHA256`).
* Debug builds don't need the key.

A release-signed APK cannot be installed over a debug-signed one (or the reverse); uninstall first.

### Licences
Ulfur is free software, licensed under the **GNU General Public License v3.0** ([LICENSE](LICENSE)). Copyright (C) 2026 Jam Howman.
You may use, modify and redistribute it under the GPL-3.0 terms; modified versions you distribute must also be GPL-3.0 with source available.

Bundled third-party components keep their own licences. Full list and source locations: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

* uBlock Origin is GPL-3.0 (c) Raymond Hill and contributors (https://github.com/gorhill/uBlock). Ulfur's
  `beast-bridge.js` addition is GPL-3.0 as well. If you distribute an APK that contains uBO, you must follow the GPLv3
  for that component, which includes offering its source (included here).
* GeckoView is MPL-2.0.
* Readability.js 0.6.0 (Mozilla, https://github.com/mozilla/readability) is Apache-2.0. It is bundled unmodified in
  `assets/extensions/beast-helper/reader/`, with its licence in `LICENSE-Readability.txt`.
