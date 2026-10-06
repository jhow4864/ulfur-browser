/* Beast Helper content script (top frame of http/https pages).
 * Talks to the app over a per-session native port ("beast_tab"), so the app knows which GeckoSession sent it:
 *   -> {type:"readerable", value, url}     Reader view available?
 *   -> {type:"media", items, pageUrl, title}  full detected-media list for this page (from the background sniffer)
 *   -> {type:"drm", url}                   page uses EME/DRM: the app must not offer downloads
 *   <- {id, type:"extract"}  ->  {id, ok, article}   Readability extraction on demand
 * Readability.js itself is injected by the background page only when the user opens Reader view. */
(() => {
  "use strict";
  if (window.top !== window) return;

  const NATIVE_APP = "beast_tab";
  const MAX_ARTICLE_CHARS = 3 * 1024 * 1024;
  let port = null;
  let attempts = 0;
  let readerable = null;
  let lastMedia = null;
  let drm = false;

  function post(msg) {
    if (!port) return;
    try { port.postMessage(msg); } catch (e) { }
  }

  /** (Re)sends the current state; called after every (re)connect. */
  function sendState() {
    if (readerable !== null) post({ type: "readerable", value: readerable, url: location.href });
    if (drm) post({ type: "drm", url: location.href });
    else if (lastMedia) post(lastMedia);
  }

  function connect() {
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
      port = null; retry(); return;
    }
    port.onMessage.addListener(onAppMessage);
    port.onDisconnect.addListener(() => { port = null; retry(); });
    sendState();
  }

  function retry() {
    if (attempts++ < 6) setTimeout(connect, 400 * Math.pow(2, attempts));
  }

  // ------------------------------------------------------------ Reader view
  function checkReaderable() {
    let v = false;
    try { v = typeof isProbablyReaderable === "function" && isProbablyReaderable(document); } catch (e) { v = false; }
    if (v !== readerable) {
      readerable = v;
      post({ type: "readerable", value: v, url: location.href });
    }
  }

  async function extract() {
    if (typeof Readability !== "function") {
      const r = await browser.runtime.sendMessage({ type: "loadReadability" });
      if (!r || !r.ok || typeof Readability !== "function") throw new Error((r && r.error) || "Readability unavailable");
    }
    const clone = document.cloneNode(true);
    const a = new Readability(clone, { charThreshold: 400, keepClasses: false }).parse();
    if (!a || !a.content) throw new Error("No article found on this page");
    const meta = n => (document.querySelector(`meta[property="${n}"],meta[name="${n}"]`) || {}).content || "";
    return {
      title: a.title || document.title || "",
      byline: a.byline || "",
      siteName: a.siteName || meta("og:site_name") || location.hostname.replace(/^www\./, ""),
      excerpt: a.excerpt || "",
      content: a.content.length > MAX_ARTICLE_CHARS ? a.content.substring(0, MAX_ARTICLE_CHARS) : a.content,
      length: a.length || 0,
      lang: a.lang || document.documentElement.lang || "",
      dir: a.dir || "",
      publishedTime: a.publishedTime || meta("article:published_time") || "",
      url: location.href,
    };
  }

  async function onAppMessage(msg) {
    if (!msg || typeof msg !== "object") return;
    if (msg.type === "extract") {
      try { post({ id: msg.id, ok: true, article: await extract() }); }
      catch (e) { post({ id: msg.id, ok: false, error: String(e && e.message || e) }); }
    } else if (msg.type === "ping") {
      post({ id: msg.id, ok: true });
    }
  }

  // ------------------------------------------------------------ Media
  /** Adds the real resolution for progressive files that are playing in a <video> on the page. */
  function enrich(items) {
    const vids = Array.from(document.querySelectorAll("video"));
    return items.map(it => {
      if (it.kind !== "progressive" || it.h) return it;
      const v = vids.find(x => x.currentSrc === it.url || x.src === it.url);
      if (v && v.videoHeight) return Object.assign({}, it, { w: v.videoWidth, h: v.videoHeight, quality: v.videoHeight + "p" });
      return it;
    });
  }

  function onMedia(items, pageUrl) {
    if (drm) return;
    lastMedia = { type: "media", items: enrich(items || []), pageUrl: pageUrl || location.href, title: document.title || "" };
    post(lastMedia);
  }

  function markDrm() {
    if (drm) return;
    drm = true;
    lastMedia = null;
    browser.runtime.sendMessage({ type: "drm" }).catch(() => { });
    post({ type: "drm", url: location.href });
  }

  browser.runtime.onMessage.addListener(msg => {
    if (msg && msg.type === "media") onMedia(msg.items, msg.pageUrl);
  });

  // EME: "encrypted" doesn't bubble, so listen in the capture phase; also poll mediaKeys briefly.
  document.addEventListener("encrypted", markDrm, true);
  let polls = 0;
  const poll = setInterval(() => {
    if (Array.from(document.querySelectorAll("video,audio")).some(m => m.mediaKeys)) markDrm();
    if (drm || ++polls > 20) clearInterval(poll);
  }, 1500);

  // Back/forward cache: the app reset its per-page state on location change, so resend ours.
  window.addEventListener("pageshow", e => {
    if (!e.persisted) return;
    if (!port) { attempts = 0; connect(); } else sendState();
  });

  // ------------------------------------------------------------ start
  connect();
  checkReaderable();
  setTimeout(checkReaderable, 2500);               // late-rendering pages
  browser.runtime.sendMessage({ type: "getMedia" }).then(r => { if (r && r.items && r.items.length) onMedia(r.items, r.pageUrl); }, () => { });
})();
