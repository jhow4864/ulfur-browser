// Run: node --test app/src/test/js/   (also run from ExtensionJsTest during ./gradlew testDebugUnitTest)
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const core = require(path.join(__dirname, "../../main/assets/extensions/beast-helper/media/sniffer-core.js"));
const { parseM3U8, classifyResponse, isBlockedUrl, qualityLabel, sizeOf } = core;

const MASTER = `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-STREAM-INF:BANDWIDTH=1400000,AVERAGE-BANDWIDTH=1200000,RESOLUTION=842x480,CODECS="avc1.4d401e,mp4a.40.2",FRAME-RATE=30
480p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
https://cdn.example.net/v/1080p/index.m3u8?token=abc
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
720p/index.m3u8
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,RESOLUTION=1280x720,URI="720p/iframes.m3u8"
`;

test("master playlist: variants resolved, deduped, best first", () => {
  const r = parseM3U8(MASTER, "https://media.example.org/hls/master.m3u8");
  assert.equal(r.valid, true);
  assert.equal(r.isMaster, true);
  assert.equal(r.drm, false);
  assert.deepEqual(r.variants.map(v => v.height), [1080, 720, 480]);
  assert.equal(r.variants[0].url, "https://cdn.example.net/v/1080p/index.m3u8?token=abc");
  assert.equal(r.variants[1].url, "https://media.example.org/hls/720p/index.m3u8");
  assert.equal(r.variants[2].bandwidth, 1400000);
  assert.equal(r.variants[2].averageBandwidth, 1200000);
  assert.equal(r.variants[2].codecs, "avc1.4d401e,mp4a.40.2");
  assert.equal(r.variants[2].width, 842);
  assert.ok(!r.variants.some(v => v.url.includes("iframes")), "I-frame playlists are not downloadable variants");
});

test("quality labels", () => {
  assert.equal(qualityLabel({ height: 1080, width: 1920 }), "1080p");
  assert.equal(qualityLabel({ height: 720 }), "720p");
  assert.match(qualityLabel({ height: 0, bandwidth: 800000 }), /kbps|Mbps|HLS/);
});

test("media playlist (no variants)", () => {
  const r = parseM3U8("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXTINF:6.0,\nseg1.ts\n#EXT-X-ENDLIST\n", "https://e.org/a/index.m3u8");
  assert.equal(r.valid, true);
  assert.equal(r.isMaster, false);
  assert.equal(r.variants.length, 0);
  assert.equal(r.drm, false);
});

test("not a playlist", () => {
  assert.equal(parseM3U8("<html>nope</html>", "https://e.org/x").valid, false);
  assert.equal(parseM3U8("", "https://e.org/x").valid, false);
});

test("DRM: SAMPLE-AES, Widevine/PlayReady/FairPlay key formats, skd:", () => {
  const seg = "\n#EXTINF:6,\ns.ts\n";
  for (const key of [
    '#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key1",KEYFORMAT="com.apple.streamingkeydelivery"',
    '#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:x",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"',
    '#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="https://k.example/key",KEYFORMAT="com.microsoft.playready"',
    '#EXT-X-KEY:METHOD=AES-128,URI="skd://abc"',
  ]) {
    assert.equal(parseM3U8("#EXTM3U\n" + key + seg, "https://e.org/p.m3u8").drm, true, key);
  }
});

test("AES-128 is refused (encrypted, not listed), though not a DRM key format", () => {
  const r = parseM3U8('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="https://e.org/key.bin"\n#EXTINF:6,\ns.ts\n', "https://e.org/p.m3u8");
  assert.equal(r.drm, false);
  assert.equal(r.encrypted, true);
  assert.equal(r.refuse, true);
});

test("AES-128 session key on a master playlist is refused", () => {
  const r = parseM3U8('#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI="https://e.org/key.bin"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n', "https://e.org/m.m3u8");
  assert.equal(r.refuse, true);
});

test("unencrypted and METHOD=NONE playlists are not refused", () => {
  for (const t of ['#EXTM3U\n#EXTINF:6,\ns.ts\n', '#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\ns.ts\n']) {
    const r = parseM3U8(t, "https://e.org/p.m3u8");
    assert.equal(r.encrypted, false);
    assert.equal(r.refuse, false);
  }
});

