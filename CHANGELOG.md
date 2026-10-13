# Changelog

Ulfur was called Beast Browser up to 2.3.8. Version tags are `vX.Y.Z`. Each release ships one
`Ulfur-<version>-arm64.apk`, signed with the Ulfur release key.

## 2.12.0 (versionCode 33), site permissions + quick-pick icons

- **Site permissions** (Settings › Privacy & security, or long-press the shield): every site that has asked for something, most recently used first, with filter chips and search. Each site's page has an Allow · Ask · Block choice per permission, plus "Reset this site" and "Clear site data". Changes write Gecko's own stored permission, so they apply right away in open tabs, and you get 10 seconds to undo. Camera and mic choices are checked on every request. Private and Ghost choices only last for the session and are never shown or saved. Ulfur doesn't hold Android camera, mic or location access, so for those you can only choose Ask or Block.
- **Quick-pick icons**: home speed-dial tiles show the site's real icon (32dp on the tile plate) and fall back to the letter tile. Icons come over https only, are capped at 1 MB, are sent with no cookies, are cached on the phone and refreshed weekly. They are never fetched while the home page belongs to a Private or Ghost tab, and never for tiles added from one.

## 2.10.0 (versionCode 27), theme packs v2 (part 1)

- **Match toolbar to site colour** (Settings › General, off by default): the toolbar takes the page `<meta name="theme-color">` (honours `media`; beast-helper 1.3.0) or the manifest `theme_color`. Opaque colours only; ink flips white/near-black for ≥ 4.5:1; never in private tabs or Ghost; colours within ΔE 10 of the private toolbar are nudged away. Overrides a theme pack toolbar only while on. Fix: address text uses text-on-pill colour with packs.

* **Theme packs can now change backgrounds, surfaces and text colours** (schema 2) and carry up to four background
  images: home/new tab, the toolbar strip, the tab switcher and the Settings header. Images are drawn to fill their
  area around a focal point, under a dimming layer that Ulfur strengthens automatically when text would be hard to
  read.
* Images are checked before they're opened (PNG, JPEG or WebP only; nothing animated, no SVG, GIF or HEIC; at most
  4096 px per side and 16 megapixels), then re-encoded, so location and camera data never get stored.
* The preview shows the images in the mock-up, and the readability warning now covers text over images. It checks
  the darkest and lightest spots under the text, not an average.
* Existing accent-only packs (schema 1) work exactly as before. Private tabs, Work and Ghost keep their own look.

## 2.9.1 (versionCode 26)

* **Fix: no more false safe starts.** 2.9.0 counted every start of Ulfur's process, including background ones such as
  the speed-dial widget's half-hourly refresh, so two widget refreshes could open Ulfur in safe start. Now only
  launches you can see count: when Ulfur is started to show a screen (counted at once, so a crash during startup still
  counts) or when the first Ulfur screen opens in a process that started in the background. Background-only starts
  never touch the counter, and leaving Ulfur normally still resets it.
* Theme packs can only be opened through the system file picker or a file manager (content:// links); plain file
  paths are refused.

## 2.9.0 (versionCode 25)

* **Safe start.** If Ulfur crashes while starting twice in a row, it opens with your saved tabs and extensions
  (uBlock Origin) paused, and an imported theme pack switched back to its base preset. Nothing is deleted. Pick
  "Restore everything", "Continue in safe start" or "Share crash report"; the report is shown in full before it can be
  shared. A slim amber "Safe start · Restore" bar stays under the address bar until you restore. If it crashes again
  right after restoring, the screen says so and lets you turn tabs, extensions and the theme back on one at a time.
  Counting launch crashes works even with crash reports off; a launch counts as stable after the first page loads,
  12 s in the foreground, or when you leave the app normally.
* **Crash reports never include private browsing.** Titles and addresses from private tabs and Ghost are masked in
  any saved report, on top of the existing URL, e-mail and IP scrubbing.
* **Theme packs.** Settings › Appearance › Import theme pack (or open a `.ulfurtheme` file) shows a full preview
  first: a live mock-up of Ulfur in the pack's colours, a labelled swatch for every colour (with a "default" tag where
  the pack leaves one to Ulfur), a light/dark toggle when the pack has both, and a warning if any text would be below
  4.5:1 contrast. Nothing changes until you tap Apply, and Undo puts your previous theme back exactly for 10 s.
  Broken, oversized or unsafe files get a plain error and leave your theme alone. Installed packs show as cards under
  the import row; tap to switch, long-press to delete. Format: docs/theme-packs.md.

## 2.8.1 (versionCode 24)

