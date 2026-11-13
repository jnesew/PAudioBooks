package io.github.jnesew.paudiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTimeTest {
    @Test fun estimatesKeepMinuteUnitsWithoutOverflowOrDayWrapping() {
        assertEquals(0L, listeningMinutes(-1))
        assertEquals(0L, listeningMinutes(1))
        assertEquals(1L, listeningMinutes(60_000))
        assertEquals(1L, listeningMinutes(60_001))
        assertEquals(1500L, listeningMinutes(25L * 60 * 60_000 + 1))
        assertEquals(153722867280912L, listeningMinutes(Long.MAX_VALUE))
        assertEquals("25:00:00", mediaTime(25L * 60 * 60_000))
    }
}
