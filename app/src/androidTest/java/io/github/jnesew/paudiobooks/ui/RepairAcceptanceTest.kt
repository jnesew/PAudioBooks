package io.github.jnesew.paudiobooks.ui

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit host phases around a real native picker journey, scoped to newly created synthetic data. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RepairAcceptanceTest {
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val prefs get() = app.getSharedPreferences("synthetic_native_repair", 0)

    @Test fun seedMissingAudioWithProgressNoteAndOverride() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        check(!prefs.contains("book")) { "Preserve existing trial; verify or explicitly clean it first" }
        val fixture = File(app.filesDir, "metadata-fixtures/chapters-quicktime.m4b")
        assertTrue(fixture.isFile)
        val sha = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) }
        val book = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString()
        val track = UUID.randomUUID().toString()
        app.library.books().insert(Book(book, "Synthetic repair acceptance", titleOverride = "Repair acceptance user title"))
        app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", fixture.length(), sha, 12_000)))
        app.library.library().insertTracks(listOf(BookTrack(track, book, asset, 0)))
        assertTrue(app.repository.saveProgress(book, track, 5000, 1.5f))
        val mark = requireNotNull(app.repository.addBookmark(book, track, 4000, "Preserve repair note"))
        val state = requireNotNull(app.library.library().progress(book))
        check(prefs.edit().putString("book", book).putString("asset", asset).putString("track", track)
            .putString("sha", sha).putString("bookmark", mark).putLong("updatedAt", state.updatedAt).commit())
        assertNull("New test-owned asset deliberately starts missing", app.audioStore.resolve("media/$asset"))
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("phase", "NATIVE_REPAIR_FIXTURE_READY")
            putString("syntheticSha256", sha)
        })
    }

    @Test fun verifyNativeRepairPreservedIdentityProgressAndNote() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hostDriven") == "true")
        val book = requireNotNull(prefs.getString("book", null))
        val asset = requireNotNull(prefs.getString("asset", null))
        val track = requireNotNull(prefs.getString("track", null))
        val row = requireNotNull(app.library.books().get(book))
        assertEquals("Repair acceptance user title", row.titleOverride)
        assertEquals("Synthetic repair acceptance", row.title)
        val state = requireNotNull(app.library.library().progress(book))
        assertEquals(track, state.trackId)
        assertEquals(5000L, state.offsetMs)
        assertEquals(1.5f, state.speed, 0.0001f)
        assertEquals(prefs.getLong("updatedAt", -1), state.updatedAt)
        val note = app.library.library().bookmarks(book).single()
        assertEquals(prefs.getString("bookmark", null), note.id)
        assertEquals(track, note.trackId)
        assertEquals("Preserve repair note", note.note)
        val tracks = app.library.library().tracks(book)
        assertEquals(track, tracks.single().id)
        assertEquals(asset, tracks.single().assetId)
        val stored = requireNotNull(app.library.library().asset(asset))
        assertTrue("Native repair must restore matching private bytes", app.audioStore.matches(stored.relativePath, stored.sizeBytes, requireNotNull(prefs.getString("sha", null))))
        assertTrue(app.library.library().jobs().isEmpty())
    }
}
