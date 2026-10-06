# Third-party notices

Ulfur bundles the components below. Each one stays under its own licence; the full licence texts ship
next to the code in this repo (paths given) and inside every APK built from it.

| Component | Version | Licence | Where |
|---|---|---|---|
| GeckoView (Mozilla) | 157.0.20260924084938 | MPL-2.0 | Maven dependency `org.mozilla.geckoview:geckoview-*` |
| uBlock Origin (Raymond Hill and contributors) | 1.75.0 | GPL-3.0 | `app/src/main/assets/extensions/ublock/` (`LICENSE.txt`) |
| Readability.js (Mozilla) | 0.6.0 | Apache-2.0 | `app/src/main/assets/extensions/beast-helper/reader/` (`LICENSE-Readability.txt`) |
| Libraries bundled inside uBlock Origin (CodeMirror, CSSTree, hsluv, js-beautify, lz4, publicsuffixlist, punycode.js, regexanalyzer, diff) | as shipped with uBO 1.75.0 | their own licences (MIT and similar) | `app/src/main/assets/extensions/ublock/lib/` |
| Inter font / Metropolis font | as shipped with uBO 1.75.0 | SIL OFL 1.1 / Unlicense | `app/src/main/assets/extensions/ublock/css/fonts/` |

## GeckoView (MPL-2.0)
GeckoView is used unmodified as a library. Its source code is available from Mozilla at
https://hg.mozilla.org/mozilla-central and https://github.com/mozilla-firefox/firefox, and the licence text is at
https://www.mozilla.org/MPL/2.0/.

GeckoView is used unmodified as a library. Ulfur is not a Mozilla product and is not affiliated with the Mozilla Foundation. Firefox and the Mozilla logo are trademarks of the Mozilla Foundation; they are not used as Ulfur branding.

## uBlock Origin (GPL-3.0)
uBlock Origin is bundled as a built-in extension. Its complete corresponding source is included in this repository
under `app/src/main/assets/extensions/ublock/`, and upstream at https://github.com/gorhill/uBlock. Ulfur's addition
to it, `beast-bridge.js`, is also GPL-3.0. Anyone who redistributes an APK containing uBlock Origin must follow the
GPLv3 for that component, including making this source available.

## Readability.js (Apache-2.0)
Bundled unmodified from https://github.com/mozilla/readability.
