package io.github.jnesew.paudiobooks.lifecycle

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
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
import io.github.jnesew.paudiobooks.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class ActivityRecreationTest {

    private lateinit var app: AudiobookApplication
    private val createdMediaFiles = mutableListOf<File>()
    private val createdBookIds = mutableListOf<String>()
    private val createdAssetIds = mutableListOf<String>()
    private val activeControllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (controller in activeControllers) {
            var unloadFuture: ListenableFuture<SessionResult>? = null
            instrumentation.runOnMainSync {
                if (controller.isConnected) {
                    unloadFuture = controller.sendCustomCommand(
                        SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
            }
            if (unloadFuture != null) {
                // Await completion off main thread before release/deletion; do not swallow cleanup errors
                val result = unloadFuture!!.get(10, TimeUnit.SECONDS)
                assertEquals("UNLOAD_AND_SAVE in teardown must succeed", SessionResult.RESULT_SUCCESS, result.resultCode)
            }
            instrumentation.runOnMainSync {
                controller.release()
            }
        }
        activeControllers.clear()

        runBlocking {
            for (bookId in createdBookIds) {
                app.library.books().delete(bookId)
            }
            for (assetId in createdAssetIds) {
                app.library.library().deleteAsset(assetId)
            }
        }

        for (file in createdMediaFiles) {
            if (file.exists()) {
                assertTrue("Media fixture file delete must succeed: ${file.name}", file.delete())
            }
        }
        createdMediaFiles.clear()
        createdBookIds.clear()
        createdAssetIds.clear()
    }

    private fun connectController(): MediaController {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(
                app,
                SessionToken(app, ComponentName(app, PlaybackService::class.java))
            ).buildAsync()
        }
        val controller = future.get(20, TimeUnit.SECONDS)
        activeControllers.add(controller)
        return controller
    }

    private fun loadBook(controller: MediaController, bookId: String): SessionResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var load: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            load = controller.sendCustomCommand(
                SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }
            )
        }
        return load.get(20, TimeUnit.SECONDS)
    }

    private fun waitForCondition(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (System.currentTimeMillis() < deadline) {
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return
            Thread.sleep(50)
        }
        var finalCheck = false
        instrumentation.runOnMainSync { finalCheck = condition() }
        assertTrue("Condition not met within ${timeoutMs}ms", finalCheck)
    }

    private fun waitForReady(controller: MediaController) {
        waitForCondition(5000L) { controller.playbackState == Player.STATE_READY }
    }

    private fun setSpeed(controller: MediaController, speed: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var future: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            future = controller.sendCustomCommand(
                SessionCommand(PlaybackService.SET_SPEED, Bundle.EMPTY),
                Bundle().apply { putFloat(PlaybackService.SPEED, speed) }
            )
        }
        val result = future.get(20, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
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

    private fun createSyntheticBook(): Triple<String, String, String> {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val media = createSyntheticWavFixture(assetId)

        runBlocking {
            createdBookIds.add(bookId)
            createdAssetIds.add(assetId)
            app.library.books().insert(Book(bookId, "Synthetic Recreation Book"))
            app.library.library().insertAssets(
                listOf(AudioAsset(assetId, "media/$assetId", media.length(), "sha-$assetId", 10_000L))
            )
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }
        return Triple(bookId, assetId, trackId)
    }

    @Test
    fun releasingControllerDoesNotStopSession() {
        val (bookId, _, trackId) = createSyntheticBook()
        val controller1 = connectController()
        val loadResult = loadBook(controller1, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)
        waitForReady(controller1)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Seek to 2500 ms and set speed to 1.25x while paused
        instrumentation.runOnMainSync {
            controller1.seekTo(2500L)
        }
        setSpeed(controller1, 1.25f)
        waitForCondition {
            !controller1.playWhenReady &&
                kotlin.math.abs(controller1.playbackParameters.speed - 1.25f) < 0.01f &&
                controller1.currentPosition >= 2400L
        }

        instrumentation.runOnMainSync {
            assertFalse(controller1.playWhenReady)
            assertEquals(1.25f, controller1.playbackParameters.speed, 0.01f)
            assertEquals(trackId, controller1.currentMediaItem?.mediaId)
        }

        // Release the first controller (simulating unbind on UI disposal)
        instrumentation.runOnMainSync {
            controller1.release()
        }
        activeControllers.remove(controller1)

        // Intentional observation window while uncontrollered
        Thread.sleep(500)

        // Connect a second controller (simulating new Activity connecting)
        val controller2 = connectController()
        waitForCondition { controller2.currentMediaItem != null }
        instrumentation.runOnMainSync {
            assertNotNull("Session must not be unloaded after controller release", controller2.currentMediaItem)
            assertEquals("Loaded track must be preserved", trackId, controller2.currentMediaItem?.mediaId)
            assertEquals("Playback speed must be preserved", 1.25f, controller2.playbackParameters.speed, 0.01f)
            assertFalse("Playback must remain paused", controller2.playWhenReady)
            assertTrue("Position must be retained", controller2.currentPosition >= 2400L)
        }
    }

    @Test
    fun pausedBookSurvivesActivityRecreation() {
        val (bookId, _, trackId) = createSyntheticBook()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val controller = connectController()
            val loadResult = loadBook(controller, bookId)
            assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)
            waitForReady(controller)

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                controller.seekTo(3000L)
            }
            setSpeed(controller, 1.50f)
            waitForCondition {
                !controller.playWhenReady &&
                    kotlin.math.abs(controller.playbackParameters.speed - 1.50f) < 0.01f &&
                    controller.currentPosition >= 2900L
            }

            instrumentation.runOnMainSync {
                assertFalse("Must remain paused", controller.playWhenReady)
                assertEquals(1.50f, controller.playbackParameters.speed, 0.01f)
                assertEquals(trackId, controller.currentMediaItem?.mediaId)
            }

            // Release test setup controller so the Activity is the only controller owner
            instrumentation.runOnMainSync {
                controller.release()
            }
            activeControllers.remove(controller)

            // Recreate the Activity (triggers onStop, onDestroy, composition disposal, onCreate)
            scenario.recreate()

            // Verify via connected controller that session is intact
            val reconnected = connectController()
            waitForCondition { reconnected.currentMediaItem != null }
            instrumentation.runOnMainSync {
                assertNotNull("Loaded book must not be cleared across recreation", reconnected.currentMediaItem)
                assertEquals(trackId, reconnected.currentMediaItem?.mediaId)
                assertEquals("Speed must not change across recreation", 1.50f, reconnected.playbackParameters.speed, 0.01f)
                assertFalse("Must not resume paused playback", reconnected.playWhenReady)
                assertTrue("Position must not seek unexpectedly", reconnected.currentPosition >= 2900L)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun playingAudioContinuesAcrossRecreation() {
        val (bookId, _, trackId) = createSyntheticBook()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val controller = connectController()
            val loadResult = loadBook(controller, bookId)
            assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)
            waitForReady(controller)

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                controller.play()
            }
            waitForCondition { controller.isPlaying }

            // Recreate activity while playing
            scenario.recreate()

            val reconnected = connectController()
            waitForCondition { reconnected.isPlaying && reconnected.currentMediaItem != null }
            instrumentation.runOnMainSync {
                assertEquals(trackId, reconnected.currentMediaItem?.mediaId)
                assertTrue("Playback must continue playing across recreation", reconnected.isPlaying)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun durablePausedRestartRestoresPosition() {
        val (bookId, _, trackId) = createSyntheticBook()
        runBlocking {
            app.library.library().saveProgress(
                ReadingState(bookId, trackId, 4500L, 1.0f, false, System.currentTimeMillis())
            )
        }

        val controller = connectController()
        val loadResult = loadBook(controller, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)
        waitForReady(controller)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertFalse("Restored book must be paused", controller.playWhenReady)
            assertEquals("Restored position must match durable saved progress", 4500L, controller.currentPosition)
        }
    }

    @Test
    fun explicitUnloadClearsSession() {
        val (bookId, _, trackId) = createSyntheticBook()
        val controller = connectController()
        val loadResult = loadBook(controller, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)
        waitForReady(controller)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            controller.seekTo(3500L)
        }
        waitForCondition { controller.currentPosition >= 3400L }

        lateinit var unloadFuture: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            unloadFuture = controller.sendCustomCommand(
                SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
        val unloadResult = unloadFuture.get(20, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, unloadResult.resultCode)

        instrumentation.runOnMainSync {
            assertNull("Explicit unload must clear media item", controller.currentMediaItem)
        }

        val progress = runBlocking { app.library.library().progress(bookId) }
        assertNotNull("Progress must be written before unload future completes", progress)
        assertEquals(trackId, progress?.trackId)
        assertTrue("Saved position must match seek target", (progress?.offsetMs ?: 0L) >= 3400L)
    }

    @Test
    fun unsupportedCommandReturnsErrorAndLeavesSessionIntact() {
        val (bookId, _, trackId) = createSyntheticBook()
        val controller = connectController()
        val loadResult = loadBook(controller, bookId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)
        waitForReady(controller)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            controller.seekTo(2000L)
        }
        waitForCondition { controller.currentPosition >= 1900L }

        lateinit var cmdFuture: ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            cmdFuture = controller.sendCustomCommand(
                SessionCommand("io.github.jnesew.paudiobooks.UNSUPPORTED_COMMAND", Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
        val result = cmdFuture.get(10, TimeUnit.SECONDS)
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)

        instrumentation.runOnMainSync {
            assertFalse("Playback must remain paused", controller.playWhenReady)
            assertEquals(trackId, controller.currentMediaItem?.mediaId)
            assertTrue("Position must remain intact", controller.currentPosition >= 1900L)
        }
    }
}
