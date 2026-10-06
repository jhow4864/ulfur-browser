/* Ulfur Site Prefs - forced dark core (pure functions, no browser APIs).
 * Loaded as a content script before content.js, in the background page, and as a CommonJS module by the
 * node unit tests (app/src/test/js/dark-core.test.js). */
"use strict";

const ULFUR_DARK_CLASS = "ulfur-force-dark";
const ULFUR_DARK_STYLE_ID = "ulfur-force-dark-style";

/* Designer's SPEC.md recipe. invert(.93) lands white near #121212 (close to the app's bg). A filter on the root
 * element also paints the canvas and doesn't break position:fixed (the root is exempt), so sticky headers survive.
 * SPEC's extra `background:#fff` on html is left out on purpose: Gecko already inverts the canvas with the root
 * filter (checked in Firefox), and forcing an html background would stop a body background from propagating to
 * the canvas. Media is inverted back so photos and video keep their colours; anything already inside a
 * re-inverted element is skipped so it isn't flipped twice (<picture> is left out too: its <img> is re-inverted). */
const ULFUR_DARK_CSS =
  "html." + ULFUR_DARK_CLASS + "{filter:invert(.93) hue-rotate(180deg)!important}" +
  "html." + ULFUR_DARK_CLASS + " :is(img,video,canvas,svg image,iframe,embed,object,[style*=\"background-image\"])" +
  ":not(:is(img,video,canvas,iframe,embed,object,[style*=\"background-image\"]) *)" +
  "{filter:invert(1) hue-rotate(180deg)!important}";

/** Below this background luminance a page counts as already dark (SPEC: about 0.2). */
const ULFUR_DARK_LUMINANCE = 0.2;

/** Parses a computed CSS colour ("rgb(1, 2, 3)", "rgba(1, 2, 3, 0.5)", "rgb(1 2 3 / 50%)", "transparent", "#fff"). */
function parseColor(s) {
  if (!s) return null;
  s = String(s).trim().toLowerCase();
  if (s === "transparent") return { r: 0, g: 0, b: 0, a: 0 };
  let m = s.match(/^#([0-9a-f]{3,8})$/);
  if (m) {
    let h = m[1];
    if (h.length === 3 || h.length === 4) h = h.split("").map(c => c + c).join("");
    if (h.length !== 6 && h.length !== 8) return null;
    const n = i => parseInt(h.substr(i, 2), 16);
    return { r: n(0), g: n(2), b: n(4), a: h.length === 8 ? n(6) / 255 : 1 };
  }
  m = s.match(/^rgba?\(([^)]*)\)$/);
  if (!m) return null;
  const parts = m[1].split(/[\s,\/]+/).filter(Boolean);
  if (parts.length < 3) return null;
  const ch = v => v.endsWith("%") ? parseFloat(v) * 2.55 : parseFloat(v);
  const r = ch(parts[0]), g = ch(parts[1]), b = ch(parts[2]);
  let a = 1;
  if (parts.length >= 4) a = parts[3].endsWith("%") ? parseFloat(parts[3]) / 100 : parseFloat(parts[3]);
  if ([r, g, b, a].some(x => isNaN(x))) return null;
  return { r, g, b, a };
}

/** WCAG relative luminance, 0 (black) .. 1 (white). */
function luminance(c) {
  const f = v => { v = Math.max(0, Math.min(255, v)) / 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
}

/** True when the root's color-scheme lets the page render dark ("dark", "light dark", "only dark"). */
function schemeAllowsDark(colorScheme) {
  return /\bdark\b/.test(String(colorScheme || "").toLowerCase());
}

/**
 * Is the page already dark, so forced dark should step aside? True when the visible background (body, else html)
 * has luminance below [ULFUR_DARK_LUMINANCE], when nothing is painted and the page declares a dark colour scheme
 * (CSS color-scheme or <meta name="color-scheme">; we ask for dark, so it renders dark), or when the text is light
 * (a dark design over an image). With no background at all the default canvas is white, so the page is light.
 * @param {{htmlBg?: string, bodyBg?: string, colorScheme?: string, metaScheme?: string, textColor?: string}} s
 */
function pageIsDark(s) {
  if (schemeAllowsDark(s.colorScheme) || schemeAllowsDark(s.metaScheme)) return true;
  const opaque = c => c && c.a >= 0.5;
  const body = parseColor(s.bodyBg), html = parseColor(s.htmlBg);
  const bg = opaque(body) ? body : opaque(html) ? html : { r: 255, g: 255, b: 255, a: 1 };
  if (luminance(bg) < ULFUR_DARK_LUMINANCE) return true;
  const text = parseColor(s.textColor);
  return !!(text && text.a >= 0.5 && luminance(text) > 0.6);
}

/** "www.Example.com" -> "example.com" (same rule as the zoom map). */
function darkHostKey(host) {
  return String(host || "").toLowerCase().replace(/\.$/, "").replace(/^www\./, "");
}

/** Is [host] one of the user's "don't darken" sites (registrable domains), or a subdomain of one? */
function isDarkOffSite(host, offSites) {
  const h = darkHostKey(host);
  if (!h) return false;
  for (const site of offSites || []) {
    const s = darkHostKey(site);
    if (s && (h === s || h.endsWith("." + s))) return true;
  }
  return false;
}

/** Final answer for a page: forced dark is on, the site isn't in the exception list and the host wasn't already
 * found dark this session ([alreadyDark]: a Set of hostnames, or undefined). */
function darkFor(host, state, alreadyDark) {
  if (!(state && state.enabled) || isDarkOffSite(host, state && state.off)) return false;
  return !(alreadyDark && alreadyDark.has(darkHostKey(host)));
}

if (typeof module === "object" && module.exports) {
  module.exports = {
    ULFUR_DARK_CLASS, ULFUR_DARK_STYLE_ID, ULFUR_DARK_CSS, ULFUR_DARK_LUMINANCE,
    parseColor, luminance, schemeAllowsDark, pageIsDark, darkHostKey, isDarkOffSite, darkFor,
  };
}
