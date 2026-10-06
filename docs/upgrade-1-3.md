# Upgrades 1–3 (theme chrome, accent leftovers, unlock-to-fill)

## 1. Light-theme address-bar white text

**Cause:** `MainActivity` keeps `uiMode` in `android:configChanges` (needed so Gecko sessions are not torn down). There was no `onConfigurationChanged`, so when night mode flipped the activity stayed alive with **inflated** night colours on `urlInput` / hints / icons (`text_primary` = `#F2F2F7`), while `styleAddressBar()` called `getColor(R.color.surface2)` which already resolved to the **new** light resource → white text on a light bar.

**Fix (option B — no recreate):**
- Keep `uiMode` in `configChanges` (sessions live on the Activity’s `Tab` list; recreate would orphan/`releaseSession` without closing and then reload URLs from prefs).
- Track `lastUiNightMask`.
- `onConfigurationChanged` + `onResume` call `maybeReapplyUiModeChrome`.
- `reapplyUiModeChrome()` re-applies bg / text / hint / icon tints, status/nav bar appearance, soft shape drawables, then `applyAccent()` → `styleAddressBar` / `refreshUi`.

**Verify:** Open a page → Settings → Appearance → Light (or system light). Address bar text and icons should be dark on the light chip immediately, without losing the tab session. Flip back to dark and confirm the reverse.

## 2. Accent leftovers

Hard-coded `@color/gx_red` in layouts/styles that are not solely owned by `applyAccent()` now use `?attr/colorPrimary` so accent overlays (and activities that only `theme.applyStyle(accent.overlay)`) pick up the chosen accent at inflate/bind time.

Changed: `BeastStatValue`, `item_library`, `item_download`, `item_speed_dial`, `item_tab_card`, `view_home`, `sheet_menu`, `sheet_shields`, `activity_main` tints/indicators.

Left alone: badge `#FFFFFF` text, theme base/default Red overlay definitions, `bg_badge` / `bg_swatch` (still mutated at runtime by `applyAccent` where needed).

**Verify:** Settings → Accent → Purple/Cyan/etc. Home stats, library letter tiles, download type chips, and sheets should follow the accent (not stay GX Red).

## 3. Unlock-to-fill

**Files:** `PasswordVault.metaForOrigin`, `VaultStorageDelegate`, `UnlockToFill`, layouts `sheet_unlock_fill` / `item_unlock_fill`, `ic_fingerprint`.

**Behaviour:**
- `onLoginFetch(domain)` while **locked** still returns an **empty** array to Gecko (no biometric on every focus).
- If `metaForOrigin(domain)` has entries and no unlock sheet is already showing, posts a bottom sheet: letter avatar + username + fingerprint icon + accent “Unlock to fill”.
- Tap → `PasswordVault.unlock(activity)`; on success toast *Unlocked — tap the password field again*; next fetch returns real logins.
- Debounced via `UnlockToFill.isShowing()` (no stacked sheets). Save path unchanged.

**Verify:** Save a login → lock vault (idle or Settings → Passwords → Lock) → focus a login field on that site → sheet appears → unlock → tap field again → fill works. Site with no saved login → no sheet.

## Build

This environment had no JDK/Android SDK (`java` missing, `ANDROID_HOME` empty). Source changes are complete; run locally:

```bash
./gradlew :app:assembleDebug
```

APK path when built: `app/build/outputs/apk/debug/app-debug.apk`.
