# Ulfur theme packs (`.ulfurtheme`, schema 1)

Added in 2.9.0. Parser: `app/src/main/java/com/jamhowman/beastbrowser/data/ThemePack.kt` (`ThemePackParser`).

## File
Either:
- a **zip** named `*.ulfurtheme` containing `theme.json` and, optionally, `README.txt` and `LICENSE.txt` (nothing
  else, flat, no folders), or
- the bare **`theme.json`**.

## theme.json
```json
{
  "format": "ulfurtheme",
  "schema": 1,
  "name": "Moss",
  "author": "Jam",
  "version": "1.0",
  "base": "toxic",
  "colors": {
    "dark":  { "accent": "#4CAF50", "accentStart": "#66BB6A", "accentEnd": "#43A047",
               "onAccent": "#0E0E12", "accentText": "#81C784", "surfaceTint": "#19372B", "eye": "#C8FF2E" },
    "light": { "accentText": "#1B5E20", "surfaceTint": "#E6FFF1" }
  }
}
```

| Key | Required | Rule |
|---|---|---|
| `format` | yes | exactly `"ulfurtheme"` |
| `schema` | yes | integer `1` (anything else is refused, so a future schema can't be misread) |
| `name` | yes | 1–48 chars, no control or bidi-override characters |
| `author`, `version` | no | ≤ 48 chars, same rules |
| `base` | no | a preset key (`blood_moon`/`red`, `frost`, `toxic`, `ember`, `void`, `gold`, `sakura`, `ash`); default Blood Moon. XML-styled parts of the UI follow this preset. |
| `colors.dark` / `colors.light` | at least one colour overall | objects of colour tokens below |

Colour tokens (all opaque `#RRGGBB`; anything else rejects the pack): `accent` (solid accent: icons, outlines),
`accentStart` / `accentEnd` (135° gradient on filled buttons), `onAccent` (text on accent fills), `accentText`
(accent used as text on backgrounds), `surfaceTint` (selected containers), `eye` (wolf eye).

Missing colours: `accent`, `accentStart`, `accentEnd`, `onAccent` and `eye` fall back to the other mode's value, then
to the base preset; `accentText` and `surfaceTint` fall back to the base preset for that mode. The preview tags these
"default". Unknown keys (top level or colour tokens) are ignored. Duplicate JSON keys: last one wins.

## Limits (all enforced on the bytes actually read/inflated, never on zip headers)
- whole file ≤ 256 KiB; `theme.json` ≤ 64 KiB; all entries together ≤ 512 KiB uncompressed
- ≤ 8 entries; per-entry and whole-archive compression ratio ≤ 100:1
- entry names: plain ASCII file names only; `..`, `/`, `\`, `:`, leading `.`, directories and duplicates are refused
- JSON nesting depth ≤ 8, UTF-8 only, a single JSON object with nothing after it

Errors map to: "This file isn't a theme pack" (not a zip/JSON, broken JSON, wrong format/schema, bad colour),
"This pack is too large" (any size/ratio/count cap), "This pack contains files Ulfur doesn't allow" (names).

## Storage and apply
Nothing is written until Apply. Apply stores a canonical re-serialised `theme.json` (never the imported file) at
`filesDir/theme-packs/<id>.json` (id = first 16 hex chars of its SHA-256), sets `accent` to the base preset and
`theme_pack` to the id. Undo (10 s) restores both prefs exactly (including "absent") and removes the file if Apply
added it. The pack recolours the Play theme; Work, Ghost and the private-mode override are unchanged.

## Contrast check
WCAG 2.x ratio < 4.5:1 on: `onAccent` on `accent`, on `accentStart` and on `accentEnd`; `accentText` on Ulfur's `bg`
and `surface` for that mode. Computed from the same palette Apply paints with. Warning only.
