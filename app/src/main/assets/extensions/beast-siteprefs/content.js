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
  });
  request();
  // Re-apply after the document element is ready (some pages reset styles)
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", request, { once: true });
  }
})();
