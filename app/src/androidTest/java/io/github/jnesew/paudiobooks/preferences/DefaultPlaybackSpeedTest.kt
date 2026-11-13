package io.github.jnesew.paudiobooks.preferences

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookTrack
import io.github.jnesew.paudiobooks.library.ReadingState
import io.github.jnesew.paudiobooks.playback.PlaybackService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DefaultPlaybackSpeedTest {
    @get:org.junit.Rule
    val foregroundPlayback = io.github.jnesew.paudiobooks.playback.ForegroundPlaybackRule()


    private lateinit var app: AudiobookApplication
    private var originalDefaultSpeed: Float = PlaybackSpeed.DEFAULT_SPEED
    private val createdMediaFiles = mutableListOf<File>()
    private val createdBookIds = mutableListOf<String>()
    private val createdAssetIds = mutableListOf<String>()
    private var activeController: MediaController? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        runBlocking {
            originalDefaultSpeed = app.settings.playback.first().defaultSpeed
        }
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activeController?.let { controller ->
            var unloadFuture: ListenableFuture<SessionResult>? = null
            instrumentation.runOnMainSync {
                if (controller.isConnected) {
                    unloadFuture = controller.sendCustomCommand(
                        SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
            }
            unloadFuture?.get(20, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                controller.release()
            }
            activeController = null
        }

        runBlocking {
            for (bookId in createdBookIds) {
                app.library.books().delete(bookId)
            }
            for (assetId in createdAssetIds) {
                app.library.library().deleteAsset(assetId)
            }
            app.settings.setDefaultSpeed(originalDefaultSpeed)
            val restored = app.settings.playback.first().defaultSpeed
            assertEquals("Original default speed must be restored", originalDefaultSpeed, restored, 0.001f)
        }

        for (file in createdMediaFiles) {
            if (file.exists()) {
                assertTrue("Failed to delete synthetic fixture file: $file", file.delete())
            }
        }
        createdMediaFiles.clear()
        createdBookIds.clear()
        createdAssetIds.clear()
    }

    @Test
    fun preferencePersistenceAndValidation() = runBlocking {
        // Reset to initial default
        app.settings.setDefaultSpeed(PlaybackSpeed.DEFAULT_SPEED)
        val initialRepo = SettingsRepository(app)
        assertEquals(1.00f, initialRepo.playback.first().defaultSpeed, 0.001f)

        // Set valid preference and verify from a newly constructed repository instance
        app.settings.setDefaultSpeed(1.50f)
        val repoAfter150 = SettingsRepository(app)
        assertEquals(1.50f, repoAfter150.playback.first().defaultSpeed, 0.001f)

        // Lower boundary: 0.50x
        app.settings.setDefaultSpeed(0.50f)
        val repoAfter050 = SettingsRepository(app)
        assertEquals(0.50f, repoAfter050.playback.first().defaultSpeed, 0.001f)

        // Upper boundary: 3.00x
        app.settings.setDefaultSpeed(3.00f)
        val repoAfter300 = SettingsRepository(app)
        assertEquals(3.00f, repoAfter300.playback.first().defaultSpeed, 0.001f)

        // Reject below range
        try {
            app.settings.setDefaultSpeed(0.40f)
            fail("Expected IllegalArgumentException for out-of-range speed 0.40")
        } catch (_: IllegalArgumentException) { }

        // Reject above range
        try {
            app.settings.setDefaultSpeed(3.50f)
            fail("Expected IllegalArgumentException for out-of-range speed 3.50")
        } catch (_: IllegalArgumentException) { }

        // Reject NaN
        try {
            app.settings.setDefaultSpeed(Float.NaN)
            fail("Expected IllegalArgumentException for NaN")
        } catch (_: IllegalArgumentException) { }

        // Reject Infinity
        try {
            app.settings.setDefaultSpeed(Float.POSITIVE_INFINITY)
            fail("Expected IllegalArgumentException for POSITIVE_INFINITY")
        } catch (_: IllegalArgumentException) { }

        // Stays unchanged at last valid value (3.00f) when checked from a new repo instance
        val repoFinal = SettingsRepository(app)
        assertEquals(3.00f, repoFinal.playback.first().defaultSpeed, 0.001f)
    }

    @Test
    fun freshBookUsesPersistedDefaultSpeed() {
        runBlocking {
            app.settings.setDefaultSpeed(1.75f)
        }

        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val media = createSyntheticWavFixture(assetId)

        runBlocking {
            createdBookIds.add(bookId)
            createdAssetIds.add(assetId)
            app.library.books().insert(Book(bookId, "Synthetic Fresh Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", media.length(), "sha-$assetId", 10_000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val controller = connectController()
        val loadResult = loadBook(controller, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)

        waitForReady(controller)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertFalse("Loading a fresh book must remain paused", controller.playWhenReady)
            assertEquals("Fresh book must adopt persisted default speed 1.75x", 1.75f, controller.playbackParameters.speed, 0.01f)
        }
    }

    @Test
    fun rememberedSpeedTakesPrecedenceOverDefault() {
        runBlocking {
            app.settings.setDefaultSpeed(1.50f)
        }

        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val media = createSyntheticWavFixture(assetId)

        runBlocking {
            createdBookIds.add(bookId)
            createdAssetIds.add(assetId)
            app.library.books().insert(Book(bookId, "Synthetic Saved Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", media.length(), "sha-$assetId", 10_000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
            app.library.library().saveProgress(ReadingState(bookId, trackId, 2_000L, 1.25f, false, System.currentTimeMillis()))
        }

        val controller = connectController()
        val loadResult = loadBook(controller, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)

        waitForReady(controller)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertFalse("Restoring must remain paused", controller.playWhenReady)
            assertEquals("Valid remembered speed 1.25x must take precedence over default speed 1.50x",
                1.25f, controller.playbackParameters.speed, 0.01f)
        }

        // Change speed dynamically for this book
        lateinit var setSpeedResult: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            setSpeedResult = controller.sendCustomCommand(SessionCommand(PlaybackService.SET_SPEED, Bundle.EMPTY),
                Bundle().apply { putFloat(PlaybackService.SPEED, 2.25f) })
        }
        assertEquals(SessionResult.RESULT_SUCCESS, setSpeedResult.get(20, TimeUnit.SECONDS).resultCode)

        // Save progress and unload
        lateinit var pauseResult: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            pauseResult = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
        }
        assertEquals(SessionResult.RESULT_SUCCESS, pauseResult.get(20, TimeUnit.SECONDS).resultCode)

        lateinit var unloadResult: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            unloadResult = controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
        }
        assertEquals(SessionResult.RESULT_SUCCESS, unloadResult.get(20, TimeUnit.SECONDS).resultCode)

        // Reload book: the newly saved speed (2.25x) must be restored, still taking precedence over default (1.50x)
        val reloadResult = loadBook(controller, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, reloadResult.resultCode)

        waitForReady(controller)

        instrumentation.runOnMainSync {
            assertEquals("Updated remembered speed 2.25x must be restored",
                2.25f, controller.playbackParameters.speed, 0.01f)
        }
    }

    @Test
    fun activePlaybackSpeedRemainsStableWhenDefaultChanges() {
        runBlocking {
            app.settings.setDefaultSpeed(1.50f)
        }

        val bookIdA = UUID.randomUUID().toString()
        val assetIdA = UUID.randomUUID().toString()
        val trackIdA = UUID.randomUUID().toString()
        val mediaA = createSyntheticWavFixture(assetIdA)

        val bookIdB = UUID.randomUUID().toString()
        val assetIdB = UUID.randomUUID().toString()
        val trackIdB = UUID.randomUUID().toString()
        val mediaB = createSyntheticWavFixture(assetIdB)

        runBlocking {
            createdBookIds.add(bookIdA)
            createdAssetIds.add(assetIdA)
            createdBookIds.add(bookIdB)
            createdAssetIds.add(assetIdB)
            app.library.books().insert(Book(bookIdA, "Book A"))
            app.library.library().insertAssets(listOf(AudioAsset(assetIdA, "media/$assetIdA", mediaA.length(), "sha-$assetIdA", 10_000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackIdA, bookIdA, assetIdA, 0)))

            app.library.books().insert(Book(bookIdB, "Book B"))
            app.library.library().insertAssets(listOf(AudioAsset(assetIdB, "media/$assetIdB", mediaB.length(), "sha-$assetIdB", 10_000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackIdB, bookIdB, assetIdB, 0)))
        }

        val controller = connectController()
        val loadResultA = loadBook(controller, bookIdA)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResultA.resultCode)

        waitForReady(controller)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertEquals(1.50f, controller.playbackParameters.speed, 0.01f)
            assertFalse(controller.playWhenReady)
            controller.play()
        }

        // Wait until playback is actively playing
        var isPlaying = false
        repeat(50) {
            instrumentation.runOnMainSync { isPlaying = controller.isPlaying }
            if (isPlaying) return@repeat
            Thread.sleep(100)
        }
        assertTrue("Controller should start playing", isPlaying)

        // Capture initial position while playing
        var initialPos = 0L
        instrumentation.runOnMainSync { initialPos = controller.currentPosition }

        // Change the default speed setting while Book A is actively PLAYING
        runBlocking {
            app.settings.setDefaultSpeed(2.50f)
        }

        // Allow playback to proceed
        Thread.sleep(400)

        // Active speed and play state must remain unaffected, and position advances normally
        instrumentation.runOnMainSync {
            assertEquals("Active playback speed must NOT change when default setting is updated",
                1.50f, controller.playbackParameters.speed, 0.01f)
            assertTrue("Playing state must remain playing", controller.isPlaying)
            assertTrue("Position must advance normally while playing", controller.currentPosition > initialPos)
        }

        // Pause and save Book A
        lateinit var pauseResult: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            pauseResult = controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
        }
        assertEquals(SessionResult.RESULT_SUCCESS, pauseResult.get(20, TimeUnit.SECONDS).resultCode)

        // Verify Book A's saved progress retains speed 1.50f
        val savedA = runBlocking { app.library.library().progress(bookIdA) }
        assertNotNull("Progress for Book A must be saved", savedA)
        assertEquals("Saved progress must retain active book speed 1.50x", 1.50f, savedA!!.speed, 0.01f)

        // Unload Book A
        lateinit var unloadResult: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            unloadResult = controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
        }
        assertEquals(SessionResult.RESULT_SUCCESS, unloadResult.get(20, TimeUnit.SECONDS).resultCode)

        // Load Book B (fresh book): it must now adopt the NEW default speed (2.50x)
        val loadResultB = loadBook(controller, bookIdB)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResultB.resultCode)

        waitForReady(controller)

        instrumentation.runOnMainSync {
            assertFalse("Fresh book load must remain paused", controller.playWhenReady)
            assertEquals("Subsequent fresh book must adopt the updated default speed 2.50x",
                2.50f, controller.playbackParameters.speed, 0.01f)
        }
    }

    private fun connectController(): MediaController {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        activeController = controller
        return controller
    }

    private fun loadBook(controller: MediaController, bookId: String): SessionResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var load: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            load = controller.sendCustomCommand(SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) })
        }
        return load.get(20, TimeUnit.SECONDS)
    }

    private fun waitForReady(controller: MediaController) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var ready = false
        repeat(50) {
            instrumentation.runOnMainSync { ready = controller.playbackState == Player.STATE_READY }
            if (ready) return
            Thread.sleep(100)
        }
        assertTrue("Player did not reach STATE_READY", ready)
    }

    private fun createSyntheticWavFixture(assetId: String): File {
        val media = File(app.noBackupFilesDir, "audiobooks/media/$assetId")
        media.parentFile?.mkdirs()
        createdMediaFiles.add(media)
        // 10 seconds of silent 8 kHz mono PCM (160,000 bytes)
        val dataSize = 160_000
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataSize)
        media.writeBytes(bytes.array())
        return media
    }
}
