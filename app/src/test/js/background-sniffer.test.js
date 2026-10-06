// Drives the real background.js (+ sniffer-core.js) with a mocked WebExtension API.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const EXT = path.join(__dirname, "../../main/assets/extensions/beast-helper");

function load() {
  const listeners = {};
  const ev = name => ({ addListener: (fn) => { listeners[name] = fn; } });
  const sent = [];
  const filters = {};
  const browser = {
    runtime: {
      connectNative: () => ({ onMessage: ev("port.msg"), onDisconnect: ev("port.dc"), postMessage() { } }),
      onMessage: ev("runtime.onMessage"),
    },
    webRequest: {
      onBeforeRequest: ev("onBeforeRequest"),
      onHeadersReceived: ev("onHeadersReceived"),
      filterResponseData: (id) => (filters[id] = { write() { }, close() { } }),
    },
    tabs: {
      onRemoved: ev("tabs.onRemoved"),
      sendMessage: (tabId, msg) => { sent.push({ tabId, msg }); return Promise.resolve(); },
      executeScript: () => Promise.resolve([]),
    },
  };
  const ctx = vm.createContext({ browser, console, URL, TextDecoder, setTimeout, clearTimeout, Promise });
  for (const f of ["media/sniffer-core.js", "background.js"]) vm.runInContext(fs.readFileSync(path.join(EXT, f), "utf8"), ctx, { filename: f });
  const feed = (filter, text) => { filter.ondata({ data: new TextEncoder().encode(text).buffer }); filter.onstop(); };
  return { listeners, sent, filters, feed, last: () => JSON.parse(JSON.stringify(sent[sent.length - 1].msg)) };
}

const H = o => Object.entries(o).map(([name, value]) => ({ name, value }));
const MASTER = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"\n720/i.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360\n360/i.m3u8\n';

test("master playlist becomes one item per variant; variant fetches don't duplicate", () => {
  const b = load();
  b.listeners.onBeforeRequest({ tabId: 3, type: "main_frame", url: "https://news.example.org/story" });
  b.listeners.onHeadersReceived({ tabId: 3, requestId: "r1", type: "xmlhttprequest", statusCode: 200,
    url: "https://cdn.example.net/v/master.m3u8", responseHeaders: H({ "content-type": "application/x-mpegURL" }) });
  b.feed(b.filters.r1, MASTER);
  let items = b.last().items;
  assert.deepEqual(items.map(i => i.quality), ["720p", "360p"]);
  assert.equal(items[0].url, "https://cdn.example.net/v/720/i.m3u8");
  assert.equal(items[0].kind, "hls");
  assert.equal(items[0].master, "https://cdn.example.net/v/master.m3u8");
  assert.equal(items[0].bandwidth, 2800000);
  assert.equal(b.last().pageUrl, "https://news.example.org/story");
  // The player then loads the 720p media playlist: no extra "HLS" entry.
  b.listeners.onHeadersReceived({ tabId: 3, requestId: "r2", type: "xmlhttprequest", statusCode: 200,
    url: "https://cdn.example.net/v/720/i.m3u8", responseHeaders: H({ "content-type": "application/vnd.apple.mpegurl" }) });
  const before = b.sent.length;
  b.feed(b.filters.r2, "#EXTM3U\n#EXTINF:6,\na.ts\n");
  assert.equal(b.sent.length, before);
});

test("progressive file listed; DRM page and DRM CDN never listed", async () => {
  const b = load();
  b.listeners.onBeforeRequest({ tabId: 1, type: "main_frame", url: "https://example.org/watch" });
  b.listeners.onHeadersReceived({ tabId: 1, requestId: "p", type: "media", statusCode: 206, url: "https://files.example.org/a.mp4",
    responseHeaders: H({ "content-type": "video/mp4", "content-range": "bytes 0-1/10000000" }) });
  assert.deepEqual(b.last().items.map(i => [i.kind, i.bytes]), [["progressive", 10000000]]);

  b.listeners.onBeforeRequest({ tabId: 2, type: "main_frame", url: "https://www.youtube.com/watch?v=1" });
  b.listeners.onHeadersReceived({ tabId: 2, requestId: "y", type: "media", statusCode: 200, url: "https://files.example.org/b.mp4",
    responseHeaders: H({ "content-type": "video/mp4", "content-length": "10000000" }) });
  b.listeners.onHeadersReceived({ tabId: 1, requestId: "f", type: "media", statusCode: 200, url: "https://video.xx.fbcdn.net/c.mp4",
    responseHeaders: H({ "content-type": "video/mp4", "content-length": "10000000" }) });
  assert.ok(!b.sent.some(s => s.tabId === 2));
  assert.equal(b.last().items.length, 1);

  // EME reported by the content script: list emptied and stays empty
  const r = await b.listeners["runtime.onMessage"]({ type: "drm" }, { tab: { id: 1 } });
  assert.equal(r.ok, true);
  const g = await b.listeners["runtime.onMessage"]({ type: "getMedia" }, { tab: { id: 1 } });
  assert.equal(g.items.length, 0);
});

test("SAMPLE-AES stream dropped", () => {
  const b = load();
  b.listeners.onBeforeRequest({ tabId: 5, type: "main_frame", url: "https://example.org/" });
  b.listeners.onHeadersReceived({ tabId: 5, requestId: "d", type: "xmlhttprequest", statusCode: 200,
    url: "https://cdn.example.org/drm.m3u8", responseHeaders: H({ "content-type": "application/vnd.apple.mpegurl" }) });
  b.feed(b.filters.d, '#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://x",KEYFORMAT="com.apple.streamingkeydelivery"\n#EXTINF:6,\na.ts\n');
  assert.ok(!b.sent.some(s => s.msg.items.length > 0));
});
