package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.backup.PasswordCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordCsvTest {
    @Test fun chromeExport() {
        val csv = "name,url,username,password,note\r\n" +
            "example.org,https://www.example.org/login?x=1,ann,\"p,w\"\"d\",\r\n" +
            "app,android://abc@com.example/,bob,pw,\r\n" +          // Android app entry: no web origin → skipped
            "multi,https://m.test:8443/a,carol,\"line1\nline2\",\"a note\"\r\n"
        val l = PasswordCsv.parse(csv)
        assertEquals(2, l.size)
        assertEquals("https://www.example.org", l[0].origin)
        assertEquals("ann", l[0].username); assertEquals("p,w\"d", l[0].password)
        assertEquals("https://m.test:8443", l[1].origin); assertEquals("line1\nline2", l[1].password)
    }

    @Test fun firefoxExport() {
        val csv = "\uFEFF\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
            "\"https://shop.test\",\"\",\"pw1\",,\"https://shop.test\",\"{g}\",\"1700000000000\",\"1700000000001\",\"1700000000002\"\n"
        val l = PasswordCsv.parse(csv).single()
        assertEquals("https://shop.test", l.origin); assertEquals("", l.username)
        assertEquals("https://shop.test", l.formActionOrigin); assertNull(l.httpRealm)
        assertEquals(1700000000000, l.createdAt); assertEquals(1700000000002, l.updatedAt)
    }

    @Test(expected = PasswordCsv.CsvException::class)
    fun unrelatedCsvRejected() { PasswordCsv.parse("date,amount\n2026-01-01,5\n") }
}
