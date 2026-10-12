# Ulfur Browser 2.9 design drop: theme-pack import + safe start

Use existing tokens only (gx_red #FA1E4E, surface2 etc.), 48dp targets, and the GX look.

## 1. Theme-pack import (.ulfurtheme)
**Entry:** in Settings › Appearance, an "Import theme pack" row under the icon picker. Opening a .ulfurtheme file from another app also lands here.

**Preview screen** (full screen, nothing is applied yet):
- Top: the pack name, author and version, with "Unverified pack" in text_dim (every pack is someone else's file).
- Middle: a live mock-up of Ulfur at about 70% scale, with the toolbar, a tab card, the address bar, the menu sheet and a button, all drawn in the pack's colours. Add a light/dark toggle if the pack has both.
- A swatch row of every colour in the pack, each labelled with what it colours. Tapping a swatch highlights where it's used in the mock-up.
- If a colour is missing, the Ulfur default fills in, marked with a small "default" tag.
- **Contrast check:** if any text-on-background pair is below 4.5:1, show an amber notice: "Some text may be hard to read." It's a warning only, so Apply still works.
- Bottom bar: Cancel (text button) and Apply (filled, gx_red).

**After Apply:** the theme switches with a 200ms crossfade, and a snackbar says "Theme applied" with an Undo button for 10 s that restores the previous theme exactly.

**Bad packs** never reach the preview. A clear error sheet says what went wrong, in plain words: "This file isn't a theme pack", "This pack is too large", or "This pack contains files Ulfur doesn't allow". There's one OK button, and the current theme is untouched.

**Installed packs:** shown in Appearance as a horizontal row of theme cards (mini mock-ups, 96×64dp). Tap one to switch; long-press for Delete. Built-in themes can't be deleted.

## 2. Safe start
**Trigger:** two crashes on launch in a row (the counter resets after a clean launch).

**Safe start screen** (shows instead of restoring tabs):
- A wolf icon in text_dim, then the title "Ulfur closed unexpectedly" and the body "We've opened Ulfur with your tabs and extensions turned off, so you can get back in."
- A status list with a row each for Tabs (paused, not deleted), Extensions (including uBlock: off for now) and Theme (back to the default, if a theme pack was applied just before the crashes).
- Buttons:
  - "Restore everything" (filled, gx_red) turns it all back on exactly as it was.
  - "Continue in safe start" (outlined) keeps things off for this session.
  - "Share crash report" (text button) opens the existing CrashReportUi share sheet, with the report shown first so the user sees what gets shared.
- **While in safe start:** a slim amber bar sits under the address bar with "Safe start · Restore", and it can't be swiped away until the user picks.

**If it crashes again after Restore:** show the same screen with one extra line: "It crashed again after restoring. Try turning extensions back on one at a time." Add per-item toggles in that case.

## Strings
theme_import "Import theme pack", theme_unverified "Unverified pack", theme_default_tag "default", theme_low_contrast "Some text may be hard to read.", theme_apply "Apply", theme_applied "Theme applied", theme_bad_file "This file isn't a theme pack", theme_too_large "This pack is too large", theme_bad_contents "This pack contains files Ulfur doesn't allow", safe_title "Ulfur closed unexpectedly", safe_body "We've opened Ulfur with your tabs and extensions turned off, so you can get back in.", safe_restore "Restore everything", safe_continue "Continue in safe start", safe_share "Share crash report", safe_bar "Safe start", safe_again "It crashed again after restoring. Try turning extensions back on one at a time."
