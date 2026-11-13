package io.github.jnesew.paudiobooks.playback

/**
 * Pure rules for controller access, idle shutdown, progress failures and chapter timers.
 */
internal object PlaybackPolicy {
    const val IDLE_TIMEOUT_MS = 600_000L
    const val SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 2L

    /**
     * Evaluates whether a progress save failure should pause the current player.
     * Only failures on the CURRENT active playing book may pause playback.
     * Failures on unrelated/switched books must never interrupt healthy playback.
     */
    fun shouldPauseOnSaveFailure(
        currentBookId: String?,
        failedBookId: String?
    ): Boolean {
        return currentBookId != null && currentBookId == failedBookId
    }

    /**
     * Only same-UID non-internal controllers are classified as UI controllers.
     * Internal Media3 notification controllers share Process.myUid() but must NOT
     * be classified as UI controllers, preventing indefinite keepalive while paused
     * with an active notification.
     */
    fun isUiController(
        isSameUid: Boolean,
        isNotificationController: Boolean,
        isAutomotiveController: Boolean,
        isAutoCompanionController: Boolean
    ): Boolean {
        return isSameUid && !isNotificationController && !isAutomotiveController && !isAutoCompanionController
    }

    /**
     * Policy evaluation for idle timeout cancellation on connection.
     * Only authenticated UI controllers may cancel cleanup.
     * Rejected or non-UI controllers must NEVER cancel idle cleanup.
     */
    fun shouldCancelIdleTimeout(
        isAuthenticated: Boolean,
        isUiController: Boolean
    ): Boolean {
        return isAuthenticated && isUiController
    }

    /**
     * Determines if the service is eligible to schedule or maintain the idle timeout.
     * Timeout runs when paused, a book is loaded, and no UI controllers are connected.
     */
    fun shouldScheduleIdleTimeout(
        hasUiController: Boolean,
        isPlaying: Boolean,
        hasLoadedBook: Boolean
    ): Boolean {
        return !hasUiController && !isPlaying && hasLoadedBook
    }

    /**
     * Rechecks relevant load/playback state before completing intentional asynchronous shutdown.
     * If a new book was loaded, playback resumed, or a UI controller connected
     * during the wait, shutdown must not proceed.
     */
    fun shouldCompleteShutdown(
        isDestroyed: Boolean,
        isPlaying: Boolean,
        loadGenerationUnchanged: Boolean,
        hasUiController: Boolean
    ): Boolean {
        return !isDestroyed && !isPlaying && loadGenerationUnchanged && !hasUiController
    }

    /**
     * Determines if a failed save state is safe to restore without corrupting newer state.
     * If a newer sequence is already pending, committed, or recorded in latest in-memory state,
     * the failed older state must never be put back into pending writes.
     */
    fun shouldRestoreFailedState(
        failedSequence: Long,
        pendingSequence: Long?,
        committedSequence: Long?,
        latestSequence: Long?
    ): Boolean {
        val hasNewerPending = pendingSequence?.let { it > failedSequence } ?: false
        val hasNewerCommitted = committedSequence?.let { it >= failedSequence } ?: false
        val hasNewerLatest = latestSequence?.let { it > failedSequence } ?: false
        return !hasNewerPending && !hasNewerCommitted && !hasNewerLatest
    }

    /**
     * Evaluates whether an explicit user seek should cancel an armed end-of-chapter timer.
     * Explicit seeking to or past the armed boundary indicates intentional user navigation,
     * which must cancel the timer rather than snapping the user backwards.
     */
    fun shouldCancelSleepChapterOnSeek(
        targetTrack: Int,
        targetOffsetMs: Long,
        seekTrack: Int,
        seekOffsetMs: Long
    ): Boolean {
        return seekTrack > targetTrack || (seekTrack == targetTrack && seekOffsetMs >= targetOffsetMs)
    }

    /**
     * Distinguishes natural playback decoder/loop overshoot from explicit forward navigation.
     * Natural playback may slightly overshoot the target chapter boundary during normal playback
     * (bounded by scheduling jitter and decoder frames). Overshoot within tolerance is corrected
     * to exact chapter end; navigation beyond tolerance is not snapped backwards.
     */
    fun shouldCorrectDecoderOvershoot(
        targetTrack: Int,
        targetOffsetMs: Long,
        currentTrack: Int,
        currentOffsetMs: Long,
        toleranceMs: Long = 3000L
    ): Boolean {
        val isSameTrack = (currentTrack == targetTrack && currentOffsetMs in targetOffsetMs..(targetOffsetMs + toleranceMs))
        val isNextTrackStart = (currentTrack == targetTrack + 1 && currentOffsetMs in 0..toleranceMs)
        return isSameTrack || isNextTrackStart
    }
}
