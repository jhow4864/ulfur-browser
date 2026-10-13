package com.jamhowman.beastbrowser

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.jamhowman.beastbrowser.crash.SafeStart
import com.jamhowman.beastbrowser.data.Prefs
import com.jamhowman.beastbrowser.downloads.DlStatus
import com.jamhowman.beastbrowser.downloads.DownloadCenter
import com.jamhowman.beastbrowser.downloads.DownloadItem
import com.jamhowman.beastbrowser.downloads.PrivateCrypto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * 2.11.2 launch crash: R8 removed androidx.work.impl.WorkDatabase_Impl.<init>(), so WorkManager's androidx.startup
 * initializer threw in a ContentProvider before Application.onCreate (no safe start). Plus vault-migration hardening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchFixTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val main = File("src/main")
    private lateinit var dir: File

    @Before fun setUp() {
        PrivateCrypto.keys = MemKeys()
        Prefs.init(app); Prefs.sp.edit().clear().commit()
        dir = File(app.filesDir, "lf-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun tearDown() { PrivateCrypto.keys = PrivateCrypto.AndroidKeystore; dir.deleteRecursively(); SafeStart.reset() }

    @Test fun r8KeepsRoomDatabaseConstructorsAndWorkerClasses() {
        val rules = File("proguard-rules.pro").readText()
        listOf("-keep class * extends androidx.room.RoomDatabase { <init>(); }",
            "-keep class androidx.work.impl.WorkDatabase_Impl { *; }",
            "-keep class * extends androidx.work.ListenableWorker",
            "-keep class * extends androidx.work.InputMerger").forEach { assertTrue(it, rules.contains(it)) }
        // Room creates the database exactly this way; the class must have a public no-arg constructor.
        val c = Class.forName("androidx.work.impl.WorkDatabase_Impl")
        assertNotNull(c.getDeclaredConstructor())
    }

    @Test fun workManagerNoLongerInitialisesInAStartupProvider() {
        val m = File(main, "AndroidManifest.xml").readText()
        val block = m.substringAfter("androidx.work.WorkManagerInitializer").substringBefore("/>")
        assertTrue(block.contains("tools:node=\"remove\""))
        assertTrue(app is androidx.work.Configuration.Provider)
        // On-demand init works (what FilterAutoUpdate.schedule does after safe start).
        assertNotNull(WorkManager.getInstance(app))
    }

    @Test fun safeStartCountsOnTheVeryFirstLine() {
        val b = File(main, "java/com/jamhowman/beastbrowser/BeastApp.kt").readText()
        val body = b.substringAfter("override fun onCreate() {")
        val sup = body.indexOf("\n        super.onCreate()\n")
        assertTrue(body.indexOf("SafeStart.begin(") in 0 until sup)
        assertTrue(body.substring(0, sup).contains("catch (_: Throwable)"))
        assertTrue(body.indexOf("FilterAutoUpdate.schedule") > body.indexOf("SafeStart.begin("))
    }

    // ------------------------------------------------------------- vault migration

    private fun v1(name: String, plain: ByteArray) = File(dir, name).also { f -> PrivateCrypto.writer(f).use { it.write(plain); it.finish() } }
    private fun item(f: File, name: String) = DownloadItem(id = Random.nextLong(1, Long.MAX_VALUE), url = "https://x/v", fileName = name,
        mime = "application/octet-stream", domain = "x", isPrivate = true, status = DlStatus.DONE, filePath = f.absolutePath)

    @Test fun startupNeverMigratesVaultFiles() {
        val c = File(main, "java/com/jamhowman/beastbrowser/downloads/DownloadCenter.kt").readText()
        val init = c.substringAfter("fun init(").substringBefore("@Volatile private var migrationStarted")
        assertFalse(init.contains("migrateVaultItem(")); assertFalse(init.contains("encryptLegacy(")); assertFalse(init.contains("reencryptV2("))
        val vault = File(main, "java/com/jamhowman/beastbrowser/ui/PrivateDownloadsActivity.kt").readText()
        assertTrue(vault.contains("DownloadCenter.migrateVaultLazily()"))
        val lazy = c.substringAfter("fun migrateVaultLazily(").substringBefore("\n    }\n")
        assertTrue(lazy.contains("io.execute") && lazy.contains("catch (t: Throwable)") && lazy.contains("SafeStart.active"))
    }

    @Test fun failedMigrationLeavesFileUntouchedAndCrashLoopDisablesIt() {
        val mp4 = ByteArray(4) + "ftypisom".toByteArray() + Random(1).nextBytes(90_000)
        val f = v1("videoplayback.bin", mp4)
        // Damaged UPD1 file: migration fails, file byte-for-byte untouched, no temp left behind.
        java.io.RandomAccessFile(f, "rw").use { it.seek(f.length() - 5); it.write(0x55) }
        val before = f.readBytes()
        val d = item(f, "videoplayback.bin")
        assertEquals(d, DownloadCenter.migrateVaultItem(d))
        assertArrayEquals(before, f.readBytes())
        assertFalse(File(dir, DownloadCenter.ENC_PREFIX + f.name).exists())
        // A previous run died mid-migration (marker set): the next start disables migration instead of retrying.
        Prefs.sp.edit().putBoolean(DownloadCenter.KEY_MIGRATION_RUNNING, true).commit()
        DownloadCenter.init(app)
        DownloadCenter.migrateVaultLazily(Prefs.sp)
        assertTrue(Prefs.sp.getBoolean(DownloadCenter.KEY_MIGRATION_DISABLED, false))
    }

    @Test fun swapRenamesOverOriginalInOneStep() {
        val c = File(main, "java/com/jamhowman/beastbrowser/downloads/DownloadCenter.kt").readText()
        assertFalse("never delete before rename", c.contains("!f.delete() || !tmp.renameTo(f)"))
        val f = v1("a.bin", "hello".toByteArray())
        assertTrue(DownloadCenter.reencryptV2(f, PrivateCrypto.Meta("a.txt", "text/plain")))
        assertEquals("hello", String(PrivateCrypto.reader(f).use { it.readBytes() }))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun metaRecordNeverOverflows() {
        for (n in listOf("", "a", "é".repeat(300), "🎥".repeat(400), "x".repeat(5000) + ".mp4", "\u0000\"\\".repeat(200)))
            for (m in listOf("video/mp4", "x/" + "y".repeat(500))) {
                val enc = PrivateCrypto.Meta(n, m).encode()
                assertEquals(PrivateCrypto.META_PLAIN, enc.size)
                PrivateCrypto.Meta.decode(enc) // round-trips without throwing
            }
        val f = File(dir, "m").also { PrivateCrypto.writer(it, meta = PrivateCrypto.Meta("🎥".repeat(400), "video/mp4")).use { w -> w.write(ByteArray(10)); w.finish() } }
        assertEquals("video/mp4", PrivateCrypto.readMeta(f)!!.mime)
    }
}
