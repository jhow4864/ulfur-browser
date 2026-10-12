package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.ThemeColor
import com.jamhowman.beastbrowser.data.ThemeContrast
import com.jamhowman.beastbrowser.data.ThemePack
import com.jamhowman.beastbrowser.data.ThemePackError
import com.jamhowman.beastbrowser.data.ThemePackParser
import com.jamhowman.beastbrowser.data.ThemePackResult
import com.jamhowman.beastbrowser.data.ThemePackStore
import com.jamhowman.beastbrowser.data.ThemePacks
import com.jamhowman.beastbrowser.data.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 2.9 theme packs: strict validation, nothing before Apply, exact Undo, contrast from the applied colours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemePackTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private val good = """{"format":"ulfurtheme","schema":1,"name":"Moss","author":"Jam","version":"1.0","base":"toxic",
        "colors":{"dark":{"accent":"#4CAF50","accentStart":"#66BB6A","accentEnd":"#43A047","onAccent":"#0E0E12","accentText":"#81C784","futureKey":"#123456"},
                  "light":{"accentText":"#1B5E20"}},"extra":{"ignored":true}}"""

    @Before fun setUp() {
        Prefs.init(ctx); Prefs.sp.edit().clear().commit()
        File(ctx.filesDir, ThemePackStore.DIR).deleteRecursively()
        ThemePacks.resetForTests(ctx)
        SafeStart.reset()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, method: Int = ZipEntry.DEFLATED): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.setMethod(method)
            entries.forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n).apply {
                    if (method == ZipEntry.STORED) { size = b.size.toLong(); compressedSize = b.size.toLong(); crc = java.util.zip.CRC32().also { it.update(b) }.value }
                })
                z.write(b); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun ok(b: ByteArray) = (ThemePackParser.parse(b) as ThemePackResult.Ok).pack
    private fun err(b: ByteArray) = when (val r = ThemePackParser.parse(b)) {
        is ThemePackResult.Bad -> r.error
        is ThemePackResult.Ok -> throw AssertionError("accepted: " + String(b).take(120))
    }
    private fun json(s: String) = s.toByteArray()

    // ---------------------------------------------------------------- format

    @Test fun parsesJsonAndZipIdentically() {
        val a = ok(json(good)); val b = ok(zip("theme.json" to json(good), "README.txt" to json("hi")))
        assertEquals(a, b)
        assertEquals("Moss", a.name); assertEquals(ThemePreset.TOXIC, a.base)
        assertEquals(ThemeColor.parse("#4CAF50"), a.dark["accent"])
        assertFalse("unknown colour keys are ignored", "futureKey" in a.dark)
        // light inherits mode-free colours from dark; accentText is light's own; surfaceTint falls back to the base.
        val light = a.palette(night = false)
        assertEquals(ThemeColor.parse("#4CAF50"), light.color)
        assertEquals(ThemeColor.parse("#1B5E20"), light.accentText)
        assertEquals(ThemePreset.TOXIC.surfaceTintLight, light.surfaceTint)
        assertNull(a.colorFor("surfaceTint", false))
    }

    @Test fun canonicalJsonRoundTrips() {
        val p = ok(json(good))
        assertEquals(p, ok(p.toJson().toString().toByteArray()))
    }

    // ---------------------------------------------------------------- zip-slip and friends

    @Test fun rejectsPathTraversalAndOddNames() {
        listOf("../theme.json", "/theme.json", "themes/theme.json", "..\\theme.json", "C:theme.json", ".hidden", "theme.json/")
            .forEach { n -> assertEquals(n, ThemePackError.BAD_CONTENTS, err(zip(n to json(good)))) }
        assertEquals(ThemePackError.BAD_CONTENTS, err(zip("theme.json" to json(good), "payload.dex" to json("x"))))
        assertEquals(ThemePackError.BAD_CONTENTS, err(dupZip()))
        assertFalse(ThemePackParser.safeEntryName("a/../b"))
        assertTrue(ThemePackParser.safeEntryName("theme.json"))
    }

    /** Two "theme.json" entries (second one would override the first in a naive reader). */
    private fun dupZip(): ByteArray {
        val one = zip("theme.json" to json(good), method = ZipEntry.STORED)
        val two = zip("theme.jsoX" to json(good), method = ZipEntry.STORED)
        // Rename the second archive's entry to theme.json in place (same length), then splice the local entries.
        val renamed = String(two, Charsets.ISO_8859_1).replace("theme.jsoX", "theme.json").toByteArray(Charsets.ISO_8859_1)
        val localEnd = { b: ByteArray -> String(b, Charsets.ISO_8859_1).indexOf("PK\u0001\u0002") }
        return one.copyOfRange(0, localEnd(one)) + renamed
    }

    @Test fun rejectsZipBombs() {
        // Highly compressible theme.json under the JSON cap but far past the ratio.
        val padded = good.dropLast(1) + ",\"pad\":\"" + " ".repeat(60_000) + "\"}"
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to json(padded))))
        // An allowed README that inflates past the total cap.
        assertEquals(ThemePackError.TOO_LARGE, err(zip("theme.json" to json(good), "README.txt" to ByteArray(600_000))))
    }

    @Test fun entryCountCapAppliesBeforeAnythingElse() {
        assertEquals(8, ThemePackParser.MAX_ENTRIES)
        // 9 copies of an allowed name built by splicing (names repeat, but the count check comes first).
        val single = zip("README.txt" to json("x"), method = ZipEntry.STORED)
        val local = single.copyOfRange(0, String(single, Charsets.ISO_8859_1).indexOf("PK\u0001\u0002"))
        val nine = (1..9).fold(ByteArray(0)) { acc, _ -> acc + local }
        assertTrue(err(nine) in setOf(ThemePackError.TOO_LARGE, ThemePackError.BAD_CONTENTS))
        val tenUnknown = zip("theme.json" to json(good), *(1..9).map { "f$it.txt" to json("x") }.toTypedArray())
        assertTrue(err(tenUnknown) in setOf(ThemePackError.TOO_LARGE, ThemePackError.BAD_CONTENTS))
    }

    @Test fun rejectsOversizedPacks() {
        assertEquals(ThemePackError.TOO_LARGE, err(ByteArray(ThemePackParser.MAX_FILE_BYTES + 1) { '{'.code.toByte() }))
        val bigJson = good.dropLast(1) + ",\"pad\":\"" + "x".repeat(70_000) + "\"}"
        assertEquals(ThemePackError.TOO_LARGE, err(json(bigJson)))
    }

    // ---------------------------------------------------------------- JSON and colours

    @Test fun rejectsBrokenJsonAndWrongFormat() {
        listOf("", "{", "{\"format\":\"ulfurtheme\",", "[]", "\"hi\"", "not json", "$good trailing",
            good.replace("\"ulfurtheme\"", "\"other\""), good.replace("\"schema\":1", "\"schema\":3"),
            good.replace("\"schema\":1", "\"schema\":\"1\""), good.replace("\"name\":\"Moss\"", "\"name\":\"\""),
            good.replace("\"name\":\"Moss\"", "\"name\":\"${"x".repeat(60)}\""), good.replace("Moss", "Mo\\u202Ess"),
            good.replace("\"toxic\"", "\"ghost\""), good.replace("\"toxic\"", "\"nope\""),
            """{"format":"ulfurtheme","schema":1,"name":"x","colors":{}}""",
            "[".repeat(5000) + "]".repeat(5000),
        ).forEach { assertEquals(it.take(60), ThemePackError.NOT_A_THEME, err(json(it))) }
        assertEquals(ThemePackError.NOT_A_THEME, err(zip("README.txt" to json("no theme"))))
        assertEquals(ThemePackError.NOT_A_THEME, err(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)))
    }

    @Test fun rejectsBadColourValues() {
        listOf("#12345", "#1234567", "#4CAF50FF", "4CAF50", "red", "#GGGGGG", "rgb(1,2,3)", " #4CAF50").forEach { c ->
            assertEquals(c, ThemePackError.NOT_A_THEME, err(json(good.replace("\"#4CAF50\"", "\"$c\""))))
        }
        assertEquals(ThemePackError.NOT_A_THEME, err(json(good.replace("\"#4CAF50\"", "123"))))
        assertEquals(ThemePackError.NOT_A_THEME, err(json(good.replace("\"#4CAF50\"", "null"))))
    }

    // ---------------------------------------------------------------- apply / undo

    private fun state(store: ThemePackStore) = Triple(Prefs.sp.all.toMap(), store.dir.list()?.sorted().orEmpty(), AppTheme.palette(ctx, Realm.PLAY, false))

    @Test fun nothingChangesBeforeApply() {
        Prefs.setAccent(Realm.PLAY, ThemePreset.FROST)
        val store = ThemePacks.store()!!
        val before = state(store)
        val pack = ok(zip("theme.json" to json(good)))
        pack.palette(true); pack.palette(false); ThemeContrast.lowContrast(pack.palette(true)) // what the preview does
        assertEquals(before, state(store))
        assertNull(store.activeId())
    }

    @Test fun undoRestoresExactlyThePreviousState() {
        val store = ThemePacks.store()!!
        // Previous state: Frost picked, no pack, nothing installed.
        Prefs.setAccent(Realm.PLAY, ThemePreset.FROST)
        val before = state(store)
        val snap = store.apply(ok(json(good)))
        assertNotEquals(before, state(store))
        assertEquals(ThemePreset.TOXIC, Prefs.theme)
        store.undo(snap)
        assertEquals(before, state(store))
    }

    @Test fun undoRestoresAPreviousPackAndAbsentKeys() {
        val store = ThemePacks.store()!!
        val first = ok(json(good))
        store.apply(first)                               // pack A active, `accent` was absent before it
        val before = state(store)
        val second = ok(json(good.replace("Moss", "Ember Moss").replace("#4CAF50", "#FF5722")))
        val snap = store.apply(second)
        assertEquals(second.id, store.activeId())
        store.undo(snap)
        assertEquals(before, state(store))
        assertEquals(first.id, store.activeId())
        // From a clean install: undo removes the keys entirely (not just resets them).
        Prefs.sp.edit().clear().commit(); store.dir.deleteRecursively()
        val clean = state(store)
        store.undo(store.apply(first))
        assertEquals(clean, state(store))
        assertFalse(Prefs.sp.contains("accent")); assertFalse(Prefs.sp.contains("theme_pack"))
    }

    @Test fun safeStartPausesThePackWithoutRemovingIt() {
        val store = ThemePacks.store()!!
        val p = ok(json(good))
        store.apply(p)
        assertEquals(p.palette(AppTheme.isNight(ctx)), AppTheme.palette(ctx, Realm.PLAY, false))
        SafeStart.themePaused = true
        assertEquals(AppTheme.palette(ThemePreset.TOXIC, AppTheme.isNight(ctx)), AppTheme.palette(ctx, Realm.PLAY, false))
        assertEquals(p.id, store.activeId())
        SafeStart.themePaused = false
    }

    // ---------------------------------------------------------------- contrast

    @Test fun contrastWarningUsesTheAppliedColours() {
        val low = good.replace("\"onAccent\":\"#0E0E12\"", "\"onAccent\":\"#5A9E5D\"")
        val pack = ok(json(low))
        val store = ThemePacks.store()!!
        store.apply(pack)
        val night = AppTheme.isNight(ctx)
        val applied = AppTheme.palette(ctx, Realm.PLAY, false)
        assertEquals(pack.palette(night), applied)            // the preview's palette IS the applied one
        val issues = ThemeContrast.lowContrast(applied)
        assertTrue(issues.toString(), issues.any { it.label == "Text on accent" && it.text == ThemeColor.parse("#5A9E5D") && it.background == ThemeColor.parse("#4CAF50") })
        assertTrue(issues.toString(), issues.isNotEmpty())
        issues.forEach { assertTrue(it.ratio < 4.5) }
        assertTrue(ThemeContrast.lowContrast(ok(json(good)).palette(true)).toString(), ThemeContrast.lowContrast(ok(json(good)).palette(true)).none { it.label.startsWith("Text on") })
    }

    @Test fun contrastMatchesWcag() {
        assertEquals(21.0, ThemeContrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(4.48, ThemeContrast.ratio(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.01) // just under AA
    }

    @Test fun deleteActivePackFallsBackToPreset() {
        val store = ThemePacks.store()!!
        val p = ok(json(good)); store.apply(p)
        store.delete(p.id)
        assertNull(store.activeId()); assertTrue(store.installed().toString(), store.installed().isEmpty())
        store.delete("../../shared_prefs/x") // ignored
    }

    @Test fun storedFileIsCanonicalNotTheImport() {
        val store = ThemePacks.store()!!
        val p = ok(zip("theme.json" to json(good), "README.txt" to json("readme")))
        store.apply(p)
        assertEquals(listOf("${p.id}.json"), store.dir.list()!!.toList())
        assertFalse(File(store.dir, "${p.id}.json").readText().contains("extra"))
        assertEquals(p, store.installed().single())
    }
}
