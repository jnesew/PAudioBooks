package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.library.Book
import org.junit.Assert.*
import org.junit.Test

class MetadataEditTest {
    @Test fun changingOneFieldDoesNotFreezeOrTruncateOtherImportedCredits() {
        val longCredit = "A narrator ".repeat(200)
        assertNull(metadataOverride(longCredit, longCredit, null))
        assertEquals("Edited translator", metadataOverride(" Edited translator ", "Original translator", null))
        assertEquals("Old override", metadataOverride("Old override", "Old override", "Old override"))
    }
    @Test fun clearingAnOptionalCreditMasksTheTagUntilEditsAreReset() {
        val hidden = metadataOverride("", "Imported narrator", null)
        assertEquals("", hidden)
        val book = Book("b","Book",importedNarrator="Imported narrator",narratorOverride=hidden)
        assertNull(book.displayNarrator)
        assertEquals("", metadataOverride("", book.displayNarrator, hidden))
        assertEquals("Imported narrator", book.copy(narratorOverride=null).displayNarrator)
    }
}
