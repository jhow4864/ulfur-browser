#!/usr/bin/env python3
"""Losslessly recompress every deflated entry of an APK with zopfli (max-effort deflate).
Stored entries (resources.arsc etc.) stay stored. Output must then be zipaligned + signed.
usage: repack_apk.py in.apk out.apk"""
import sys, zipfile, zlib, multiprocessing as mp
import zopfli.zopfli as Z

SRC, DST = sys.argv[1], sys.argv[2]

def best(name):
    with zipfile.ZipFile(SRC) as z:
        data = z.read(name)
    c = zlib.compressobj(9, zlib.DEFLATED, -15, 9)
    out = c.compress(data) + c.flush()
    it = 1 if len(data) > 8_000_000 else (5 if len(data) > 500_000 else 15)
    zo = Z.compress(data, numiterations=it, gzip_mode=0)[2:-4]   # zlib wrapper -> raw deflate
    if len(zo) < len(out):
        out = zo
    assert zlib.decompress(out, -15) == data, name
    return name, out

class Pre:
    def __init__(self, raw): self.raw = raw
    def compress(self, _): return b""
    def flush(self): return self.raw

if __name__ == "__main__":
    zin = zipfile.ZipFile(SRC)
    infos = zin.infolist()
    todo = sorted((i.filename for i in infos if i.compress_type == zipfile.ZIP_DEFLATED),
                  key=lambda n: -zin.getinfo(n).file_size)
    with mp.Pool(mp.cpu_count()) as p:
        pre = dict(p.imap_unordered(best, todo))
    orig = zipfile._get_compressor
    cur = {}
    zipfile._get_compressor = lambda t, l=None: Pre(cur["raw"]) if t == zipfile.ZIP_DEFLATED else orig(t, l)
    with zipfile.ZipFile(DST, "w") as zout:
        for i in infos:
            ni = zipfile.ZipInfo(i.filename, i.date_time)
            ni.compress_type = i.compress_type
            ni.external_attr = i.external_attr
            cur["raw"] = pre.get(i.filename)
            with zout.open(ni, "w") as w:
                w.write(zin.read(i.filename))
    a = sum(i.compress_size for i in infos)
    b = sum(i.compress_size for i in zipfile.ZipFile(DST).infolist())
    print(f"entries: {a/1e6:.2f} MB -> {b/1e6:.2f} MB")
