package io.github.jnesew.paudiobooks.portability

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ProgressPortabilityTest {
    @Test fun previewRequiresExplicitSelectionAndApplyIsTransactional() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            val library = db.library()
            db.books().insert(Book("source", "Book", titleOverride = "Edited", translatorOverride = "Edited translator", seriesNumberOverride = "2.5"))
            db.books().insert(Book("target", "Book"))
            library.insertAssets(listOf(AudioAsset("asset1", "media/one", 20, "a".repeat(64), 1000), AudioAsset("asset2", "media/two", 20, "a".repeat(64), 1000)))
            library.insertTracks(listOf(BookTrack("track1", "source", "asset1", 0), BookTrack("track2", "target", "asset2", 0)))
            library.saveProgress(ReadingState("source", "track1", 500, 1.25f, false, 123))
            library.insertBookmark(Bookmark("mark", "source", "track1", 400, "note", 100))
            val portability = ProgressPortability(db)
            val output = ByteArrayOutputStream()
            portability.export(output)
            val json = output.toString("UTF-8")
            assertFalse(json.contains("media/")); assertFalse(json.contains("track1")); assertFalse(json.contains("asset1"))
            val preview = portability.preview(ByteArrayInputStream(output.toByteArray()))
            val entry = preview.entries.single { it.displayTitle == "Edited" }
            assertEquals(setOf("source", "target"), entry.matchingBookIds.toSet())
            assertNull(library.progress("target"))
            assertEquals(0, portability.apply(preview, listOf(PortabilitySelection(entry.index, "target", false))))
            assertNull(library.progress("target"))
            try {
                portability.apply(preview, listOf(PortabilitySelection(entry.index, "target", true), PortabilitySelection(999, "source", true)))
                fail("Unknown entry must roll back the entire operation")
            } catch (_: IllegalArgumentException) { }
            assertNull(library.progress("target"))
            assertEquals(1, portability.apply(preview, listOf(PortabilitySelection(entry.index, "target", true))))
            assertEquals(500, library.progress("target")!!.offsetMs)
            assertEquals("track2", library.progress("target")!!.trackId)
            assertEquals("Edited", db.books().get("target")!!.titleOverride)
            assertEquals(2, org.json.JSONObject(json).getInt("version"))
            assertEquals("Edited translator", db.books().get("target")!!.translatorOverride)
            assertEquals("2.5", db.books().get("target")!!.seriesNumberOverride)
            assertEquals("note", library.bookmarks("target").single().note)
        } finally { db.close() }
    }

    @Test fun rejectsOversizeAndMalformedInput() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            val portability = ProgressPortability(db)
            for (input in listOf(ByteArray(ProgressPortability.MAX_BYTES + 1), "{\"version\":3,\"books\":[]}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), ("[".repeat(40) + "]".repeat(40)).toByteArray())) {
                try { portability.preview(ByteArrayInputStream(input)); fail("Invalid input accepted") } catch (_: Exception) { }
            }
        } finally { db.close() }
    }
    @Test fun acceptsOnlyOneTransferDocumentWithJsonWhitespace() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            val portability = ProgressPortability(db)
            val document = "{\"version\":2,\"books\":[]}"
            for (input in listOf(document, " \t\r\n$document \t\r\n")) {
                assertTrue(portability.preview(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))).entries.isEmpty())
            }
            for (suffix in listOf("{}", "[]", "true", "0", "\"extra\"", "junk", "/* comment */", "\u0000", " \u0000 ")) {
                var rejected = false
                try { portability.preview(ByteArrayInputStream((document + suffix).toByteArray(Charsets.UTF_8))) }
                catch (_: Exception) { rejected = true }
                assertTrue("Trailing input must be rejected: ${suffix.toCharArray().map { it.code }}", rejected)
            }
        } finally { db.close() }
    }
    @Test fun rejectsInvalidSpeedAndOutOfRangePositionsWithoutChangingTarget() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            db.books().insert(Book("book", "Book"))
            db.library().insertAssets(listOf(AudioAsset("asset", "media/a", 20, "a".repeat(64), 1000)))
            db.library().insertTracks(listOf(BookTrack("track", "book", "asset", 0)))
            db.library().saveProgress(ReadingState("book", "track", 500, 1f, false, 1))
            val portability = ProgressPortability(db)
            val output = ByteArrayOutputStream()
            portability.export(output)
            val original = output.toString("UTF-8")
            for (speed in listOf(0.1, 3.1)) {
                val json = org.json.JSONObject(original)
                json.getJSONArray("books").getJSONObject(0).getJSONObject("progress").put("speed", speed)
                try { portability.preview(ByteArrayInputStream(json.toString().toByteArray())); fail("Invalid speed accepted") }
                catch (_: IllegalArgumentException) { }
            }
            val json = org.json.JSONObject(original)
            json.getJSONArray("books").getJSONObject(0).getJSONObject("progress").put("offset", 1001)
            val preview = portability.preview(ByteArrayInputStream(json.toString().toByteArray()))
            try { portability.apply(preview, listOf(PortabilitySelection(0, "book", true))); fail("Invalid position accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(500, db.library().progress("book")!!.offsetMs)
        } finally { db.close() }
    }

}
