package io.github.jnesew.paudiobooks.metadata

import android.content.ComponentName
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.os.SystemClock
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
import io.github.jnesew.paudiobooks.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Validates acceptance criterion E02: Actual audio decoding and seek behavior across the format matrix
 * through the real [PlaybackService] and [MediaController].
 *
 * Distinguishes media-controller reported position/timeline presentation timestamps from audible/decoded
 * acoustic output. Automated harness verifies the Media3 decoder state machine, timeline duration,
 * decode progression, seek accuracy, and durable reload persistence. Audible acoustic fidelity and physical
 * speaker capture are verified separately on physical stock hardware.
 *
 * Default mode uses isolated private audio entries. Explicit sourceMode=linked uses the real
 * linker, persisted read grants and provider descriptors with a 17-byte start offset and exact
 * range, with no private audio. Full picker/folder journeys are accepted separately.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FormatPlaybackTest {
    @get:org.junit.Rule
    val foregroundPlayback = io.github.jnesew.paudiobooks.playback.ForegroundPlaybackRule()
    private val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var controller: MediaController
    private val linkedSources get() = InstrumentationRegistry.getArguments().getString("sourceMode") == "linked"
    private val fixtureProvider = Uri.parse("content://io.github.jnesew.paudiobooks.test.linkedfixture")
    private data class FixtureBook(val book: String, val asset: String, val track: String,
        val media: File, val seed: String? = null)

    private fun createFixtureBook(name: String): FixtureBook {
        val book = UUID.randomUUID().toString()
        val source = fixture(name)
        if (!linkedSources) {
            val asset = UUID.randomUUID().toString(); val track = UUID.randomUUID().toString()
            val media = File(app.noBackupFilesDir,"audiobooks/media/$asset")
            media.parentFile?.mkdirs(); source.copyTo(media)
            runBlocking {
                app.library.books().insert(Book(book,"Format Test - $name"))
                app.library.library().insertAssets(listOf(AudioAsset(asset,"media/$asset",media.length(),"sha-$name",12000L)))
                app.library.library().insertTracks(listOf(BookTrack(track,book,asset,0)))
            }
            return FixtureBook(book,asset,track,media)
        }
        val seed = UUID.randomUUID().toString()
        val uri = Uri.withAppendedPath(fixtureProvider,"format-$seed")
        try {
            ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                requireNotNull(app.contentResolver.call(fixtureProvider,"seed-format",seed,Bundle().apply {
                    putString("name",name); putParcelable("descriptor",descriptor)
                }))
            }
            return runBlocking {
                val selected = io.github.jnesew.paudiobooks.importing.DocumentInventory(app.contentResolver).describe(uri)
                val preview = app.linker.prepare(listOf(selected),listOf(uri))
                app.linker.commit(preview,preview.tracks,"Linked Format Test - $name",book)
                val track = app.library.library().tracks(book).single()
                val asset = requireNotNull(app.library.library().asset(track.assetId))
                val media = File(app.noBackupFilesDir,"audiobooks/media/${asset.id}")
                assertEquals("",asset.relativePath); assertEquals("",asset.sha256); assertFalse(asset.identityVerified)
                assertFalse("Linking must not create private audio",media.exists())
                assertEquals(uri.toString(),app.library.sources().source(asset.id)?.documentUri)
                assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
                FixtureBook(book,asset.id,track.id,media,seed)
            }
        } catch (failure: Throwable) {
            runBlocking {
                val tracks = app.library.library().tracks(book)
                app.library.books().delete(book)
                for (track in tracks) app.library.library().deleteAsset(track.assetId)
                app.sourceGrants.collectUnused()
            }
            app.contentResolver.call(fixtureProvider,"discard-format",seed,null)
            throw failure
        }
    }

    private fun removeFixtureBook(entry: FixtureBook) {
        runBlocking {
            app.library.books().delete(entry.book); app.library.library().deleteAsset(entry.asset)
            app.sourceGrants.collectUnused()
        }
        if (entry.seed != null) {
            assertTrue(runBlocking { io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess.awaitIdle() })
            val uri = Uri.withAppendedPath(fixtureProvider,"format-${entry.seed}")
            assertFalse("No private audio may appear during linked playback",entry.media.exists())
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (app.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                runBlocking { app.sourceGrants.collectUnused() }
                check(SystemClock.elapsedRealtime() < deadline) { "Finished test must release its own persisted read" }
                Thread.sleep(25)
            }
            app.contentResolver.call(fixtureProvider,"discard-format",entry.seed,null)
        } else if (entry.media.exists()) entry.media.delete()
    }

    private fun <T> runOnMain(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun fixture(name: String): File {
        val file = File(app.filesDir, "metadata-fixtures/$name")
        assertTrue("Missing required synthetic fixture: $name at ${file.absolutePath}", file.isFile)
        return file
    }

    @Before
    fun setUp() {
        val future: ListenableFuture<MediaController> = runOnMain {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        controller = future.get(20, TimeUnit.SECONDS)
        // Ensure starting in clean, unloaded state with awaited drain
        val unload: ListenableFuture<SessionResult> = runOnMain {
            controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
        }
        val result = unload.get(10, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun ensureUnloadedAndDrained() {
        if (!::controller.isInitialized) return
        val activeController = if (controller.isConnected) controller else {
            try {
                runOnMain {
                    MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
                }.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) { null }
        }
        if (activeController != null) {
            try {
                val unload: ListenableFuture<SessionResult> = runOnMain {
                    activeController.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
                }
                val result = unload.get(10, TimeUnit.SECONDS)
                assertEquals("Unload drain must succeed", SessionResult.RESULT_SUCCESS, result.resultCode)
            } finally {
                if (activeController !== controller) {
                    runOnMain { activeController.release() }
                }
            }
        }
    }

    @After
    fun tearDown() {
        try {
            ensureUnloadedAndDrained()
        } finally {
            if (::controller.isInitialized) {
                runOnMain { controller.release() }
            }
        }
    }

    /**
     * Executes playback, seek, decode-advance, pause-save, and durable reload verification:
     * 1. Creates isolated private records or links a real granted provider fixture.
     * 2. Checks the selected storage boundary before playback.
     * 3. Loads book via PlaybackService and asserts STATE_READY and valid timeline duration.
     * 4. Plays audio and asserts position monotonically advances beyond start.
     * 5. Seeks to target (6000 ms), awaits STATE_READY, then plays and verifies decode advance from seek baseline.
     * 6. Pauses audio, captures actual paused position, and asserts progress saved matches within 500 ms.
     * 7. Unloads session, reloads book with fresh controller, and verifies durable restored paused position matches saved offset.
     * 8. Always awaits unload and drain before deleting database records and media files.
     */
    private fun verifyFormatPlaybackAndSeek(fixtureName: String, maxSeekToleranceMs: Long) {
        val entry = createFixtureBook(fixtureName)
        val bookId = entry.book
        try {
            // 1. Load book via custom command
            val loadFuture: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }
                )
            }
            assertEquals("LOAD_BOOK failed for $fixtureName", SessionResult.RESULT_SUCCESS, loadFuture.get(20, TimeUnit.SECONDS).resultCode)

            // Bounded condition wait for STATE_READY
            var ready = false
            val startReadyWait = SystemClock.elapsedRealtime()
            while (!ready && SystemClock.elapsedRealtime() - startReadyWait < 10_000L) {
                ready = runOnMain { controller.playbackState == Player.STATE_READY }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Format $fixtureName did not reach STATE_READY within 10s", ready)

            // Verify duration
            val duration = runOnMain { controller.duration }
            assertTrue("Duration for $fixtureName must be ~12000ms (got $duration)", duration in 11_500L..12_500L)

            // 2. Initial playback advance
            runOnMain { controller.play() }
            var playing = false
            val playWait = SystemClock.elapsedRealtime()
            while (!playing && SystemClock.elapsedRealtime() - playWait < 5_000L) {
                playing = runOnMain { controller.isPlaying }
                if (!playing) Thread.sleep(50)
            }
            assertTrue("Format $fixtureName did not start playing within 5s", playing)

            var initialAdvanced = false
            val advanceWait = SystemClock.elapsedRealtime()
            while (!initialAdvanced && SystemClock.elapsedRealtime() - advanceWait < 5_000L) {
                initialAdvanced = runOnMain { controller.currentPosition > 300L }
                if (!initialAdvanced) Thread.sleep(50)
            }
            assertTrue("Position did not advance initially for format $fixtureName", initialAdvanced)

            // 3. Seek to target (6000 ms)
            val targetSeekMs = 6000L
            val seekFuture: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.SEEK_BOOK_POSITION, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, targetSeekMs) }
                )
            }
            assertEquals(SessionResult.RESULT_SUCCESS, seekFuture.get(20, TimeUnit.SECONDS).resultCode)

            // Require actual STATE_READY after seek
            var readyAfterSeek = false
            val seekReadyWait = SystemClock.elapsedRealtime()
            while (!readyAfterSeek && SystemClock.elapsedRealtime() - seekReadyWait < 5_000L) {
                readyAfterSeek = runOnMain { controller.playbackState == Player.STATE_READY }
                if (!readyAfterSeek) Thread.sleep(50)
            }
            assertTrue("Format $fixtureName did not reach STATE_READY after seek", readyAfterSeek)

            // Verify media-controller reported position matches target within tolerance
            val posAfterSeek = runOnMain { controller.currentPosition }
            val seekDiff = Math.abs(posAfterSeek - targetSeekMs)
            assertTrue(
                "Seek position for $fixtureName exceeds tolerance $maxSeekToleranceMs ms (target: $targetSeekMs, got: $posAfterSeek, diff: $seekDiff)",
                seekDiff <= maxSeekToleranceMs
            )

            // 4. Play and verify actual decode advance from the seek baseline with no error
            runOnMain { controller.play() }
            var advancedFromSeek = false
            val seekAdvanceWait = SystemClock.elapsedRealtime()
            val baseline = posAfterSeek
            while (!advancedFromSeek && SystemClock.elapsedRealtime() - seekAdvanceWait < 5_000L) {
                assertNull("Player encountered error during post-seek playback of $fixtureName", runOnMain { controller.playerError })
                val current = runOnMain { controller.currentPosition }
                if (current > baseline + 250L) {
                    advancedFromSeek = true
                }
                if (!advancedFromSeek) Thread.sleep(50)
            }
            assertTrue("Decode position did not advance beyond seek baseline for format $fixtureName", advancedFromSeek)

            // 5. Pause and capture actual paused position
            val pauseFuture: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(SessionCommand(PlaybackService.PAUSE_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
            }
            assertEquals(SessionResult.RESULT_SUCCESS, pauseFuture.get(20, TimeUnit.SECONDS).resultCode)

            var pausedFlagsSet = false
            val pauseWait = SystemClock.elapsedRealtime()
            while (!pausedFlagsSet && SystemClock.elapsedRealtime() - pauseWait < 5_000L) {
                pausedFlagsSet = runOnMain { !controller.isPlaying && !controller.playWhenReady }
                if (!pausedFlagsSet) Thread.sleep(50)
            }
            assertTrue("Controller did not transition to paused state flags after PAUSE_AND_SAVE for $fixtureName", pausedFlagsSet)
            val actualPausedPos = runOnMain { controller.currentPosition }

            // Verify immediate saved progress matches actual paused position within 500 ms
            val immediateSavedProgress = runBlocking { app.library.library().progress(bookId) }
            assertNotNull("Progress must be saved on pause for $fixtureName", immediateSavedProgress)
            val saveDiff = Math.abs(immediateSavedProgress!!.offsetMs - actualPausedPos)
            assertTrue("Immediate saved progress (${immediateSavedProgress.offsetMs}) deviates from actual paused position ($actualPausedPos) by $saveDiff ms", saveDiff <= 500L)

            // 6. Unload and reload with fresh controller to verify client reconnection and durable restored paused position
            val unloadFuture: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
            }
            val unloadResult = unloadFuture.get(10, TimeUnit.SECONDS)
            assertEquals("Unload before reload must succeed", SessionResult.RESULT_SUCCESS, unloadResult.resultCode)
            runOnMain { assertNull(controller.currentMediaItem) }

            // After UNLOAD_AND_SAVE successful drain, query authoritative persisted state
            val durableProgress = runBlocking { app.library.library().progress(bookId) }
            assertNotNull("Authoritative persisted progress must exist after unload for $fixtureName", durableProgress)
            assertEquals("Track ID must be stable across unload", immediateSavedProgress.trackId, durableProgress!!.trackId)
            assertEquals("Playback speed must be stable across unload", immediateSavedProgress.speed, durableProgress.speed, 0.001f)
            val drainedDiff = Math.abs(durableProgress.offsetMs - actualPausedPos)
            assertTrue("Drained progress (${durableProgress.offsetMs}) deviates from actual paused position ($actualPausedPos) by $drainedDiff ms", drainedDiff <= 500L)

            // Rebuild fresh controller to verify reconnection after service unload
            runOnMain { controller.release() }
            val reconnectFuture: ListenableFuture<MediaController> = runOnMain {
                MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            }
            controller = reconnectFuture.get(20, TimeUnit.SECONDS)

            val reloadFuture: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }
                )
            }
            assertEquals(SessionResult.RESULT_SUCCESS, reloadFuture.get(20, TimeUnit.SECONDS).resultCode)

            var restoredReady = false
            val restoreWait = SystemClock.elapsedRealtime()
            while (!restoredReady && SystemClock.elapsedRealtime() - restoreWait < 5_000L) {
                restoredReady = runOnMain { controller.playbackState == Player.STATE_READY }
                if (!restoredReady) Thread.sleep(50)
            }
            assertTrue("Format $fixtureName did not reach STATE_READY upon reload", restoredReady)

            val restoredPos = runOnMain { controller.currentPosition }
            val playWhenReady = runOnMain { controller.playWhenReady }
            assertFalse("Book must restore paused, not auto-playing", playWhenReady)
            val restoreDiff = Math.abs(restoredPos - durableProgress.offsetMs)
            assertTrue(
                "Restored position ($restoredPos) must match durable offset (${durableProgress.offsetMs}) within 200ms (diff: $restoreDiff; pauseSnapshot: ${immediateSavedProgress.offsetMs}, pausedPos: $actualPausedPos)",
                restoreDiff <= 200L
            )
        } finally {
            // ALWAYS unload and await drain off main BEFORE deleting records or files
            try {
                ensureUnloadedAndDrained()
            } catch (e: Exception) {
                throw AssertionError("Failed to unload and drain session before cleanup", e)
            }

            removeFixtureBook(entry)
        }
    }

    // --- MP3 Formats (Seek tolerance <= 2000 ms due to Xing TOC header interpolation) ---

    @Test
    fun cbrMp3PlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("chapters.mp3", maxSeekToleranceMs = 2000L)
    }

    @Test
    fun vbrMp3PlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("chapters-vbr.mp3", maxSeekToleranceMs = 2000L)
    }

    // --- AAC / M4B / M4A Formats (Seek tolerance <= 1000 ms) ---

    @Test
    fun aacQuickTimeChaptersPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("chapters-quicktime.m4b", maxSeekToleranceMs = 1000L)
    }

    @Test
    fun aacNeroChaptersPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("chapters-nero.m4b", maxSeekToleranceMs = 1000L)
    }

    @Test
    fun aacBothChaptersPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("chapters-both.m4b", maxSeekToleranceMs = 1000L)
    }

    @Test
    fun aacNoChaptersPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("no-chapters.m4a", maxSeekToleranceMs = 1000L)
    }

    // --- Lossless & Other Formats (Seek tolerance <= 1000 ms) ---

    @Test
    fun pcmWavPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("no-chapters.wav", maxSeekToleranceMs = 1000L)
    }

    @Test
    fun flacPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("no-chapters.flac", maxSeekToleranceMs = 1000L)
    }

    @Test
    fun vorbisOggPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("no-chapters.ogg", maxSeekToleranceMs = 1000L)
    }

    @Test
    fun opusOggPlaysAdvancesSeeksAndRestoresWithinTolerance() {
        verifyFormatPlaybackAndSeek("no-chapters-opus.ogg", maxSeekToleranceMs = 1000L)
    }

    // --- Valid Container with Unsupported Codec (MS-ADPCM in RIFF WAV) ---

    @Test
    fun validContainerUnsupportedCodecRejectsAndAllowsHealthyPlaybackRecovery() {
        val booksBefore = runBlocking { app.library.books().all().map { it.id }.toSet() }
        val jobsBefore = runBlocking { app.library.library().jobs().map { it.id }.toSet() }
        val entry = try { createFixtureBook("unsupported-msadpcm.wav") }
        catch (failure: InspectionException) {
            if (!linkedSources) throw failure
            assertEquals("Linked addition must reject the unsupported fixture explicitly",
                InspectionException.Reason.UNSUPPORTED_OR_CORRUPT,failure.reason)
            assertEquals(booksBefore,runBlocking { app.library.books().all().map { it.id }.toSet() })
            assertEquals(jobsBefore,runBlocking { app.library.library().jobs().map { it.id }.toSet() })
            verifyFormatPlaybackAndSeek("no-chapters.wav",maxSeekToleranceMs=1000L)
            return
        }
        val badBookId = entry.book
        try {
            val loadBad: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.LOAD_BOOK, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.BOOK_ID, badBookId) }
                )
            }
            loadBad.get(20, TimeUnit.SECONDS)

            // Trigger playback on the unsupported codec
            runOnMain { controller.play() }

            // Bounded wait for actual Player error or STATE_IDLE rejection (not merely !isPlaying while buffering)
            var observedRejection = false
            val errorWait = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - errorWait < 5_000L) {
                observedRejection = runOnMain {
                    controller.playerError != null || controller.playbackState == Player.STATE_IDLE
                }
                if (observedRejection) break
                Thread.sleep(50)
            }
            assertTrue("Unsupported codec must trigger actual player error or transition to STATE_IDLE", observedRejection)
            assertFalse("Unsupported codec must not be playing", runOnMain { controller.isPlaying })

            // Unload the failed session with awaited drain
            val unloadBad: ListenableFuture<SessionResult> = runOnMain {
                controller.sendCustomCommand(SessionCommand(PlaybackService.UNLOAD_AND_SAVE, Bundle.EMPTY), Bundle.EMPTY)
            }
            val unloadBadResult = unloadBad.get(10, TimeUnit.SECONDS)
            assertEquals("Unload after unsupported codec must succeed", SessionResult.RESULT_SUCCESS, unloadBadResult.resultCode)

            // Verify subsequent healthy playback recovery: a valid format plays and advances cleanly
            verifyFormatPlaybackAndSeek("no-chapters.wav", maxSeekToleranceMs = 1000L)
        } finally {
            try {
                ensureUnloadedAndDrained()
            } catch (e: Exception) {
                throw AssertionError("Failed to unload and drain session before cleanup", e)
            }

            removeFixtureBook(entry)
        }
    }
}