* **Add to Home screen uses the site's real icon on more sites.** Ulfur now reads the icons the page itself links
  (`<link rel="icon">`, `apple-touch-icon` and `apple-touch-icon-precomposed`, including ones on other domains and CDNs)
  and picks the largest. BBC Weather, for example, now pins with its own 152 px icon instead of a "B" letter tile.
  Icons that redirect, are missing or won't load are skipped, SVG-only icons fall back to the next image, and a letter
  tile is still used when nothing is at least 96 px.
* Icons that aren't designed for adaptive masks now sit inside the launcher's safe zone on a background that matches
  the icon's corner colour (white if transparent), so circle and squircle masks no longer clip them. Maskable web app
  icons still fill the whole shape.

## 2.8.0 (versionCode 23)

* **Alternate app icons.** Settings › Appearance has an App icon picker with the brand Default icon plus eight colourways that match the accent themes (Blood Moon, Frost, Toxic, Ember, Void, Gold, Sakura, Ash).
* **Ulfur Monitor replaces Ulfur Control.** It still shows live CPU, memory and network use. The three "limit" sliders
  are gone because they never limited anything; they only changed the colour of the figures.
* The README now says Ulfur is arm64-only, and no longer lists a 32-bit build.
* **Video download button is back on by default.** When a page has a video Ulfur can save, a download button shows in
  the address bar again; tap it to save. You can turn it off in Settings › General › Video download button.
* **Media Radar is gone.** The full-screen radar overlay, its menu entry and the long-press on the download button
  have been removed. The download button and save sheet do the same job.
* **GeckoView 158.** The engine moves from GeckoView 157 to stable GeckoView 158 (158.0.20261007201254, Firefox 158),
  with its security fixes.
