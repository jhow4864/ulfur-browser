/* Beast Helper - media sniffer core (pure functions, no browser APIs).
 * Loaded as a classic script in the background page and as a CommonJS module by the node unit tests.
 *
 * BEAST_DRM_HOSTS is THE single extension-side blocklist. The app enforces the same list again in
 * MediaSniffer (Kotlin); ExtensionJsTest checks that both lists stay identical. */
"use strict";

/** DRM / ToS-sensitive hosts: exact host or any subdomain. Checked against BOTH the page and the media host. */
const BEAST_DRM_HOSTS = Object.freeze([
  // Google / YouTube
  "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com",
  // Netflix
  "netflix.com", "nflxvideo.net",
  // Disney
  "disneyplus.com", "disney.com",
  // Amazon
  "primevideo.com", "amazonvideo.com", "amazon.com",
  // Others (subscription / DRM streaming)
  "hulu.com", "max.com", "hbomax.com",
  "spotify.com", "scdn.co",
  "tv.apple.com",
  "peacocktv.com", "paramountplus.com", "crunchyroll.com",
  "twitch.tv", "ttvnw.net",
  "itv.com", "channel4.com",
  // Social platforms whose terms forbid downloading (app-side list, mirrored here)
  "vimeo.com", "tiktok.com", "instagram.com", "facebook.com", "fbcdn.net",
]);

/** Path-scoped blocks: [host suffix, path prefix]. */
const BEAST_DRM_PATHS = Object.freeze([
  ["bbc.co.uk", "/iplayer"],
]);

const MIN_PROGRESSIVE_BYTES = 200 * 1024;

const HLS_MIMES = ["application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/mpegurl"];
const SEGMENT_MIMES = ["video/mp2t", "video/iso.segment", "audio/mp2t", "video/vnd.dlna.mpeg-tts"];
const SEGMENT_EXTS = ["ts", "m4s", "m4f", "cmfv", "cmfa", "m4i"];
const PROGRESSIVE_EXTS = { mp4: "video/mp4", m4v: "video/mp4", webm: "video/webm", mov: "video/quicktime", mkv: "video/x-matroska",
  ogv: "video/ogg", mp3: "audio/mpeg", m4a: "audio/mp4", aac: "audio/aac", ogg: "audio/ogg", oga: "audio/ogg", opus: "audio/ogg",
  flac: "audio/flac", wav: "audio/wav" };

function normHost(host) {
  return String(host || "").toLowerCase().replace(/\.$/, "").replace(/^www\./, "");
}

function hostMatches(host, suffix) {
  const h = normHost(host);
  return h === suffix || h.endsWith("." + suffix);
}

/** True when the URL (or bare host) belongs to a blocked DRM/ToS host. Unparseable input is not blocked. */
function isBlockedUrl(urlOrHost) {
  if (!urlOrHost) return false;
  let host, path = "/";
  try {
    if (String(urlOrHost).includes("://")) { const u = new URL(urlOrHost); host = u.hostname; path = u.pathname || "/"; }
    else host = String(urlOrHost);
  } catch (e) { return false; }
  if (BEAST_DRM_HOSTS.some(s => hostMatches(host, s))) return true;
  return BEAST_DRM_PATHS.some(([s, p]) => hostMatches(host, s) && (path === p || path.startsWith(p + "/") || path.startsWith(p + "?")));
}

function header(headers, name) {
  const n = name.toLowerCase();
  const h = (headers || []).find(x => String(x.name).toLowerCase() === n);
  return h ? String(h.value || "") : "";
}

function extOf(url) {
  try {
    const p = new URL(url).pathname;
    const last = p.substring(p.lastIndexOf("/") + 1);
    const dot = last.lastIndexOf(".");
    return dot > 0 ? last.substring(dot + 1).toLowerCase() : "";
  } catch (e) { return ""; }
}

/** Total size from Content-Range ("bytes 0-99/1234") or Content-Length on a 200. -1 if unknown. */
function sizeOf(statusCode, headers) {
  const range = header(headers, "content-range");
  const m = /\/(\d+)\s*$/.exec(range);
  if (m) return Number(m[1]);
  const len = Number(header(headers, "content-length"));
  if (statusCode === 200 && Number.isFinite(len) && len > 0) return len;
  return -1;
}

/**
 * Classifies a webRequest response.
 * @param d {url, type, statusCode, responseHeaders}
 * @returns null (not interesting) | {kind:"hls"|"progressive", mime, bytes}
 */
