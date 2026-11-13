package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookSummary
import io.github.jnesew.paudiobooks.library.ReadingState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderingTest {
    @Test fun recentSortHandlesPlayedAndUnplayedBooksWithStableTies() {
        fun book(id: String, title: String, updated: Long?) = BookSummary(
            Book(id, title), null,
            updated?.let { ReadingState(id, "track-$id", 0, 1f, false, it) }, 1, null)
        val books = listOf(book("d", "Zulu", null), book("b", "Écho", 10),
            book("c", "Alpha", null), book("a", "echo", 10), book("e", "Later", 20))
        assertEquals(listOf("e", "a", "b", "c", "d"), books.sortedWith(recentBookOrder).map { it.book.id })
    }

    @Test fun searchUsesVisibleOverridesAndIgnoresCaseAndAccents() {
        val book = BookSummary(Book("b", "Original", importedNarrator = "Hidden narrator",
            titleOverride = "Écho", narratorOverride = "", importedSeries = "Série"), null, null, 1, null)
        fun search(query: String) = filterBooks(listOf(book), query, BookFilter.ALL, BookSort.TITLE)
        assertEquals(listOf(book), search("ECHO"))
        assertEquals(listOf(book), search("serie"))
        assertEquals(emptyList<BookSummary>(), search("Original"))
        assertEquals(emptyList<BookSummary>(), search("Hidden narrator"))
    }

    @Test fun filtersUseWholeBookPositionAndKeepFinishedBooksSeparate() {
        fun book(id: String, position: Long?, finished: Boolean = false) = BookSummary(
            Book(id, id), 1000,
            position?.let { ReadingState(id, "track-$id", 0, 1f, finished, 1) }, 2, position)
        val books = listOf(book("new", null), book("reset", 0), book("reading", 500), book("done", 1000, true))
        fun ids(filter: BookFilter) = filterBooks(books, "", filter, BookSort.TITLE).map { it.book.id }
        assertEquals(listOf("new", "reset"), ids(BookFilter.UNPLAYED))
        assertEquals(listOf("reading"), ids(BookFilter.IN_PROGRESS))
        assertEquals(listOf("done"), ids(BookFilter.FINISHED))
    }

}
