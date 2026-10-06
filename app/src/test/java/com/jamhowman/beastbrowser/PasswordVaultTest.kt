
package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.SavedLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.Autocomplete
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordVaultTest {
    @Test fun originNormalization() {
        assertEquals("https://example.com", PasswordVault.normalizeOrigin("https://www.example.com/"))
        assertEquals("https://example.com", PasswordVault.normalizeOrigin("HTTPS://EXAMPLE.COM"))
        assertEquals("https://example.com", PasswordVault.normalizeOrigin("example.com"))
    }

    @Test fun geckoRoundTrip() {
        val entry = Autocomplete.LoginEntry.Builder()
            .guid("g1")
            .origin("https://example.com")
            .formActionOrigin("https://example.com")
            .username("jam")
            .password("s3cret")
            .build()
        val saved = SavedLogin.fromGecko(entry)
        assertEquals("jam", saved.username)
        assertEquals("s3cret", saved.password)
        val back = saved.toGecko()
        assertEquals("g1", back.guid)
        assertEquals("https://example.com", back.origin)
        assertEquals("jam", back.username)
        assertEquals("s3cret", back.password)
    }

    @Test fun toGeckoArray() {
        val list = listOf(
            SavedLogin(origin = "https://a.com", username = "u", password = "p"),
            SavedLogin(origin = "https://b.com", username = "v", password = "q"),
        )
        val arr = PasswordVault.toGeckoArray(list)
        assertEquals(2, arr.size)
        assertTrue(arr[0].username == "u" || arr[1].username == "u")
    }

    @Test fun metaForOriginEmptyWhenUninitialisedOrUnknown() {
        // Without init / meta file, locked-safe lookup must not throw and must be empty.
        assertTrue(PasswordVault.metaForOrigin("https://example.com").isEmpty())
        assertTrue(PasswordVault.metaForOrigin(null).isEmpty())
        assertTrue(PasswordVault.metaForOrigin("").isEmpty())
    }
}
