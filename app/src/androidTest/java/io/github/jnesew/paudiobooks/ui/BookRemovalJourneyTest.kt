package io.github.jnesew.paudiobooks.ui

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
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
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookTrack
import io.github.jnesew.paudiobooks.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
@RunWith(AndroidJUnit4::class)
class BookRemovalJourneyTest {
    @get:org.junit.Rule
    val foregroundPlayback = io.github.jnesew.paudiobooks.playback.ForegroundPlaybackRule()


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
            var isIdleOrNoMedia = false
            instrumentation.runOnMainSync {
                if (controller.isConnected) {
                    isIdleOrNoMedia = controller.currentMediaItem == null && controller.playbackState == Player.STATE_IDLE
                }
            }
            if (!isIdleOrNoMedia) {
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
                    val result = try {
                        unloadFuture!!.get(10, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        null
                    }
                    if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                        throw IllegalStateException("Teardown unload failed or timed out; refusing to delete possible in-flight assets")
                    }
                }
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
                file.delete()
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

    private fun waitForCondition(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (SystemClock.elapsedRealtime() < deadline) {
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return
            Thread.sleep(50)
        }
        var finalCheck = false
        instrumentation.runOnMainSync { finalCheck = condition() }
        assertTrue("Condition not met within ${timeoutMs}ms", finalCheck)
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

    private fun createSyntheticBook(title: String = "Synthetic Book"): Triple<String, String, String> {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val media = createSyntheticWavFixture(assetId)

        runBlocking {
            createdBookIds.add(bookId)
            createdAssetIds.add(assetId)
            app.library.books().insert(Book(bookId, title))
            app.library.library().insertAssets(
                listOf(AudioAsset(assetId, "media/$assetId", media.length(), "sha-$assetId", 10_000L))
            )
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }
        return Triple(bookId, assetId, trackId)
    }

    @Test
    fun removingUnrelatedBookDoesNotInterruptPlayingBook() {
        val (bookAId, _, trackAId) = createSyntheticBook("Book A Playing")
        val (bookBId, _, _) = createSyntheticBook("Book B Unrelated")

        val controller = connectController()
        val loadResult = loadBook(controller, bookAId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)

        waitForCondition { controller.playbackState == Player.STATE_READY }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        setSpeed(controller, 1.5f)

        instrumentation.runOnMainSync {
            controller.play()
        }

        waitForCondition { controller.isPlaying && controller.currentPosition > 200L }

        // Track events during the removal window to ensure no interruption occurs
        val sawIsPlayingFalse = AtomicBoolean(false)
        val sawStateIdle = AtomicBoolean(false)
        val transitions = CopyOnWriteArrayList<String>()

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) sawIsPlayingFalse.set(true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) sawStateIdle.set(true)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions.add("transition-to-${mediaItem?.mediaId}-reason-$reason")
            }
        }

        var positionBefore = 0L
        instrumentation.runOnMainSync {
            controller.addListener(listener)
            positionBefore = controller.currentPosition
        }

        // Execute the exact production removal orchestration used by RootApp
        var removalSucceeded = false
        var reportedError: Int? = null
        val handled = runBlocking(Dispatchers.Main) {
            executeBookRemoval(
                bookId = bookBId,
                activeBookId = bookAId,
                controller = controller,
                removeBook = { id -> withContext(Dispatchers.IO) { app.importer.removeBook(id) } },
                showError = { reportedError = it },
                onSuccess = { removalSucceeded = true }
            )
        }
        assertTrue("executeBookRemoval must return true", handled)
        assertTrue("onSuccess must be called", removalSucceeded)
        assertNull("showError must not be called", reportedError)

        // Observe playback for 1 second during and after removal
        Thread.sleep(1000)

        instrumentation.runOnMainSync {
            controller.removeListener(listener)
            // Assert that Book A continued playing uninterrupted
            assertTrue("Book A must remain actively playing", controller.isPlaying)
            assertTrue("Book A playbackState must remain STATE_READY", controller.playbackState == Player.STATE_READY)
            assertEquals("Book A track must remain active", trackAId, controller.currentMediaItem?.mediaId)
            assertEquals(1.5f, controller.playbackParameters.speed, 0.01f)
            assertTrue(
                "Book A position must have advanced continuously (before=$positionBefore, after=${controller.currentPosition})",
                controller.currentPosition > positionBefore + 500L
            )
            assertFalse("isPlaying must never have transitioned to false during unrelated removal", sawIsPlayingFalse.get())
            assertFalse("playbackState must never have transitioned to IDLE during unrelated removal", sawStateIdle.get())
            assertTrue("No unexpected media item transitions should occur: $transitions", transitions.isEmpty())
        }

        // Verify Book B was actually deleted from database
        runBlocking {
            assertNull("Book B must be deleted from books table", app.library.books().get(bookBId))
            assertTrue("Book B tracks must be deleted", app.library.library().tracks(bookBId).isEmpty())
        }
    }

    @Test
    fun removingActiveBookWithoutControllerFailsSafelyAndPreservesBook() {
        val (bookAId, _, _) = createSyntheticBook("Book A Active No Controller")

        // Removing active book when controller is null must fail safely and not delete
        var removalSucceeded = false
        var reportedError: Int? = null
        val handled = runBlocking(Dispatchers.Main) {
            executeBookRemoval(
                bookId = bookAId,
                activeBookId = bookAId,
                controller = null,
                removeBook = { id -> withContext(Dispatchers.IO) { app.importer.removeBook(id) } },
                showError = { reportedError = it },
                onSuccess = { removalSucceeded = true }
            )
        }
        assertFalse("Removal of active book without controller must return false", handled)
        assertFalse("onSuccess must not be called when drain is impossible", removalSucceeded)
        assertEquals("showError must report progress_save_failed", R.string.progress_save_failed, reportedError)

        // Verify Book A was preserved in the database
        runBlocking {
            assertNotNull("Book A must remain in database", app.library.books().get(bookAId))
        }
    }

    @Test
    fun removingActiveBookWithControllerDrainsAndDeletes() {
        val (bookAId, _, _) = createSyntheticBook("Book A To Delete")

        val controller = connectController()
        val loadResult = loadBook(controller, bookAId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)

        waitForCondition { controller.playbackState == Player.STATE_READY }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            controller.play()
        }
        waitForCondition { controller.isPlaying }

        var removalSucceeded = false
        var reportedError: Int? = null
        val handled = runBlocking(Dispatchers.Main) {
            executeBookRemoval(
                bookId = bookAId,
                activeBookId = bookAId,
                controller = controller,
                removeBook = { id -> withContext(Dispatchers.IO) { app.importer.removeBook(id) } },
                showError = { reportedError = it },
                onSuccess = { removalSucceeded = true }
            )
        }
        assertTrue("Removal of active book with controller must succeed", handled)
        assertTrue("onSuccess must be called", removalSucceeded)
        assertNull("showError must not be called", reportedError)

        waitForCondition { !controller.isPlaying && controller.currentMediaItem == null }

        runBlocking {
            assertNull("Book A must be deleted", app.library.books().get(bookAId))
        }
    }

    @Test
    fun removingUnrelatedBookWhilePausedLeavesSessionPausedWithLoadedMedia() {
        val (bookAId, _, trackAId) = createSyntheticBook("Book A Paused")
        val (bookBId, _, _) = createSyntheticBook("Book B Unrelated")

        val controller = connectController()
        val loadResult = loadBook(controller, bookAId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)

        waitForCondition { controller.playbackState == Player.STATE_READY }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            controller.seekTo(3000L)
        }
        setSpeed(controller, 1.25f)

        waitForCondition { !controller.playWhenReady && controller.currentPosition >= 2900L }

        // Remove unrelated Book B using production removal orchestration
        var removalSucceeded = false
        var reportedError: Int? = null
        val handled = runBlocking(Dispatchers.Main) {
            executeBookRemoval(
                bookId = bookBId,
                activeBookId = bookAId,
                controller = controller,
                removeBook = { id -> withContext(Dispatchers.IO) { app.importer.removeBook(id) } },
                showError = { reportedError = it },
                onSuccess = { removalSucceeded = true }
            )
        }
        assertTrue("Book B removal must succeed", handled)
        assertTrue(removalSucceeded)
        assertNull(reportedError)

        instrumentation.runOnMainSync {
            assertFalse("Book A must remain paused", controller.playWhenReady)
            assertFalse("Book A must not be playing", controller.isPlaying)
            assertEquals(trackAId, controller.currentMediaItem?.mediaId)
            assertEquals(1.25f, controller.playbackParameters.speed, 0.01f)
            assertTrue(controller.currentPosition >= 2900L)
        }

        runBlocking {
            assertNull("Book B must be deleted", app.library.books().get(bookBId))
            assertNotNull("Book A must remain in database", app.library.books().get(bookAId))
        }
    }

    @Test
    fun sharedAssetSurvivesRemovalOfOneBook() {
        val assetId = UUID.randomUUID().toString()
        val media = createSyntheticWavFixture(assetId)
        createdAssetIds.add(assetId)

        val bookAId = UUID.randomUUID().toString()
        val trackAId = UUID.randomUUID().toString()
        val bookBId = UUID.randomUUID().toString()
        val trackBId = UUID.randomUUID().toString()

        runBlocking {
            createdBookIds.add(bookAId)
            createdBookIds.add(bookBId)
            app.library.books().insert(Book(bookAId, "Book A Shared"))
            app.library.books().insert(Book(bookBId, "Book B Shared"))
            app.library.library().insertAssets(
                listOf(AudioAsset(assetId, "media/$assetId", media.length(), "sha-$assetId", 10_000L))
            )
            app.library.library().insertTracks(listOf(BookTrack(trackAId, bookAId, assetId, 0)))
            app.library.library().insertTracks(listOf(BookTrack(trackBId, bookBId, assetId, 0)))
        }

        // Start playing Book A
        val controller = connectController()
        val loadResult = loadBook(controller, bookAId)
        assertEquals(SessionResult.RESULT_SUCCESS, loadResult.resultCode)

        waitForCondition { controller.playbackState == Player.STATE_READY }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            controller.play()
        }
        waitForCondition { controller.isPlaying }

        // Remove Book B via production removal orchestration
        var removalSucceeded = false
        var reportedError: Int? = null
        val handled = runBlocking(Dispatchers.Main) {
            executeBookRemoval(
                bookId = bookBId,
                activeBookId = bookAId,
                controller = controller,
                removeBook = { id -> withContext(Dispatchers.IO) { app.importer.removeBook(id) } },
                showError = { reportedError = it },
                onSuccess = { removalSucceeded = true }
            )
        }
        assertTrue("Book B removal must succeed", handled)
        assertTrue(removalSucceeded)
        assertNull(reportedError)

        // Asset file on disk must STILL exist because Book A still references it
        assertTrue("Shared media file must survive Book B deletion", media.exists())

        // Book A continues playing from the shared asset without error
        Thread.sleep(500)
        instrumentation.runOnMainSync {
            assertTrue("Book A must continue playing from shared asset", controller.isPlaying)
            assertEquals(Player.STATE_READY, controller.playbackState)
        }
    }

    @Test
    fun removalFailingInStorageReportsErrorAndDoesNotInvokeSuccess() {
        val (bookAId, _, _) = createSyntheticBook("Book A Failing Storage")

        var removalSucceeded = false
        var reportedError: Int? = null
        val handled = runBlocking(Dispatchers.Main) {
            executeBookRemoval(
                bookId = bookAId,
                activeBookId = null,
                controller = null,
                removeBook = { _ -> false },
                showError = { reportedError = it },
                onSuccess = { removalSucceeded = true }
            )
        }
        assertFalse("Failed storage removal must return false", handled)
        assertFalse("onSuccess must not be called when storage removal fails", removalSucceeded)
        assertEquals("showError must report removal_failed", R.string.removal_failed, reportedError)

        // Verify book was preserved
        runBlocking {
            assertNotNull("Book must remain in database when removal fails", app.library.books().get(bookAId))
        }
    }

    @Test
    fun removalCancelledPropagatesCancellationException() {
        val (bookAId, _, _) = createSyntheticBook("Book A Cancelled")

        var removalSucceeded = false
        var reportedError: Int? = null
        var caughtCancellation = false

        try {
            runBlocking(Dispatchers.Main) {
                executeBookRemoval(
                    bookId = bookAId,
                    activeBookId = null,
                    controller = null,
                    removeBook = { _ -> throw CancellationException("test cancellation") },
                    showError = { reportedError = it },
                    onSuccess = { removalSucceeded = true }
                )
            }
        } catch (e: CancellationException) {
            caughtCancellation = true
        }

        assertTrue("CancellationException must be propagated", caughtCancellation)
        assertFalse("onSuccess must not be called on cancellation", removalSucceeded)
        assertNull("showError must not report removal_failed on cancellation", reportedError)

        // Verify book was preserved
        runBlocking {
            assertNotNull("Book must remain in database when removal is cancelled", app.library.books().get(bookAId))
        }
    }
}
