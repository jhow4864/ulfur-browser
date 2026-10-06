/* Beast Reader view (extension page). The app supplies the article over native messaging ("beast_tab"):
 *   {type:"getArticle", id|saved}  -> {ok, article, prefs:{size,font,theme}, accent:"#RRGGBB", onAccent, private, saved}
 *   {type:"readerPrefs", prefs}    {type:"saveArticle", id}  -> {ok, saved}    {type:"readerClose", url}
 * The article HTML from Readability is sanitised again here before insertion. */
(() => {
  "use strict";
  const NATIVE_APP = "beast_tab";
  const $ = id => document.getElementById(id);
  const root = document.documentElement;
  const params = new URLSearchParams(location.hash.slice(1));
  const state = { prefs: { size: 100, font: "sans", theme: "dark" }, article: null, saved: false, savedId: params.get("saved") };

  function native(msg) {
    try { return browser.runtime.sendNativeMessage(NATIVE_APP, msg); }
    catch (e) { return Promise.reject(e); }
  }

  // ------------------------------------------------------------ sanitising
  const DROP = "script,style,link,meta,base,iframe,frame,frameset,object,embed,applet,form,input,button,select,textarea,noscript,template,svg script";
  function safeUrl(v, allowData) {
    const s = String(v || "").trim();
    if (/^(https?:|mailto:|#)/i.test(s)) return s;
    if (allowData && /^data:image\//i.test(s)) return s;
    return null;
  }
  function sanitize(html, baseUrl) {
    const doc = new DOMParser().parseFromString(`<!DOCTYPE html><html><head><base href=""></head><body>${html}</body></html>`, "text/html");
    try { doc.querySelector("base").setAttribute("href", baseUrl); } catch (e) { }
    doc.body.querySelectorAll(DROP).forEach(n => n.remove());
    doc.body.querySelectorAll("*").forEach(el => {
      for (const a of Array.from(el.attributes)) {
        const n = a.name.toLowerCase();
        if (n.startsWith("on") || n === "style" || n === "srcdoc" || n === "formaction" || n === "class" || n === "id") { el.removeAttribute(a.name); continue; }
        if (n === "href" || n === "src" || n === "poster" || n === "xlink:href") {
          let abs = a.value;
          try { abs = new URL(a.value, baseUrl).href; } catch (e) { }
          const ok = safeUrl(abs, n !== "href");
          if (ok) el.setAttribute(a.name, ok); else el.removeAttribute(a.name);
        } else if (n === "srcset") {
          const parts = a.value.split(",").map(p => p.trim()).filter(Boolean).map(p => {
            const [u, d] = p.split(/\s+/);
            try { const abs = new URL(u, baseUrl).href; return safeUrl(abs, true) ? abs + (d ? " " + d : "") : null; } catch (e) { return null; }
          }).filter(Boolean);
          if (parts.length) el.setAttribute("srcset", parts.join(", ")); else el.removeAttribute("srcset");
        }
      }
      if (el.tagName === "A") { el.setAttribute("rel", "noreferrer noopener"); }
      if (el.tagName === "IMG") { el.setAttribute("loading", "lazy"); el.setAttribute("decoding", "async"); el.setAttribute("referrerpolicy", "no-referrer"); }
      if (el.tagName === "VIDEO" || el.tagName === "AUDIO") { el.removeAttribute("autoplay"); el.setAttribute("controls", ""); el.setAttribute("preload", "none"); }
    });
    return doc.body;
  }

  // ------------------------------------------------------------ prefs / UI
  function applyPrefs() {
    const p = state.prefs;
    root.style.setProperty("--size", (19 * p.size / 100).toFixed(1) + "px");
    root.dataset.font = p.font;
    root.dataset.theme = p.theme;
    $("sizeVal").textContent = p.size + "%";
    document.querySelectorAll("#panel .font").forEach(b => b.classList.toggle("on", b.dataset.font === p.font));
    document.querySelectorAll("#panel .swatch").forEach(b => b.classList.toggle("on", b.dataset.theme === p.theme));
  }
  function setPrefs(patch) {
    Object.assign(state.prefs, patch);
    state.prefs.size = Math.max(70, Math.min(200, state.prefs.size));
    applyPrefs();
    native({ type: "readerPrefs", prefs: state.prefs }).catch(() => { });
  }
  function setSaved(saved) {
    state.saved = saved;
    $("save").classList.toggle("saved", saved);
    $("saveLabel").textContent = saved ? "Saved" : "Save";
    $("save").setAttribute("aria-label", saved ? "Saved to reading list" : "Save to reading list");
  }
  let toastTimer = 0;
  function toast(text) {
    const t = $("toast");
    t.textContent = text; t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 2200);
  }
  function host(u) { try { return new URL(u).hostname.replace(/^www\./, ""); } catch (e) { return ""; } }
  function minutes(a) {
    const words = a.words || Math.round((a.length || 0) / 5.5);
    return words ? Math.max(1, Math.round(words / 230)) + " min read" : "";
  }

  function render(a) {
    state.article = a;
    document.title = a.title || "Reader view";
    if (a.lang) root.lang = a.lang;
    if (a.dir) $("article").dir = a.dir;
    const site = a.siteName || host(a.url);
    $("site").textContent = site;
    $("meta").textContent = [site, minutes(a)].filter(Boolean).join(" · ");
    $("title").textContent = a.title || "";
    let by = a.byline || "";
    if (a.publishedTime) {
      const d = new Date(a.publishedTime);
      if (!isNaN(d)) by = [by, d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" })].filter(Boolean).join(" · ");
    }
    $("byline").textContent = by;
    const body = sanitize(a.content || "", a.url || "about:blank");
    const c = $("content");
    c.textContent = "";
    while (body.firstChild) c.appendChild(document.adoptNode(body.firstChild));
    const orig = safeUrl(a.url);
    if (orig) $("original").href = orig; else $("original").remove();
    $("article").hidden = false;
    $("status").hidden = true;
  }

  function showUnavailable(url) {
    const s = $("status");
    s.textContent = "This Reader view is no longer available. ";
    const ok = safeUrl(params.get("url") || url);
    if (ok) {
      const a = document.createElement("a");
      a.href = ok; a.textContent = "Open the original page";
      s.appendChild(a);
    }
    $("save").hidden = true;
  }

  // ------------------------------------------------------------ events
  $("aa").addEventListener("click", () => {
    const p = $("panel"); p.hidden = !p.hidden;
    $("aa").setAttribute("aria-expanded", String(!p.hidden));
  });
  $("smaller").addEventListener("click", () => setPrefs({ size: state.prefs.size - 10 }));
  $("bigger").addEventListener("click", () => setPrefs({ size: state.prefs.size + 10 }));
  document.querySelectorAll("#panel .font").forEach(b => b.addEventListener("click", () => setPrefs({ font: b.dataset.font })));
  document.querySelectorAll("#panel .swatch").forEach(b => b.addEventListener("click", () => setPrefs({ theme: b.dataset.theme })));
  $("close").addEventListener("click", () => {
    native({ type: "readerClose", url: (state.article && state.article.url) || params.get("url") || "" }).catch(() => history.back());
  });
  $("save").addEventListener("click", () => {
    if (state.saved) { toast("Already in your reading list"); return; }
    native({ type: "saveArticle", id: params.get("id"), saved: state.savedId }).then(r => {
      if (r && r.ok) { setSaved(true); toast(r.note || "Saved to reading list"); } else toast((r && r.error) || "Couldn't save");
    }, () => toast("Couldn't save"));
  });

  // ------------------------------------------------------------ load
  applyPrefs();
  const req = state.savedId ? { type: "getArticle", saved: state.savedId } : { type: "getArticle", id: params.get("id") };
  native(req).then(r => {
    if (r && r.prefs) state.prefs = Object.assign(state.prefs, r.prefs);
    if (r && r.accent) root.style.setProperty("--accent", r.accent);
    if (r && r.onAccent) root.style.setProperty("--on-accent", r.onAccent);
    applyPrefs();
    if (r && r.ok && r.article) { render(r.article); setSaved(!!r.saved); }
    else showUnavailable(r && r.url);
  }, () => showUnavailable());
})();
