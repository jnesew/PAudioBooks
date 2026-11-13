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
import io.github.jnesew.paudiobooks.library.Chapter
import io.github.jnesew.paudiobooks.library.BookTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Phase-separated recovery and durability tests across real process death / force-stop.
 * Split into seed (playback under test + process kill) and verify (cold restart inspection).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlaybackRecoveryTest {

    private val app get() = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun controller(): MediaController {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(
                app,
                SessionToken(app, ComponentName(app, PlaybackService::class.java))
            ).buildAsync()
        }
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun command(controller: MediaController, action: String, args: Bundle = Bundle.EMPTY): Int {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<SessionResult>
        instrumentation.runOnMainSync {
            future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        }
        return future.get(20, TimeUnit.SECONDS).resultCode
    }

    private fun createSyntheticWav(assetId: String, durationSec: Int): File {
        val file = File(app.noBackupFilesDir, "audiobooks/media/$assetId")
        file.parentFile?.mkdirs()
        val size = 8000 * 2 * durationSec
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        file.writeBytes(wav.array())
        return file
    }

    private fun waitForPosition(c: MediaController, expected: Long, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var pos = -1L
            instrumentation.runOnMainSync { pos = c.currentPosition }
            if (pos == expected) return
            Thread.sleep(50)
        }
        var finalPos = -1L
        instrumentation.runOnMainSync { finalPos = c.currentPosition }
        assertEquals("Position did not sync to $expected ms within ${timeoutMs}ms", expected, finalPos)
    }

    private fun cleanupBook(c: MediaController?, bookId: String, assetId: String) {
        if (c != null && c.isConnected) {
            runCatching {
                command(c, PlaybackService.UNLOAD_AND_SAVE)
            }
        }
        runBlocking {
            app.library.books().delete(bookId)
            app.library.library().deleteAsset(assetId)
        }
        val file = File(app.noBackupFilesDir, "audiobooks/media/$assetId")
        if (file.exists()) file.delete()
    }

    // --- 3.0x Speed Abrupt Kill & Recovery ---

    @Test
    fun seedKillAt3x() {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val durationSec = 60
        val file = createSyntheticWav(assetId, durationSec)

        runBlocking {
            app.library.books().insert(Book(bookId, "Synthetic 3x Kill Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", file.length(), "sha-3x", durationSec * 1000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            // Set speed to 3.0x
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, 3.0f) }))
            instrumentation.runOnMainSync {
                assertEquals(3.0f, c.playbackParameters.speed, 0.01f)
                c.play()
            }

            // Play for ~6.5 seconds wall clock -> ~19.5 media seconds at 3x
            Thread.sleep(6500)

            var lastPos = 0L
            var isPlaying = false
            instrumentation.runOnMainSync {
                lastPos = c.currentPosition
                isPlaying = c.isPlaying
            }
            assertTrue("Audio must be playing at 3x", isPlaying)
            assertTrue("Position must have advanced past 10s at 3x, was $lastPos ms", lastPos >= 10000L)

            assertTrue(
                app.getSharedPreferences("recovery_test_3x", 0).edit()
                    .putString("book", bookId)
                    .putString("track", trackId)
                    .putString("asset", assetId)
                    .putLong("last_pos", lastPos)
                    .putFloat("speed", 3.0f)
                    .putInt("seed_pid", android.os.Process.myPid())
                    .commit()
            )
        } finally {
            // Release controller unbinds without stopping playing background service
            instrumentation.runOnMainSync { c.release() }
        }
    }

    @Test
    fun verifyKillAt3x() {
        val prefs = app.getSharedPreferences("recovery_test_3x", 0)
        val bookId = checkNotNull(prefs.getString("book", null)) { "Seed preconditions missing for verifyKillAt3x" }
        val trackId = prefs.getString("track", null)!!
        val assetId = prefs.getString("asset", null)!!
        val lastPos = prefs.getLong("last_pos", 0L)
        val expectedSpeed = prefs.getFloat("speed", 3.0f)
        val seedPid = prefs.getInt("seed_pid", -1)
        val currentPid = android.os.Process.myPid()
        assertNotEquals("Process must be fresh/restarted (seed PID $seedPid == restart PID $currentPid)", seedPid, currentPid)

        val saved = runBlocking { app.library.library().progress(bookId) }
        assertNotNull("Durable progress must be present after abrupt kill", saved)
        assertEquals("Track ID must match", trackId, saved!!.trackId)
        assertFalse("Progress must not be marked finished", saved.finished)

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            waitForPosition(c, saved.offsetMs)
            instrumentation.runOnMainSync {
                assertFalse("Restored book must be paused after process restart", c.playWhenReady)
                assertEquals("Restored speed must be 3.0x", expectedSpeed, c.playbackParameters.speed, 0.01f)
            }

            // Verify healthy-storage loss <= 5000 media ms (5 media seconds)
            val lossMs = abs(lastPos - saved.offsetMs)
            assertTrue(
                "Healthy-storage loss at 3.0x speed must be <= 5000 media ms, observed loss was $lossMs ms (lastPos=$lastPos, saved=${saved.offsetMs})",
                lossMs <= 5000L
            )
        } finally {
            cleanupBook(c, bookId, assetId)
            instrumentation.runOnMainSync { c.release() }
            prefs.edit().clear().commit()
        }
    }

    // --- 1.0x Speed Abrupt Kill & Recovery ---

    @Test
    fun seedKillAt1x() {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val durationSec = 30
        val file = createSyntheticWav(assetId, durationSec)

        runBlocking {
            app.library.books().insert(Book(bookId, "Synthetic 1x Kill Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", file.length(), "sha-1x", durationSec * 1000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, 1.0f) }))
            instrumentation.runOnMainSync { c.play() }

            // Play for ~5.5 seconds wall clock -> ~5.5 media seconds at 1x
            Thread.sleep(5500)

            var lastPos = 0L
            var isPlaying = false
            instrumentation.runOnMainSync {
                lastPos = c.currentPosition
                isPlaying = c.isPlaying
            }
            assertTrue("Audio must be playing at 1x", isPlaying)
            assertTrue("Position must have advanced past 3000ms at 1x, was $lastPos ms", lastPos >= 3000L)

            assertTrue(
                app.getSharedPreferences("recovery_test_1x", 0).edit()
                    .putString("book", bookId)
                    .putString("track", trackId)
                    .putString("asset", assetId)
                    .putLong("last_pos", lastPos)
                    .putFloat("speed", 1.0f)
                    .putInt("seed_pid", android.os.Process.myPid())
                    .commit()
            )
        } finally {
            instrumentation.runOnMainSync { c.release() }
        }
    }

    @Test
    fun verifyKillAt1x() {
        val prefs = app.getSharedPreferences("recovery_test_1x", 0)
        val bookId = checkNotNull(prefs.getString("book", null)) { "Seed preconditions missing for verifyKillAt1x" }
        val trackId = prefs.getString("track", null)!!
        val assetId = prefs.getString("asset", null)!!
        val lastPos = prefs.getLong("last_pos", 0L)
        val expectedSpeed = prefs.getFloat("speed", 1.0f)
        val seedPid = prefs.getInt("seed_pid", -1)
        val currentPid = android.os.Process.myPid()
        assertNotEquals("Process must be fresh/restarted (seed PID $seedPid == restart PID $currentPid)", seedPid, currentPid)

        val saved = runBlocking { app.library.library().progress(bookId) }
        assertNotNull("Durable progress must be present after abrupt kill", saved)
        assertEquals("Track ID must match", trackId, saved!!.trackId)

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            waitForPosition(c, saved.offsetMs)
            instrumentation.runOnMainSync {
                assertFalse("Restored book must be paused after process restart", c.playWhenReady)
                assertEquals("Restored speed must be 1.0x", expectedSpeed, c.playbackParameters.speed, 0.01f)
            }

            val lossMs = abs(lastPos - saved.offsetMs)
            assertTrue(
                "Healthy-storage loss at 1.0x speed must be <= 5000 media ms, observed loss was $lossMs ms (lastPos=$lastPos, saved=${saved.offsetMs})",
                lossMs <= 5000L
            )
        } finally {
            cleanupBook(c, bookId, assetId)
            instrumentation.runOnMainSync { c.release() }
            prefs.edit().clear().commit()
        }
    }

    // --- Explicit Pause & Cold Restart (0 Media Loss) ---

    @Test
    fun seedPauseAndRestart() {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val durationSec = 30
        val file = createSyntheticWav(assetId, durationSec)

        runBlocking {
            app.library.books().insert(Book(bookId, "Synthetic Pause Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", file.length(), "sha-p", durationSec * 1000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, 1.5f) }))
            instrumentation.runOnMainSync { c.play() }
            Thread.sleep(3000)

            // Explicit pause and save
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.PAUSE_AND_SAVE))

            instrumentation.runOnMainSync {
                assertFalse(c.playWhenReady)
            }
            val savedProgress = runBlocking { app.library.library().progress(bookId) }
            assertNotNull(savedProgress)
            val pausedPos = savedProgress!!.offsetMs
            assertTrue("Paused position must be > 1000ms", pausedPos > 1000L)

            assertTrue(
                app.getSharedPreferences("recovery_test_pause", 0).edit()
                    .putString("book", bookId)
                    .putString("track", trackId)
                    .putString("asset", assetId)
                    .putLong("paused_pos", pausedPos)
                    .putFloat("speed", 1.5f)
                    .putInt("seed_pid", android.os.Process.myPid())
                    .commit()
            )
        } finally {
            instrumentation.runOnMainSync { c.release() }
        }
    }

    @Test
    fun verifyPauseAndRestart() {
        val prefs = app.getSharedPreferences("recovery_test_pause", 0)
        val bookId = checkNotNull(prefs.getString("book", null)) { "Seed preconditions missing for verifyPauseAndRestart" }
        val trackId = prefs.getString("track", null)!!
        val assetId = prefs.getString("asset", null)!!
        val expectedPos = prefs.getLong("paused_pos", 0L)
        val expectedSpeed = prefs.getFloat("speed", 1.5f)
        val seedPid = prefs.getInt("seed_pid", -1)
        val currentPid = android.os.Process.myPid()
        assertNotEquals("Process must be fresh/restarted (seed PID $seedPid == restart PID $currentPid)", seedPid, currentPid)

        val saved = runBlocking { app.library.library().progress(bookId) }
        assertNotNull(saved)
        assertEquals(expectedPos, saved!!.offsetMs)

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            waitForPosition(c, expectedPos)
            instrumentation.runOnMainSync {
                assertFalse("Must remain paused", c.playWhenReady)
                assertEquals(expectedSpeed, c.playbackParameters.speed, 0.01f)
            }
        } finally {
            cleanupBook(c, bookId, assetId)
            instrumentation.runOnMainSync { c.release() }
            prefs.edit().clear().commit()
        }
    }

    // --- Book Switch & Ordered Seek Writes ---

    @Test
    fun orderedBookSwitchAndSeekWrites() {
        val book1Id = UUID.randomUUID().toString()
        val asset1Id = UUID.randomUUID().toString()
        val track1Id = UUID.randomUUID().toString()
        val file1 = createSyntheticWav(asset1Id, 30)

        val book2Id = UUID.randomUUID().toString()
        val asset2Id = UUID.randomUUID().toString()
        val track2Id = UUID.randomUUID().toString()
        val file2 = createSyntheticWav(asset2Id, 30)

        runBlocking {
            app.library.books().insert(Book(book1Id, "Book One"))
            app.library.library().insertAssets(listOf(AudioAsset(asset1Id, "media/$asset1Id", file1.length(), "sha-1", 30000L)))
            app.library.library().insertTracks(listOf(BookTrack(track1Id, book1Id, asset1Id, 0)))

            app.library.books().insert(Book(book2Id, "Book Two"))
            app.library.library().insertAssets(listOf(AudioAsset(asset2Id, "media/$asset2Id", file2.length(), "sha-2", 30000L)))
            app.library.library().insertTracks(listOf(BookTrack(track2Id, book2Id, asset2Id, 0)))
        }

        val c = controller()
        try {
            // Load Book 1, seek to 4000ms, pause & save
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book1Id) }))
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 4000L) }))
            waitForPosition(c, 4000L)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.PAUSE_AND_SAVE))

            // Switch to Book 2, seek to 9000ms, pause & save
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book2Id) }))
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, 9000L) }))
            waitForPosition(c, 9000L)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.PAUSE_AND_SAVE))

            // Switch back to Book 1: verify position is 4000ms
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book1Id) }))
            waitForPosition(c, 4000L)
            instrumentation.runOnMainSync {
                assertFalse(c.playWhenReady)
            }

            // Switch back to Book 2: verify position is 9000ms
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, book2Id) }))
            waitForPosition(c, 9000L)
            instrumentation.runOnMainSync {
                assertFalse(c.playWhenReady)
            }

            // Verify both saved progress rows in Room match
            val p1 = runBlocking { app.library.library().progress(book1Id) }
            val p2 = runBlocking { app.library.library().progress(book2Id) }
            assertEquals(4000L, p1?.offsetMs)
            assertEquals(9000L, p2?.offsetMs)
        } finally {
            cleanupBook(c, book1Id, asset1Id)
            cleanupBook(c, book2Id, asset2Id)
            instrumentation.runOnMainSync { c.release() }
        }
    }

    // --- Monotonic Duration Sleep Timer at Non-1x ---

    @Test
    fun monotonicDurationTimerAtNon1x() {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val durationSec = 30
        val file = createSyntheticWav(assetId, durationSec)

        runBlocking {
            app.library.books().insert(Book(bookId, "Monotonic Timer Non-1x Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", file.length(), "sha-t", durationSec * 1000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            // Set speed to 2.0x
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, 2.0f) }))
            // Set 3000 ms sleep timer (countdown uses elapsed monotonic real time, not audio speed)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SLEEP_TIMER, Bundle().apply { putLong(PlaybackService.SLEEP_DURATION_MS, 3000L) }))

            instrumentation.runOnMainSync { c.play() }

            // Sleep 4000ms wall time: 3s monotonic timer must fire and pause playback
            Thread.sleep(4000)

            var isPaused = false
            var finalPos = 0L
            instrumentation.runOnMainSync {
                isPaused = !c.playWhenReady
                finalPos = c.currentPosition
            }
            assertTrue("Sleep timer must have paused playback", isPaused)
            // At 2.0x speed, 3 seconds elapsed = ~6000 media ms
            assertTrue("Media position must have advanced ~6000ms at 2x speed, was $finalPos ms", finalPos in 4500L..8000L)

            // Progress must be durably checkpointed
            val saved = runBlocking { app.library.library().progress(bookId) }
            assertNotNull(saved)
            assertTrue("Durable progress must match timer pause position", saved!!.offsetMs >= 4500L)
        } finally {
            cleanupBook(c, bookId, assetId)
            instrumentation.runOnMainSync { c.release() }
        }
    }

    // --- Native UI Live Playing Journey Fixtures ---

    @Test
    fun setupNativeUiBook() {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val durationSec = 120
        val file = createSyntheticWav(assetId, durationSec)

        runBlocking {
            app.library.books().insert(Book(bookId, "Native Journey Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", file.length(), "sha-ui", durationSec * 1000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
        }

        assertTrue(
            app.getSharedPreferences("recovery_test_native_ui", 0).edit()
                .putString("book", bookId)
                .putString("track", trackId)
                .putString("asset", assetId)
                .commit()
        )
    }

    @Test
    fun verifyNativeUiKill() {
        val prefs = app.getSharedPreferences("recovery_test_native_ui", 0)
        val bookId = checkNotNull(prefs.getString("book", null)) { "Native UI preconditions missing for verifyNativeUiKill" }
        val trackId = prefs.getString("track", null)!!
        val assetId = prefs.getString("asset", null)!!
        val lastObservedPos = prefs.getLong("last_session_pos", 0L)

        val saved = runBlocking { app.library.library().progress(bookId) }
        assertNotNull("Durable progress must exist after live force-stop", saved)
        assertEquals(trackId, saved!!.trackId)
        assertFalse("Progress must not be marked finished", saved.finished)
        assertTrue("Progress must have advanced during live playback (>1000ms)", saved.offsetMs > 1000L)

        if (lastObservedPos > 0) {
            val loss = abs(lastObservedPos - saved.offsetMs)
            assertTrue("Loss near termination must be <= 5000 media ms, was $loss ms", loss <= 5000L)
        }

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            waitForPosition(c, saved.offsetMs)
            instrumentation.runOnMainSync {
                assertFalse("Restored book must be paused after external force-stop", c.playWhenReady)
            }
        } finally {
            cleanupBook(c, bookId, assetId)
            instrumentation.runOnMainSync { c.release() }
            prefs.edit().clear().commit()
        }
    }

    // --- End-of-Chapter Sleep Timer at Non-1x ---

    @Test
    fun endChapterTimerAtNon1x() {
        val bookId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val trackId = UUID.randomUUID().toString()
        val durationSec = 30
        val file = createSyntheticWav(assetId, durationSec)

        val ch1Id = UUID.randomUUID().toString()
        val ch2Id = UUID.randomUUID().toString()

        runBlocking {
            app.library.books().insert(Book(bookId, "End Chapter Timer Book"))
            app.library.library().insertAssets(listOf(AudioAsset(assetId, "media/$assetId", file.length(), "sha-ec", durationSec * 1000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackId, bookId, assetId, 0)))
            app.library.library().insertChapters(listOf(
                Chapter(ch1Id, trackId, 0, 0L, 4000L, "Chapter 1", "test"),
                Chapter(ch2Id, trackId, 1, 4000L, 15000L, "Chapter 2", "test")
            ))
        }

        val c = controller()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookId) }))
            // Seek to 1000ms inside chapter 1
            instrumentation.runOnMainSync { c.seekTo(1000L) }
            Thread.sleep(500)

            // Set speed to 2.0x
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, 2.0f) }))

            // Set end-chapter sleep timer
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.SET_SLEEP_TIMER, Bundle().apply { putBoolean(PlaybackService.SLEEP_END_CHAPTER, true) }))

            instrumentation.runOnMainSync { c.play() }

            // To play from 1000ms to 4000ms (3000 media ms) at 2x speed takes ~1.5 wall-clock seconds
            Thread.sleep(3000)

            var isPaused = false
            var finalPos = 0L
            instrumentation.runOnMainSync {
                isPaused = !c.playWhenReady
                finalPos = c.currentPosition
            }
            assertTrue("End-chapter timer must have paused playback", isPaused)
            assertEquals("End-chapter timer must pause at exact chapter boundary (4000ms)", 4000L, finalPos)
        } finally {
            cleanupBook(c, bookId, assetId)
            instrumentation.runOnMainSync { c.release() }
        }
    }

    // --- Regression: Switched-Book Deletion Race (D05) ---

    @Test
    fun staleSwitchedBookDeleteWhilePlayingCurrentBookDoesNotPauseCurrentBook() {
        val bookBId = UUID.randomUUID().toString()
        val assetBId = UUID.randomUUID().toString()
        val trackBId = UUID.randomUUID().toString()
        val fileB = createSyntheticWav(assetBId, 30)

        val bookAId = UUID.randomUUID().toString()
        val assetAId = UUID.randomUUID().toString()
        val trackAId = UUID.randomUUID().toString()
        val fileA = createSyntheticWav(assetAId, 30)

        runBlocking {
            app.library.books().insert(Book(bookBId, "Book B (To Delete)"))
            app.library.library().insertAssets(listOf(AudioAsset(assetBId, "media/$assetBId", fileB.length(), "sha-b", 30_000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackBId, bookBId, assetBId, 0)))

            app.library.books().insert(Book(bookAId, "Book A (Keep Playing)"))
            app.library.library().insertAssets(listOf(AudioAsset(assetAId, "media/$assetAId", fileA.length(), "sha-a", 30_000L)))
            app.library.library().insertTracks(listOf(BookTrack(trackAId, bookAId, assetAId, 0)))
        }

        val c = controller()
        try {
            // 1. Load Book B and play briefly
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookBId) }))
            instrumentation.runOnMainSync { c.play() }
            Thread.sleep(1000)

            // 2. Switch to Book A (triggers checkpoint on Book B)
            assertEquals(SessionResult.RESULT_SUCCESS, command(c, PlaybackService.LOAD_BOOK, Bundle().apply { putString(PlaybackService.BOOK_ID, bookAId) }))
            instrumentation.runOnMainSync { c.play() }

            // 3. Delete Book B from Room directly while Book A is playing
            runBlocking {
                app.library.books().delete(bookBId)
                app.library.library().deleteAsset(assetBId)
            }
            if (fileB.exists()) fileB.delete()

            // Wait 2 seconds while Book A continues playing
            Thread.sleep(2000)

            // 4. Assert that Book A was NOT paused
            var isPlaying = false
            var saveFailed = true
            instrumentation.runOnMainSync {
                isPlaying = c.playWhenReady
                saveFailed = c.sessionExtras.getBoolean(PlaybackService.SAVE_FAILED, false)
            }
            assertTrue("Playing Book A must not be paused by stale Book B deletion failure", isPlaying)
            assertFalse("Session extras for Book A must not report saveFailed", saveFailed)
        } finally {
            cleanupBook(c, bookAId, assetAId)
            cleanupBook(null, bookBId, assetBId)
            instrumentation.runOnMainSync { c.release() }
        }
    }
}
