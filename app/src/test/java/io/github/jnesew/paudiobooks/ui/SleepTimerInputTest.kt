package io.github.jnesew.paudiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerInputTest {

    @Test
    fun acceptsValidWholeMinutesAtBoundaries() {
        assertEquals(1, SleepTimerInput.parseMinutes("1"))
        assertEquals(60_000L, SleepTimerInput.parseMinutesToMillis("1"))

        assertEquals(1440, SleepTimerInput.parseMinutes("1440"))
        assertEquals(86_400_000L, SleepTimerInput.parseMinutesToMillis("1440"))
    }

    @Test
    fun acceptsStandardPresetsAndIntermediateValues() {
        val cases = listOf(
            "15" to (15 to 900_000L),
            "30" to (30 to 1_800_000L),
            "45" to (45 to 2_700_000L),
            "60" to (60 to 3_600_000L),
            "90" to (90 to 5_400_000L),
            "120" to (120 to 7_200_000L),
            "720" to (720 to 43_200_000L)
        )
        for ((input, expected) in cases) {
            val (minutes, millis) = expected
            assertEquals("Failed for input '$input'", minutes, SleepTimerInput.parseMinutes(input))
            assertEquals("Failed millis for input '$input'", millis, SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun acceptsInputWithTrimmedOrdinaryWhitespace() {
        assertEquals(1, SleepTimerInput.parseMinutes("  1  "))
        assertEquals(60_000L, SleepTimerInput.parseMinutesToMillis("  1  "))

        assertEquals(15, SleepTimerInput.parseMinutes(" 15 "))
        assertEquals(900_000L, SleepTimerInput.parseMinutesToMillis(" 15 "))

        assertEquals(30, SleepTimerInput.parseMinutes("\t30\t"))
        assertEquals(1_800_000L, SleepTimerInput.parseMinutesToMillis("\t30\t"))

        assertEquals(45, SleepTimerInput.parseMinutes("\n45\r\n"))
        assertEquals(2_700_000L, SleepTimerInput.parseMinutesToMillis("\n45\r\n"))

        assertEquals(1440, SleepTimerInput.parseMinutes("   1440 \n"))
        assertEquals(86_400_000L, SleepTimerInput.parseMinutesToMillis("   1440 \n"))
    }

    @Test
    fun rejectsBlankAndEmptyInput() {
        val blankCases = listOf(null, "", " ", "   ", "\t", "\n", "\r\n", " \t \n ")
        for (input in blankCases) {
            assertNull("Expected null for blank '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for blank '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun rejectsZeroAndPaddedZeroes() {
        val zeroCases = listOf("0", "00", "000", " 0 ", "\t00\n")
        for (input in zeroCases) {
            assertNull("Expected null for zero '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for zero '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun rejectsNegativeNumbers() {
        val negativeCases = listOf("-1", "-15", "-60", "-1440", "-0", " -5 ")
        for (input in negativeCases) {
            assertNull("Expected null for negative '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for negative '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun rejectsFractionalNumbers() {
        val fractionalCases = listOf(
            "1.5", "1,5", "0.5", "15.0", "1440.0",
            ".5", "15.", "30,0", "0,25"
        )
        for (input in fractionalCases) {
            assertNull("Expected null for fractional '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for fractional '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun rejectsNonNumericAndMalformedPastedText() {
        val malformedCases = listOf(
            "abc", "15m", "15 min", "15 minutes", "1 hour",
            "1 5", "3 0", "15_0", "+15", "+1", "+30",
            "1e2", "0x10", "1440!", "ten", "1\u00005"
        )
        for (input in malformedCases) {
            assertNull("Expected null for malformed '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for malformed '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun rejectsOversizedAndOutRangeValues() {
        val oversizedCases = listOf(
            "1441", "1442", "1500", "2000", "9999", "100000"
        )
        for (input in oversizedCases) {
            assertNull("Expected null for oversized '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for oversized '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }

    @Test
    fun rejectsOverflowInputsWithoutException() {
        val overflowCases = listOf(
            "${Int.MAX_VALUE.toLong() + 1}",
            "${Long.MAX_VALUE}",
            "${Long.MAX_VALUE}0",
            "99999999999999999999999999999999999999999999999999",
            "123456789012345678901234567890"
        )
        for (input in overflowCases) {
            assertNull("Expected null for overflow '$input'", SleepTimerInput.parseMinutes(input))
            assertNull("Expected null millis for overflow '$input'", SleepTimerInput.parseMinutesToMillis(input))
        }
    }
}
