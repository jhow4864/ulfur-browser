package com.jamhowman.beastbrowser

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.AndroidImageCodec
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.ImageContrast
import com.jamhowman.beastbrowser.data.ImageSlot
import com.jamhowman.beastbrowser.data.ImageSniffer
import com.jamhowman.beastbrowser.data.ImageSpec
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.ThemeColor
import com.jamhowman.beastbrowser.data.ThemeContrast
import com.jamhowman.beastbrowser.data.ThemeImage
import com.jamhowman.beastbrowser.data.ThemePack
import com.jamhowman.beastbrowser.data.ThemePackError
import com.jamhowman.beastbrowser.data.ThemePackParser
import com.jamhowman.beastbrowser.data.ThemePackResult
import com.jamhowman.beastbrowser.data.ThemePackStore
import com.jamhowman.beastbrowser.data.ThemePacks
import com.jamhowman.beastbrowser.data.ThemePalette
import com.jamhowman.beastbrowser.data.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 2.10 theme packs v2: schema 2 colours and images, image safety, caps, readability over images, Undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemePackV2Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val codec = AndroidImageCodec

    @Before fun setUp() {
        Prefs.init(ctx); Prefs.sp.edit().clear().commit()
        File(ctx.filesDir, ThemePackStore.DIR).deleteRecursively()
        ThemePacks.resetForTests(ctx)
        SafeStart.reset()
    }

    // ---------------------------------------------------------------- helpers

    private fun bitmap(w: Int, h: Int, f: (Int, Int) -> Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { for (y in 0 until h) for (x in 0 until w) setPixel(x, y, f(x, y)) }

    private fun encode(b: Bitmap, fmt: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, q: Int = 90): ByteArray =
        ByteArrayOutputStream().also { b.compress(fmt, q, it) }.toByteArray()

    private fun grey(w: Int = 64, h: Int = 128, v: Int = 0x80) = encode(bitmap(w, h) { _, _ -> Color.rgb(v, v, v) })

    private fun json(images: String = "", extraColors: String = "", schema: Int = 2) = """{"format":"ulfurtheme","schema":$schema,"name":"V2","base":"ash",
        "colors":{"dark":{"accent":"#8FA3BF","onAccent":"#0E0E12","background":"#15171C","surface":"#1D2027","text":"#EDEFF3",
        "textSecondary":"#A7ADBA","toolbar":"#1A1D23","onToolbar":"#EDEFF3","divider":"#2E323C","surfaceVariant":"#262A33"$extraColors},
        "light":{"background":"#F3F4F6","text":"#16181D"}}${if (images.isEmpty()) "" else ",\"images\":{$images}"}}"""

    private fun homeSpec(file: String = "images/home.png", extra: String = "") =
        """"home":{"file":"$file","fit":"cover","focus":{"x":0.5,"y":0.4},"scrim":0.3$extra}"""

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n).apply {
                    if (stored) { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = size; crc = CRC32().also { it.update(b) }.value }
                })
                z.write(b); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun parse(b: ByteArray) = ThemePackParser.parse(b, codec)
    private fun ok(b: ByteArray) = when (val r = parse(b)) { is ThemePackResult.Ok -> r.pack; is ThemePackResult.Bad -> throw AssertionError("refused: $r") }
    private fun err(b: ByteArray) = when (val r = parse(b)) { is ThemePackResult.Bad -> r.error; is ThemePackResult.Ok -> throw AssertionError("accepted") }

    /** PNG with only a (valid-CRC) IHDR claiming [w]×[h]. */
    private fun pngHeader(w: Int, h: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        fun chunk(t: String, d: ByteArray): ByteArray {
            val tb = t.toByteArray()
            val crc = CRC32().apply { update(tb); update(d) }.value
            return be(d.size) + tb + d + be(crc.toInt())
        }
        val ihdr = be(w) + be(h) + byteArrayOf(8, 2, 0, 0, 0)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + extra +
            chunk("IDAT", java.util.zip.Deflater().run { setInput(ByteArray(64)); finish(); val o = ByteArray(128); o.copyOf(deflate(o)) }) + chunk("IEND", ByteArray(0))
    }

    private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- schema 1 unchanged

    @Test fun designerSchema1PacksImportUnchanged() {
        for (name in listOf("Glacier", "Wildfire")) {
            val bytes = File("../sample-themes/$name.ulfurtheme").readBytes()
            val p = ok(bytes)
            assertEquals(1, p.schema)
            assertEquals(name, p.name)
            assertTrue(p.images.isEmpty() && p.imageSpecs.isEmpty())
            assertNull(p.surfaces(true)); assertNull(p.surfaces(false))
            // Same id formula as 2.9 (sha256 of the canonical JSON), so installed 2.9 packs keep their ids.
            assertEquals(sha(p.toJson().toString()).take(16), p.id)
            assertFalse(p.toJson().has("images"))
            // Palette = preset with only the accent tokens changed (no surfaces anywhere).
            val pal = p.palette(true)
            assertNull(pal.surfaces)
            assertEquals(p.dark["accent"], pal.color)
            assertEquals(ThemePalette.of(p.base, true).copy(color = pal.color, accentStart = pal.accentStart, accentEnd = pal.accentEnd,
                onColor = pal.onColor, accentText = pal.accentText, surfaceTint = pal.surfaceTint, eye = pal.eye), pal)
            // Parses the same with or without an image codec (2.9 behaviour).
            assertEquals(p, (ThemePackParser.parse(bytes) as ThemePackResult.Ok).pack)
        }
    }

    @Test fun schema1IgnoresSurfaceKeysAndRefusesImages() {
        val p = ok(json(schema = 1).toByteArray())
        assertTrue(p.dark.keys.none { it in ThemePackParser.SURFACE_KEYS })
        assertEquals(ThemePackError.BAD_CONTENTS, err(zip("theme.json" to json(schema = 1).toByteArray(), "images/home.png" to grey())))
    }

    @Test fun schema1KeepsItsOldCaps() {
        val readme = ByteArray(ThemePackParser.MAX_FILE_BYTES_V1).also { java.util.Random(1).nextBytes(it) }
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to json(schema = 1).toByteArray(), "README.txt" to readme, stored = true)))
        // The same README is fine in a schema 2 pack.
        ok(zip("theme.json" to json().toByteArray(), "README.txt" to readme, stored = true))
    }

    // ---------------------------------------------------------------- schema 2 parsing

    @Test fun parsesSchema2ColoursAndImages() {
        val p = ok(zip("theme.json" to json(homeSpec()).toByteArray(), "images/home.png" to grey()))
        assertEquals(2, p.schema)
        val s = p.surfaces(true)!!
        assertEquals(ThemeColor.parse("#15171C"), s.background)
        assertEquals(ThemeColor.parse("#EDEFF3"), s.onToolbar)
        assertNull(p.surfaces(false)!!.surface)                // light: unset → Ulfur default
        assertEquals(ThemeContrast.SURFACE_LIGHT, p.surfaces(false)!!.resolved(false).surface)
        val spec = p.imageSpecs.getValue(ImageSlot.HOME)
        assertEquals(0.4f, spec.focusY, 1e-6f); assertEquals(0.3f, spec.scrim, 1e-6f); assertTrue(spec.scrimAuto)
        assertEquals(64, p.images.getValue(ImageSlot.HOME).width)
        assertEquals(p, (ThemePackParser.parseJson(p.toJson().toString().toByteArray(),
            mapOf(p.storedName(ImageSlot.HOME) to p.images.getValue(ImageSlot.HOME).bytes), codec, sanitize = false) as ThemePackResult.Ok).pack)
    }

    @Test fun sampleSlatePackParses() {
        val p = ok(File("../sample-themes/Slate.ulfurtheme").readBytes())
        assertEquals(setOf(ImageSlot.HOME, ImageSlot.TOOLBAR, ImageSlot.HEADER), p.images.keys)
        assertEquals(1080 to 2400, p.images.getValue(ImageSlot.HOME).let { it.width to it.height })
        // Every image is readable in both modes once the scrim is auto-raised.
        for (night in listOf(true, false)) for (slot in p.images.keys) {
            val v = p.scrimVerdict(slot, night)!!
            assertTrue("$slot night=$night $v", v.ratio >= 4.5)
        }
        assertTrue(ThemeContrast.warnings(p, true).toString(), ThemeContrast.warnings(p, true).isEmpty())
    }

    @Test fun rejectsBadImageSpecs() {
        listOf(
            homeSpec(extra = ",\"fit\":\"contain\"").replace("\"fit\":\"cover\",", ""),
            homeSpec().replace("0.4", "1.4"), homeSpec().replace("0.3", "0.95"), homeSpec().replace("0.3", "-0.1"),
            homeSpec(extra = ",\"scrimAuto\":\"yes\""), homeSpec(extra = ",\"scrimMax\":0.1"),
            homeSpec(file = "home.png"), homeSpec(file = "images/../theme.json"), homeSpec(file = "images/a/b.png"),
        ).forEach { assertEquals(it, ThemePackError.NOT_A_THEME, err(zip("theme.json" to json(it).toByteArray(), "images/home.png" to grey()))) }
        // Referenced but missing; present but unreferenced; one file for two slots.
        assertEquals(ThemePackError.NOT_A_THEME, err(zip("theme.json" to json(homeSpec()).toByteArray())))
        assertEquals(ThemePackError.BAD_CONTENTS, err(zip("theme.json" to json(homeSpec()).toByteArray(), "images/home.png" to grey(), "images/x.png" to grey())))
        assertEquals(ThemePackError.NOT_A_THEME, err(zip("theme.json" to json(homeSpec() + "," + homeSpec().replace("\"home\"", "\"toolbar\"")).toByteArray(), "images/home.png" to grey())))
        // Unknown slots are ignored.
        ok(zip("theme.json" to json(homeSpec() + ",\"sidebar2\":{\"file\":\"images/q.png\"}").toByteArray(), "images/home.png" to grey()))
    }

    // ---------------------------------------------------------------- sniffing

    @Test fun sniffsByMagicBytesNotExtension() {
        val png = grey(); val jpg = encode(bitmap(40, 30) { _, _ -> Color.GRAY }, Bitmap.CompressFormat.JPEG)
        @Suppress("DEPRECATION") val webp = encode(bitmap(40, 30) { _, _ -> Color.GRAY }, Bitmap.CompressFormat.WEBP)
        assertEquals(ImageSniffer.Result.Ok(ImageSniffer.Format.PNG, 64, 128), ImageSniffer.sniff(png))
        assertEquals(ImageSniffer.Result.Ok(ImageSniffer.Format.JPEG, 40, 30), ImageSniffer.sniff(jpg))
        assertEquals(ImageSniffer.Result.Ok(ImageSniffer.Format.WEBP, 40, 30), ImageSniffer.sniff(webp))
        // A JPEG named .png and a PNG named .webp are fine (content decides); a text file named .png is not.
        ok(zip("theme.json" to json(homeSpec(file = "images/photo.webp")).toByteArray(), "images/photo.webp" to png))
        ok(zip("theme.json" to json(homeSpec()).toByteArray(), "images/home.png" to jpg))
        assertEquals(ThemePackError.BAD_IMAGE, err(zip("theme.json" to json(homeSpec()).toByteArray(), "images/home.png" to "hello".toByteArray())))
    }

    @Test fun refusesSvgGifHeicAndAnimatedImages() {
        val svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>".toByteArray()
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(32)
        val apng = pngHeader(10, 10, extra = byteArrayOf(0, 0, 0, 8) + "acTL".toByteArray() + ByteArray(8) + ByteArray(4))
        // Animated WebP: RIFF/WEBP/VP8X with the animation flag.
        val awebp = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) +
            byteArrayOf(0x02, 0, 0, 0) + byteArrayOf(9, 0, 0, 9, 0, 0) + ByteArray(16)
        assertEquals(ImageSniffer.Result.Refused("svg"), ImageSniffer.sniff(svg))
        assertEquals(ImageSniffer.Result.Refused("gif"), ImageSniffer.sniff(gif))
        assertEquals(ImageSniffer.Result.Refused("heic"), ImageSniffer.sniff(heic))
        assertEquals(ImageSniffer.Result.Refused("animated"), ImageSniffer.sniff(apng))
        assertEquals(ImageSniffer.Result.Refused("animated"), ImageSniffer.sniff(awebp))
        listOf(svg, gif, heic, apng, awebp).forEach {
            assertEquals(ThemePackError.BAD_IMAGE, err(zip("theme.json" to json(homeSpec()).toByteArray(), "images/home.png" to it)))
        }
    }

    // ---------------------------------------------------------------- bombs and caps

    @Test fun pixelBombRefusedFromBoundsWithoutDecoding() {
        val bomb = pngHeader(30000, 30000)
        assertTrue(bomb.size < 200)
        assertEquals(ImageSniffer.Result.Refused("too many pixels"), ImageSniffer.sniff(bomb))
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to json(homeSpec()).toByteArray(), "images/home.png" to bomb)))
        // The codec's own inJustDecodeBounds check also refuses it, without allocating 3.6 GB.
        assertNull(codec.sanitize(bomb, 2400))
        val b = AndroidImageCodec.bounds(bomb)
        if (b != null) assertEquals(30000, b.width)
    }

    @Test fun dimensionAndMegapixelCapsAreExact() {
        fun s(w: Int, h: Int) = ImageSniffer.sniff(pngHeader(w, h))
        assertTrue(s(4096, 3906) is ImageSniffer.Result.Ok)                 // 15,998,976 px: under 16 MP
        assertEquals(ImageSniffer.Result.Refused("too many pixels"), s(4096, 3907))   // 16,003,072 px
        assertEquals(ImageSniffer.Result.Refused("too many pixels"), s(4097, 100))    // side
        assertEquals(ImageSniffer.Result.Refused("too many pixels"), s(100, 4097))
        assertTrue(s(4096, 100) is ImageSniffer.Result.Ok)
        assertEquals(ImageSniffer.Result.Refused("bad header"), s(0, 10))
    }

    @Test fun filesJustOverEachCapAreRefused() {
        val j = json(homeSpec()).toByteArray()
        fun noise(n: Int) = ByteArray(n).also { java.util.Random(7).nextBytes(it) }
        // Image entry: 2 MiB + 1.
        val bigImage = grey() + noise(ThemePackParser.MAX_IMAGE_BYTES + 1 - grey().size)
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to j, "images/home.png" to bigImage, stored = true)))
        // 5 images (cap 4).
        val five = (1..5).map { "images/i$it.png" to grey() }.toTypedArray()
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to j, *five)))
        // Total uncompressed: 3 × 2 MiB images + theme.json > 6 MiB.
        val twoMb = ByteArray(ThemePackParser.MAX_IMAGE_BYTES).also { grey().copyInto(it) }
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to j, "images/a.png" to twoMb, "images/b.png" to twoMb, "images/c.png" to twoMb, stored = true)))
        // Whole file: 8 MiB + 1.
        assertEquals(ThemePackError.TOO_LARGE, err(ByteArray(ThemePackParser.MAX_FILE_BYTES + 1)))
        // theme.json: 64 KiB + 1.
        val bigJson = json(homeSpec()).dropLast(1) + ",\"pad\":\"" + "x".repeat(ThemePackParser.MAX_JSON_BYTES) + "\"}"
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to bigJson.toByteArray(), "images/home.png" to grey(), stored = true)))
        // Highly compressible README (zip-bomb ratio) in a schema 2 pack.
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to j, "images/home.png" to grey(), "README.txt" to ByteArray(1_500_000))))
    }

    @Test fun zipSlipUnderImagesStillRefused() {
        val j = json(homeSpec()).toByteArray()
        listOf("images/../theme.json", "images/../../x.png", "images/a/b.png", "images/.hidden.png", "images\\home.png",
            "/images/home.png", "images/C:home.png", "img/home.png", "images//home.png").forEach { n ->
            assertEquals(n, ThemePackError.BAD_CONTENTS, err(zip("theme.json" to j, n to grey())))
        }
        assertFalse(ThemePackParser.safeImageEntry("images/../x"))
        assertTrue(ThemePackParser.safeImageEntry("images/home.png"))
    }

    // ---------------------------------------------------------------- re-encoding

    @Test fun reEncodeStripsExifGpsAndOriginalBytes() {
        // A camera-style original (q100), as a phone would write it.
        val clean = encode(bitmap(80, 60) { x, y -> Color.rgb(x * 3, y * 4, 90) }, Bitmap.CompressFormat.JPEG, q = 100)
        val marker = "GPSLatitude 51.5007N SECRET-CAMERA-SERIAL-1234"
        val exif = "Exif\u0000\u0000MM\u0000*$marker".toByteArray(Charsets.ISO_8859_1)
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((exif.size + 2) ushr 8).toByte(), (exif.size + 2).toByte()) + exif
        val tagged = clean.copyOfRange(0, 2) + app1 + clean.copyOfRange(2, clean.size)
        assertTrue(String(tagged, Charsets.ISO_8859_1).contains(marker))
        val p = ok(zip("theme.json" to json(homeSpec(file = "images/photo.jpg")).toByteArray(), "images/photo.jpg" to tagged))
        val out = p.images.getValue(ImageSlot.HOME).bytes
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse("SECRET", text.contains("SECRET")); assertFalse("GPS", text.contains("GPS")); assertFalse("Exif", text.contains("Exif"))
        assertFalse("same as tagged", out.contentEquals(tagged)); assertFalse("same as original", out.contentEquals(clean))
        // No run of 64 original bytes after the header survives (pixels were decoded and re-compressed).
        val orig = tagged.copyOfRange(tagged.size / 2, tagged.size / 2 + 64)
        assertFalse("original run kept", String(out, Charsets.ISO_8859_1).contains(String(orig, Charsets.ISO_8859_1)))
        // Stored copy is the re-encoded one too.
        val store = ThemePacks.store()!!
        store.apply(p)
        val stored = File(store.dir, "${p.id}/home.jpg").readBytes()
        assertTrue("stored", stored.contentEquals(out))
    }

    @Test fun downsamplesToTheSlotSize() {
        val big = encode(bitmap(3000, 3000) { _, _ -> Color.GRAY }, Bitmap.CompressFormat.JPEG)
        val p = ok(zip("theme.json" to json(homeSpec(file = "images/b.jpg").replace("\"home\"", "\"toolbar\"")).toByteArray(), "images/b.jpg" to big))
        val img = p.images.getValue(ImageSlot.TOOLBAR)
        assertTrue("${img.width}", maxOf(img.width, img.height) <= ImageSlot.TOOLBAR.maxSide)
    }

    // ---------------------------------------------------------------- readability over images

    private fun img(w: Int, h: Int, f: (Int, Int) -> Int): ThemeImage {
        val px = IntArray(w * h) { f(it % w, it / w) }
        return ThemeImage(ByteArray(1), "png", w, h, w, h, px)
    }

    @Test fun usesDarkestAndLightestNotAverage() {
        // Mid grey with one bright stripe inside the home text band: the average would pass, the lightest point fails.
        val i = img(108, 240) { _, y -> if (y in 100..104) Color.WHITE else Color.rgb(40, 40, 40) }
        val spec = ImageSpec(ImageSlot.HOME, "images/h.png", scrim = 0f, scrimAuto = false)
        val e = ImageContrast.extremes(i, ImageSlot.HOME, spec, Color.BLACK, 0f)!!
        assertEquals(1.0, e.lightest, 1e-6)
        assertTrue(ImageContrast.worstRatio(Color.WHITE, e) < 1.01)
        val avgLum = (5 * 1.0 + 235 * androidx.core.graphics.ColorUtils.calculateLuminance(Color.rgb(40, 40, 40))) / 240
        assertTrue("average would pass", (1.05) / (avgLum + 0.05) > 4.5)
        // Same image with dark text: the darkest point decides.
        val dark = img(108, 240) { _, y -> if (y in 100..104) Color.BLACK else Color.rgb(230, 230, 230) }
        assertTrue(ImageContrast.worstRatio(Color.BLACK, ImageContrast.extremes(dark, ImageSlot.HOME, spec, Color.WHITE, 0f)!!) < 1.01)
    }

    @Test fun figuresInTheBottom30PercentDontAffectHomeText() {
        // A white figure in the bottom 30% (Designer's figure-safe area) only; white text everywhere else reads fine.
        val i = img(108, 240) { _, y -> if (y > 240 * 0.72) Color.WHITE else Color.BLACK }
        val spec = ImageSpec(ImageSlot.HOME, "images/h.png", focusY = 0.5f, scrim = 0f, scrimAuto = false)
        assertTrue(ImageContrast.resolve(i, spec, Color.WHITE, Color.BLACK).ratio >= 4.5)
        // …but the same figure in the 22–70% band is caught.
        val j = img(108, 240) { _, y -> if (y in 120..150) Color.WHITE else Color.BLACK }
        assertTrue(ImageContrast.resolve(j, spec, Color.WHITE, Color.BLACK).ratio < 4.5)
    }

    @Test fun scrimAutoRaisesToMeet45OrWarns() {
        val i = img(108, 240) { x, _ -> if (x % 2 == 0) Color.WHITE else Color.rgb(120, 120, 120) }
        val auto = ImageSpec(ImageSlot.HOME, "images/h.png", scrim = 0.1f, scrimAuto = true, scrimMax = 0.9f)
        val v = ImageContrast.resolve(i, auto, Color.WHITE, Color.BLACK)
        assertTrue(v.raised); assertTrue(v.ratio >= 4.5); assertTrue(v.scrim > 0.1f && v.scrim <= 0.9f)
        // Minimal: one step less would fail.
        val e = ImageContrast.extremes(i, ImageSlot.HOME, auto, Color.BLACK, v.scrim - 0.01f)!!
        assertTrue(ImageContrast.worstRatio(Color.WHITE, e) < 4.5)
        val fixed = auto.copy(scrimAuto = false)
        val f = ImageContrast.resolve(i, fixed, Color.WHITE, Color.BLACK)
        assertFalse(f.raised); assertEquals(0.1f, f.scrim, 1e-6f); assertTrue(f.ratio < 4.5)
        val capped = auto.copy(scrimMax = 0.2f)
        assertTrue(ImageContrast.resolve(i, capped, Color.WHITE, Color.BLACK).ratio < 4.5) // can't reach: warning stays
    }

    @Test fun packWarnsForUnreadableImageAndColourPairs() {
        val white = encode(bitmap(64, 128) { _, _ -> Color.WHITE })
        val p = ok(zip("theme.json" to json(homeSpec(extra = ",\"scrimAuto\":false").replace("0.3", "0.0")).toByteArray(), "images/home.png" to white))
        assertTrue(ThemeContrast.warnings(p, true).contains("Text over the home image"))
        val low = ok(json(extraColors = ",\"onToolbar\":\"#2A2D33\"").replace("\"onToolbar\":\"#EDEFF3\",", "").toByteArray())
        assertTrue(ThemeContrast.lowContrast(low.palette(true)).any { it.label == "Toolbar text on toolbar" })
        val lowBg = ok(json().replace("\"background\":\"#15171C\"", "\"background\":\"#E0E0E0\"").toByteArray())
        assertTrue(ThemeContrast.lowContrast(lowBg.palette(true)).map { it.label }.containsAll(listOf("Text on background", "Ulfur's own text on background")))
    }

    // ---------------------------------------------------------------- apply / undo / safe start

    private fun tree(d: File): List<String> = d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path + ":" + it.readBytes().contentHashCode() }.sorted().toList()

    @Test fun undoRestoresExactlyWithImages() {
        val store = ThemePacks.store()!!
        Prefs.setAccent(Realm.PLAY, ThemePreset.FROST)
        val schema1 = (ThemePackParser.parse(File("../sample-themes/Glacier.ulfurtheme").readBytes()) as ThemePackResult.Ok).pack
        store.apply(schema1)
        val before = Triple(Prefs.sp.all.toMap(), tree(store.dir), AppTheme.palette(ctx, Realm.PLAY, false))
        val v2 = ok(File("../sample-themes/Slate.ulfurtheme").readBytes())
        val snap = store.apply(v2)
        assertTrue(File(store.dir, "${v2.id}/home.png").isFile || File(store.dir, "${v2.id}/home.jpg").isFile)
        assertEquals(v2, store.active())
        assertNotNull(AppTheme.palette(ctx, Realm.PLAY, false).surfaces)
        store.undo(snap)
        assertEquals(before, Triple(Prefs.sp.all.toMap(), tree(store.dir), AppTheme.palette(ctx, Realm.PLAY, false)))
        assertFalse(File(store.dir, v2.id).exists())
    }

    @Test fun storedPackReloadsIdentically() {
        val store = ThemePacks.store()!!
        val v2 = ok(File("../sample-themes/Slate.ulfurtheme").readBytes())
        store.apply(v2)
        assertEquals(v2, store.load(v2.id))
        store.delete(v2.id)
        assertFalse(File(store.dir, v2.id).exists()); assertNull(store.activeId())
    }

    @Test fun privateWorkGhostAndSafeStartAreUntouched() {
        val store = ThemePacks.store()!!
        store.apply(ok(File("../sample-themes/Slate.ulfurtheme").readBytes()))
        assertNotNull(AppTheme.palette(ctx, Realm.PLAY, false).surfaces)
        assertNull(AppTheme.palette(ctx, Realm.PLAY, true).surfaces)      // private tab
        assertNull(AppTheme.palette(ctx, Realm.WORK, false).surfaces)
        assertNull(AppTheme.palette(ctx, Realm.GHOST).surfaces)
        SafeStart.themePaused = true
        assertNull(AppTheme.palette(ctx, Realm.PLAY, false).surfaces)
        assertNull(ThemePacks.activeBitmap(ImageSlot.HOME))
        SafeStart.themePaused = false
        assertNotNull(ThemePacks.activeBitmap(ImageSlot.HOME))
    }

    @Test fun nothingChangesBeforeApply() {
        val store = ThemePacks.store()!!
        val before = Triple(Prefs.sp.all.toMap(), tree(store.dir.also { it.mkdirs() }), AppTheme.palette(ctx, Realm.PLAY, false))
        val p = ok(File("../sample-themes/Slate.ulfurtheme").readBytes())
        ThemeContrast.warnings(p, true); ThemeContrast.warnings(p, false); p.palette(true)
        assertEquals(before, Triple(Prefs.sp.all.toMap(), tree(store.dir), AppTheme.palette(ctx, Realm.PLAY, false)))
    }
}
