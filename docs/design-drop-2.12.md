# Design drop 2.12 — Site permissions dashboard + quick-pick icons

## A. Permissions dashboard
Entry points: Settings > Privacy > "Site permissions"; and long-press shield > "Permissions for this site".

### Screen 1: Site permissions
- Top: segmented filter chips: All · Camera · Mic · Location · Notifications · Pop-ups · Downloads · Clipboard.
- Search field (filters by domain).
- List, sorted by most recently used. Row (72dp): favicon 24dp (letter tile fallback), domain (body), and a row of small granted-permission glyphs (16dp, max 5, then "+n"). Chevron right.
- Empty state: shield glyph + "No site has asked for anything yet."
- Overflow: "Reset all permissions" (danger, confirm dialog: "Reset all? Sites will ask again.").

### Screen 2: Per-site detail
- Header: favicon 40dp, domain, "Last visited <relative time>".
- One row per permission the site has requested: icon, name, state as a 3-way dropdown: Allow · Ask · Block. Allowed rows show "Used <relative time>" if known.
- Footer buttons: "Reset this site" (text, danger colour) and "Clear site data" (secondary).
- Revoking: instant, Snackbar "Camera blocked for <domain>" with Undo (10 s), matching element-picker undo.

### Rules
- http and https origins are separate rows; the http row shows "http://" before the domain plus the not-secure glyph.
- Private/Ghost tab grants are session-only and never appear here (state "Session only" note in footer text).
- Contrast >= 4.5:1 for glyphs and state labels in all themes; state also shown by text, never colour alone.
- Location "Allow" glyph uses accent colour; Block uses outline-crossed glyph (shape, not just colour).

## B. Quick-pick icons on home
- Tile 56dp, radius 16dp. Icon 32dp centred on a surface-variant plate; label below (1 line, ellipsised).
- Source order: site manifest icon (>=96px) > apple-touch-icon > favicon >=32px > letter tile (existing).
- Icons <32px or blurry when upscaled: use the letter tile instead.
- Cache to disk, refresh weekly; never fetch in Private/Ghost.
- Transparent or very dark icons on dark themes: add a 1dp outline plate so they stay visible (3:1 non-text contrast).
- Theme packs: plate takes 85% opacity surface so the home image shows through.
