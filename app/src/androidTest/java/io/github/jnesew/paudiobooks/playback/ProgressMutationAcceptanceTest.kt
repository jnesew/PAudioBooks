package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.*
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
import java.util.UUID
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ProgressMutationAcceptanceTest {
    @get:org.junit.Rule val foreground = ForegroundPlaybackRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check()) { assertTrue(message, SystemClock.elapsedRealtime() < deadline); Thread.sleep(25) }
    }
    @Test fun resettingCachedInactiveBookKeepsCurrentPlaybackAndSurvivesReload() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val asset = UUID.randomUUID().toString(); val ta = UUID.randomUUID().toString(); val tb = UUID.randomUUID().toString()
        val mark = UUID.randomUUID().toString()
        val file = File(app.noBackupFilesDir, "audiobooks/media/$asset").also { it.parentFile!!.mkdirs() }
        val length = 30 * 16000
        val bytes = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(length)
        file.writeBytes(bytes.array())
        var controller: MediaController? = null
        fun command(action: String, args: Bundle = Bundle.EMPTY): SessionResult {
            val f = main { requireNotNull(controller).sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
            return f.get(15, TimeUnit.SECONDS)
        }
        try {
            app.library.books().insert(Book(a, "Synthetic current book"))
            app.library.books().insert(Book(b, "Synthetic cached book", translatorOverride = "Keep translator", seriesNumberOverride = "2.5"))
            app.library.library().insertAssets(listOf(AudioAsset(asset, "media/$asset", file.length(), "synthetic-mutation", 30_000)))
            app.library.library().insertTracks(listOf(BookTrack(ta, a, asset, 0), BookTrack(tb, b, asset, 0)))
            app.library.library().saveProgress(ReadingState(b, tb, 9000, 1.25f, false, 123))
            app.library.library().insertBookmark(Bookmark(mark, b, tb, 4000, "Keep reset note", 456))
            val future = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            controller = future.get(15, TimeUnit.SECONDS)
            assertEquals(SessionResult.RESULT_SUCCESS, command(PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, b) }).resultCode)
            await("B ready") { main { controller!!.playbackState == Player.STATE_READY } }
            main { controller!!.seekTo(8000) }
            assertEquals(SessionResult.RESULT_SUCCESS, command(PlaybackService.PAUSE_AND_SAVE).resultCode)
            assertEquals(SessionResult.RESULT_SUCCESS, command(PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, a) }).resultCode)
            await("A ready") { main { controller!!.playbackState == Player.STATE_READY } }
            main { controller!!.play() }
            await("A playing") { main { controller!!.isPlaying } }
            assertEquals(SessionResult.RESULT_SUCCESS, command(PlaybackService.SET_BOOK_FINISHED, Bundle().apply {
                putString(PlaybackService.BOOK_ID, b); putBoolean(PlaybackService.FINISHED, false)
            }).resultCode)
            assertTrue("Resetting B must not pause A", main { controller!!.playWhenReady })
            assertEquals(0L, app.library.library().progress(b)!!.offsetMs)
            assertFalse(app.library.library().progress(b)!!.finished)
            assertEquals(SessionResult.RESULT_SUCCESS, command(PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, b) }).resultCode)
            await("B reloaded at reset position") { main { controller!!.playbackState == Player.STATE_READY && controller.currentPosition == 0L } }
            assertFalse(main { controller!!.playWhenReady })
            assertEquals(SessionResult.RESULT_SUCCESS, command(PlaybackService.SEEK_TRACK_POSITION, Bundle().apply {
                putString(PlaybackService.BOOK_ID, b); putString(PlaybackService.TRACK_ID, tb); putLong(PlaybackService.TRACK_POSITION_MS, 4000)
            }).resultCode)
            await("Explicit stable-track seek") { main { controller!!.currentPosition == 4000L } }
            assertFalse("Overview seek must preserve pause", main { controller!!.playWhenReady })
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, command(PlaybackService.SEEK_TRACK_POSITION, Bundle().apply {
                putString(PlaybackService.BOOK_ID, a); putString(PlaybackService.TRACK_ID, tb); putLong(PlaybackService.TRACK_POSITION_MS, 2000)
            }).resultCode)
            assertEquals("Keep translator", app.library.books().get(b)!!.translatorOverride)
            assertEquals("2.5", app.library.books().get(b)!!.seriesNumberOverride)
            assertEquals(listOf(Bookmark(mark, b, tb, 4000, "Keep reset note", 456)), app.library.library().bookmarks(b))
        } finally {
            if (controller != null) {
                command(PlaybackService.UNLOAD_AND_SAVE)
                main { controller.release() }
            }
            app.importer.removeBook(a); app.importer.removeBook(b)
            if (app.library.library().asset(asset) == null) file.delete()
        }
    }
}
