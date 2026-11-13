package io.github.jnesew.paudiobooks.ui

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Explicit host phases for native RTL/tablet/assistive-tool journeys; all data is synthetic. */
@RunWith(AndroidJUnit4::class)
class AccessibilityFixtureTest {
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val prefs get() = app.getSharedPreferences("synthetic_accessibility_trial", 0)
    private fun hostOnly() = org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")

    @Test fun seedLongTitleUnknownDurationAndRetainedNote() = runBlocking<Unit> {
        hostOnly()
        if (prefs.contains("book")) {
            verifyFixture()
            return@runBlocking
        }
        fun id() = UUID.randomUUID().toString()
        val book = id(); val asset = id(); val track = id(); val mark = id()
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset")
        file.parentFile!!.mkdirs()
        val size = 16000 * 120
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        file.outputStream().use { out -> out.write(header.array()); repeat(120) { out.write(ByteArray(16000)) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        app.library.books().insert(Book(book,
            "000 Accessibility acceptance, a deliberately long local title with notes — عنوان محلي",
            importedAuthor = "Synthetic local fixture", addedAt = System.currentTimeMillis()))
        app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", file.length(), sha, null)))
        app.library.library().insertTracks(listOf(BookTrack(track, book, asset, 0, "Unknown catalog duration")))
        app.library.library().saveProgress(ReadingState(book, track, 4000, 1.25f, false, System.currentTimeMillis()))
        app.library.library().insertBookmark(Bookmark(mark, book, track, 3000, "Preserve accessibility note", 43))
        check(prefs.edit().putString("book", book).putString("asset", asset).putString("track", track)
            .putString("mark", mark).putString("sha", sha).commit())
        verifyFixture()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("phase", "SYNTHETIC_ACCESSIBILITY_FIXTURE_READY"); putString("syntheticSha256", sha)
        })
    }

    private suspend fun verifyFixture() {
        val book = requireNotNull(prefs.getString("book", null))
        val asset = requireNotNull(app.library.library().asset(requireNotNull(prefs.getString("asset", null))))
        assertTrue(app.audioStore.matches(asset.relativePath, asset.sizeBytes, requireNotNull(prefs.getString("sha", null))))
        assertNull("Unknown catalog duration must not invent a book total", asset.durationMs)
        val row = requireNotNull(app.library.books().get(book))
        assertTrue(row.title.startsWith("000 Accessibility acceptance"))
        val note = requireNotNull(app.library.library().bookmark(requireNotNull(prefs.getString("mark", null))))
        assertEquals(book, note.bookId); assertEquals(prefs.getString("track", null), note.trackId)
        assertEquals(3000L, note.offsetMs); assertEquals(43L, note.createdAt)
        assertEquals("Preserve accessibility note", note.note)
        val state = requireNotNull(app.library.library().progress(book))
        assertEquals(prefs.getString("track", null), state.trackId)
        assertTrue(state.offsetMs in 0..120_000L)
    }

    @Test fun verifyOwnedAudioAndOriginalNotePreserved() = runBlocking<Unit> {
        hostOnly()
        verifyFixture()
    }
}
