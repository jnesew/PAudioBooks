package io.github.jnesew.paudiobooks.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun constantsAndOptionsConformToSpecification() {
        assertEquals(0.50f, PlaybackSpeed.MIN_SPEED, 0.001f)
        assertEquals(3.00f, PlaybackSpeed.MAX_SPEED, 0.001f)
        assertEquals(1.00f, PlaybackSpeed.DEFAULT_SPEED, 0.001f)
        assertEquals(0.05f, PlaybackSpeed.STEP, 0.001f)

        assertEquals(51, PlaybackSpeed.OPTIONS.size)
        assertEquals(0.50f, PlaybackSpeed.OPTIONS.first(), 0.001f)
        assertEquals(3.00f, PlaybackSpeed.OPTIONS.last(), 0.001f)

        var previous = 0.45f
        for (option in PlaybackSpeed.OPTIONS) {
            assertTrue("Option $option must be valid", PlaybackSpeed.isValid(option))
            assertTrue("Options must be strictly increasing", option > previous)
            assertEquals("Step must be approximately 0.05", 0.05f, option - previous, 0.001f)
            previous = option
        }
        assertTrue("Options must contain 1.00x", PlaybackSpeed.OPTIONS.any { Math.abs(it - 1.0f) < 0.001f })
    }

    @Test
    fun isValidAcceptsFiniteValuesWithinRange() {
        assertTrue(PlaybackSpeed.isValid(0.50f))
        assertTrue(PlaybackSpeed.isValid(1.00f))
        assertTrue(PlaybackSpeed.isValid(1.25f))
        assertTrue(PlaybackSpeed.isValid(2.50f))
        assertTrue(PlaybackSpeed.isValid(3.00f))
    }

    @Test
    fun isValidRejectsNonfiniteAndOutOfRangeValues() {
        assertFalse(PlaybackSpeed.isValid(0.49f))
        assertFalse(PlaybackSpeed.isValid(0.0f))
        assertFalse(PlaybackSpeed.isValid(-1.0f))
        assertFalse(PlaybackSpeed.isValid(3.01f))
        assertFalse(PlaybackSpeed.isValid(10.0f))

        assertFalse(PlaybackSpeed.isValid(Float.NaN))
        assertFalse(PlaybackSpeed.isValid(Float.POSITIVE_INFINITY))
        assertFalse(PlaybackSpeed.isValid(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun sanitizeReturnsSpeedWhenValidOrFallsBackToDefault() {
        assertEquals(1.25f, PlaybackSpeed.sanitize(1.25f), 0.001f)
        assertEquals(0.50f, PlaybackSpeed.sanitize(0.50f), 0.001f)
        assertEquals(3.00f, PlaybackSpeed.sanitize(3.00f), 0.001f)

        assertEquals(1.00f, PlaybackSpeed.sanitize(null), 0.001f)
        assertEquals(1.00f, PlaybackSpeed.sanitize(0.20f), 0.001f)
        assertEquals(1.00f, PlaybackSpeed.sanitize(4.00f), 0.001f)
        assertEquals(1.00f, PlaybackSpeed.sanitize(Float.NaN), 0.001f)
        assertEquals(1.00f, PlaybackSpeed.sanitize(Float.POSITIVE_INFINITY), 0.001f)
    }

    @Test
    fun resolvePrioritizesRecentWhenValid() {
        assertEquals(1.75f, PlaybackSpeed.resolve(recentSpeed = 1.75f, savedSpeed = 1.25f, defaultSpeed = 2.00f), 0.001f)
        assertEquals(0.50f, PlaybackSpeed.resolve(recentSpeed = 0.50f, savedSpeed = 1.00f, defaultSpeed = 1.50f), 0.001f)
        assertEquals(3.00f, PlaybackSpeed.resolve(recentSpeed = 3.00f, savedSpeed = 2.00f, defaultSpeed = 1.00f), 0.001f)
    }

    @Test
    fun resolveInvalidRecentDoesNotHideValidSaved() {
        // Higher-priority recent is null -> saved wins
        assertEquals(1.25f, PlaybackSpeed.resolve(recentSpeed = null, savedSpeed = 1.25f, defaultSpeed = 2.00f), 0.001f)
        // Higher-priority recent is NaN -> saved wins
        assertEquals(1.25f, PlaybackSpeed.resolve(recentSpeed = Float.NaN, savedSpeed = 1.25f, defaultSpeed = 2.00f), 0.001f)
        // Higher-priority recent is out of range low -> saved wins
        assertEquals(1.25f, PlaybackSpeed.resolve(recentSpeed = 0.10f, savedSpeed = 1.25f, defaultSpeed = 2.00f), 0.001f)
        // Higher-priority recent is out of range high -> saved wins
        assertEquals(1.25f, PlaybackSpeed.resolve(recentSpeed = 5.00f, savedSpeed = 1.25f, defaultSpeed = 2.00f), 0.001f)
        // Higher-priority recent is infinity -> saved wins
        assertEquals(1.25f, PlaybackSpeed.resolve(recentSpeed = Float.POSITIVE_INFINITY, savedSpeed = 1.25f, defaultSpeed = 2.00f), 0.001f)
    }

    @Test
    fun resolveInvalidSavedDoesNotHideValidDefault() {
        // Both recent and saved null -> default wins
        assertEquals(2.00f, PlaybackSpeed.resolve(recentSpeed = null, savedSpeed = null, defaultSpeed = 2.00f), 0.001f)
        // Recent invalid and saved invalid -> default wins
        assertEquals(2.00f, PlaybackSpeed.resolve(recentSpeed = Float.NaN, savedSpeed = Float.NaN, defaultSpeed = 2.00f), 0.001f)
        assertEquals(2.00f, PlaybackSpeed.resolve(recentSpeed = 0.20f, savedSpeed = 4.00f, defaultSpeed = 2.00f), 0.001f)
        assertEquals(2.00f, PlaybackSpeed.resolve(recentSpeed = null, savedSpeed = 0.10f, defaultSpeed = 2.00f), 0.001f)
        assertEquals(2.00f, PlaybackSpeed.resolve(recentSpeed = null, savedSpeed = Float.POSITIVE_INFINITY, defaultSpeed = 2.00f), 0.001f)
    }

    @Test
    fun resolveFallsBackToInitialDefaultWhenAllAreInvalid() {
        assertEquals(1.00f, PlaybackSpeed.resolve(recentSpeed = null, savedSpeed = null, defaultSpeed = null), 0.001f)
        assertEquals(1.00f, PlaybackSpeed.resolve(recentSpeed = null, savedSpeed = null, defaultSpeed = Float.NaN), 0.001f)
        assertEquals(1.00f, PlaybackSpeed.resolve(recentSpeed = 0.10f, savedSpeed = 4.00f, defaultSpeed = 0.20f), 0.001f)
    }
}
