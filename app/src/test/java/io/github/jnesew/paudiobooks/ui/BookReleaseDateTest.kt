package io.github.jnesew.paudiobooks.ui

import org.junit.Assert.*
import org.junit.Test

class BookReleaseDateTest {
    @Test fun explicitIsoDatesRetainTheirOriginalPrecision() {
        assertEquals("y",bookReleaseDate("2021")!!.skeleton)
        assertEquals("yMMMM",bookReleaseDate("2021-06")!!.skeleton)
        assertEquals("yMMMd",bookReleaseDate("2021-06-10")!!.skeleton)
        assertEquals(10,bookReleaseDate("2021-06-10")!!.date.dayOfMonth)
    }
    @Test fun impossibleOrAmbiguousDatesStayHidden() {
        for (value in listOf(null,"","June 2021","06/10/2021","2021-2","0000","2021-13","2021-02-29","2021-06-10T12:00:00Z")) {
            assertNull(value,bookReleaseDate(value))
        }
    }
    @Test fun leapDaysAndBoundaryYearsAreValidated() {
        assertNotNull(bookReleaseDate("2020-02-29"))
        assertNotNull(bookReleaseDate("0001"))
        assertNotNull(bookReleaseDate("9999-12-31"))
        assertNull(bookReleaseDate("10000"))
    }
}
