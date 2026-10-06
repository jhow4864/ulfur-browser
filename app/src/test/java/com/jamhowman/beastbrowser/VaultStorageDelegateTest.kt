package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.VaultStorageDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.Autocomplete
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultStorageDelegateTest {
    private fun awaitArray(result: org.mozilla.geckoview.GeckoResult<Array<Autocomplete.LoginEntry>>): Array<Autocomplete.LoginEntry> {
        val latch = CountDownLatch(1)
        val box = AtomicReference<Array<Autocomplete.LoginEntry>>(emptyArray())
        result.accept(
            { arr -> box.set(arr ?: emptyArray()); latch.countDown() },
            { latch.countDown() },
        )
        latch.await(2, TimeUnit.SECONDS)
        return box.get()
    }

    @Test fun fetchWhileLockedReturnsEmpty() {
        // activityProvider null → no unlock sheet; still must return empty (no hang).
        PasswordVault.lock()
        val delegate = VaultStorageDelegate { null }
        val arr = awaitArray(delegate.onLoginFetch("https://example.com"))
        assertEquals(0, arr.size)
    }

    @Test fun fetchAllWhileLockedReturnsEmpty() {
        PasswordVault.lock()
        val delegate = VaultStorageDelegate { null }
        val arr = awaitArray(delegate.onLoginFetch())
        assertEquals(0, arr.size)
    }
}