* **Add to Home screen makes real home-screen web apps** (#34, PR #35). Menu › Add to Home screen pins a shortcut with
  the site's own icon (its web app icon, apple-touch-icon or favicon, falling back to a letter tile). Sites with a web
  app manifest open in their own standalone window, and links outside the app open in a normal tab. If the launcher
  can't pin shortcuts, Ulfur explains why and offers App info. Adding the same site again updates its shortcut.
* Ulfur is now licensed under GPL-3.0.
* 2.8.0 is versionCode 23 and installs over the 2.8.0-pre1, pre2 and pre3 test builds (versionCodes 20 to 22).

## 2.7.1 (versionCode 19)

**New look**
* **New Ulfur logo.** The wolf is now a wolf howling against the moon. It replaces the old wolf everywhere: the app icon
  (including Android's themed icons), the animated wolf on the new-tab page, the dim Ghost wolf, the PRIVATE label and
  the small logo in tabs and Settings. It takes on your theme's colours in all eight themes, and the breathing,
  loading ring and eye flash work as before.

Nothing else has changed since 2.7.0.

## 2.7.0 (versionCode 18)

**New features**
* **Save as PDF.** A new menu item saves the page as it looks right now as a PDF in Downloads/Ulfur, and you get the
  usual "Downloaded … Open" message. In Reader view it saves just the clean article. Private and Ghost tabs save
  into the private vault instead, so the file never shows up in Gallery or Files.
* **Cookie banners hidden.** Shields now hides cookie consent banners by default, using uBlock Origin's own
  cookie-notice lists. It hides the banner rather than clicking accept, so sites treat you as never having agreed,
  and a few sites lock scrolling or hold back videos, maps or comments until you answer it. You can turn this off
  in Settings › Shields › Hide cookie banners.
* If a site acts up because its banner is hidden, open Shields on that page and turn off **Hide cookie banners on
  this site**. The page reloads with the banner back, and ad and tracker blocking stays on. uBlock Origin's other
  cosmetic cleanup also pauses on that site, and a few banners may stay hidden where the list blocks the consent
  script itself.
* **Private vault empty screen.** The empty private downloads vault now has an illustrated file-and-padlock picture
  in the vault's violet, matching Ulfur's other empty screens.

**Fixes**
* **Reader view no longer gets stuck on Loading….** In 2.6.0, Reader view could sit on Loading… on every site
  (BBC News and flashscore.com, for example). The article now opens within a few seconds. If it really can't be
  shown, you get an error and a link back to the original page instead of an endless Loading….

**Changes**
* **Encrypted HLS streams.** Media Radar no longer lists AES-128 HLS streams, and the downloader won't save them.
  Ulfur treats them like SAMPLE-AES: it doesn't fetch the key and doesn't decrypt anything. Plain MP4/WebM and
  unencrypted HLS still work as before.
* Ulfur is an independent browser built on Mozilla's GeckoView. It isn't made by, affiliated with or endorsed by
  Mozilla. The README, third-party notices and Media Radar docs now say this and describe what Media Radar does
  more accurately.

**Corrections to earlier notes**
* The 2.3.6 note below says HLS downloads included "AES-128-encrypted ones (DRM is never supported)". That was
  inaccurate: AES-128 is encryption, and those builds decrypted it when saving. From this release AES-128 HLS is
  refused.
* The 2.3.7 note says Media Radar "shows every stream on the page". It never did: it skips SAMPLE-AES and EME
  (Widevine, PlayReady, FairPlay) streams and the blocked streaming hosts, and from this release it also skips
  AES-128 HLS.
* Ulfur is an independent project. It embeds Mozilla's GeckoView library and is not affiliated with or endorsed by
  Mozilla. Firefox is a trademark of the Mozilla Foundation.

## 2.6.0 (versionCode 17)

**New features**
* **Weekly tracker tally.** Settings › Shields › Weekly tracker tally shows how many ads and trackers were blocked
  in the last 7 days, your top 5 sites and a count for each kind of tracker, with uBlock Origin's blocks on their
  own line. The tally is kept only on your phone: it stores just the site name, the kind of block and a daily count.
  Private tabs and Ghost are never counted, it isn't part of backups, and days older than 8 weeks are deleted.
  Clear tally empties it, and so do clearing browsing history and Clear data on exit.
* **Tracker shield.** The shield in the toolbar shows how much the page blocked: a plain shield when nothing was
  blocked, a count for 1 to 9, and a shield with a check and a badge in your theme's gradient for 10 or more.
* **Private mode you can't mistake.** Private tabs, Ghost and the private vault now have violet bars (dark violet
  in dark mode, pale violet in light mode). Private and Ghost tabs also get a violet stripe under the top bar and a
  wolf-eye **PRIVATE** label in the address bar. On plain http pages the label reads **NOT SECURE**. Your theme's
  accent colours stay as they are.
* **Theme picker.** Settings › Appearance › Theme & accent has eight preset themes: Blood Moon, Frost, Toxic,
  Ember, Void, Gold, Sakura and Ash. A live preview shows each one before you leave the screen, and Dark / Light /
  System mode moved there too. Realm accents: Work follows Auto (Frost, or Ember while the theme is Frost) or gets
  its own preset, and Ghost is always dim. Blood Moon is the default, and earlier accents carry over: GX Red →
  Blood Moon, Cyber Cyan → Frost, Toxic Green → Toxic, Lava Orange → Ember, Ultraviolet → Void.
* **Animated wolf.** The new-tab page has a full-colour wolf in your theme's colours. It breathes gently, its
  ring fills while a search you started from the new-tab page loads, and its eye flashes when the page is ready.
  Ghost gets a dimmer, slower wolf with no flash. Turn the motion off in Settings › Appearance › Motion; it also
  stays still while Android's "Remove animations" is on, though the ring still shows progress.
* **Crash reports (opt-in, off by default).** Turn on Settings › Crash reports › Save crash reports and, if Ulfur
  crashes, a short report is kept on your phone: Ulfur and Android versions, phone model, GeckoView version, time,
  thread and the stack trace. Reports never include web addresses, page titles, history or anything from private
  tabs, and web addresses in error messages are blanked out. Only the newest 5 are kept.
* Nothing is ever sent automatically. After a crash, the next start shows a notice where you can view the report,
  delete it, or tap **Report on GitHub** to open a pre-filled bug report that you can edit before submitting.
  Saved reports can also be viewed and deleted in Settings.
* Crashes of a web page's content process are noted too (without any details about the page). GeckoView's own
  crash reporter, which sends reports to Mozilla, stays off.
* **Move to Downloads from the private vault.** Use a file's ⋯ menu, or long-press to pick several and tap
  **Move to Downloads**. Files go to Downloads/Ulfur. Ulfur always asks first and warns that moved files show up in
  Gallery, Files and other apps without a fingerprint. Each copy is checked against the original before the vault
  copy is removed, and any file that can't be copied safely stays in the vault and is named in the result notice.
  Multi-select also lets you share or delete several vault files at once.

**Fixes**
* Restoring a backup or importing passwords no longer replaces a password you've changed since. The newer one is
  kept, and on undated Chrome, Edge or Brave exports the one on your phone wins. The summary says how many
  passwords on your phone were kept.
* Password changes waiting for your fingerprint are no longer lost if the vault locks first. They're saved after
  your next unlock, as long as Android doesn't close Ulfur before then.
* The private vault no longer asks for your fingerprint twice when you first open it.
* The all-time "blocked" counter no longer counts uBlock Origin's blocks twice when you move to another page, and
  the shield badge no longer shows the previous page's uBlock Origin number on the new page.
* Long download names keep their file extension, so the file still opens in the right app, and very long Chinese
  or emoji names can now be saved.
* Half-finished private downloads, left behind when Ulfur was closed or crashed mid-download, are now deleted at the
  next start instead of quietly taking up space.
* Android 11 and later: paused downloads no longer expire and get deleted by Android after a week, as long as you
  open Ulfur at least every few days. If the unfinished file is gone anyway, Resume starts the download over
  cleanly instead of failing. On Android 8 and 9, resuming a download whose unfinished file was deleted no longer
  produces a corrupt file.
* A failed update download no longer leaves the update stuck, offered again at every start even after "Skip this
  version", and no longer leaves a partial download behind. Unexpected errors show "Download failed" instead of
  crashing Ulfur.
* Android 14 and later: if you leave Ulfur while an update downloads, an "Update ready to install" notification now
  appears. Tap it to finish updating. Before, the install screen could silently fail to open.
* Accent-coloured text (home stats, download types, reading-list sites) is now readable in light mode.
* Android 8.0: the navigation bar stays black in light mode, so its buttons stay visible.

## 2.5.0 (versionCode 16)

**New features**
* **Picture-in-picture.** A fullscreen video keeps playing in a small window when you leave Ulfur, with play/pause
  and skip back/forward 10 s. There's also a PiP button in fullscreen. Switch it off in Settings › Browsing › Site content.
* **Secure DNS** (DNS over HTTPS), off by default. Choose Cloudflare, Quad9, NextDNS, AdGuard or your own https
  address, in Automatic mode (falls back to normal DNS) or Strict mode (never falls back).
* **Autoplay blocker.** Block audio only (the default), block all, or allow, with per-site exceptions from the
  "Autoplay blocked" notice or the Shields sheet.
* **Forced dark mode (beta).** Darkens sites that have no dark theme when "Prefer dark websites" is on, with a
  "Never force dark on" list and a Dark page tile in the menu.

**Fixes**
* Backups now include on/off settings; earlier versions left them out.
* Snackbar buttons (like Undo and Allow) are now readable on every accent colour.

## 2.4.1 (versionCode 15)

**Fixes**
* Restoring a backup now recreates missing bookmark folders, nested ones included, so bookmarks no longer land
  outside their folder.
* Restoring passwords asks for your fingerprint once when the vault is unlocked or doesn't exist yet. A locked
  vault still needs two prompts, now labelled "1 of 2" and "2 of 2", because each fingerprint only unlocks one
  read or one write.
* Buttons and badges on the red accent now use dark text. White on red was too low-contrast to read comfortably
  (3.9:1 against the 4.5:1 accessibility minimum, and dark text gives 4.9:1).

## 2.4.0 (versionCode 14): Beast Browser is now Ulfur

**New name and look**
* Beast Browser is now **Ulfur**, with a new wolf icon and an icon that follows your wallpaper colours on Android 13
  and later.
* New downloads go to `Downloads/Ulfur`. Files already in `Downloads/Beast` stay where they are and still open from
  the Downloads screen.

**New features**
* **Encrypted backup and restore** (Settings → Backup & restore). Choose any of passwords, bookmarks with folders,
  Speed Dial, the reading list and settings, and save them to one `.ulfur` file locked with your own passphrase
  (PBKDF2 with 600,000 rounds, then AES-256-GCM). Restoring merges without creating duplicates, and it also imports
  password exports (CSV) from Chrome and Firefox.
* **Automatic updates.** Ulfur checks this repo's releases about once a day, and there's a Check for updates option
  in Settings. Before installing, it checks the download is intact, is a newer version and is signed with the same key.

**Everything from Beast Browser 2.3.4 to 2.3.8**, rebuilt from scratch:
* 2.3.4: on-device page translation, a password generator, autofill straight after unlocking the vault, bookmark
  folders and new empty-screen illustrations
* 2.3.5: Beast Control (live CPU, memory and data use), tab groups, tab search and a Speed Dial widget
* 2.3.6: downloads of HLS video streams, including AES-128-encrypted ones (DRM is never supported)
* 2.3.7: Media Radar, which shows every stream on the page
* 2.3.8: Realms (Play, Work and Ghost) and Tab DNA

**Under the hood**
* Release builds now refuse to build without the real signing key, and check the finished APK's signature, so a
  build can never again go out signed with a temporary key.
* The internal package ID stays `com.jamhowman.beastbrowser`, so Ulfur installs as an update.

**Upgrading**
* From Beast Browser 2.3.3 or earlier: install over it and everything carries over.
* From 2.3.4 to 2.3.8 or the "1-3" build: those were signed with a temporary key by mistake, so uninstall first.
  This deletes the old app's data, and those versions have no export option.

**Known limits**
* Beast Control's sliders show warnings but don't limit anything yet.
