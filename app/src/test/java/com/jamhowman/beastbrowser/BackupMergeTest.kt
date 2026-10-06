package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.backup.BackupArticle
import com.jamhowman.beastbrowser.backup.BackupBookmark
import com.jamhowman.beastbrowser.backup.BackupCrypto
import com.jamhowman.beastbrowser.backup.BackupMerge
import com.jamhowman.beastbrowser.backup.BackupPayload
import com.jamhowman.beastbrowser.backup.BackupTile
import com.jamhowman.beastbrowser.data.BookmarkFolder
import com.jamhowman.beastbrowser.data.CustomFolder
import com.jamhowman.beastbrowser.data.CustomFolders
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

    @Test fun bookmarksAddedOnlyIfUrlMissingAndFoldersResolved() {
        val add = BackupMerge.newBookmarks(
            existingUrls = setOf("https://have.test/"),
            incoming = listOf(
                BackupBookmark("https://have.test/", "Have", 1, "work"),
                BackupBookmark("https://new.test/", "New", 2, "reading"),
                BackupBookmark("https://new.test/", "New again", 3, null),
                BackupBookmark("https://odd.test/", "Odd", 4, "no-such-folder"),
            ),
        ) { if (it == "no-such-folder") "c-created" else it }
        assertEquals(listOf("https://new.test/", "https://odd.test/"), add.map { it.url })
        assertEquals("reading", add[0].folder)
        assertEquals("c-created", add[1].folder)
    }

    private val builtIn: (String) -> Boolean = { BookmarkFolder.from(it) != null }
    private fun ids(): () -> String { var n = 0; return { "c-gen${++n}" } }

    @Test fun missingFoldersAreCreatedWithNesting() {
        val backupFolders = listOf(
            CustomFolder("c-travel", "Travel", null),
            CustomFolder("c-japan", "Japan", "c-travel"),
            CustomFolder("c-tokyo", "Tokyo", "c-japan"),
            CustomFolder("c-recipes", "Recipes", "personal"),   // nested under a built-in folder
            CustomFolder("c-unused", "Unused", null),
        )
        val bms = listOf(
            BackupBookmark("https://a.test/", "A", 1, "c-tokyo"),
            BackupBookmark("https://b.test/", "B", 2, "c-recipes"),
            BackupBookmark("https://c.test/", "C", 3, "work"),
            BackupBookmark("https://d.test/", "D", 4, null),
        )
        val plan = BackupMerge.planFolders(emptyList(), backupFolders, bms, builtIn, ids())
        // Parents first, only folders that are used (directly or as an ancestor), ids kept.
        assertEquals(listOf("c-travel", "c-japan", "c-tokyo", "c-recipes"), plan.create.map { it.id })
        assertEquals("c-japan", plan.create.single { it.id == "c-tokyo" }.parentId)
        assertEquals("c-travel", plan.create.single { it.id == "c-japan" }.parentId)
        assertNull(plan.create.single { it.id == "c-travel" }.parentId)
        assertEquals("personal", plan.create.single { it.id == "c-recipes" }.parentId)
        assertEquals("c-tokyo", plan.resolve("c-tokyo"))
        assertEquals("work", plan.resolve("work"))
        assertEquals("Travel / Japan / Tokyo", CustomFolders.path("c-tokyo", plan.create) { it.name })
        assertEquals("PERSONAL / Recipes", CustomFolders.path("c-recipes", plan.create) { it.name })
    }

    @Test fun existingFoldersAreReusedNotDuplicated() {
        val existing = listOf(CustomFolder("c-mine", "Travel", null), CustomFolder("c-same-id", "Renamed here", null))
        val backupFolders = listOf(
            CustomFolder("c-other", "  travel ", null),         // same name (case/space-insensitive), same parent
            CustomFolder("c-child", "Japan", "c-other"),        // child goes under the device's folder
            CustomFolder("c-same-id", "Old name", null),        // same id → same folder even if renamed
        )
        val bms = listOf(
            BackupBookmark("https://a.test/", "A", 1, "c-child"),
            BackupBookmark("https://b.test/", "B", 2, "c-same-id"),
            BackupBookmark("https://c.test/", "C", 3, "c-other"),
        )
        val plan = BackupMerge.planFolders(existing, backupFolders, bms, builtIn, ids())
        assertEquals(listOf("c-child"), plan.create.map { it.id })
        assertEquals("c-mine", plan.create.single().parentId)
        assertEquals("c-mine", plan.resolve("c-other"))
        assertEquals("c-same-id", plan.resolve("c-same-id"))
        // Importing the same backup again creates nothing.
        val again = BackupMerge.planFolders(existing + plan.create, backupFolders, bms, builtIn, ids())
        assertTrue(again.create.isEmpty())
        assertEquals("c-child", again.resolve("c-child"))
    }

    @Test fun unlistedFolderBecomesTopLevelFolderAndCyclesAreBroken() {
        val backupFolders = listOf(CustomFolder("c-a", "A", "c-b"), CustomFolder("c-b", "B", "c-a"))
        val bms = listOf(
            BackupBookmark("https://x.test/", "X", 1, "Holiday ideas"), // folder not listed (hand-edited / newer app)
            BackupBookmark("https://y.test/", "Y", 2, "c-a"),
        )
        val plan = BackupMerge.planFolders(emptyList(), backupFolders, bms, builtIn, ids())
        val holiday = plan.create.single { it.name == "Holiday ideas" }
        assertNull(holiday.parentId)
        assertEquals(holiday.id, plan.resolve("Holiday ideas"))
        assertEquals(3, plan.create.size)
        assertTrue("no folder may end up its own ancestor", plan.create.none { f ->
            generateSequence(f.parentId) { p -> plan.create.firstOrNull { it.id == p }?.parentId }.take(10).any { it == f.id }
        })
    }

    @Test fun customFoldersPrefsRoundTripAndOrdering() {
        val list = listOf(
            CustomFolder("c-2", "beta", "c-1"), CustomFolder("c-1", "Alpha", null), CustomFolder("c-3", "Gamma", "work"),
        )
        assertEquals(list, CustomFolders.parse(CustomFolders.serialize(list)))
        assertTrue(CustomFolders.parse("not json").isEmpty())
        // Built-in ids, blanks, self-parents and duplicates are dropped / repaired.
        val junk = """[{"id":"work","name":"W"},{"id":"","name":"x"},{"id":"c-s","name":"S","parent":"c-s"},{"id":"c-s","name":"dup"}]"""
        assertEquals(listOf(CustomFolder("c-s", "S", null)), CustomFolders.parse(junk))
        assertEquals(listOf("c-1", "c-2", "c-3"), CustomFolders.ordered(list).map { it.id })
        assertTrue(CustomFolders.newId().startsWith(CustomFolders.ID_PREFIX))
        assertNull(BookmarkFolder.from(CustomFolders.newId()))
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
            folders = listOf(CustomFolder("c-1", "Travel", null), CustomFolder("c-2", "Japan", "c-1")),
        )
        val pass = "a long passphrase".toCharArray()
        val back = BackupPayload.fromJson(String(BackupCrypto.decrypt(BackupCrypto.encrypt(p.toJson().toByteArray(), pass.copyOf()), pass), Charsets.UTF_8))
        assertEquals(p.logins!!.single().copy(guid = "x"), back.logins!!.single().copy(guid = "x"))
        assertEquals(p.bookmarks, back.bookmarks)
        assertEquals(p.speedDial, back.speedDial)
        assertEquals(p.readingList, back.readingList)
        assertEquals(p.settings, back.settings)
        assertEquals(p.folders, back.folders)
        assertNull("2.4.0 backups have no folder list", BackupPayload.fromJson("""{"bookmarks":[]}""").folders)
        assertTrue("toString must not leak passwords", !p.toString().contains("s3cr3t"))
        assertNull(BackupPayload.fromJson(BackupPayload(bookmarks = emptyList()).toJson()).logins) // absent ≠ empty
    }
}