function classifyResponse(d) {
  if (!d || !d.url || !/^https?:/i.test(d.url)) return null;
  if (d.statusCode !== 200 && d.statusCode !== 206) return null;
  const mime = header(d.responseHeaders, "content-type").split(";")[0].trim().toLowerCase();
  const ext = extOf(d.url);
  if (SEGMENT_MIMES.includes(mime) || SEGMENT_EXTS.includes(ext)) return null;           // HLS/DASH segments
  if (HLS_MIMES.includes(mime) || ext === "m3u8") return { kind: "hls", mime: "application/vnd.apple.mpegurl", bytes: -1 };
  if (mime === "application/dash+xml" || ext === "mpd") return null;                         // DASH: not supported in v1
  const isAv = mime.startsWith("video/") || mime.startsWith("audio/");
  const byExt = PROGRESSIVE_EXTS[ext];
  const generic = mime === "" || mime === "application/octet-stream" || mime === "binary/octet-stream";
  if (!isAv && !(generic && byExt)) return null;
  // MSE players fetch fragments with XHR/fetch: those are segments, not downloadable files.
  if (d.type === "xmlhttprequest" || d.type === "fetch" || d.type === "beacon") return null;
  const bytes = sizeOf(d.statusCode, d.responseHeaders);
  if (bytes >= 0 && bytes < MIN_PROGRESSIVE_BYTES) return null;
  return { kind: "progressive", mime: isAv ? mime : byExt, bytes };
}

/** Parses an attribute list: KEY=VALUE,KEY="quoted, value" */
function parseAttrs(s) {
  const out = {};
  const re = /([A-Z0-9-]+)=("[^"]*"|[^,]*)/g;
  let m;
  while ((m = re.exec(s)) !== null) {
    let v = m[2];
    if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1);
    out[m[1]] = v;
  }
  return out;
}

const DRM_KEYFORMATS = /(widevine|playready|com\.apple\.streamingkeydelivery|com\.microsoft|urn:uuid:edef8ba9|urn:uuid:9a04f079|urn:uuid:94ce86fb)/i;

/**
 * Parses an HLS playlist.
 * @returns {valid, isMaster, drm, encrypted, variants:[{url, bandwidth, averageBandwidth, width, height, codecs, frameRate}]}
 *   variants sorted best-first (height, then bandwidth); for media playlists `variants` is empty.
 */
function parseM3U8(text, baseUrl) {
  const res = { valid: false, isMaster: false, drm: false, encrypted: false, variants: [] };
  if (typeof text !== "string") return res;
  const lines = text.replace(/^\uFEFF/, "").split(/\r?\n/).map(l => l.trim());
  if (!lines.length || !lines[0].startsWith("#EXTM3U")) return res;
  res.valid = true;
  const seen = new Set();
  for (let i = 1; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
      const a = parseAttrs(line.substring(line.indexOf(":") + 1));
      const method = (a.METHOD || "").toUpperCase();
      if (method && method !== "NONE") res.encrypted = true;
      if (method.startsWith("SAMPLE-AES") || DRM_KEYFORMATS.test(a.KEYFORMAT || "") || /^skd:/i.test(a.URI || "")) res.drm = true;
    } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
      res.isMaster = true;
      const a = parseAttrs(line.substring(line.indexOf(":") + 1));
      let j = i + 1;
      while (j < lines.length && (lines[j] === "" || lines[j].startsWith("#"))) j++;
      if (j >= lines.length) break;
      let url;
      try { url = new URL(lines[j], baseUrl).href; } catch (e) { i = j; continue; }
      i = j;
      if (seen.has(url)) continue;
      seen.add(url);
      const [w, h] = /^\d+x\d+$/.test(a.RESOLUTION || "") ? a.RESOLUTION.split("x").map(Number) : [0, 0];
      res.variants.push({
        url,
        bandwidth: Number(a.BANDWIDTH) || 0,
        averageBandwidth: Number(a["AVERAGE-BANDWIDTH"]) || 0,
        width: w, height: h,
        codecs: a.CODECS || "",
        frameRate: Number(a["FRAME-RATE"]) || 0,
      });
    }
  }
  res.variants.sort((x, y) => (y.height - x.height) || (y.bandwidth - x.bandwidth));
  return res;
}

/** "1080p", "720p60", or "2.5 Mbps" when there is no resolution. */
function qualityLabel(v) {
  if (v.height > 0) return v.height + "p" + (v.frameRate >= 49 ? Math.round(v.frameRate) : "");
  if (v.bandwidth > 0) return v.bandwidth >= 1e6 ? (v.bandwidth / 1e6).toFixed(1) + " Mbps" : Math.round(v.bandwidth / 1e3) + " kbps";
  return "HLS";
}

if (typeof module === "object" && module.exports) {
  module.exports = { BEAST_DRM_HOSTS, BEAST_DRM_PATHS, MIN_PROGRESSIVE_BYTES, isBlockedUrl, hostMatches, classifyResponse, parseM3U8, qualityLabel, sizeOf };
}
