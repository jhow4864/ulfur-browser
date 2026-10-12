package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.downloads.WipeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.10: "Wipe vault and key" never runs without a fresh fingerprint/face/PIN check. */
class WipeGateTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Test fun noWipeWhenAuthFailsOrIsCancelled() {
        var wiped = 0
        WipeGate({ it(false) }, { wiped++ }).request()
        assertEquals(0, wiped)
    }

    @Test fun noWipeWhileAuthIsPending() {
        var wiped = 0
        var pending: ((Boolean) -> Unit)? = null
        val g = WipeGate({ pending = it }, { wiped++ })
        g.request()
        assertEquals(0, wiped) // prompt shown, nothing answered yet
        pending!!(false)
        assertEquals(0, wiped)
    }

    @Test fun wipeRunsOnceAfterSuccess() {
        var wiped = 0
        var cb: ((Boolean) -> Unit)? = null
        val g = WipeGate({ cb = it }, { wiped++ })
        g.request(); cb!!(true); cb!!(true)
        assertEquals(1, wiped)
    }

    @Test fun uiOnlyWipesThroughTheGate() {
        val ui = File(main, "java/com/jamhowman/beastbrowser/ui/PrivateDownloadsActivity.kt").readText()
        assertEquals("wipe is only called inside the gate", 1, Regex("""wipePrivateVaultAndKey\(""").findAll(ui).count())
        assertTrue(ui.substringBefore("wipePrivateVaultAndKey(").substringAfterLast("WipeGate(").contains("authenticate"))
        assertTrue(ui.contains("wipeGate().request()"))
        val strings = File(main, "res/values/strings.xml").readText()
        assertTrue(strings.contains(">Wipe private downloads?<"))
        assertTrue(strings.contains(">Wipe private downloads<"))
        assertTrue(strings.contains(">Your private downloads will be deleted for good. This can\\'t be undone.<"))
        assertTrue(strings.contains(">Wipe everything<"))
        val msg = strings.substringAfter("name=\"vault_wipe_key_message\">").substringBefore("<")
        assertFalse("wipe doesn't touch the password store", msg.contains("password", ignoreCase = true))
    }
}
