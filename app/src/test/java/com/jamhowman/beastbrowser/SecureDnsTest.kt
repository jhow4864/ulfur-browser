package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.data.SecureDns
import com.jamhowman.beastbrowser.data.SecureDns.Provider
import com.jamhowman.beastbrowser.data.SecureDns.UrlCheck
import com.jamhowman.beastbrowser.data.SecureDns.UrlError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/** 2.5 Secure DNS: provider/mode mapping to Gecko TRR settings and custom URL validation. */
class SecureDnsTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun invalid(url: String?) = (SecureDns.validateCustomUrl(url) as? UrlCheck.Invalid)?.error

    private val DISABLED = GeckoRuntimeSettings.TRR_MODE_DISABLED

    @Test fun offDisablesTrrSoGeckoCantTurnDohOnByItself() {
        assertEquals(5, GeckoRuntimeSettings.TRR_MODE_DISABLED)
        val c = SecureDns.config("off", "nextdns", "abc123", "https://dns.example.com/q")
        assertEquals(DISABLED, c.trrMode)
        assertTrue(c.trrMode != GeckoRuntimeSettings.TRR_MODE_OFF)
        assertNull(c.uri)
        assertFalse(c.enabled)
    }

    @Test fun defaultIsOffAndIsOneConstant() {
        assertEquals(SecureDns.Mode.OFF, SecureDns.DEFAULT_MODE)
        assertEquals(SecureDns.DEFAULT_MODE, SecureDns.Mode.from(null))
        assertEquals(SecureDns.DEFAULT_MODE, SecureDns.Mode.from("bogus"))
        assertEquals(DISABLED, SecureDns.config(null, null, null, null).trrMode)
        // No XML default for doh_mode, so the constant is the only source of the default.
        val xml = File(main, "res/xml/preferences_secure_dns.xml").readText()
        val modeTag = Regex("<ListPreference app:key=\"doh_mode\"[^>]*>").find(xml)!!.value
        assertFalse(modeTag.contains("defaultValue"))
    }

    @Test fun modesMapToTrrFirstAndOnly() {
        assertEquals(GeckoRuntimeSettings.TRR_MODE_FIRST, SecureDns.config("default", "quad9", null, null).trrMode)
        assertEquals(GeckoRuntimeSettings.TRR_MODE_ONLY, SecureDns.config("max", "quad9", null, null).trrMode)
        assertTrue(SecureDns.config("max", "quad9", null, null).enabled)
        assertEquals(SecureDns.DEFAULT_PROVIDER.uri, SecureDns.config("default", null, null, null).uri)
    }

    @Test fun providerEndpoints() {
        assertEquals("https://cloudflare-dns.com/dns-query", SecureDns.config("default", "cloudflare", null, null).uri)
        assertEquals("https://dns.quad9.net/dns-query", SecureDns.config("default", "quad9", null, null).uri)
        assertEquals("https://dns.adguard-dns.com/dns-query", SecureDns.config("default", "adguard", null, null).uri)
        assertEquals("https://dns.nextdns.io/abc123", SecureDns.config("default", "nextdns", "ABC123", null).uri)
        for (p in Provider.entries) p.uri?.let { assertTrue("${p.key}: $it", SecureDns.validateCustomUrl(it) is UrlCheck.Ok) }
        assertFalse("Mullvad's DoH shuts down 2 Nov 2026", Provider.entries.any { it.key == "mullvad" || it.uri.orEmpty().contains("mullvad") })
        assertFalse(Provider.entries.any { it.uri.orEmpty().contains("mozilla.cloudflare") })
    }

    @Test fun removedProviderFallsBackSafelyNeverStrict() {
        val m = SecureDns.choice("max", "mullvad")
        assertEquals(Provider.QUAD9, m.provider)
        assertEquals(SecureDns.Mode.DEFAULT, m.mode)
        assertTrue(m.fellBack)
        assertEquals(GeckoRuntimeSettings.TRR_MODE_FIRST, SecureDns.config("max", "mullvad", null, null).trrMode)
        val unknown = SecureDns.choice("max", "someday-gone")
        assertEquals(SecureDns.DEFAULT_PROVIDER, unknown.provider)
        assertEquals(SecureDns.Mode.DEFAULT, unknown.mode)
        assertEquals(SecureDns.Mode.OFF, SecureDns.choice("off", "mullvad").mode) // off stays off
        assertEquals(SecureDns.Mode.OFF, SecureDns.choice(null, "off").mode)      // pre-release provider "off"
    }

    @Test fun nextDnsIds() {
        assertEquals("abc123", SecureDns.normalizeNextDnsId(" ABC123 "))
        assertEquals("abc123", SecureDns.normalizeNextDnsId("https://dns.nextdns.io/abc123"))
        assertEquals("abc123", SecureDns.normalizeNextDnsId("https://dns.nextdns.io/abc123/"))
        for (bad in listOf(null, "", "abc12", "abc1234", "abc-12", "https://evil.example/abc123")) {
            assertNull("'$bad'", SecureDns.normalizeNextDnsId(bad))
        }
    }

    @Test fun nextDnsOrCustomWithoutAValidValueStaysOff() {
        for (bad in listOf(null, "", "http://dns.nextdns.io/abc", "dns.nextdns.io/abc")) {
            val c = SecureDns.config("max", "custom", null, bad)
            assertEquals("'$bad'", DISABLED, c.trrMode)
            assertNull(c.uri)
            assertTrue(c.customInvalid)
        }
        val n = SecureDns.config("max", "nextdns", "nope", null)
        assertEquals(DISABLED, n.trrMode)
        assertTrue(n.customInvalid)
    }

    @Test fun customUsesTheValidatedUrl() {
        val c = SecureDns.config("max", "custom", null, "  https://dns.example.net/dns-query  ")
        assertEquals("https://dns.example.net/dns-query", c.uri)
        assertEquals(GeckoRuntimeSettings.TRR_MODE_ONLY, c.trrMode)
        assertFalse(c.customInvalid)
    }

    @Test fun validCustomUrls() {
        for (ok in listOf(
            "https://dns.nextdns.io/abc123",
            "https://doh.example.org/dns-query",
            "https://doh.example.org:8443/dns-query",
            "https://1.1.1.1/dns-query",
            "https://[2606:4700:4700::1111]/dns-query",
            "https://dns.example.com/dns-query?ct=application/dns-message",
        )) assertEquals(ok, (SecureDns.validateCustomUrl(ok) as UrlCheck.Ok).url)
        assertEquals("https://Dns.Example.com/q", (SecureDns.validateCustomUrl("HTTPS://Dns.Example.com/q") as UrlCheck.Ok).url)
    }

    @Test fun invalidCustomUrls() {
        assertEquals(UrlError.EMPTY, invalid(null))
        assertEquals(UrlError.EMPTY, invalid("   "))
        assertEquals(UrlError.NOT_HTTPS, invalid("http://dns.nextdns.io/abc"))
        assertEquals(UrlError.NOT_HTTPS, invalid("dns.nextdns.io/abc"))
        assertEquals(UrlError.NOT_HTTPS, invalid("ftp://dns.example.com/"))
        assertEquals(UrlError.NOT_HTTPS, invalid("javascript:alert(1)"))
        assertEquals(UrlError.NO_HOST, invalid("https:///dns-query"))
        assertEquals(UrlError.NO_HOST, invalid("https://nextdns/abc"))
        assertEquals(UrlError.NO_HOST, invalid("https://bad_host.example.com/q"))
        assertEquals(UrlError.MALFORMED, invalid("https://dns.example.com/a b"))
        assertEquals(UrlError.MALFORMED, invalid("https://user:pass@dns.example.com/q"))
        assertEquals(UrlError.MALFORMED, invalid("https://dns.example.com/q#frag"))
        assertEquals(UrlError.MALFORMED, invalid("https:dns.example.com"))
        assertEquals(UrlError.MALFORMED, invalid("https://dns.example.com:99999/q"))
        assertEquals(UrlError.TOO_LONG, invalid("https://dns.example.com/" + "a".repeat(SecureDns.MAX_URL_LENGTH)))
    }

    @Test fun pickerKeysMatchTheCode() {
        val arrays = File(main, "res/values/arrays.xml").readText()
        fun items(name: String) = Regex("<string-array name=\"$name\">(.*?)</string-array>", RegexOption.DOT_MATCHES_ALL)
            .find(arrays)!!.groupValues[1].let { Regex("<item>([^<]*)</item>").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(Provider.entries.map { it.key }, items("doh_provider_keys"))
        assertEquals(SecureDns.Mode.entries.map { it.key }, items("doh_mode_keys"))
        assertEquals(items("doh_provider_keys").size, items("doh_provider_names").size)
        assertEquals(items("doh_mode_keys").size, items("doh_mode_names").size)
    }
}
