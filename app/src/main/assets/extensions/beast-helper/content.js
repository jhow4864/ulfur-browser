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
    if (lastTheme !== undefined) post({ type: "themeColor", value: lastTheme });
  }

  // 1.3.0 (Ulfur 2.10 #5): <meta name="theme-color"> of the top frame, honouring media (dark/light), raw CSS text.
  // The app validates it (opaque colours only) and only uses it when "Match toolbar to site colour" is on.
  let lastTheme;
  function themeColor() {
    const metas = Array.from(document.querySelectorAll('meta[name="theme-color" i]'));
    for (const m of metas) {
      const media = m.getAttribute("media");
      try { if (media && !window.matchMedia(media).matches) continue; } catch (e) { continue; }
      const v = (m.getAttribute("content") || "").trim();
      if (v) return v.slice(0, 64);
    }
    return null;
  }
  function checkTheme() {
    const v = themeColor();
    if (v === lastTheme) return;
    lastTheme = v;
    post({ type: "themeColor", value: v });
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

  // ------------------------------------------------------------ Login field context (Ulfur 2.10 #2)
  // On focus of a field, tell the app where it is: top frame or a same-origin frame, and whether it is visible.
  // The app refuses to fill a saved login unless the latest report is fresh, visible, and not cross-origin.
  // The script runs in the top frame only, so a focused cross-origin iframe shows up as the IFRAME element whose
  // document we can't read.
  function visibleEl(el, win) {
    try {
      if (!el || !el.getClientRects || el.getClientRects().length === 0) return false;
      const r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) return false;
      if (r.bottom < 0 || r.right < 0 || r.top > win.innerHeight || r.left > win.innerWidth) return false;
      for (let e = el; e && e.nodeType === 1; e = e.parentElement) {
        const cs = win.getComputedStyle(e);
        if (cs.visibility === "hidden" || cs.display === "none" || parseFloat(cs.opacity) < 0.1) return false;
      }
      return true;
    } catch (e) { return false; }
  }
  function reportField() {
    const a = document.activeElement;
    if (!a) return;
    if (a.tagName === "IFRAME" || a.tagName === "FRAME") {
      let doc = null;
      try { doc = a.contentDocument; } catch (e) { doc = null; }
      const same = !!doc && (() => { try { return a.contentWindow.location.origin === location.origin; } catch (e) { return false; } })();
      const inner = same ? doc.activeElement : null;
      post({ type: "loginFocus", top: false, sameOrigin: same,
        visible: visibleEl(a, window) && (!same || visibleEl(inner, a.contentWindow)) });
      return;
    }
    if (a.tagName !== "INPUT") return;
    post({ type: "loginFocus", top: true, sameOrigin: true, visible: visibleEl(a, window) });
  }
  document.addEventListener("focusin", reportField, true);
  document.addEventListener("pointerdown", () => setTimeout(reportField, 0), true);

  // ------------------------------------------------------------ Element picker (Ulfur 2.10 #3)
  // Tap an element, optionally widen to its parent, then Hide. Replies {ok, host, selector}; the app checks host
  // against the tab's own URL and stores the rule as host##selector, so it only ever applies on this site.
  function cssPath(el) {
    const ok = s => /^[A-Za-z_][\w-]{0,63}$/.test(s);
    if (el.id && ok(el.id) && document.querySelectorAll("#" + CSS.escape(el.id)).length === 1) return "#" + CSS.escape(el.id);
    const parts = [];
    for (let e = el, depth = 0; e && e.nodeType === 1 && e !== document.body && depth < 6; e = e.parentElement, depth++) {
      if (depth > 0 && e.id && ok(e.id) && document.querySelectorAll("#" + CSS.escape(e.id)).length === 1) {
        parts.unshift("#" + CSS.escape(e.id)); break;
      }
      let p = e.tagName.toLowerCase();
      const cls = Array.from(e.classList).filter(ok).slice(0, 2);
      if (cls.length) p += "." + cls.map(c => CSS.escape(c)).join(".");
      const parent = e.parentElement;
      if (parent) {
        const same = Array.from(parent.children).filter(c => c.tagName === e.tagName);
        if (same.length > 1) p += ":nth-of-type(" + (same.indexOf(e) + 1) + ")";
      }
      parts.unshift(p);
    }
    const sel = parts.join(" > ");
    try { return Array.from(document.querySelectorAll(sel)).includes(el) ? sel : null; } catch (e) { return null; }
  }
  // Designer spec (2.10): tap highlights in the accent colour; a bottom sheet shows the rule with a Bigger/Smaller
  // slider (parent / child element) and the page previews the hide live; Hide (primary) + Cancel.
  let picker = null;
  let lastHidden = null;
  function stopPicker(restore) {
    if (!picker) return;
    if (restore && picker.preview) picker.preview.style.removeProperty("opacity");
    picker.root.remove(); picker = null;
  }
  function startPicker(id, accent) {
    stopPicker(true);
    const color = /^#[0-9a-fA-F]{6}$/.test(accent || "") ? accent : "#E91E63";
    const root = document.createElement("div");
    root.style.cssText = "position:fixed;inset:0;z-index:2147483647;background:rgba(0,0,0,.06);touch-action:manipulation";
    const box = document.createElement("div");
    box.style.cssText = `position:fixed;pointer-events:none;border:2px solid ${color};background:${color}33;display:none;border-radius:4px`;
    const sheet = document.createElement("div");
    sheet.style.cssText = "position:fixed;left:0;right:0;bottom:0;padding:14px 16px 18px;background:#16161c;color:#fff;" +
      "font:14px sans-serif;border-radius:16px 16px 0 0;box-shadow:0 -4px 16px rgba(0,0,0,.4)";
    const rule = document.createElement("div");
    rule.style.cssText = "font:12px monospace;opacity:.85;word-break:break-all;min-height:2.4em;margin-bottom:8px";
    rule.textContent = "Tap something on the page to hide it";
    const row = document.createElement("div");
    row.style.cssText = "display:flex;align-items:center;gap:8px;margin-bottom:12px";
    const lab = t => { const s = document.createElement("span"); s.textContent = t; return s; };
    const slider = document.createElement("input");
    slider.type = "range"; slider.min = "-3"; slider.max = "6"; slider.step = "1"; slider.value = "0"; slider.disabled = true;
    slider.style.cssText = `flex:1;accent-color:${color}`;
    slider.setAttribute("aria-label", "Bigger or smaller");
    row.append(lab("Smaller"), slider, lab("Bigger"));
    const btns = document.createElement("div");
    btns.style.cssText = "display:flex;gap:8px";
    const mk = (t, primary) => { const b = document.createElement("button"); b.textContent = t;
      b.style.cssText = "flex:1;padding:12px;border-radius:10px;font:600 15px sans-serif;" +
        (primary ? `border:0;color:#fff;background:${color}` : `background:transparent;color:#fff;border:1px solid #555`);
      btns.appendChild(b); return b; };
    const cancel = mk("Cancel", false), hide = mk("Hide", true);
    hide.disabled = true;
    sheet.append(rule, row, btns);
    root.append(box, sheet);
    document.documentElement.appendChild(root);
    picker = { root, base: null, el: null, preview: null };
    const at = (base, step) => {
      let e = base;
      for (let i = 0; i < step && e.parentElement && e.parentElement !== document.body && e.parentElement !== document.documentElement; i++) e = e.parentElement;
      for (let i = 0; i > step && e.firstElementChild; i--) e = e.firstElementChild;
      return e;
    };
    const show = () => {
      if (!picker.base) return;
      const el = at(picker.base, parseInt(slider.value, 10) || 0);
      if (picker.preview && picker.preview !== el) picker.preview.style.removeProperty("opacity");
      picker.el = el; picker.preview = el;
      el.style.setProperty("opacity", "0.15", "important"); // live preview of the hide
      const r = el.getBoundingClientRect();
      Object.assign(box.style, { display: "block", left: r.left + "px", top: r.top + "px", width: r.width + "px", height: r.height + "px" });
      const sel = cssPath(el);
      rule.textContent = sel ? location.hostname + "##" + sel : "Can't describe this element: try Bigger";
      hide.disabled = !sel;
    };
    root.addEventListener("click", ev => {
      if (sheet.contains(ev.target)) return;
      ev.preventDefault(); ev.stopPropagation();
      root.style.pointerEvents = "none";
      const el = document.elementFromPoint(ev.clientX, ev.clientY);
      root.style.pointerEvents = "";
      if (!el || el === document.documentElement || el === document.body) return;
      if (picker.preview) picker.preview.style.removeProperty("opacity");
      picker.base = el; slider.value = "0"; slider.disabled = false; show();
    }, true);
    slider.addEventListener("input", show);
    cancel.onclick = () => { stopPicker(true); post({ id, ok: false }); };
    hide.onclick = () => {
      const el = picker && picker.el; if (!el) return;
      const selector = cssPath(el);
      if (!selector) return;
      el.style.removeProperty("opacity");
      stopPicker(false);
      el.style.setProperty("display", "none", "important");
      lastHidden = el;
      post({ id, ok: true, host: location.hostname, selector });
    };
  }
  /** Undo from the app's snackbar: show the element again (the app has already deleted the rule). */
  function unhideLast() {
    if (!lastHidden) return false;
    lastHidden.style.removeProperty("display"); lastHidden = null; return true;
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

  function isLoginField(el) {
    if (!el || el.disabled || el.readOnly) return false;
    if (el.tagName !== "INPUT" && el.tagName !== "TEXTAREA") return false;
    const t = (el.type || "").toLowerCase();
    if (t === "password") return true;
    if (t === "hidden" || t === "submit" || t === "button" || t === "checkbox" || t === "radio" || t === "file") return false;
    const ac = (el.autocomplete || "").toLowerCase();
    const name = ((el.name || "") + " " + (el.id || "")).toLowerCase();
    return /user|email|login|account|identifier/.test(ac + " " + name);
  }

  function findLoginField() {
    const active = document.activeElement;
    if (isLoginField(active)) return active;
    return document.querySelector("input[type=password]")
      || document.querySelector("input[autocomplete*=username i],input[autocomplete=email i],input[type=email]");
  }

  /** Blur then re-focus so Gecko re-fetches logins after vault unlock (no second tap). */
  function softFocusField() {
    const el = findLoginField();
    if (!el) return false;
    try {
      el.blur();
      setTimeout(() => {
        try { el.focus({ preventScroll: false }); } catch (e) { try { el.focus(); } catch (e2) { } }
      }, 40);
    } catch (e) { return false; }
    return true;
  }

  function fillPassword(pw) {
    if (typeof pw !== "string" || !pw) return false;
    const pwd = document.querySelector("input[type=password]");
    if (!pwd) return false;
    const desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
    if (desc && desc.set) desc.set.call(pwd, pw); else pwd.value = pw;
    pwd.dispatchEvent(new Event("input", { bubbles: true }));
    pwd.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }

  async function onAppMessage(msg) {
    if (!msg || typeof msg !== "object") return;
    if (msg.type === "extract") {
      try { post({ id: msg.id, ok: true, article: await extract() }); }
      catch (e) { post({ id: msg.id, ok: false, error: String(e && e.message || e) }); }
    } else if (msg.type === "ping") {
      post({ id: msg.id, ok: true });
    } else if (msg.type === "softFocus") {
      post({ id: msg.id, ok: softFocusField() });
    } else if (msg.type === "fillPassword") {
      post({ id: msg.id, ok: fillPassword(msg.password || "") });
    } else if (msg.type === "startPicker") {
      startPicker(msg.id, msg.accent);
    } else if (msg.type === "unhideLast") {
      post({ id: msg.id, ok: unhideLast() });
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
  if (window === window.top) {
    checkTheme();
    try {
      new MutationObserver(checkTheme).observe(document.head || document.documentElement,
        { subtree: true, childList: true, attributes: true, attributeFilter: ["content", "media", "name"] });
      window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", checkTheme);
    } catch (e) { }
  }
  checkReaderable();
  setTimeout(checkReaderable, 2500);               // late-rendering pages
  browser.runtime.sendMessage({ type: "getMedia" }).then(r => { if (r && r.items && r.items.length) onMedia(r.items, r.pageUrl); }, () => { });
})();
