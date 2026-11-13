package io.github.jnesew.paudiobooks.preferences

object PlaybackSpeed {
    const val MIN_SPEED: Float = 0.50f
    const val MAX_SPEED: Float = 3.00f
    const val DEFAULT_SPEED: Float = 1.00f
    const val STEP: Float = 0.05f

    /**
     * Discrete selectable speeds from 0.50x to 3.00x in 0.05 increments.
     */
    val OPTIONS: List<Float> = (10..60).map { it / 20f }

    /**
     * Determines whether a given playback speed is finite and within the valid range [0.50, 3.00].
     */
    fun isValid(speed: Float): Boolean {
        return speed.isFinite() && speed in MIN_SPEED..MAX_SPEED
    }

    /**
     * Returns the speed if valid, otherwise falls back to 1.00x.
     */
    fun sanitize(speed: Float?): Float {
        return if (speed != null && isValid(speed)) speed else DEFAULT_SPEED
    }

    /**
     * Chooses the first valid speed in order:
     * 1. recent in-memory speed
     * 2. saved database speed
     * 3. persisted default speed
     * 4. final 1.00x fallback
     *
     * An invalid higher-priority value must never hide a valid lower-priority speed.
     */
    fun resolve(recentSpeed: Float?, savedSpeed: Float?, defaultSpeed: Float?): Float {
        return when {
            recentSpeed != null && isValid(recentSpeed) -> recentSpeed
            savedSpeed != null && isValid(savedSpeed) -> savedSpeed
            defaultSpeed != null && isValid(defaultSpeed) -> defaultSpeed
            else -> DEFAULT_SPEED
        }
    }
}
