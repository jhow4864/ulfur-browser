package com.jamhowman.beastbrowser

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.AndroidImageCodec
import com.jamhowman.beastbrowser.data.ImageContrast
import com.jamhowman.beastbrowser.data.ImageSlot
import com.jamhowman.beastbrowser.data.ImageSniffer
import com.jamhowman.beastbrowser.data.PhotoImage
import com.jamhowman.beastbrowser.data.PhotoTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.ThemeContrast
import com.jamhowman.beastbrowser.data.ThemeImage
import com.jamhowman.beastbrowser.data.ThemePack
import com.jamhowman.beastbrowser.data.ThemePackParser
import com.jamhowman.beastbrowser.data.ThemePackResult
import com.jamhowman.beastbrowser.data.ThemePackStore
import com.jamhowman.beastbrowser.data.ThemePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** 2.11 "Create theme from photo", including the Apk Tester checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoThemeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val src = File("src/main")

    @Before fun setUp() {
        Prefs.init(ctx); Prefs.sp.edit().clear().commit()
        File(ctx.filesDir, ThemePackStore.DIR).deleteRecursively()
        ThemePacks.resetForTests(ctx)
        SafeStart.reset()
    }

    // ---------------------------------------------------------------- photos

    private fun photo(w: Int, h: Int, kind: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint()
        val rnd = Random(kind)
        when (kind) {
            0 -> { c.drawColor(Color.rgb(30, 90, 200)); p.color = Color.rgb(250, 140, 20); c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), p) } // sky + sand
            1 -> c.drawColor(Color.WHITE)
            2 -> c.drawColor(Color.BLACK)
            3 -> for (i in 0 until 60) { p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)); c.drawRect(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat() * w, rnd.nextFloat() * h, p) }
            4 -> { for (y in 0 until h step 8) { p.color = if ((y / 8) % 2 == 0) Color.WHITE else Color.BLACK; c.drawRect(0f, y.toFloat(), w.toFloat(), y + 8f, p) } } // stripes
            5 -> c.drawColor(Color.rgb(0, 255, 0))
            else -> c.drawColor(Color.rgb(128, 128, 128))
        }
        return b
    }

    private fun jpeg(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()

    /** A JPEG with an APP1 Exif segment carrying GPS-ish markers right after SOI. */
    private fun jpegWithExif(b: Bitmap): ByteArray {
        val plain = jpeg(b)
        val tiff = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00) + "GPSLatitude=51.5074N;GPSLongitude=0.1278W;UlfurSecretMarker".toByteArray()
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    private fun contains(hay: ByteArray, needle: String): Boolean = String(hay, Charsets.ISO_8859_1).contains(needle)

    private fun homeImage(kind: Int, w: Int = 540, h: Int = 1200): ThemeImage {
        val r = PhotoImage.decode(jpeg(photo(w, h, kind))) as PhotoImage.Result.Ok
        return PhotoImage.homeImage(r.bitmap, 0.5f, 0.5f)!!
    }

    // ---------------------------------------------------------------- palette -> colours

    @Test fun paletteExtractsDominantAndAccentColours() {
        val px = IntArray(1000) { if (it < 700) 0xFF1E5AC8.toInt() else 0xFFFA8C14.toInt() }
        val ex = PhotoTheme.extract(px)
        assertEquals(2, ex.size)
        assertEquals(0xFF1E5AC8.toInt() and 0xF0F0F0, ex[0].first and 0xF0F0F0)
        assertEquals(700, ex[0].second)
        assertTrue(PhotoTheme.extract(IntArray(5000) { Random(it).nextInt() or 0xFF000000.toInt() }).size in 4..6)
        assertTrue(PhotoTheme.extract(IntArray(0)).isEmpty())
    }

    @Test fun suggestionsCoverEverySchema2Key() {
        val d = PhotoTheme.suggest("Beach", homeImage(0).sample, image = homeImage(0))
        val keys = listOf("background", "surface", "surfaceVariant", "text", "textSecondary", "toolbar", "onToolbar", "divider",
            "accent", "accentStart", "accentEnd", "onAccent", "accentText")
        keys.forEach { assertNotNull(it, d.dark[it]); assertNotNull(it, d.light[it]) }
        assertTrue((d.dark.keys + d.light.keys).all { it in ThemePackParser.COLOR_KEYS || it in ThemePackParser.SURFACE_KEYS })
    }

    @Test fun everySuggestionReaches45IncludingScrim() {
        for (kind in 0..6) {
            val img = homeImage(kind)
            val swatches = PhotoTheme.extract(img.sample).map { it.first }.ifEmpty { listOf(0xFF808080.toInt()) }
            for (acc in swatches + listOf(null)) {
                val d = PhotoTheme.suggest("T$kind", img.sample, acc, img)
                val pack = PhotoTheme.toPack(d)
                for (night in listOf(true, false)) {
                    assertTrue("kind $kind acc $acc night $night: ${ThemeContrast.warnings(pack, night)}", ThemeContrast.warnings(pack, night).isEmpty())
                    ThemeContrast.pairs(pack.palette(night)).forEach { assertTrue("${it.label} ${it.ratio}", it.ratio >= ThemeContrast.MIN_TEXT) }
                    val v = pack.scrimVerdict(ImageSlot.HOME, night)!!
                    assertTrue("scrim verdict ${v.ratio}", v.ratio >= ThemeContrast.MIN_TEXT)
                }
            }
        }
    }

    @Test fun swatchesGreyOutLowContrast() {
        val img = homeImage(3)
        for (night in listOf(true, false)) {
            val sw = PhotoTheme.swatches(img.sample, night)
            assertTrue(sw.size in 1..6)
            val bg = PhotoTheme.backgroundFor(PhotoTheme.extract(img.sample).first().first, night)
            sw.forEach { assertEquals(ThemeContrast.ratio(it.color, bg) >= 4.5, it.readable) }
        }
        // White on a light background can't be read: greyed out.
        val light = PhotoTheme.swatches(IntArray(100) { 0xFFFFFFFF.toInt() }, night = false)
        assertFalse(light.single().readable)
    }

    @Test fun tweaksThatWouldFailAreAutoFixed() {
        val img = homeImage(0)
        var d = PhotoTheme.suggest("T", img.sample, image = img)
        for (night in listOf(true, false)) {
            val bg = (if (night) d.dark else d.light).getValue("background")
            val (nd, fixed) = PhotoTheme.tweak(d, night, "text", bg) // text = background: unreadable
            assertTrue(fixed)
            val m = if (night) nd.dark else nd.light
            assertTrue(ThemeContrast.ratio(m.getValue("text"), m.getValue("background")) >= 4.5)
            val (nd2, _) = PhotoTheme.tweak(nd, night, "toolbar", m.getValue("onToolbar"))
            val m2 = if (night) nd2.dark else nd2.light
            assertTrue(ThemeContrast.ratio(m2.getValue("onToolbar"), m2.getValue("toolbar")) >= 4.5)
            val (nd3, _) = PhotoTheme.tweak(nd2, night, "accent", 0xFF777777.toInt())
            listOf(true, false).forEach { assertTrue(ThemeContrast.warnings(PhotoTheme.toPack(nd3), it).isEmpty()) }
            d = nd3
        }
    }

    @Test fun scrimSliderClampedAtReadableMinimum() {
        val img = homeImage(4) // black/white stripes: needs darkening
        val d = PhotoTheme.suggest("Stripes", img.sample, image = img)
        val min = PhotoTheme.minScrim(d)
        assertTrue(min > 0f)
        assertEquals(min, PhotoTheme.clampScrim(d, 0f))
        assertEquals(PhotoTheme.SCRIM_MAX, PhotoTheme.clampScrim(d, 1f))
        val pack = PhotoTheme.toPack(d.copy(scrim = 0f))
        assertTrue(pack.imageSpecs.getValue(ImageSlot.HOME).scrim >= min)
        val activity = File(src, "java/com/jamhowman/beastbrowser/ui/PhotoThemeActivity.kt").readText()
        assertTrue(activity.contains("PhotoTheme.clampScrim(d, p / 100f)"))
    }

    // ---------------------------------------------------------------- round trip

    @Test fun savedPackRoundTripsThroughImportValidatorWithSameContrast() {
        val img = homeImage(0)
        val pack = PhotoTheme.toPack(PhotoTheme.suggest("Beach day", img.sample, image = img))
        val bytes = PhotoTheme.export(pack)
        val back = (ThemePackParser.parse(bytes, AndroidImageCodec) as ThemePackResult.Ok).pack
        assertEquals(pack.name, back.name); assertEquals(pack.dark, back.dark); assertEquals(pack.light, back.light)
        assertEquals(pack.imageSpecs.keys, back.imageSpecs.keys)
        assertEquals(pack.imageSpecs.getValue(ImageSlot.HOME).scrim, back.imageSpecs.getValue(ImageSlot.HOME).scrim, 1e-3f)
        for (night in listOf(true, false)) assertTrue(ThemeContrast.warnings(back, night).isEmpty())
        // Saved in the store (not activated) and loaded back the same way.
        val store = ThemePacks.store()!!
        val id = store.install(pack)
        assertEquals(null, store.activeId())
        val loaded = store.load(id)!!
        assertEquals(pack.dark, loaded.dark); assertEquals(pack.light, loaded.light)
        assertEquals(pack.toJson().toString(), loaded.toJson().toString())
    }

    // ---------------------------------------------------------------- Apk Tester: EXIF / sizes / storage

    @Test fun check_exifAndGpsStrippedOnSave() {
        val raw = jpegWithExif(photo(600, 1200, 0))
        assertTrue(contains(raw, "Exif") && contains(raw, "GPSLatitude"))
        val bmp = (PhotoImage.decode(raw) as PhotoImage.Result.Ok).bitmap
        val crop = PhotoImage.cropHome(bmp, 0.5f, 0.5f)
        val img = PhotoImage.homeImage(bmp, 0.5f, 0.5f)!!
        for (b in listOf(crop, img.bytes)) {
            assertFalse(contains(b, "Exif")); assertFalse(contains(b, "GPS")); assertFalse(contains(b, "UlfurSecretMarker"))
        }
        val pack = PhotoTheme.toPack(PhotoTheme.suggest("x", img.sample, image = img))
        val zip = PhotoTheme.export(pack)
        val store = ThemePacks.store()!!
        val id = store.install(pack)
        File(File(ctx.filesDir, ThemePackStore.DIR), id).walk().filter { it.isFile }.forEach {
            assertFalse(contains(it.readBytes(), "GPS")); assertFalse(contains(it.readBytes(), "Exif"))
        }
        assertFalse(contains(java.util.zip.ZipInputStream(zip.inputStream()).let { z -> z.nextEntry; z.nextEntry; z.readBytes() }, "Exif"))
    }

    @Test fun check_hugeTinyCorruptHandledWithBoundedDecode() {
        // Bounded sample sizes: always within 4096 px a side and 16 MP.
        for ((w, h) in listOf(8160 to 6120, 20000 to 20000, 4096 to 4096, 12000 to 1000, 4000 to 3000, 100 to 100)) {
            val s = PhotoImage.sampleSize(w, h)
            assertTrue("$w x $h / $s", w / s <= ImageSniffer.MAX_SIDE && h / s <= ImageSniffer.MAX_SIDE &&
                (w / s).toLong() * (h / s) <= ImageSniffer.MAX_PIXELS)
            assertEquals(Integer.bitCount(s), 1)
        }
        assertEquals(1, PhotoImage.sampleSize(4000, 3000))
        // A real photo past the 4096 cap decodes downsampled.
        val wide = PhotoImage.decode(jpeg(photo(5000, 600, 0))) as PhotoImage.Result.Ok
        assertTrue(wide.bitmap.width <= ImageSniffer.MAX_SIDE)
        assertNotNull(PhotoImage.homeImage(wide.bitmap, 0.5f, 0.5f))
        // Tiny, corrupt, empty, truncated and over-cap input: refused, never thrown.
        assertTrue(PhotoImage.decode(jpeg(photo(10, 10, 0))) is PhotoImage.Result.Refused)
        assertTrue(PhotoImage.decode(Random(1).nextBytes(5000)) is PhotoImage.Result.Refused)
        assertTrue(PhotoImage.decode(ByteArray(0)) is PhotoImage.Result.Refused)
        assertTrue(PhotoImage.decode(null) is PhotoImage.Result.Refused)
        val trunc = jpeg(photo(400, 800, 3)).let { it.copyOf(it.size / 3) }
        PhotoImage.decode(trunc) // Ok (partial) or Refused, but no crash
        assertEquals(null, PhotoImage.readCapped(object : java.io.InputStream() {
            var n = 0L
            override fun read(): Int = if (n++ < PhotoImage.MAX_INPUT_BYTES + 10) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { val k = minOf(len.toLong(), PhotoImage.MAX_INPUT_BYTES + 10 - n).toInt(); if (k <= 0) return -1; n += k; return k }
        }))
        val decode = File(src, "java/com/jamhowman/beastbrowser/data/PhotoImage.kt").readText()
        assertTrue(decode.contains("inJustDecodeBounds = true") && decode.contains("inSampleSize = sampleSize("))
    }

    @Test fun check_privateRealmPhotosAndPacksStayAppPrivate() {
        val files = listOf("ui/PhotoThemeActivity.kt", "data/PhotoImage.kt", "data/PhotoTheme.kt")
            .map { File(src, "java/com/jamhowman/beastbrowser/$it").readText() }
        for (t in files) for (bad in listOf("MediaStore", "getExternalStorage", "getExternalFilesDir", "Environment.", "DIRECTORY_PICTURES", "insertImage"))
            assertFalse(bad, t.contains(bad))
        val activity = files[0]
        assertTrue(activity.contains("PickVisualMedia")) // system picker, no storage permission
        assertTrue(activity.contains("File(cacheDir, EXPORT_DIR)"))
        assertTrue("export off in Ghost", activity.contains("if (!realm.alwaysPrivate) body.addView(button(\"Export") &&
            activity.contains("if (Prefs.realm.alwaysPrivate) return"))
        assertTrue(File(src, "res/xml/file_paths.xml").readText().contains("<cache-path name=\"theme_exports\" path=\"theme-exports/\""))
        val manifest = File(src, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("READ_MEDIA_IMAGES")); assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        // A pack saved while in Ghost lands only in the app's own files.
        Prefs.realm = com.jamhowman.beastbrowser.data.Realm.GHOST
        val img = homeImage(0)
        val id = ThemePacks.store()!!.install(PhotoTheme.toPack(PhotoTheme.suggest("g", img.sample, image = img)))
        assertTrue(File(File(ctx.filesDir, ThemePackStore.DIR), "$id.json").isFile)
        assertTrue(File(File(ctx.filesDir, ThemePackStore.DIR), id).canonicalPath.startsWith(ctx.filesDir.canonicalPath))
    }

    @Test fun appearanceHasCreateFromPhotoRow() {
        assertTrue(File(src, "java/com/jamhowman/beastbrowser/ui/AppearanceFragment.kt").readText().contains("PhotoThemeActivity::class.java"))
        assertTrue(File(src, "res/layout/fragment_appearance.xml").readText().contains("@string/theme_from_photo"))
    }

    @Test fun unusedImport() { assertNotNull(ImageContrast); assertTrue(ThemePack::class.java.name.isNotEmpty()) }
}
