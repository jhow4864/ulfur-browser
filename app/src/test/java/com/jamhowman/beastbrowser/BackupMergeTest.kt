package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.backup.BackupArticle
import com.jamhowman.beastbrowser.backup.BackupBookmark
import com.jamhowman.beastbrowser.backup.BackupCrypto
import com.jamhowman.beastbrowser.backup.BackupMerge
import com.jamhowman.beastbrowser.backup.BackupPayload
import com.jamhowman.beastbrowser.backup.BackupTile
import com.jamhowman.beastbrowser.data.BookmarkFolder
import com.jamhowman.beastbrowser.passwords.PasswordVault
import com.jamhowman.beastbrowser.passwords.SavedLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupMergeTest {
    private val norm: (String) -> String = PasswordVault::normalizeOrigin

    private fun login(origin: String, user: String, pw: String, guid: String = java.util.UUID.randomUUID().toString()) =
        SavedLogin(guid = guid, origin = origin, username = user, password = pw, createdAt = 10, updatedAt = 20, timesUsed = 3)

    @Test fun loginsSkipExactUpdateChangedAddNew() {
        val existing = listOf(
            login("https://example.org", "ann", "same", guid = "g1"),
            login("https://shop.test", "bob", "old", guid = "g2"),
        )
        val incoming = listOf(
            login("https://www.example.org/", "ann", "same"),      // same origin after normalising → exact duplicate
            login("https://shop.test", "bob", "new"),              // same origin+user, different password → update
            login("https://shop.test", "carol", "pw"),             // new user → add
            login("https://shop.test", "carol", "pw"),             // duplicate within the import → skip
            login("https://empty.test", "x", ""),                  // no password → skip
        )
        val r = BackupMerge.mergeLogins(existing, incoming, norm, now = 999)
        assertEquals(1, r.added); assertEquals(1, r.updated); assertEquals(3, r.skipped)
        assertEquals(3, r.merged.size)
        val bob = r.merged.single { it.username == "bob" }
        assertEquals("new", bob.password)
        assertEquals("g2", bob.guid)            // identity, created and usage kept
        assertEquals(10, bob.createdAt); assertEquals(3, bob.timesUsed); assertEquals(999, bob.updatedAt)
        assertEquals("same", r.merged.single { it.username == "ann" }.password)
    }

    @Test fun importedGuidCollisionGetsNewGuid() {
        val r = BackupMerge.mergeLogins(listOf(login("https://a.test", "u", "p", guid = "dup")),
            listOf(login("https://b.test", "u", "p", guid = "dup")), norm)
        assertEquals(1, r.added)
        assertEquals(2, r.merged.map { it.guid }.toSet().size)
    }

    @Test fun bookmarksAddedOnlyIfUrlMissingAndFoldersKept() {
        val add = BackupMerge.newBookmarks(
            existingUrls = setOf("https://have.test/"),
            incoming = listOf(
                BackupBookmark("https://have.test/", "Have", 1, "work"),
                BackupBookmark("https://new.test/", "New", 2, "reading"),
                BackupBookmark("https://new.test/", "New again", 3, null),
                BackupBookmark("https://odd.test/", "Odd", 4, "no-such-folder"),
            ),
        ) { BookmarkFolder.from(it) != null }
        assertEquals(listOf("https://new.test/", "https://odd.test/"), add.map { it.url })
        assertEquals("reading", add[0].folder)
        assertNull(add[1].folder)
    }

    @Test fun speedDialAndReadingListDedupeByUrl() {
        val (tiles, added) = BackupMerge.mergeSpeedDial(
            listOf(BackupTile("A", "https://a.test")),
            listOf(BackupTile("A2", "https://a.test"), BackupTile("B", "https://b.test")),
        )
        assertEquals(1, added); assertEquals(listOf("https://a.test", "https://b.test"), tiles.map { it.url })
        val art = BackupArticle("https://r.test/1", "T", "", "", "", "<p>x</p>", "en", "ltr", "", 5, 10, false)
        assertEquals(1, BackupMerge.newArticles(setOf("https://r.test/0"), listOf(art, art)).size)
    }

    @Test fun payloadSurvivesEncryptedRoundTrip() {
        val p = BackupPayload(
            appVersion = "2.4.0",
            logins = listOf(login("https://example.org", "ann", "s3cr3t-Sample!").copy(httpRealm = "realm")),
            bookmarks = listOf(BackupBookmark("https://b.test/", "B", 7, "work")),
            speedDial = listOf(BackupTile("T", "https://t.test")),
            readingList = listOf(BackupArticle("https://r.test/", "R", "by", "site", "ex", "<p>é</p>", "fr", "ltr", "2026", 9, 100, true)),
            settings = mapOf("block_ads" to false, "accent" to "cyan"),
        )
        val pass = "a long passphrase".toCharArray()
        val back = BackupPayload.fromJson(String(BackupCrypto.decrypt(BackupCrypto.encrypt(p.toJson().toByteArray(), pass.copyOf()), pass), Charsets.UTF_8))
        assertEquals(p.logins!!.single().copy(guid = "x"), back.logins!!.single().copy(guid = "x"))
        assertEquals(p.bookmarks, back.bookmarks)
        assertEquals(p.speedDial, back.speedDial)
        assertEquals(p.readingList, back.readingList)
        assertEquals(p.settings, back.settings)
        assertTrue("toString must not leak passwords", !p.toString().contains("s3cr3t"))
        assertNull(BackupPayload.fromJson(BackupPayload(bookmarks = emptyList()).toJson()).logins) // absent ≠ empty
    }
}
