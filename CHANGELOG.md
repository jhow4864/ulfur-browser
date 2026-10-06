# Changelog

Ulfur was called Beast Browser up to 2.3.8. Version tags are `vX.Y.Z`. Each release ships one
`Ulfur-<version>-arm64.apk`, signed with the Ulfur release key.

## Unreleased (2.8)

**New features**
* **Theme picker.** Settings › Appearance › Theme & accent has eight preset themes: Blood Moon, Frost, Toxic,
  Ember, Void, Gold, Sakura and Ash. A live preview shows each one before you leave the screen, and Dark / Light /
  System mode moved there too. Realm accents: Work follows Auto (Frost, or Ember while the theme is Frost) or gets
  its own preset, and Ghost is always dim. Blood Moon is the default, and earlier accents carry over: GX Red →
  Blood Moon, Cyber Cyan → Frost, Toxic Green → Toxic, Lava Orange → Ember, Ultraviolet → Void.
* **Animated wolf.** The new-tab page has a full-colour wolf in your theme's colours. It breathes gently, its
  ring fills while a search you started from the new-tab page loads, and its eye flashes when the page is ready.
  Ghost gets a dimmer, slower wolf with no flash. Turn the motion off in Settings › Appearance › Motion; it also
  stays still while Android's "Remove animations" is on, though the ring still shows progress.

**Fixes**
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
