// Reader view relay: the reader page's fallback route to the app through background.js and the beast_helper port.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const EXT = path.join(__dirname, "../../main/assets/extensions/beast-helper");
const BASE = "moz-extension://abc-123/";

function load() {
  const listeners = {};
  const ev = name => ({ addListener: (fn) => { listeners[name] = fn; } });
  const posted = [];
  const browser = {
    runtime: {
      connectNative: () => ({ onMessage: ev("port.msg"), onDisconnect: ev("port.dc"), postMessage(m) { posted.push(JSON.parse(JSON.stringify(m))); } }),
      onMessage: ev("runtime.onMessage"),
      getURL: p => BASE + p,
    },
    webRequest: { onBeforeRequest: ev("a"), onHeadersReceived: ev("b"), filterResponseData: () => ({}) },
    tabs: { onRemoved: ev("c"), sendMessage: () => Promise.resolve(), executeScript: () => Promise.resolve([]) },
  };
  const ctx = vm.createContext({ browser, console, URL, TextDecoder, setTimeout, clearTimeout, Promise });
  for (const f of ["media/sniffer-core.js", "background.js"]) vm.runInContext(fs.readFileSync(path.join(EXT, f), "utf8"), ctx, { filename: f });
  return { listeners, posted, send: (msg, sender) => listeners["runtime.onMessage"](msg, sender), fromApp: m => listeners["port.msg"](m) };
}

const READER = { url: BASE + "reader/reader.html#id=abc&url=https%3A%2F%2Fwww.bbc.co.uk%2Fnews", tab: { id: 4, incognito: false } };

test("reader page request goes over the beast_helper port and the app's reply comes back", async () => {
  const b = load();
  const p = b.send({ type: "readerRelay", msg: { type: "getArticle", id: "abc" } }, READER);
  const out = b.posted.find(m => m.type === "readerRelay");
  assert.ok(out, "relayed to the app");
  assert.deepEqual(out.msg, { type: "getArticle", id: "abc" });
  assert.equal(out.private, false);
  await b.fromApp({ type: "readerReply", rid: out.rid, reply: { ok: true, article: { title: "T" } } });
  const r = await p;
  assert.equal(r.ok, true);
  assert.equal(r.article.title, "T");
  // the reply is not answered as an unknown request
  assert.ok(!b.posted.some(m => m.error && /unknown type/.test(m.error)));
});

test("app error rejects the relayed request", async () => {
  const b = load();
  const p = b.send({ type: "readerRelay", msg: { type: "getArticle", id: "x" } }, { ...READER, tab: { id: 5, incognito: true } });
  const out = b.posted.find(m => m.type === "readerRelay");
  assert.equal(out.private, true);
  await b.fromApp({ type: "readerReply", rid: out.rid, error: "boom" });
  await assert.rejects(p, /boom/);
});

test("only Beast Helper's own reader page may use the relay", async () => {
  const b = load();
  const r1 = await b.send({ type: "readerRelay", msg: { type: "saveArticle", id: "abc" } }, { url: "https://evil.example/", tab: { id: 1 } });
  const r2 = await b.send({ type: "readerRelay", msg: { type: "getArticle" } }, { url: BASE + "other.html", tab: { id: 1 } });
  assert.equal(r1.ok, false);
  assert.equal(r2.ok, false);
  assert.ok(!b.posted.some(m => m.type === "readerRelay"));
});
