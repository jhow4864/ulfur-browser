# Media sniffer contract (Beast 2.2)

## Goal
Detect plain progressive MP4/WebM and clear HLS media on the current page, and let the app show a Save sheet. **Never** offer downloads for DRM / ToS-sensitive hosts.

## Hard block (app-side, not optional)
`MediaSniffer.isDrmHost` rejects YouTube, Netflix, Disney+, Hulu, Prime Video, Max, Spotify, Twitch, TikTok, Instagram, Facebook, and related CDNs. The extension must also skip these hosts before messaging the app, but the app filter is the Play Store safety net.

## App API (`com.jamhowman.beastbrowser.media`)
- `DetectedMedia` — one stream (`url`, `mime`, `qualityLabel`, `width`/`height`, `bytes`, `kind` = PROGRESSIVE|HLS, `pageUrl`, `title`)
- `MediaSniffer.publish(session, items)` — replace the list for a tab (blocked hosts stripped)
- `MediaSniffer.clear(session)` — on navigation / tab close
- `MediaSniffer.forSession(session)` / `count` / `hasMedia`
- `MediaSniffer.addListener { session -> … }` — UI badge updates

## UI hook
Toolbar media badge (next to Shields) is visible when `hasMedia(current.session)`.
Tap → `MainActivity.onMediaBadgeTapped(session)` → Save sheet → `DownloadCenter.enqueue(url, isPrivate, referrer, mime)`.

Private tabs use the private download vault automatically via `isPrivate`.

## Extension (Beast Helper)
Push JSON over the existing native port, e.g.:
```json
{ "type": "media", "items": [ { "url": "...", "mime": "video/mp4", "quality": "720p", "w": 1280, "h": 720, "bytes": 123456, "kind": "progressive" } ] }
```
Clear on `pageshow` / location change. Do not report DRM hosts at all.

## v1 scope
- Progressive files and single-quality HLS fetch (Download Center already does Range)
- No muxing separate audio+video tracks yet
- No YouTube / DRM paths, even via deep link

## 2.2 additions (beast-helper extension side)
Additive only; nothing above changed.

**Where detection happens.** `assets/extensions/beast-helper/`:
- `media/sniffer-core.js` — pure logic (also unit-tested with `node --test app/src/test/js/`): `classifyResponse` (progressive ≥ 200 KB with status 200/206, or an HLS playlist; skips `.ts`/`.m4s` segments, DASH, and MSE `fetch`/XHR chunks), `parseM3U8` (master → variants, best first), `qualityLabel`, `isBlockedUrl`.
- `background.js` — `webRequest.onHeadersReceived` sniffer, kept per tab. HLS playlists are read with `filterResponseData` (passed through unchanged, up to 1 MB) and parsed. Each master variant becomes its own item. A media-only playlist becomes one item labelled "HLS". Media playlists already listed as a variant are skipped.
- `content.js` — forwards the full list over a per-session native port **`beast_tab`**, using the JSON format above plus the optional HLS fields `bandwidth`, `codecs`, `master`. It reads `<video>` resolution where it can.
- App receiver: `browser/HelperSessions.kt` maps the JSON to `DetectedMedia` and calls `MediaSniffer.publish(session, items)` (the full list each time). It calls `MediaSniffer.clear(session)` on DRM.

**DRM (two layers, kept in sync by `MediaSnifferSyncTest`).** The extension list `BEAST_DRM_HOSTS` must match `MediaSniffer.DRM_SUFFIXES`. Added hosts: `ytimg.com, amazonvideo.com, scdn.co, tv.apple.com, peacocktv.com, paramountplus.com, crunchyroll.com, ttvnw.net, itv.com, channel4.com`. `DRM_PATHS` adds `bbc.co.uk` + `/iplayer`, applied only when `isDrmHost` gets a full URL. Besides the host list, the extension drops:
- HLS streams with `SAMPLE-AES*` or a Widevine/PlayReady/FairPlay `KEYFORMAT`/`skd:` key
- any page that fires an EME `encrypted` event or has `mediaKeys` (the content script sends `{type:"drm"}`, and the app clears the list and ignores further media for that page)
- any page where Gecko asks for `PERMISSION_MEDIA_KEY_SYSTEM_ACCESS`

Clear AES-128 HLS is allowed.

**`DetectedMedia` new optional fields** (defaults keep old call sites compiling):
- `bandwidth: Long = -1` (HLS BANDWIDTH, bits/s)
- `masterUrl: String? = null` (the master playlist a variant came from)
- `codecs: String = ""`

For HLS items `url` is the **variant (media) playlist**, so a Save action fetches one quality. `id` is a stable hash of the url.
