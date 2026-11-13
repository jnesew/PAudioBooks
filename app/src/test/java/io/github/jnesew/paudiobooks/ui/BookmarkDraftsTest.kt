package io.github.jnesew.paudiobooks.ui

import org.junit.Assert.*
import org.junit.Test

class BookmarkDraftsTest {
    @Test fun reopeningRetainsOriginalAnchorAndOperationIdentityAfterPlaybackMoves() {
        val drafts = BookmarkDrafts()
        drafts.begin("book", "first", 12_345)
        drafts.change("book", "A retained note")
        val original = drafts.newBookmarks.value.getValue("book")
        drafts.begin("book", "second", 90_000)
        assertEquals(original, drafts.newBookmarks.value.getValue("book"))
        drafts.clear("book", original.id)
        drafts.begin("book", "second", 90_000)
        val replacement = drafts.newBookmarks.value.getValue("book")
        assertNotEquals(original.id, replacement.id)
        assertEquals("", replacement.note)
        assertEquals("second", replacement.trackId)
        assertEquals(90_000L, replacement.positionMs)
        drafts.clear("book", original.id)
        assertEquals(replacement, drafts.newBookmarks.value["book"])
    }

    @Test fun notesAreIsolatedUntilExplicitSaveOrCancelClearsTheirDraft() {
        val drafts = BookmarkDrafts()
        drafts.changeNote("first", "Draft")
        assertEquals("Draft", drafts.note("first", "Original"))
        assertEquals("Other", drafts.note("second", "Other"))
        drafts.clearNote("first")
        assertEquals("Original", drafts.note("first", "Original"))
    }
}