test("DRM host matcher (exact + subdomain, not lookalikes) and BBC iPlayer path", () => {
  for (const u of [
    "https://www.youtube.com/watch?v=x", "https://rr3---sn-abc.googlevideo.com/videoplayback", "https://i.ytimg.com/vi/x.jpg",
    "https://www.netflix.com/title/1", "https://ipv4-c001.1.oca.nflxvideo.net/x", "https://video-fra3-1.xx.fbcdn.net/v.mp4",
    "https://usher.ttvnw.net/api/channel/hls/x.m3u8", "https://vod-secure.twitch.tv/x", "https://tv.apple.com/show",
    "https://www.bbc.co.uk/iplayer/episode/b0", "https://www.channel4.com/programmes/x", "https://open.spotify.com/x",
  ]) assert.equal(isBlockedUrl(u), true, u);
  for (const u of [
    "https://www.bbc.co.uk/news/articles/x", "https://apple.com/tv", "https://notyoutube.com/v.mp4",
    "https://example.org/video.mp4", "https://youtube.com.evil.example/v.mp4", "https://archive.org/download/x/x.mp4", "",
  ]) assert.equal(isBlockedUrl(u), false, u);
});

const H = o => Object.entries(o).map(([name, value]) => ({ name, value }));

test("classifyResponse: progressive mp4 over the size floor", () => {
  const c = classifyResponse({ url: "https://e.org/v/clip.mp4", type: "media", statusCode: 206,
    responseHeaders: H({ "Content-Type": "video/mp4", "Content-Range": "bytes 0-1023/52428800" }) });
  assert.deepEqual(c, { kind: "progressive", mime: "video/mp4", bytes: 52428800 });
});

test("classifyResponse: HLS by mime or extension", () => {
  assert.equal(classifyResponse({ url: "https://e.org/master", type: "xmlhttprequest", statusCode: 200,
    responseHeaders: H({ "content-type": "application/vnd.apple.mpegurl" }) }).kind, "hls");
  assert.equal(classifyResponse({ url: "https://e.org/x/index.m3u8?t=1", type: "xmlhttprequest", statusCode: 200,
    responseHeaders: H({ "content-type": "text/plain" }) }).kind, "hls");
});

test("classifyResponse: skips segments, DASH, small files, MSE chunks, errors", () => {
  const cases = [
    { url: "https://e.org/seg12.ts", type: "xmlhttprequest", statusCode: 200, responseHeaders: H({ "content-type": "video/mp2t", "content-length": "900000" }) },
    { url: "https://e.org/chunk.m4s", type: "xmlhttprequest", statusCode: 200, responseHeaders: H({ "content-type": "video/iso.segment", "content-length": "900000" }) },
    { url: "https://e.org/manifest.mpd", type: "xmlhttprequest", statusCode: 200, responseHeaders: H({ "content-type": "application/dash+xml" }) },
    { url: "https://e.org/tiny.mp4", type: "media", statusCode: 200, responseHeaders: H({ "content-type": "video/mp4", "content-length": "1000" }) },
    { url: "https://e.org/range.mp4", type: "xmlhttprequest", statusCode: 206, responseHeaders: H({ "content-type": "video/mp4", "content-range": "bytes 0-1/99999999" }) },
    { url: "https://e.org/missing.mp4", type: "media", statusCode: 404, responseHeaders: H({ "content-type": "video/mp4", "content-length": "99999999" }) },
    { url: "https://e.org/page", type: "main_frame", statusCode: 200, responseHeaders: H({ "content-type": "text/html" }) },
  ];
  for (const d of cases) assert.equal(classifyResponse(d), null, d.url);
});

test("sizeOf: Content-Range total wins, Content-Length only on 200", () => {
  assert.equal(sizeOf(206, H({ "content-range": "bytes 0-99/5000", "content-length": "100" })), 5000);
  assert.equal(sizeOf(200, H({ "content-length": "4096" })), 4096);
  assert.equal(sizeOf(206, H({ "content-length": "100" })), -1);
  assert.equal(sizeOf(206, H({ "content-range": "bytes 0-99/*" })), -1);
});
