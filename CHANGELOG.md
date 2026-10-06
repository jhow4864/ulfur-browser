# Changelog

Ulfur was called Beast Browser up to 2.3.8. Version tags are `vX.Y.Z`. Each release ships one
`Ulfur-<version>-arm64.apk`, signed with the Ulfur release key.

## Unreleased

**Shields**
* Cookie banners are now hidden by default, using uBlock Origin's own "EasyList/uBO – Cookie Notices" lists
  (switched off in stock uBO). It hides the banner rather than clicking accept, so sites treat you as never having
  agreed. A few sites lock scrolling or hold back videos, maps or comments until you answer the banner.
* New "Hide cookie banners on this site" switch in the Shields sheet lets banners through on one site while the rest
  of Shields stays up. It uses uBlock Origin's per-site cosmetic filtering switch, so uBO also stops hiding other page
  clutter on that site; ads and trackers are still blocked. Switch it off everywhere in Settings > Shields > Hide
  cookie banners.

**Save as PDF**
* New "Save as PDF" item in the menu saves the page as it looks now into Downloads/Ulfur, using GeckoView's built-in
  printer. In Reader view it saves the clean article. Private and Ghost tabs save into the private downloads vault
  instead, so the file never shows up in Gallery or Files.

**Media Radar and HLS downloads**
* AES-128 HLS streams are no longer listed by Media Radar or saved by the downloader. Ulfur treats them like
  SAMPLE-AES: it does not fetch the playlist key and does not decrypt anything. Plain MP4/WebM and unencrypted HLS
  still work. Ulfur 2.6.0 and earlier still list and save AES-128 HLS; this change applies from this release on.

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
