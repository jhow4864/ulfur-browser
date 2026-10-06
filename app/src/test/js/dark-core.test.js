// Run: node --test app/src/test/js/   (also run from ExtensionJsTest during ./gradlew testDebugUnitTest)
"use strict";
// 2.5 forced dark: pure helpers shared by the beast-siteprefs content script and background page.
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const d = require(path.join(__dirname, "../../main/assets/extensions/beast-siteprefs/dark-core.js"));

test("CSS follows the SPEC recipe and re-inverts media once", () => {
  assert.match(d.ULFUR_DARK_CSS, /html\.ulfur-force-dark\{filter:invert\(\.93\) hue-rotate\(180deg\)!important\}/);
  for (const sel of ["img", "video", "canvas", "svg image", "iframe", "embed", "object", '[style*="background-image"]']) {
    assert.ok(d.ULFUR_DARK_CSS.includes(sel), sel);
  }
  assert.match(d.ULFUR_DARK_CSS, /:not\(:is\([^)]*\) \*\)/); // nested media isn't flipped twice
  assert.ok(!/picture/.test(d.ULFUR_DARK_CSS));
  assert.ok(!/background:#fff/.test(d.ULFUR_DARK_CSS));
});

test("parseColor understands computed colours", () => {
  assert.deepStrictEqual(d.parseColor("rgb(255, 255, 255)"), { r: 255, g: 255, b: 255, a: 1 });
  assert.deepStrictEqual(d.parseColor("rgba(0, 0, 0, 0)"), { r: 0, g: 0, b: 0, a: 0 });
  assert.deepStrictEqual(d.parseColor("rgb(10 20 30 / 50%)"), { r: 10, g: 20, b: 30, a: 0.5 });
  assert.deepStrictEqual(d.parseColor("transparent"), { r: 0, g: 0, b: 0, a: 0 });
  assert.deepStrictEqual(d.parseColor("#fff"), { r: 255, g: 255, b: 255, a: 1 });
  assert.strictEqual(d.parseColor("canvas"), null);
  assert.strictEqual(d.parseColor(""), null);
});

test("luminance spans 0..1", () => {
  assert.strictEqual(d.luminance({ r: 0, g: 0, b: 0 }), 0);
  assert.ok(Math.abs(d.luminance({ r: 255, g: 255, b: 255 }) - 1) < 1e-9);
});

test("light pages get darkened", () => {
  assert.strictEqual(d.pageIsDark({ htmlBg: "rgba(0, 0, 0, 0)", bodyBg: "rgba(0, 0, 0, 0)", colorScheme: "normal", textColor: "rgb(0, 0, 0)" }), false);
  assert.strictEqual(d.pageIsDark({ htmlBg: "rgba(0, 0, 0, 0)", bodyBg: "rgb(245, 245, 245)", colorScheme: "normal", textColor: "rgb(33, 33, 33)" }), false);
  assert.strictEqual(d.pageIsDark({ htmlBg: "rgb(255, 255, 255)", bodyBg: "rgba(0, 0, 0, 0)", textColor: "rgb(20, 20, 20)" }), false);
  assert.strictEqual(d.pageIsDark({ bodyBg: "rgb(136, 136, 136)" }), false); // mid grey is above 0.2
});

test("pages that are already dark are left alone", () => {
  assert.strictEqual(d.pageIsDark({ bodyBg: "rgb(18, 18, 18)" }), true);
  assert.strictEqual(d.pageIsDark({ htmlBg: "rgb(13, 17, 23)", bodyBg: "rgba(0, 0, 0, 0)" }), true);
  assert.strictEqual(d.pageIsDark({ colorScheme: "light dark" }), true);
  assert.strictEqual(d.pageIsDark({ colorScheme: "normal", metaScheme: "dark light" }), true);
  assert.strictEqual(d.pageIsDark({ bodyBg: "rgba(0, 0, 0, 0)", textColor: "rgb(240, 240, 240)" }), true);
  // body wins over html when it's opaque
  assert.strictEqual(d.pageIsDark({ htmlBg: "rgb(255, 255, 255)", bodyBg: "rgb(20, 20, 20)" }), true);
  assert.strictEqual(d.pageIsDark({ htmlBg: "rgb(20, 20, 20)", bodyBg: "rgb(255, 255, 255)" }), false);
  assert.strictEqual(d.pageIsDark({ colorScheme: "light" }), false);
});

test("exceptions match the registrable domain and its subdomains", () => {
  const off = ["wikipedia.org", "bbc.co.uk"];
  assert.ok(d.isDarkOffSite("en.m.wikipedia.org", off));
  assert.ok(d.isDarkOffSite("www.bbc.co.uk", off));
  assert.ok(d.isDarkOffSite("BBC.CO.UK.", off));
  assert.ok(!d.isDarkOffSite("notwikipedia.org", off));
  assert.ok(!d.isDarkOffSite("", off));
  assert.ok(!d.isDarkOffSite("example.com", undefined));
});

test("darkFor combines the switch, the exceptions and the already-dark cache", () => {
  const state = { enabled: true, off: ["github.com"] };
  assert.strictEqual(d.darkFor("example.com", state), true);
  assert.strictEqual(d.darkFor("gist.github.com", state), false);
  assert.strictEqual(d.darkFor("example.com", { enabled: false, off: [] }), false);
  assert.strictEqual(d.darkFor("example.com", null), false);
  assert.strictEqual(d.darkFor("www.example.com", state, new Set(["example.com"])), false);
  assert.strictEqual(d.darkFor("example.com", state, new Set(["other.com"])), true);
});
