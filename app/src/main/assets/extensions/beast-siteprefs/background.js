/* Per-host page zoom and forced dark (2.5): the app pushes state over native messaging; content scripts ask
 * for their host. Forced-dark state is also kept in storage.local so pages opened before the app reconnects
 * after a restart still get it. dark-core.js is loaded first (background.scripts). */
"use strict";
const NATIVE_APP = "beast_siteprefs";
const zooms = Object.create(null); // host -> percent (100 = default)
let port = null;
/** Forced dark: { enabled: bool, off: [registrable domains, saved + this session's Private/Ghost choices] }. */
let darkSynced = false; // the app's darkSync beats the copy in storage
let darkState = { enabled: false, off: [] };
/** Hosts whose pages were already dark this session (in memory only, so a restyled site gets another look). */
const alreadyDark = new Set();
const darkReady = browser.storage.local.get("dark").then(r => {
  if (!darkSynced && r && r.dark && typeof r.dark === "object") darkState = { enabled: !!r.dark.enabled, off: Array.isArray(r.dark.off) ? r.dark.off.map(String) : [] };
}).catch(() => {});
let retryDelay = 1000;

function reply(msg) { try { port && port.postMessage(msg); } catch (e) { } }

function hostKey(host) {
  return String(host || "").toLowerCase().replace(/^www\./, "");
}

async function applyToMatchingTabs(host, zoom) {
  const key = hostKey(host);
  try {
    const tabs = await browser.tabs.query({});
    for (const t of tabs) {
      if (!t.id || !t.url) continue;
      try {
        const u = new URL(t.url);
        const h = hostKey(u.hostname);
        if (h === key || h.endsWith("." + key) || key.endsWith("." + h)) {
          browser.tabs.sendMessage(t.id, { type: "applyZoom", zoom: zoom }).catch(() => {});
        }
      } catch (e) { }
    }
  } catch (e) { }
}

async function broadcastDark() {
  try {
    const tabs = await browser.tabs.query({});
    for (const t of tabs) {
      if (!t.id || !t.url) continue;
      let host = "";
      try { host = new URL(t.url).hostname; } catch (e) { continue; }
      browser.tabs.sendMessage(t.id, { type: "applyDark", dark: darkFor(host, darkState, alreadyDark) }).catch(() => {});
    }
  } catch (e) { }
}

function handle(msg) {
  const { id, type } = msg || {};
  try {
    if (type === "setZoom") {
      const host = hostKey(msg.host);
      const zoom = Math.max(50, Math.min(300, parseInt(msg.zoom, 10) || 100));
      if (!host) { reply({ id, ok: false, error: "no host" }); return; }
      if (zoom === 100) delete zooms[host]; else zooms[host] = zoom;
      applyToMatchingTabs(host, zoom);
      reply({ id, ok: true, zoom });
    } else if (type === "sync") {
      const map = msg.map || {};
      for (const k of Object.keys(zooms)) delete zooms[k];
      for (const k of Object.keys(map)) {
        const z = parseInt(map[k], 10);
        if (!isNaN(z) && z !== 100) zooms[hostKey(k)] = Math.max(50, Math.min(300, z));
      }
      reply({ id, ok: true, count: Object.keys(zooms).length });
    } else if (type === "darkSync") {
      const saved = Array.isArray(msg.off) ? msg.off.map(String) : [];
      const session = Array.isArray(msg.sessionOff) ? msg.sessionOff.map(String) : [];
      // Only the saved exceptions are stored; Private/Ghost choices stay in memory.
      browser.storage.local.set({ dark: { enabled: !!msg.enabled, off: saved } }).catch(() => {});
      darkState = { enabled: !!msg.enabled, off: saved.concat(session) };
      darkSynced = true;
      broadcastDark();
      reply({ id, ok: true, enabled: darkState.enabled, off: darkState.off.length });
    } else if (type === "ping") {
      reply({ id, ok: true });
    } else {
      reply({ id, ok: false, error: "unknown type " + type });
    }
  } catch (e) {
    reply({ id, ok: false, error: String(e && e.message || e) });
  }
}

browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "getDark") {
    const host = msg.host || (sender.tab && sender.tab.url && (() => { try { return new URL(sender.tab.url).hostname; } catch (e) { return ""; } })());
    return darkReady.then(() => ({ dark: darkFor(host, darkState, alreadyDark) }));
  }
  if (msg && msg.type === "darkAlready") {
    const host = darkHostKey(msg.host);
    if (host && alreadyDark.size < 2000) alreadyDark.add(host);
    return;
  }
  if (!msg || msg.type !== "getZoom") return;
  const host = hostKey(msg.host || (sender.tab && sender.tab.url && (() => { try { return new URL(sender.tab.url).hostname; } catch (e) { return ""; } })()));
  // Prefer exact host, then strip subdomains toward eTLD+1-ish matches stored by the app
  let zoom = zooms[host];
  if (zoom == null) {
    const parts = host.split(".");
    for (let i = 1; i < parts.length - 1 && zoom == null; i++) {
      zoom = zooms[parts.slice(i).join(".")];
    }
  }
  return Promise.resolve({ zoom: zoom == null ? 100 : zoom });
});

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
  reply({ type: "hello" });
}
connect();
