package io.github.jnesew.paudiobooks.importing

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

/** Host phases around a real native SAF copy, external SIGKILL and user recovery. */
@RunWith(AndroidJUnit4::class)
class NativeImportInterruptionTest {
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val prefs get() = app.getSharedPreferences("synthetic_import_interruption", 0)
    private fun hostOnly() = org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")

    @Test fun seedPreservedBook() = runBlocking<Unit> {
        hostOnly()
        check(!prefs.contains("book")) { "Preserve an existing trial rather than overwrite it" }
        val book = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString()
        val track = UUID.randomUUID().toString()
        val size = 16000 * 8
        val bytes = ByteBuffer.allocate(size + 44).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes.array())
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        app.library.books().insert(Book(book, "Synthetic import baseline", titleOverride = "Keep import baseline"))
        app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", file.length(), sha, 8000)))
        app.library.library().insertTracks(listOf(BookTrack(track, book, asset, 0)))
        assertTrue(app.repository.saveProgress(book, track, 4000, 1.25f))
        val note = requireNotNull(app.repository.addBookmark(book, track, 3000, "Retain import fault note"))
        check(prefs.edit().putString("book", book).putString("asset", asset).putString("track", track)
            .putString("sha", sha).putString("note", note)
            .putLong("updatedAt", requireNotNull(app.library.library().progress(book)).updatedAt).commit())
    }

    private suspend fun retainedData() {
        val book = requireNotNull(prefs.getString("book", null))
        assertEquals("Keep import baseline", requireNotNull(app.library.books().get(book)).titleOverride)
        val progress = requireNotNull(app.library.library().progress(book))
        assertEquals(prefs.getString("track", null), progress.trackId)
        assertEquals(4000L, progress.offsetMs)
        assertEquals(1.25f, progress.speed, 0.0001f)
        assertEquals(prefs.getLong("updatedAt", -1), progress.updatedAt)
        val note = app.library.library().bookmarks(book).single()
        assertEquals(prefs.getString("note", null), note.id)
        assertEquals("Retain import fault note", note.note)
        val asset = requireNotNull(app.library.library().asset(requireNotNull(prefs.getString("asset", null))))
        assertTrue(app.audioStore.matches(asset.relativePath, asset.sizeBytes, requireNotNull(prefs.getString("sha", null))))
    }

    @Test fun observeActualInterruptedCopyBeforeNativeDiscard() = runBlocking<Unit> {
        hostOnly()
        retainedData()
        val job = app.library.library().jobs().single()
        assertEquals("COPYING", job.state)
        val copy = app.library.library().importFiles(job.id).single()
        assertEquals("COPYING", copy.state)
        val file = app.audioStore.stagedFile(copy.assetId)
        assertTrue("Actual partial file, not a fabricated journal", file.isFile)
        assertTrue("Copy must be interrupted before EOF", file.length() in 1 until (16L * 1024 * 1024 + 44))
        check(prefs.edit().putString("interruptedAsset", copy.assetId).commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("phase", "DURABLE_PARTIAL_COPY_FOUND"); putLong("partialBytes", file.length())
        })
    }

    @Test fun verifyNativeDiscardKeptExistingBookAndRemovedPartialCopy() = runBlocking<Unit> {
        hostOnly()
        retainedData()
        assertTrue(app.library.library().jobs().isEmpty())
        val partial = requireNotNull(prefs.getString("interruptedAsset", null))
        assertFalse(app.audioStore.stagedFile(partial).exists())
        assertNull(app.audioStore.resolve(app.audioStore.relativePath(partial)))
    }
}
