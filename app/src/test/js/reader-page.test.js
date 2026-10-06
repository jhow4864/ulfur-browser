// reader.js load path with a minimal DOM: a silent direct route must fall back to the relay, never hang on "Loading…".
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SRC = fs.readFileSync(path.join(__dirname, "../../main/assets/extensions/beast-helper/reader/reader.js"), "utf8");

function el(id) {
  return {
    id, hidden: false, textContent: "", dataset: {}, style: { setProperty() { } }, children: [],
    classList: { toggle() { } }, setAttribute() { }, addEventListener() { }, remove() { }, appendChild(c) { this.children.push(c); },
    get firstChild() { return null; },
  };
}

function run({ direct, relay, hash = "#id=abc&url=https%3A%2F%2Fexample.org%2Fa" }) {
  const els = {};
  const document = {
    documentElement: el("root"), title: "",
    getElementById: id => (els[id] = els[id] || el(id)),
    querySelectorAll: () => [],
    createElement: t => el(t),
    adoptNode: n => n,
  };
  class DOMParser { parseFromString() { return { querySelector: () => ({ setAttribute() { } }), body: { querySelectorAll: () => [], firstChild: null } }; } }
  const calls = [];
  const browser = { runtime: {
    sendNativeMessage: (app, msg) => { calls.push(["direct", msg.type]); return direct(msg); },
    sendMessage: (m) => { calls.push(["relay", m.msg.type]); return relay(m.msg); },
  } };
  const ctx = vm.createContext({ browser, document, DOMParser, location: { hash }, URL, URLSearchParams, setTimeout, clearTimeout, Promise, console, history: { back() { } } });
  vm.runInContext(SRC, ctx);
  return { els, calls };
}

const ARTICLE = { ok: true, saved: false, prefs: { size: 100, font: "sans", theme: "dark" }, article: { title: "Hello", url: "https://example.org/a", siteName: "Example", content: "<p>x</p>" } };
const wait = ms => new Promise(r => setTimeout(r, ms));

test("direct reply renders the article", async () => {
  const r = run({ direct: () => Promise.resolve(ARTICLE), relay: () => Promise.reject(new Error("unused")) });
  await wait(20);
  assert.equal(r.els.status.hidden, true);
  assert.equal(r.els.site.textContent, "Example");
  assert.deepEqual(r.calls, [["direct", "getArticle"]]);
});

test("direct route that never answers falls back to the relay", async () => {
  const r = run({ direct: () => new Promise(() => { }), relay: () => Promise.resolve(ARTICLE) });
  await wait(2700);
  assert.deepEqual(r.calls, [["direct", "getArticle"], ["relay", "getArticle"]]);
  assert.equal(r.els.status.hidden, true);
  assert.equal(r.els.title.textContent, "Hello");
});

test("both routes failing shows an error instead of Loading…", async () => {
  const r = run({ direct: () => Promise.reject(new Error("no")), relay: () => Promise.reject(new Error("no")) });
  await wait(30);
  assert.match(r.els.status.textContent, /couldn't reach Ulfur/);
  assert.equal(r.els.save.hidden, true);
});
