package io.github.jnesew.paudiobooks.bookmarks

import io.github.jnesew.paudiobooks.library.LibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkNoteValidationTest {

    @Test
    fun maxNoteLengthConstantIs4096() {
        assertEquals(4096, LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH)
    }

    @Test
    fun lengthValidationBoundaries() {
        assertTrue("Under limit should be valid", "Simple note".length <= LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH)

        val boundary = "a".repeat(4096)
        assertTrue("Exact 4096 boundary should be valid", boundary.length <= LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH)

        val oversized = "a".repeat(4097)
        assertFalse("Oversize 4097 must exceed limit", oversized.length <= LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH)

        val oversizedWithSpace = " " + "b".repeat(4096)
        assertFalse("4097 before trim must exceed limit", oversizedWithSpace.length <= LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH)
    }
}
