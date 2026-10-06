"use strict";
(function () {
  function apply(zoom) {
    const z = parseInt(zoom, 10);
    if (!z || z === 100) {
      document.documentElement.style.zoom = "";
    } else {
      document.documentElement.style.zoom = String(z / 100);
    }
  }
  function request() {
    let host = "";
    try { host = location.hostname; } catch (e) { return; }
    browser.runtime.sendMessage({ type: "getZoom", host: host }).then((r) => {
      if (r && r.zoom) apply(r.zoom);
    }).catch(() => {});
  }
  browser.runtime.onMessage.addListener((msg) => {
    if (msg && msg.type === "applyZoom") apply(msg.zoom);
    if (msg && msg.type === "applyDark") setDark(!!msg.dark);
  });

  // ---- 2.5 forced dark (dark-core.js). The style goes in straight away at document_start so light pages
  // don't flash white; once styles exist we check whether the page is dark already and, if so, step aside and
  // tell the background so this host isn't darkened again this session. Fullscreen (top layer) is left alone.
  let darkWanted = false;
  let darkSkipped = false; // the page turned out to be dark already: don't add the style again on this page
  let darkStyle = null;

  function pageLooksDark() {
    const root = document.documentElement;
    if (!root) return false;
    const cs = getComputedStyle(root);
    const body = document.body ? getComputedStyle(document.body) : null;
    const meta = document.querySelector('meta[name="color-scheme" i]');
    return pageIsDark({
      htmlBg: cs.backgroundColor,
      bodyBg: body ? body.backgroundColor : "",
      colorScheme: cs.colorScheme,
      metaScheme: meta ? meta.content : "",
      textColor: body ? body.color : cs.color,
    });
  }

  function addDark() {
    const root = document.documentElement;
    if (!root) return;
    if (!darkStyle || !darkStyle.isConnected) {
      darkStyle = document.getElementById(ULFUR_DARK_STYLE_ID) || document.createElement("style");
      darkStyle.id = ULFUR_DARK_STYLE_ID;
      darkStyle.textContent = ULFUR_DARK_CSS;
      (document.head || root).appendChild(darkStyle);
    }
    if (!document.fullscreenElement) root.classList.add(ULFUR_DARK_CLASS);
  }

  function removeDark() {
    const root = document.documentElement;
    if (root) root.classList.remove(ULFUR_DARK_CLASS);
    if (darkStyle) { darkStyle.remove(); darkStyle = null; }
  }

  /** Runs at DOMContentLoaded, at load and 1.5 s later (late themes, SPAs). */
  function evaluateDark() {
    if (!darkWanted || darkSkipped) return;
    let dark = false;
    try { dark = pageLooksDark(); } catch (e) { return; }
    if (!dark) { addDark(); return; } // re-add if the page replaced <head>
    darkSkipped = true;
    removeDark();
    let host = "";
    try { host = location.hostname; } catch (e) { }
    browser.runtime.sendMessage({ type: "darkAlready", host: host }).catch(() => {});
  }

  function setDark(on) {
    darkWanted = on;
    if (!on || darkSkipped) { removeDark(); return; }
    addDark();
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", evaluateDark, { once: true });
    } else {
      evaluateDark();
    }
    if (document.readyState !== "complete") {
      window.addEventListener("load", () => { evaluateDark(); setTimeout(evaluateDark, 1500); }, { once: true });
    }
  }

  document.addEventListener("fullscreenchange", () => {
    const root = document.documentElement;
    if (!root || !darkStyle) return;
    if (document.fullscreenElement) root.classList.remove(ULFUR_DARK_CLASS);
    else root.classList.add(ULFUR_DARK_CLASS);
  });

  function requestDark() {
    let host = "";
    try { host = location.hostname; } catch (e) { return; }
    browser.runtime.sendMessage({ type: "getDark", host: host }).then((r) => {
      if (r && r.dark) setDark(true);
    }).catch(() => {});
  }
  requestDark();
  request();
  // Re-apply after the document element is ready (some pages reset styles)
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", request, { once: true });
  }
})();
