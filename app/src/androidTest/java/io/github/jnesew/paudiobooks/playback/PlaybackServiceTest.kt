package io.github.jnesew.paudiobooks.playback

import android.content.ComponentName
import android.os.Bundle
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
import java.util.UUID
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    @get:org.junit.Rule
    val foregroundPlayback = io.github.jnesew.paudiobooks.playback.ForegroundPlaybackRule()

    @Test fun sessionPlayerRemovesUnapprovedMetadataFromCurrentItemAndMergedMetadata() {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(app).build()
            try {
                val unsafe = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("Local title")
                    .setArtist("Local author")
                    .setSubtitle("Untrusted subtitle")
                    .setArtworkUri(android.net.Uri.parse("https://invalid.example/cover"))
                    .setArtworkData(byteArrayOf(1, 2, 3))
                    .build()
                player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("test")
                    .setUri("paudio://asset/test").setMediaMetadata(unsafe).build())
                val wrapper = SessionPlayer(player, "Private")
                assertEquals("Local title", wrapper.mediaMetadata.title.toString())
                assertNull(wrapper.mediaMetadata.artworkUri)
                assertNull(wrapper.mediaMetadata.artworkData)
                assertNull(wrapper.mediaMetadata.subtitle)
                assertNull(wrapper.currentMediaItem!!.mediaMetadata.artworkUri)
                assertNull(wrapper.currentMediaItem!!.mediaMetadata.artworkData)
                val approved = byteArrayOf(4, 5, 6)
                wrapper.setApprovedArtwork(approved)
                assertArrayEquals(approved, wrapper.mediaMetadata.artworkData)
                assertArrayEquals(approved, wrapper.currentMediaItem!!.mediaMetadata.artworkData)
                wrapper.setShowDetails(false)
                assertNull(wrapper.mediaMetadata.artworkData)
                assertNull(wrapper.currentMediaItem!!.mediaMetadata.artworkData)
                assertEquals("Private", wrapper.mediaMetadata.title.toString())
                assertEquals("Private", wrapper.currentMediaItem!!.mediaMetadata.title.toString())
                assertNull(wrapper.mediaMetadata.artist)
            } finally {
                player.release()
            }
        }
    }
    @Test fun knownLocalBookLoadsPausedAndPauseCommits() {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val media = File(app.noBackupFilesDir, "audiobooks/media/$assetId")
        media.parentFile!!.mkdirs()
        // Ten seconds of silent 8 kHz mono PCM; no external content or codecs.
        val bytes = ByteBuffer.allocate(44 + 160000).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + 160000).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(160000)
        media.writeBytes(bytes.array())
        runBlocking {
            app.library.books().insert(Book(bookId, "Synthetic"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", media.length(), "test", 10000)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        try {
            lateinit var load: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                load = controller.sendCustomCommand(SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY), Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, load.get(20, TimeUnit.SECONDS).resultCode)
            var ready = false
            repeat(50) {
                instrumentation.runOnMainSync { ready = controller.playbackState == androidx.media3.common.Player.STATE_READY }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("Player did not reach ready", ready)
            runBlocking { app.settings.setShowLockScreenDetails(false) }
            var privateTitle = ""
            repeat(50) {
                instrumentation.runOnMainSync { privateTitle = controller.mediaMetadata.title?.toString().orEmpty() }
                if (privateTitle != app.getString(io.github.jnesew.paudiobooks.R.string.app_name)) Thread.sleep(100)
            }
            assertEquals(app.getString(io.github.jnesew.paudiobooks.R.string.app_name), privateTitle)
            instrumentation.runOnMainSync {
                assertEquals(privateTitle, controller.currentMediaItem?.mediaMetadata?.title?.toString())
                assertNull(controller.mediaMetadata.artist)
            }
            runBlocking { app.settings.setShowLockScreenDetails(true) }
            lateinit var bookSeek: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 10001) })
            }
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 500) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync {
                assertFalse(controller.playWhenReady)
                assertFalse(controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS))
                assertFalse(controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM))
                assertEquals("paudio", controller.currentMediaItem?.localConfiguration?.uri?.scheme)
                assertNull(controller.mediaMetadata.artworkUri)
                assertNull(controller.mediaMetadata.artworkData)
            }
            var position = 0L
            repeat(50) {
                instrumentation.runOnMainSync { position = controller.currentPosition }
                if (position == 500L) return@repeat
                Thread.sleep(100)
            }
            assertEquals(500L, position)
            lateinit var pause: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            val saved = runBlocking { app.library.library().progress(bookId) }
            assertEquals(trackId, saved?.trackId)
            assertEquals(500L, saved?.offsetMs)
            instrumentation.runOnMainSync { controller.play() }
            var playing = false
            repeat(50) {
                instrumentation.runOnMainSync { playing = controller.isPlaying }
                if (!playing) Thread.sleep(100)
            }
            var diagnostics = ""
            instrumentation.runOnMainSync {
                diagnostics = "state=${controller.playbackState} playWhenReady=${controller.playWhenReady} suppression=${controller.playbackSuppressionReason} error=${controller.playerError?.errorCode}"
            }
            assertTrue("Playback did not start: $diagnostics", playing)
            Thread.sleep(1500)
            var backgroundPosition = 0L
            instrumentation.runOnMainSync { backgroundPosition = controller.currentPosition }
            assertTrue("Position did not advance in background", backgroundPosition > 1000L)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync { assertNull(controller.currentMediaItem) }
            val unloaded = runBlocking { app.library.library().progress(bookId) }
            assertNotNull(unloaded)
            assertTrue(unloaded!!.offsetMs > 1000L)
            instrumentation.runOnMainSync {
                load = controller.sendCustomCommand(SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, load.get(20, TimeUnit.SECONDS).resultCode)
            var reloadedPosition = -1L
            repeat(50) {
                instrumentation.runOnMainSync { reloadedPosition = controller.currentPosition }
                if (reloadedPosition != unloaded.offsetMs) Thread.sleep(100)
            }
            instrumentation.runOnMainSync { assertFalse(controller.playWhenReady) }
            assertEquals(unloaded.offsetMs, reloadedPosition)

            lateinit var timer: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                controller.play()
                timer = controller.sendCustomCommand(SessionCommand(PlaybackService.SET_SLEEP_TIMER, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.SLEEP_DURATION_MS, 1000L) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, timer.get(20, TimeUnit.SECONDS).resultCode)
            var timerVisible = false
            repeat(20) {
                instrumentation.runOnMainSync { timerVisible = controller.sessionExtras.getBoolean(PlaybackService.SLEEP_ACTIVE) }
                if (!timerVisible) Thread.sleep(25)
            }
            assertTrue("Timer state must reach the UI controller", timerVisible)
            var pausedByTimer = false
            repeat(50) {
                Thread.sleep(100)
                instrumentation.runOnMainSync { pausedByTimer = !controller.playWhenReady }
                if (pausedByTimer) return@repeat
            }
            assertTrue("Sleep timer did not pause playback", pausedByTimer)
            instrumentation.runOnMainSync { assertFalse(controller.sessionExtras.getBoolean(PlaybackService.SLEEP_ACTIVE)) }
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            val timerSaved = runBlocking { app.library.library().progress(bookId) }
            assertTrue("Timer position was not saved", timerSaved!!.offsetMs >= unloaded.offsetMs)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.MARK_FINISHED, Bundle.EMPTY), Bundle.EMPTY)
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            assertTrue(runBlocking { app.library.library().progress(bookId) }!!.finished)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 500L) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.UNDO_SEEK, Bundle.EMPTY), Bundle.EMPTY)
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            val undone = runBlocking { app.library.library().progress(bookId) }!!
            assertFalse(undone.finished)
            assertTrue(undone.offsetMs >= unloaded.offsetMs)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 10_000L) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            Thread.sleep(500)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            assertFalse("Paused seek to end must not mark finished", runBlocking { app.library.library().progress(bookId) }!!.finished)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 1000L) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync { controller.play() }
            Thread.sleep(300)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 10_000L) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            Thread.sleep(500)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            assertFalse("Playing seek to end must not mark finished", runBlocking { app.library.library().progress(bookId) }!!.finished)
            instrumentation.runOnMainSync { controller.seekTo(1000L); controller.play() }
            Thread.sleep(300)
            instrumentation.runOnMainSync { controller.seekTo(10_000L) }
            Thread.sleep(500)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            assertFalse("Session transport seek to end must not mark finished", runBlocking { app.library.library().progress(bookId) }!!.finished)
            instrumentation.runOnMainSync {
                bookSeek = controller.sendCustomCommand(SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 9_500L) })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, bookSeek.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync { controller.play() }
            var reachedEnd = false
            repeat(50) {
                instrumentation.runOnMainSync { reachedEnd = controller.playbackState == androidx.media3.common.Player.STATE_ENDED }
                if (!reachedEnd) Thread.sleep(100)
            }
            assertTrue("Playback did not reach end", reachedEnd)
            instrumentation.runOnMainSync { pause = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, pause.get(20, TimeUnit.SECONDS).resultCode)
            assertTrue("Playback reaching end must mark finished", runBlocking { app.library.library().progress(bookId) }!!.finished)
        } finally {
            runBlocking { app.settings.setShowLockScreenDetails(true) }
            instrumentation.runOnMainSync { controller.release() }
        }
    }

    @Test
    fun missingAudioSourceReturnsErrorWithoutCrashing() {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()

        // Notice: No file is created on disk for this asset!
        runBlocking {
            app.library.books().insert(Book(bookId, "Missing File Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", 1000L, "test-missing", 10000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        try {
            lateinit var unloadFirst: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                unloadFirst = controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
            }
            unloadFirst.get(10, TimeUnit.SECONDS)

            lateinit var load: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                load = controller.sendCustomCommand(
                    SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }
                )
            }
            val result = load.get(20, TimeUnit.SECONDS)
            // Missing file must fail gracefully with BAD_VALUE, never crash
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, result.resultCode)
            instrumentation.runOnMainSync {
                assertNull("No media item should be loaded for missing file", controller.currentMediaItem)
            }
        } finally {
            runBlocking {
                app.library.books().delete(bookId)
                app.library.library().deleteAsset(assetId)
            }
            instrumentation.runOnMainSync { controller.release() }
        }
    }

    @Test
    fun corruptAudioSourceRecoversCleanly() {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val corruptFile = File(app.noBackupFilesDir, "audiobooks/media/$assetId")
        corruptFile.parentFile?.mkdirs()
        // 64 bytes of invalid garbage data, definitely not a valid WAV or MP3
        corruptFile.writeBytes(ByteArray(64) { (it * 7).toByte() })

        runBlocking {
            app.library.books().insert(Book(bookId, "Corrupt File Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", corruptFile.length(), "test-corrupt", 10000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        try {
            lateinit var load: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                load = controller.sendCustomCommand(
                    SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }
                )
            }
            // Book metadata and file existence check passes:
            assertEquals(SessionResult.RESULT_SUCCESS, load.get(20, TimeUnit.SECONDS).resultCode)

            // Start playing the corrupt source
            instrumentation.runOnMainSync { controller.play() }

            // ExoPlayer will attempt to read and parse the header, fail with PlaybackException,
            // and transition to IDLE / stopped without crashing the service.
            Thread.sleep(1000)

            instrumentation.runOnMainSync {
                // Must not be actively playing corrupt stream
                assertFalse("Corrupt stream must not be playing", controller.isPlaying)
            }

            // Unload cleanly recovers session
            lateinit var unload: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {
                unload = controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
            }
            assertEquals(SessionResult.RESULT_SUCCESS, unload.get(20, TimeUnit.SECONDS).resultCode)
            instrumentation.runOnMainSync {
                assertNull(controller.currentMediaItem)
            }
        } finally {
            runBlocking {
                app.library.books().delete(bookId)
                app.library.library().deleteAsset(assetId)
            }
            if (corruptFile.exists()) corruptFile.delete()
            instrumentation.runOnMainSync { controller.release() }
        }
    }
}
