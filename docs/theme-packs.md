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

---

# Schema 2 (2.10.0): surfaces, text and images

Schema 1 is unchanged: same keys, the same limits (256 KiB file, 512 KiB contents) and the same id. Surface keys in a
schema 1 pack are ignored as unknown keys, and an `images/` entry in one is refused.

## New colour keys (`colors.dark` / `colors.light`, `#RRGGBB`)
`background`, `surface`, `surfaceVariant`, `text`, `textSecondary`, `toolbar`, `onToolbar`, `divider`. These apply per
mode with no fallback to the other mode. A missing one uses Ulfur's own colour for that mode. `toolbar` defaults to
`background`, `onToolbar` to `text`, and `divider` to `text` at 12%.

## Images
```json
"images": {
  "home":     { "file": "images/home.jpg", "fit": "cover", "focus": { "x": 0.5, "y": 0.4 },
                "scrim": 0.3, "scrimAuto": true, "scrimMax": 0.85 },
  "toolbar":  { "file": "images/strip.webp" },
  "switcher": { "file": "images/tabs.png" },
  "header":   { "file": "images/header.png", "focus": { "x": 0.3, "y": 0.6 } }
}
```
- `fit`: only `"cover"`. `focus` x/y: 0..1 (default 0.5/0.5). `scrim`: 0..0.9 (default 0). It's drawn in the slot's
  backing colour: `background` for home and switcher, `toolbar` for toolbar and header.
- `scrimAuto` (default true): Ulfur raises the scrim in 0.01 steps up to `scrimMax` (0..0.9, default 0.85, and at
  least `scrim`) until the text reaches 4.5:1. If it still can't, the preview warns.
- Images must sit in `images/` (one level deep, plain names), each referenced exactly once. Unreferenced images are
  refused, and unknown slot names are ignored.
- **Formats:** PNG, JPEG or WebP, detected from the file's bytes (the extension doesn't matter). SVG, GIF, HEIC/AVIF
  and **animated** PNG/WebP are refused, not flattened.
- **Import:** Ulfur reads the size from the file header and again with `inJustDecodeBounds` before decoding. It then
  decodes, shrinks the image to the slot's size and re-encodes it (PNG if it has transparency, otherwise JPEG at
  quality 90). That drops EXIF, GPS, ICC, XMP and every original byte. JPEG EXIF orientation is not applied, so bake
  the rotation into the pixels.

## Slots (to match Designer's provisional slots; text zones are fractions of the *view*)
| Slot | Deliver (px) | Aspect | Kept up to (long side) | Text zones (view) | Where it shows |
|---|---|---|---|---|---|
| `home` | 1080×2400 | 9:20 portrait | 2400 | top 0–12%; 22–70% band; full width. Bottom 30% is safe for figures | new tab / speed dial |
| `toolbar` | 1440×256 | 45:8 | 1440 | the whole strip (texture only) | address bar strip |
| `header` | 1440×480 | 3:1 | 1440 | bottom-left third: x 0–33.3%, y 66.7–100% | Settings header |
| `switcher` | 1080×2400 | 9:20 | 2400 | top 12% ("Tabs" title) | tab switcher background (extra slot, not in Designer's list) |

Because "cover" crops differently on other screens, the readability check maps each text zone through the crop for
several screen sizes (phones 1080×1920/2400, 1440×3200, tablet 1600×2560, landscape 2400×1080) and checks everything
that can end up under text. With the default centre focus, the parts of the image that can sit under text are:
- home: top zone y 0–42% in landscape (0–12% on portrait phones), band y 22–70%. **y 70–100% is never under text.**
- toolbar: all of it.
- header: x 0–33.3%, y 60–100%.

## Limits (schema 2)
- file ≤ 8 MiB; `theme.json` ≤ 64 KiB; each image ≤ 2 MiB; ≤ 4 images; all entries together ≤ 6 MiB inflated;
  ≤ 8 entries (theme.json, README.txt, LICENSE.txt, `images/` + images)
- image ≤ 4096 px per side and ≤ 16,000,000 px (4096×3906 passes, 4096×3907 is refused)
- text entries keep the 100:1 compression-ratio check. Images are exempt, because their inflated size and pixel
  count are already capped.

## Readability check
Colour pairs: everything from schema 1, plus text, text and secondary text on background/surface/surfaceVariant, and
onToolbar on toolbar. It also checks **Ulfur's own text colour** on the pack's background and surface, because some
parts are still styled in XML (see below).

Text over an image: the image is sampled (192 px on the long side, nearest-neighbour, so no averaging) under every text
zone after the scrim. The **darkest and the lightest** point are each checked against the text colour, and the worse
of the two must be ≥ 4.5:1.

## Where v2 colours apply (Play only; private tabs, Work and Ghost unchanged; paused by safe start)
- **Reached:** the window background of every Ulfur screen; the main toolbar (colour or image) and the bottom bar;
  address-bar text and hint; the home page background (colour or image), wordmark, search pill, stats card fill and
  headings; the tab switcher background (colour or image); the bottom divider; the Settings header (colour or image,
  title colour).
- **Not reached yet (styled in layout XML):** speed-dial tile labels, text inside cards and tab cards, bottom sheets,
  dialogs, the menu sheet rows, preference rows (they keep Ulfur's text colours, which is why the check above exists),
  and the Library/Downloads/Passwords toolbars.
