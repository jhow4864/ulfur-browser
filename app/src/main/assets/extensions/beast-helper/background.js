/* Beast Browser helper - talks to the app over a GeckoView native messaging port. */
"use strict";
const NATIVE_APP = "beast_helper";
let port = null;
let retryDelay = 1000;

function reply(msg) { try { port && port.postMessage(msg); } catch (e) { } }

async function handle(msg) {
  const { id, type } = msg || {};
  try {
    if (type === "allowInsecure") {
      if (!browser.beastHttps) { throw new Error("experiment API unavailable"); }
      await browser.beastHttps.allowInsecure(String(msg.host), msg.private === true);
      reply({ id, ok: true });
    } else if (type === "isExempt") {
      const exempt = await browser.beastHttps.isExempt(String(msg.host), msg.private === true);
      reply({ id, ok: true, exempt });
    } else if (type === "ping") {
      reply({ id, ok: true, api: !!browser.beastHttps });
    } else {
      reply({ id, ok: false, error: "unknown type " + type });
    }
  } catch (e) {
    reply({ id, ok: false, error: String(e && e.message || e) });
  }
}

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    port = null;
    setTimeout(connect, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 30000);
    return;
  }
  port.onMessage.addListener(handle);
  port.onDisconnect.addListener(() => {
    port = null;
    setTimeout(connect, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 30000);
  });
  retryDelay = 1000;
  reply({ type: "hello", api: !!browser.beastHttps });
}
connect();

/* ======================================================================
 * Media sniffer (webRequest). Detections are kept per tab and pushed to that tab's content script,
 * which forwards them to the app over its per-session native port (so the app knows the GeckoSession).
 * DRM / ToS hosts (BEAST_DRM_HOSTS in media/sniffer-core.js) are dropped here, before anything reaches the app.
 * ====================================================================== */
const MAX_PLAYLIST_BYTES = 1024 * 1024;
const MAX_ITEMS_PER_TAB = 40;
const tabMedia = new Map(); // tabId -> { pageUrl, blocked, drm, items: Map(url -> item), variantUrls: Set }

function tabState(tabId, pageUrl) {
  let s = tabMedia.get(tabId);
  if (!s) {
    s = { pageUrl: pageUrl || "", blocked: isBlockedUrl(pageUrl), drm: false, items: new Map(), variantUrls: new Set() };
    tabMedia.set(tabId, s);
  }
  return s;
}

function resetTab(tabId, pageUrl) {
  tabMedia.set(tabId, { pageUrl, blocked: isBlockedUrl(pageUrl), drm: false, items: new Map(), variantUrls: new Set() });
}

/** Top-level page URL for a request (frameAncestors' last entry is the top document). */
function pageUrlOf(d, s) {
  if (d.type === "main_frame") return d.url;
  if (Array.isArray(d.frameAncestors) && d.frameAncestors.length) return d.frameAncestors[d.frameAncestors.length - 1].url;
  return (s && s.pageUrl) || d.documentUrl || d.originUrl || "";
}

function blockedRequest(d, s) {
  if (s.blocked || s.drm) return true;
  if (isBlockedUrl(d.url) || isBlockedUrl(pageUrlOf(d, s)) || isBlockedUrl(d.documentUrl) || isBlockedUrl(d.originUrl)) return true;
  return Array.isArray(d.frameAncestors) && d.frameAncestors.some(f => isBlockedUrl(f.url));
}

function itemsFor(s) {
  if (s.blocked || s.drm) return [];
  return Array.from(s.items.values());
}

function pushToTab(tabId) {
  const s = tabMedia.get(tabId);
  if (!s) return;
  browser.tabs.sendMessage(tabId, { type: "media", items: itemsFor(s), pageUrl: s.pageUrl }, { frameId: 0 }).catch(() => {
    // content script not ready yet: it pulls the list with "getMedia" when it starts
  });
}

function addItem(tabId, s, item) {
  if (s.items.size >= MAX_ITEMS_PER_TAB && !s.items.has(item.url)) return;
  s.items.set(item.url, item);
  pushToTab(tabId);
}

