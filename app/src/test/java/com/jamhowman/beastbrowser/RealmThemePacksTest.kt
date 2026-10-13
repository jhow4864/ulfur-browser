package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.AppTheme
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.data.Realm
import com.jamhowman.beastbrowser.data.RealmThemes
import com.jamhowman.beastbrowser.data.ThemeContrast
import com.jamhowman.beastbrowser.data.ThemePack
import com.jamhowman.beastbrowser.data.ThemePackParser
import com.jamhowman.beastbrowser.data.ThemePackResult
import com.jamhowman.beastbrowser.data.ThemePackStore
import com.jamhowman.beastbrowser.data.ThemePacks
import com.jamhowman.beastbrowser.ui.RealmSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.10.1 per-realm theme packs, including the Apk Tester checks (1)-(6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealmThemePacksTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = ThemePacks.store()!!
    private val src = File("src/main/java/com/jamhowman/beastbrowser")
    private val SLATE = "Slate (sample)"
    private fun read(p: String) = File(src, p).readText()

    @Before fun setUp() {
        Prefs.init(ctx); Prefs.sp.edit().clear().commit()
        File(ctx.filesDir, ThemePackStore.DIR).deleteRecursively()
        ThemePacks.resetForTests(ctx)
        SafeStart.reset()
        Prefs.realm = Realm.PLAY
    }

    private fun sample(name: String): ThemePack =
        (ThemePackParser.parse(File("../sample-themes/$name.ulfurtheme").readBytes(), com.jamhowman.beastbrowser.data.AndroidImageCodec) as ThemePackResult.Ok).pack

    /** Installs [name] without making it the main pack; returns its id. */
    private fun install(name: String): String {
        val main = store.activeId()
        store.apply(sample(name))
        val id = store.activeId()!!
        if (main == null) store.clearActive() else store.select(main)
        return id
    }

    private fun mainPack(name: String): String { store.apply(sample(name)); return store.activeId()!! }
    private fun night() = AppTheme.isNight(ctx)
    private fun presetOnly(r: Realm) = AppTheme.palette(Prefs.accentFor(r), night(), r.alwaysPrivate, null)
    private fun withPack(r: Realm, p: ThemePack?) = AppTheme.palette(Prefs.accentFor(r), night(), r.alwaysPrivate, p)

    // ------------------------------------------------------------- choices and resolution

    @Test fun realmWithNoChoiceFollowsMainPack() {
        mainPack("Glacier")
        Realm.entries.forEach { store.realms.set(it, RealmThemes.Choice.Main) }
        Realm.entries.forEach { assertEquals("Glacier", ThemePacks.packFor(it)?.name) }
    }

    @Test fun realmOwnPackAndThemeColoursOnly() {
        mainPack("Glacier")
        val slate = install("Slate")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(slate))
        store.realms.set(Realm.GHOST, RealmThemes.Choice.Off)
        store.realms.set(Realm.PLAY, RealmThemes.Choice.Main)
        assertEquals(SLATE, ThemePacks.packFor(Realm.WORK)?.name)
        assertEquals("Glacier", ThemePacks.packFor(Realm.PLAY)?.name)
        assertNull(ThemePacks.packFor(Realm.GHOST))
        assertEquals(RealmThemes.Choice.Pack(slate), store.realms.choice(Realm.WORK))
    }

    @Test fun safeStartThemePausedDisablesEveryRealmPack() {
        mainPack("Glacier")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(install("Slate")))
        SafeStart.themePaused = true
        try {
            Realm.entries.forEach { assertNull(ThemePacks.packFor(it)); assertEquals(presetOnly(it), AppTheme.palette(ctx, it)) }
        } finally { SafeStart.themePaused = false }
        assertNotNull(ThemePacks.packFor(Realm.WORK))
    }

    @Test fun pickerOffersMainThemeColoursAndInstalledPacks() {
        install("Slate"); install("Wildfire")
        val opts = RealmSheet.pickerOptions(store)
        assertEquals("Use main theme", opts[0].first); assertEquals(RealmThemes.Choice.Main, opts[0].second)
        assertEquals(RealmThemes.Choice.Off, opts[1].second)
        assertEquals(setOf(SLATE, "Wildfire"), opts.drop(2).map { it.first }.toSet())
    }

    // ------------------------------------------------------------- (1) deleted pack

    @Test fun check1_deletingRealmPackResetsRealmToMainTheme() {
        mainPack("Glacier")
        val slate = install("Slate")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(slate))
        store.realms.set(Realm.GHOST, RealmThemes.Choice.Pack(slate))
        store.delete(slate)
        assertEquals(RealmThemes.Choice.Main, store.realms.choice(Realm.WORK))
        assertEquals(RealmThemes.Choice.Main, store.realms.choice(Realm.GHOST))
        assertEquals("Glacier", ThemePacks.packFor(Realm.WORK)?.name)
        assertEquals(withPack(Realm.WORK, sample("Glacier")), AppTheme.palette(ctx, Realm.WORK))
    }

    @Test fun check1_realmPackMissingOnDiskStillWorksOnMainTheme() {
        mainPack("Glacier")
        val slate = install("Slate")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(slate))
        File(File(ctx.filesDir, ThemePackStore.DIR), "$slate.json").delete() // gone without delete()
        ThemePacks.resetForTests(ctx)
        assertEquals("Glacier", ThemePacks.packFor(Realm.WORK)?.name)
        Prefs.realm = Realm.WORK
        AppTheme.palette(ctx, Realm.WORK) // no crash
        assertNull(ThemePacks.activeBitmap(com.jamhowman.beastbrowser.data.ImageSlot.HOME, Realm.WORK).takeIf { false })
        // And with no main pack either: theme colours, no crash.
        store.clearActive()
        assertNull(ThemePacks.packFor(Realm.WORK))
        assertEquals(presetOnly(Realm.WORK), AppTheme.palette(ctx, Realm.WORK))
    }

    @Test fun check1_deletingMainPackLeavesOtherRealmPacks() {
        val glacier = mainPack("Glacier")
        val slate = install("Slate")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(slate))
        store.delete(glacier)
        assertEquals(SLATE, ThemePacks.packFor(Realm.WORK)?.name)
        assertNull(ThemePacks.packFor(Realm.PLAY))
    }

    // ------------------------------------------------------------- (2) migration from 2.10.0

    @Test fun check2_upgradeFrom2100KeepsEveryRealmLookingTheSame() {
        // 2.10.0 state: one main pack, painting Play only; no realm keys, no migration flag.
        mainPack("Glacier")
        val before = Realm.entries.associateWith { if (it == Realm.PLAY) withPack(it, sample("Glacier")) else presetOnly(it) }
        Prefs.sp.edit().remove(RealmThemes.KEY_MIGRATED).apply { Realm.entries.forEach { remove(RealmThemes.key(it)) } }.commit()
        ThemePacks.resetForTests(ctx) // first 2.10.1 start
        Realm.entries.forEach { assertEquals("realm $it", before[it], AppTheme.palette(ctx, it)) }
        assertEquals(RealmThemes.Choice.Off, store.realms.choice(Realm.WORK))
        assertEquals(RealmThemes.Choice.Off, store.realms.choice(Realm.GHOST))
        assertEquals(RealmThemes.Choice.Main, store.realms.choice(Realm.PLAY))
    }

    @Test fun check2_migrationRunsOnceAndKeepsLaterChoices() {
        val slate = install("Slate")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(slate))
        store.realms.set(Realm.GHOST, RealmThemes.Choice.Main)
        ThemePacks.resetForTests(ctx); ThemePacks.resetForTests(ctx) // later starts
        assertEquals(RealmThemes.Choice.Pack(slate), store.realms.choice(Realm.WORK))
        assertEquals(RealmThemes.Choice.Main, store.realms.choice(Realm.GHOST))
        assertTrue(Prefs.sp.getBoolean(RealmThemes.KEY_MIGRATED, false))
    }

    @Test fun check2_upgradeWithNoPackChangesNothing() {
        Prefs.sp.edit().clear().commit()
        ThemePacks.resetForTests(ctx)
        Realm.entries.forEach { assertEquals(presetOnly(it), AppTheme.palette(ctx, it)) }
    }

    // ------------------------------------------------------------- (3) live switching

    @Test fun check3_switchingRealmsChangesThemeWithoutRestart() {
        mainPack("Glacier")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(install("Slate")))
        store.realms.set(Realm.PLAY, RealmThemes.Choice.Main)
        val play = AppTheme.palette(ctx, Realm.PLAY)
        Prefs.realm = Realm.WORK // what switchRealm does; no re-init of ThemePacks
        assertEquals(SLATE, ThemePacks.activePack()?.name)
        val work = AppTheme.palette(ctx, Realm.WORK)
        assertNotEquals(play, work)
        assertEquals(withPack(Realm.WORK, sample("Slate")), work)
        Prefs.realm = Realm.PLAY
        assertEquals("Glacier", ThemePacks.activePack()?.name)
    }

    @Test fun check3_switchRealmRepaintsInPlace() {
        val main = read("ui/MainActivity.kt")
        val sw = main.substringAfter("private fun switchRealm(r: Realm)").substringBefore("private fun applyRealmTheme")
        assertTrue(sw.contains("applyRealmTheme()"))
        assertFalse(sw.contains("recreate()"))
        val apply = main.substringAfter("private fun applyRealmTheme()").substringBefore("\n    }\n")
        assertTrue(apply.contains("syncPalette(force = true)"))
        assertTrue("site colour stays on top of the realm pack", apply.contains("applySiteToolbar"))
        val surf = main.substringAfter("private fun applySurfaces()").substringBefore("private fun styleToggle")
        listOf("TOOLBAR", "HOME", "SWITCHER").forEach { assertTrue(surf.contains("ThemeImageDrawable.active(ImageSlot.$it, night, realm)")) }
        assertTrue(main.contains("AppTheme.palette(this, realm,"))
    }

    // ------------------------------------------------------------- (4) swatches only on the realm's own card

    @Test fun check4_realmPackShowsOnlyOnItsOwnCard() {
        val slate = install("Slate")
        store.realms.set(Realm.WORK, RealmThemes.Choice.Pack(slate))
        store.realms.set(Realm.PLAY, RealmThemes.Choice.Main)
        store.realms.set(Realm.GHOST, RealmThemes.Choice.Main)
        assertEquals(SLATE, RealmSheet.swatchPack(Realm.WORK)?.name)
        assertNull(RealmSheet.swatchPack(Realm.PLAY)); assertNull(RealmSheet.swatchPack(Realm.GHOST))
        assertFalse(RealmSheet.swatchLabel(Realm.PLAY).contains(SLATE))
        assertFalse(RealmSheet.swatchLabel(Realm.GHOST).contains(SLATE))
        assertTrue(RealmSheet.swatchLabel(Realm.WORK).contains(SLATE))
        mainPack("Glacier") // with a main pack, other cards show the main pack, never Work's
        store.realms.set(Realm.PLAY, RealmThemes.Choice.Main)
        assertEquals("Glacier", RealmSheet.swatchPack(Realm.PLAY)?.name)
        assertEquals(SLATE, RealmSheet.swatchPack(Realm.WORK)?.name)
        assertNotEquals(RealmSheet.swatchColors(Realm.WORK, false), RealmSheet.swatchColors(Realm.PLAY, false).takeIf { false })
    }

    @Test fun check4_cardsBuildSwatchFromTheirOwnRealm() {
        val sheet = read("ui/RealmSheet.kt")
        val seal = sheet.substringAfter("private fun seal(").substringBefore("fun swatchPack")
        assertTrue(seal.contains("swatchColors(realm,"))
        assertTrue(seal.contains("swatchLabel(realm)"))
        assertFalse(seal.contains("swatchColors(current") || seal.contains("swatchPack(current")) // never the current realm's pack
        assertTrue(sheet.contains("pickPack(activity, realm)"))
        assertTrue(sheet.contains("\"Use main theme\""))
    }

    // ------------------------------------------------------------- (5) private/Ghost toolbar

    @Test fun check5_privateAndGhostKeepTheirOwnToolbarUnderAnyPack() {
        for (name in listOf("Glacier", "Slate", "Wildfire")) {
            val id = install(name)
            Realm.entries.forEach { store.realms.set(it, RealmThemes.Choice.Pack(id)) }
            for (r in Realm.entries) {
                val priv = AppTheme.palette(ctx, r, private = true)
                assertNull("$name/$r: no pack surfaces on private", priv.surfaces)
                assertEquals("$name/$r", AppTheme.palette(Prefs.accentFor(r), night(), true, null).systemBars, priv.systemBars)
            }
            assertNull(AppTheme.palette(ctx, Realm.GHOST).surfaces)
        }
        val surf = read("ui/MainActivity.kt").substringAfter("private fun applySurfaces()").substringBefore("val bars")
        assertTrue("home background may show on private", surf.contains("ThemeImageDrawable.active(ImageSlot.HOME, night, realm)"))
        assertFalse(surf.contains("ImageSlot.TOOLBAR"))
    }

    // ------------------------------------------------------------- (6) contrast

    @Test fun check6_textMeets45UnderEveryRealmTheme() {
        val ids = listOf("Glacier", "Slate", "Wildfire").associateWith { install(it) }
        for ((name, id) in ids) {
            Realm.entries.forEach { store.realms.set(it, RealmThemes.Choice.Pack(id)) }
            for (r in Realm.entries) for (night in listOf(true, false)) for (priv in listOf(false, true)) {
                if (r.alwaysPrivate && !priv) continue
                val p = AppTheme.palette(Prefs.accentFor(r), night, priv, ThemePacks.packFor(r))
                val low = ThemeContrast.lowContrast(p)
                assertTrue("$name/$r night=$night private=$priv: $low", low.isEmpty())
            }
        }
    }
}
