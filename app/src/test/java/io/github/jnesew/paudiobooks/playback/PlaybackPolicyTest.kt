package io.github.jnesew.paudiobooks.playback

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun genuineUiControllerClassification() {
        val isUi = PlaybackPolicy.isUiController(
            isSameUid = true,
            isNotificationController = false,
            isAutomotiveController = false,
            isAutoCompanionController = false
        )
        assertTrue("Same UID non-internal controller must be classified as UI controller", isUi)
    }

    @Test
    fun notificationControllerNotClassifiedAsUi() {
        val isUi = PlaybackPolicy.isUiController(
            isSameUid = true,
            isNotificationController = true,
            isAutomotiveController = false,
            isAutoCompanionController = false
        )
        assertFalse("Internal notification controller must not be classified as UI controller", isUi)
    }

    @Test
    fun automotiveAndCompanionControllersNotClassifiedAsUi() {
        val isAuto = PlaybackPolicy.isUiController(
            isSameUid = true,
            isNotificationController = false,
            isAutomotiveController = true,
            isAutoCompanionController = false
        )
        assertFalse("Automotive controller must not be classified as UI controller", isAuto)

        val isCompanion = PlaybackPolicy.isUiController(
            isSameUid = true,
            isNotificationController = false,
            isAutomotiveController = false,
            isAutoCompanionController = true
        )
        assertFalse("Auto companion controller must not be classified as UI controller", isCompanion)
    }

    @Test
    fun externalUidNotClassifiedAsUi() {
        val isUi = PlaybackPolicy.isUiController(
            isSameUid = false,
            isNotificationController = false,
            isAutomotiveController = false,
            isAutoCompanionController = false
        )
        assertFalse("External UID must not be classified as UI controller", isUi)
    }

    @Test
    fun rejectedControllerCannotCancelIdleTimeout() {
        val cancelWhenRejected = PlaybackPolicy.shouldCancelIdleTimeout(
            isAuthenticated = false,
            isUiController = false
        )
        assertFalse("Unauthenticated controller must never cancel idle timeout", cancelWhenRejected)

        val cancelWhenForgedUi = PlaybackPolicy.shouldCancelIdleTimeout(
            isAuthenticated = false,
            isUiController = true
        )
        assertFalse("Unauthenticated controller must never cancel idle timeout even if claiming UI properties", cancelWhenForgedUi)
    }

    @Test
    fun nonUiControllerCannotCancelIdleTimeout() {
        val cancelForNotification = PlaybackPolicy.shouldCancelIdleTimeout(
            isAuthenticated = true,
            isUiController = false
        )
        assertFalse("Authenticated non-UI controller (such as notification) must not cancel idle timeout", cancelForNotification)
    }

    @Test
    fun authenticatedUiControllerCancelsIdleTimeout() {
        val cancel = PlaybackPolicy.shouldCancelIdleTimeout(
            isAuthenticated = true,
            isUiController = true
        )
        assertTrue("Authenticated UI controller must cancel idle timeout", cancel)
    }

    @Test
    fun idleTimeoutSchedulingRules() {
        // Active UI controller connected -> do not schedule idle timeout
        assertFalse(PlaybackPolicy.shouldScheduleIdleTimeout(hasUiController = true, isPlaying = false, hasLoadedBook = true))

        // Currently playing -> do not schedule idle timeout
        assertFalse(PlaybackPolicy.shouldScheduleIdleTimeout(hasUiController = false, isPlaying = true, hasLoadedBook = true))

        // No book loaded -> do not schedule idle timeout
        assertFalse(PlaybackPolicy.shouldScheduleIdleTimeout(hasUiController = false, isPlaying = false, hasLoadedBook = false))

        // Paused, book loaded, no UI controller -> schedule idle timeout
        assertTrue(PlaybackPolicy.shouldScheduleIdleTimeout(hasUiController = false, isPlaying = false, hasLoadedBook = true))
    }

    @Test
    fun shouldCompleteShutdownRules() {
        // Normal condition: not destroyed, paused, load unchanged, no UI controller -> proceed with shutdown
        assertTrue(PlaybackPolicy.shouldCompleteShutdown(isDestroyed = false, isPlaying = false, loadGenerationUnchanged = true, hasUiController = false))

        // Playback resumed during wait -> abort shutdown
        assertFalse(PlaybackPolicy.shouldCompleteShutdown(isDestroyed = false, isPlaying = true, loadGenerationUnchanged = true, hasUiController = false))

        // New book loaded during wait -> abort shutdown
        assertFalse(PlaybackPolicy.shouldCompleteShutdown(isDestroyed = false, isPlaying = false, loadGenerationUnchanged = false, hasUiController = false))

        // UI controller connected during wait -> abort shutdown
        assertFalse(PlaybackPolicy.shouldCompleteShutdown(isDestroyed = false, isPlaying = false, loadGenerationUnchanged = true, hasUiController = true))

        // Already destroyed -> abort shutdown
        assertFalse(PlaybackPolicy.shouldCompleteShutdown(isDestroyed = true, isPlaying = false, loadGenerationUnchanged = true, hasUiController = false))
    }

    @Test
    fun timeoutAndPersistenceLimits() {
        assertEquals("Idle timeout must be exactly 10 minutes", 600_000L, PlaybackPolicy.IDLE_TIMEOUT_MS)
        assertEquals("Shutdown flush timeout must be bounded to 2 seconds", 2L, PlaybackPolicy.SHUTDOWN_FLUSH_TIMEOUT_SECONDS)
    }

    @Test
    fun restoreFailedStateNeverCorruptsNewerState() {
        // Safe to restore when no newer sequence exists in pending, committed, or latest
        assertTrue(
            "Must allow restoration when no newer sequence exists anywhere",
            PlaybackPolicy.shouldRestoreFailedState(
                failedSequence = 5L,
                pendingSequence = null,
                committedSequence = 4L,
                latestSequence = 5L
            )
        )

        // Must reject restoration if a newer sequence is already pending
        assertFalse(
            "Must never restore stale failed sequence when newer sequence is pending",
            PlaybackPolicy.shouldRestoreFailedState(
                failedSequence = 5L,
                pendingSequence = 6L,
                committedSequence = 4L,
                latestSequence = 6L
            )
        )

        // Must reject restoration if a newer or equal sequence was committed
        assertFalse(
            "Must never restore failed sequence if already committed or exceeded",
            PlaybackPolicy.shouldRestoreFailedState(
                failedSequence = 5L,
                pendingSequence = null,
                committedSequence = 5L,
                latestSequence = 5L
            )
        )
        assertFalse(
            "Must never restore failed sequence if higher committed",
            PlaybackPolicy.shouldRestoreFailedState(
                failedSequence = 5L,
                pendingSequence = null,
                committedSequence = 6L,
                latestSequence = 6L
            )
        )

        // Must reject restoration if latest in-memory sequence is newer
        assertFalse(
            "Must never restore stale failed sequence when latest in-memory sequence is newer",
            PlaybackPolicy.shouldRestoreFailedState(
                failedSequence = 5L,
                pendingSequence = null,
                committedSequence = 3L,
                latestSequence = 6L
            )
        )
    }

    @Test
    fun regressionOlderFailedSaveCannotReplaceOrDropNewerPendingState() {
        val pendingWrites = LinkedHashMap<String, Long>()
        val latest = LinkedHashMap<String, Long>()
        val bookId = "test-book"

        // Step 1: Sequence 5 was extracted to be written to disk.
        val failedSeq = 5L

        // Step 2: While sequence 5 write was in flight, a new checkpoint sequence 6 arrived.
        pendingWrites[bookId] = 6L
        latest[bookId] = 6L

        // Step 3: Sequence 5 fails.
        // With PlaybackPolicy guard, sequence 5 is NOT restored:
        val shouldRestore = PlaybackPolicy.shouldRestoreFailedState(
            failedSequence = failedSeq,
            pendingSequence = pendingWrites[bookId],
            committedSequence = 4L,
            latestSequence = latest[bookId]
        )
        assertFalse("Must refuse to restore older sequence 5 over pending sequence 6", shouldRestore)

        // If safe, putIfAbsent would be called; here it's skipped:
        if (shouldRestore) {
            pendingWrites.putIfAbsent(bookId, failedSeq)
        }

        // Verify sequence 6 remains in pendingWrites without being dropped or corrupted
        assertEquals("Newer pending sequence 6 must be preserved", 6L, pendingWrites[bookId])
    }

    @Test
    fun regressionShutdownAbortsWhenBookLoadChangesDuringFlush() {
        val initialGeneration = 1L

        // During flush, a new book is loaded, changing loadGeneration to 2L
        val currentGenerationDuringFlush = 2L
        val loadGenerationUnchanged = (currentGenerationDuringFlush == initialGeneration)
        assertFalse("Generation must be detected as changed", loadGenerationUnchanged)

        val shouldShutdown = PlaybackPolicy.shouldCompleteShutdown(
            isDestroyed = false,
            isPlaying = false,
            loadGenerationUnchanged = loadGenerationUnchanged,
            hasUiController = false
        )
        assertFalse("Shutdown must be aborted when book is switched during flush", shouldShutdown)
    }

    @Test
    fun sleepChapterCancelOnExplicitSeek() {
        val targetTrack = 0
        val targetOffsetMs = 5000L

        // Seeking earlier in the chapter (e.g. rewind) must NOT cancel the end-of-chapter timer
        assertFalse(
            "Seeking backwards within current chapter must keep end-chapter timer armed",
            PlaybackPolicy.shouldCancelSleepChapterOnSeek(targetTrack, targetOffsetMs, seekTrack = 0, seekOffsetMs = 2000L)
        )

        // Seeking to exact chapter boundary must cancel the timer
        assertTrue(
            "Seeking to exact chapter boundary must cancel the timer",
            PlaybackPolicy.shouldCancelSleepChapterOnSeek(targetTrack, targetOffsetMs, seekTrack = 0, seekOffsetMs = 5000L)
        )

        // Seeking past chapter boundary in same track must cancel the timer
        assertTrue(
            "Seeking forward past chapter boundary must cancel the timer",
            PlaybackPolicy.shouldCancelSleepChapterOnSeek(targetTrack, targetOffsetMs, seekTrack = 0, seekOffsetMs = 6000L)
        )

        // Seeking to a later track must cancel the timer
        assertTrue(
            "Seeking to a later track must cancel the timer",
            PlaybackPolicy.shouldCancelSleepChapterOnSeek(targetTrack, targetOffsetMs, seekTrack = 1, seekOffsetMs = 0L)
        )

        // If target is in track 1, seeking in track 0 must keep timer armed
        assertFalse(
            "Seeking in an earlier track must keep timer armed",
            PlaybackPolicy.shouldCancelSleepChapterOnSeek(targetTrack = 1, targetOffsetMs = 3000L, seekTrack = 0, seekOffsetMs = 5000L)
        )
    }

    @Test
    fun decoderOvershootCorrectionDistinguishesNaturalPlaybackFromLargeJump() {
        val targetTrack = 0
        val targetOffsetMs = 5000L

        // Natural playback overshoot within 3000ms in same track must be corrected
        assertTrue(
            "Small natural overshoot (50ms) must be corrected to chapter boundary",
            PlaybackPolicy.shouldCorrectDecoderOvershoot(targetTrack, targetOffsetMs, currentTrack = 0, currentOffsetMs = 5050L)
        )
        assertTrue(
            "Natural overshoot within 3000ms must be corrected to chapter boundary",
            PlaybackPolicy.shouldCorrectDecoderOvershoot(targetTrack, targetOffsetMs, currentTrack = 0, currentOffsetMs = 7999L)
        )

        // Large jump beyond 3000ms tolerance must NOT snap backward
        assertFalse(
            "Overshoot beyond 3000ms tolerance must NOT snap backward",
            PlaybackPolicy.shouldCorrectDecoderOvershoot(targetTrack, targetOffsetMs, currentTrack = 0, currentOffsetMs = 8500L)
        )

        // Natural playback advancing to next track start within 3000ms must be corrected
        assertTrue(
            "Advancing to start of next track within 3000ms must be corrected",
            PlaybackPolicy.shouldCorrectDecoderOvershoot(targetTrack, targetOffsetMs, currentTrack = 1, currentOffsetMs = 500L)
        )
        assertFalse(
            "Advancing to next track past 3000ms must NOT snap backward",
            PlaybackPolicy.shouldCorrectDecoderOvershoot(targetTrack, targetOffsetMs, currentTrack = 1, currentOffsetMs = 4000L)
        )
    }

    @Test
    fun regressionIdleTimerGenerationGuardAbortsShutdownOnBookSwitch() {
        val startGeneration = 1L
        var currentLoadGeneration = 1L

        // Before timeout, no switch: generation matches
        val unchangedBefore = (currentLoadGeneration == startGeneration)
        assertTrue(unchangedBefore)

        // During 10-minute wait, a new book is loaded (generation becomes 2L)
        currentLoadGeneration = 2L

        // When timer fires, startGeneration comparison aborts shutdown
        val unchangedAfter = (currentLoadGeneration == startGeneration)
        assertFalse("Shutdown must be aborted because load generation changed during timeout", unchangedAfter)

        val shouldShutdown = PlaybackPolicy.shouldCompleteShutdown(
            isDestroyed = false,
            isPlaying = false,
            loadGenerationUnchanged = unchangedAfter,
            hasUiController = false
        )
        assertFalse("Service must NOT shut down when a new book was loaded during idle wait", shouldShutdown)
    }

    @Test
    fun shouldPauseOnSaveFailureOnlyForCurrentBook() {
        // Current book matches failed book -> must pause
        assertTrue(
            "Current book save failure must pause player",
            PlaybackPolicy.shouldPauseOnSaveFailure("BookA", "BookA")
        )

        // Current book does not match failed book (e.g. stale write for switched BookB) -> must NOT pause
        assertFalse(
            "Failure on unrelated/switched book must never pause current playing book",
            PlaybackPolicy.shouldPauseOnSaveFailure("BookA", "BookB")
        )

        // No book loaded in player -> must NOT pause
        assertFalse(
            "Null current book must not pause player",
            PlaybackPolicy.shouldPauseOnSaveFailure(null, "BookB")
        )

        // Null failed book ID -> must NOT pause
        assertFalse(
            "Null failed book ID must not pause player",
            PlaybackPolicy.shouldPauseOnSaveFailure("BookA", null)
        )
    }
}