function onHlsPlaylist(d, s, text) {
  if (tabMedia.get(d.tabId) !== s) return;            // navigated away meanwhile
  const pl = parseM3U8(text, d.url);
  if (!pl.valid) return;
  if (pl.refuse) {
    // Encrypted stream (AES-128, SAMPLE-AES or a DRM key format): drop it and everything derived from it
    s.items.delete(d.url);
    pl.variants.forEach(v => s.items.delete(v.url));
    pushToTab(d.tabId);
    return;
  }
  if (pl.isMaster) {
    const variants = pl.variants.filter(v => !isBlockedUrl(v.url));
    variants.forEach(v => { s.variantUrls.add(v.url); s.items.delete(v.url); });
    // One entry per quality, best first; each variant playlist is a single-quality stream the app can fetch.
    for (const v of variants) {
      addItem(d.tabId, s, {
        url: v.url, mime: "application/vnd.apple.mpegurl", kind: "hls", quality: qualityLabel(v),
        w: v.width, h: v.height, bytes: -1, bandwidth: v.bandwidth, codecs: v.codecs, master: d.url,
      });
    }
    return;
  }
  // Media playlist: skip if it's a variant of a master we already listed.
  if (s.variantUrls.has(d.url)) return;
  addItem(d.tabId, s, { url: d.url, mime: "application/vnd.apple.mpegurl", kind: "hls", quality: "HLS", w: 0, h: 0, bytes: -1 });
}

function captureBody(d, s) {
  let filter;
  try { filter = browser.webRequest.filterResponseData(d.requestId); } catch (e) { return false; }
  const decoder = new TextDecoder("utf-8");
  let text = "";
  let total = 0;
  filter.ondata = e => {
    filter.write(e.data);                                // pass through unchanged
    total += e.data.byteLength;
    if (total <= MAX_PLAYLIST_BYTES) text += decoder.decode(e.data, { stream: true });
  };
  filter.onstop = () => {
    try { filter.close(); } catch (e) { }
    if (total <= MAX_PLAYLIST_BYTES) onHlsPlaylist(d, s, text + decoder.decode());
  };
  filter.onerror = () => { };
  return true;
}

browser.webRequest.onBeforeRequest.addListener(d => {
  if (d.tabId >= 0) resetTab(d.tabId, d.url);
}, { urls: ["http://*/*", "https://*/*"], types: ["main_frame"] });

browser.webRequest.onHeadersReceived.addListener(d => {
  if (d.tabId < 0) return;
  const c = classifyResponse(d);
  if (!c) return;
  const s = tabState(d.tabId, d.type === "main_frame" ? d.url : undefined);
  if (blockedRequest(d, s)) return;
  if (c.kind === "hls") { captureBody(d, s); return; }
  addItem(d.tabId, s, {
    url: d.url, mime: c.mime, kind: "progressive",
    quality: c.mime.startsWith("audio/") ? "Audio" : "Video", w: 0, h: 0, bytes: c.bytes,
  });
}, { urls: ["http://*/*", "https://*/*"], types: ["main_frame", "media", "xmlhttprequest", "object", "other"] },
  ["responseHeaders", "blocking"]);

browser.tabs.onRemoved.addListener(tabId => tabMedia.delete(tabId));

/* ---------------------------------------------------------------- messages from content scripts */
browser.runtime.onMessage.addListener((msg, sender) => {
  const tabId = sender && sender.tab ? sender.tab.id : -1;
  switch (msg && msg.type) {
    case "getMedia": {
      const s = tabMedia.get(tabId);
      return Promise.resolve({ items: s ? itemsFor(s) : [], pageUrl: s ? s.pageUrl : "" });
    }
    case "drm": {                                        // the page uses EME: never offer anything from it
      const s = tabState(tabId);
      s.drm = true; s.items.clear();
      return Promise.resolve({ ok: true });
    }
    case "loadReadability":                              // inject the big parser only when the user opens Reader view
      return browser.tabs.executeScript(tabId, { file: "/reader/Readability.js", frameId: sender.frameId || 0 })
        .then(() => ({ ok: true }), e => ({ ok: false, error: String(e && e.message || e) }));
  }
  return undefined;
});
