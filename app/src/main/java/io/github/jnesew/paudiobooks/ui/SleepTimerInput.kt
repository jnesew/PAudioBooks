package io.github.jnesew.paudiobooks.ui

object SleepTimerInput {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440
    const val MILLIS_PER_MINUTE = 60_000L

    /**
     * Parses a custom sleep duration string representing whole minutes.
     *
     * Valid values: 1..1440 whole minutes, allowing leading/trailing ordinary whitespace.
     * Rejects: null, blank, zero, negative, fractional, non-numeric, overflow, or out-of-range input.
     * Never silently converts malformed text into a different duration.
     *
     * Returns the integer minute value in 1..1440, or null if invalid.
     */
    fun parseMinutes(input: String?): Int? {
        if (input == null) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it in '0'..'9' }) return null
        val minutes = trimmed.toLongOrNull() ?: return null
        if (minutes !in MIN_MINUTES.toLong()..MAX_MINUTES.toLong()) return null
        return minutes.toInt()
    }

    /**
     * Parses a custom sleep duration string and converts it to milliseconds.
     *
     * Returns the duration in milliseconds (60_000L..86_400_000L), or null if invalid.
     */
    fun parseMinutesToMillis(input: String?): Long? =
        parseMinutes(input)?.let { it.toLong() * MILLIS_PER_MINUTE }
}
