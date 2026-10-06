package com.jamhowman.beastbrowser

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jamhowman.beastbrowser.reader.ReaderMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Reader page requests relayed by Beast Helper's background script over the beast_helper port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderRelayTest {
    @Before fun setUp() = ReaderMode.init(ApplicationProvider.getApplicationContext())

    private fun relay(rid: Int, msg: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        ReaderMode.handleRelayed(JSONObject().put("type", "readerRelay").put("rid", rid).put("msg", msg)) { out += it }
        shadowOf(Looper.getMainLooper()).idle()
        return out
    }

    @Test fun relayedRequestGetsExactlyOneTaggedReply() {
        val out = relay(7, JSONObject().put("type", "readerPrefs").put("prefs", JSONObject().put("size", 120).put("font", "serif").put("theme", "sepia")))
        assertEquals(1, out.size)
        assertEquals("readerReply", out[0].getString("type"))
        assertEquals(7, out[0].getInt("rid"))
        assertTrue(out[0].getJSONObject("reply").getBoolean("ok"))
    }

    @Test fun closeWithoutAKnownTabTellsThePageToGoBackItself() {
        val out = relay(8, JSONObject().put("type", "readerClose").put("id", "gone").put("url", "https://example.org/"))
        assertFalse(out.single().getJSONObject("reply").getBoolean("ok"))
    }

    @Test fun unknownTypeIsAnErrorNotSilence() {
        val out = relay(9, JSONObject().put("type", "nope"))
        assertTrue(out.single().has("error"))
    }

    @Test fun missingRidIsIgnored() {
        assertTrue(relay(0, JSONObject().put("type", "readerPrefs")).isEmpty())
    }
}
