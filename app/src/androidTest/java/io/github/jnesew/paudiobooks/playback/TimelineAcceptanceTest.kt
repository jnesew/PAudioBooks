package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@UnstableApi
@RunWith(AndroidJUnit4::class)
class TimelineAcceptanceTest {
    @get:org.junit.Rule val foreground = ForegroundPlaybackRule()
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun id() = UUID.randomUUID().toString()
    private fun <T> main(action: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun controller() = main {
        MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    }.get(20, TimeUnit.SECONDS)
    private fun command(c: MediaController, action: String, args: Bundle = Bundle.EMPTY): Int =
        main { c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(20, TimeUnit.SECONDS).resultCode

    private fun wave(seconds: Int, known: Boolean = true): AudioAsset {
        val asset = id()
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset")
        file.parentFile!!.mkdirs()
        val size = 16000 * seconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        file.outputStream().use { out -> out.write(header.array()); repeat(seconds) { out.write(ByteArray(16000)) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return AudioAsset(asset, "media/$asset", file.length(), sha, if (known) seconds * 1000L else null)
    }

    private fun at(c: MediaController, track: String, offset: Long) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!main { c.currentMediaItem?.mediaId == track && abs(c.currentPosition - offset) <= 200 }) {
            check(SystemClock.elapsedRealtime() < deadline) { "Controller did not reach expected track/offset" }
            Thread.sleep(25)
        }
        assertFalse(main { c.playWhenReady })
    }

    private fun cleanup(c: MediaController, book: String, assets: List<AudioAsset>) {
        assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.UNLOAD_AND_SAVE))
        main { c.release() }
        runBlocking {
            app.library.books().delete(book)
            for (asset in assets) app.library.library().deleteAsset(asset.id)
        }
        assets.forEach { File(app.noBackupFilesDir, "audiobooks/${it.relativePath}").delete() }
    }

    @Test fun realThousandTrack33HourBookSeeksAcrossDayAndBoundaryThenUndoesPaused() {
        // Repeated two-minute PCM is real, shared synthetic audio, not a fabricated long duration.
        val asset = wave(120)
        val book = id()
        val tracks = List(1000) { BookTrack(id(), book, asset.id, it) }
        runBlocking {
            app.library.books().insert(Book(book, "Synthetic 33-hour timeline"))
            app.library.library().insertAssets(listOf(asset))
            app.library.library().insertTracks(tracks)
        }
        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) }))
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 86_401_234L) }))
            at(c, tracks[720].id, 1234)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.PAUSE_AND_SAVE))
            val saved = runBlocking { requireNotNull(app.library.library().progress(book)) }
            assertEquals(tracks[720].id, saved.trackId)
            assertEquals(1234L, saved.offsetMs)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 120_000) }))
            at(c, tracks[1].id, 0)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.UNDO_SEEK))
            at(c, tracks[720].id, 1234)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 120_000_000) }))
            at(c, tracks.last().id, 120_000)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.PAUSE_AND_SAVE))
            assertFalse("Seeking to final boundary must not mark finished", runBlocking { requireNotNull(app.library.library().progress(book)) }.finished)
        } finally { cleanup(c, book, listOf(asset)) }
    }

    @Test fun differingAndUnknownTrackDurationsKeepMediaTimeAndRejectInventedTotals() {
        val assets = listOf(wave(2), wave(7), wave(3, known = false))
        val book = id()
        val tracks = assets.mapIndexed { index, asset -> BookTrack(id(), book, asset.id, index) }
        runBlocking {
            app.library.books().insert(Book(book, "Synthetic unknown timeline"))
            app.library.library().insertAssets(assets)
            app.library.library().insertTracks(tracks)
        }
        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book) }))
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 2000) }))
            // Public file navigation remains available even when a book-wide total is unknown.
            main { c.seekToNextMediaItem() }
            at(c, tracks[1].id, 0)
            main { c.seekTo(6500) }
            at(c, tracks[1].id, 6500)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.PAUSE_AND_SAVE))
            val saved = runBlocking { requireNotNull(app.library.library().progress(book)) }
            assertEquals(tracks[1].id, saved.trackId)
            assertEquals(6500L, saved.offsetMs)
            main { c.seekToNextMediaItem() }
            at(c, tracks[2].id, 0)
            assertFalse(main { c.playWhenReady })
        } finally { cleanup(c, book, assets) }
    }
}
