package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.library.BookSummary
import java.text.Normalizer
import java.util.Locale

internal enum class BookFilter { ALL, IN_PROGRESS, UNPLAYED, FINISHED }
internal enum class BookSort { RECENT, TITLE, AUTHOR, ADDED }

private val combiningMarks = "\\p{M}+".toRegex()
internal fun librarySearchKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(combiningMarks, "").lowercase(Locale.ROOT)

/** Recently listened first, then a stable, case- and accent-insensitive title order. */
internal val recentBookOrder: Comparator<BookSummary> =
    compareByDescending<BookSummary> { it.progress?.updatedAt ?: 0L }
        .thenBy { librarySearchKey(it.book.displayTitle) }
        .thenBy { it.book.id }

/** Library selection rules shared by list and grid; no Compose state or storage work. */
internal fun filterBooks(
    books: List<BookSummary>,
    query: String,
    filter: BookFilter,
    sort: BookSort
): List<BookSummary> {
    val key = librarySearchKey(query)
    return books.filter { summary ->
        val progress = summary.progress
        val position = summary.positionMs ?: progress?.offsetMs ?: 0L
        val matchesStatus = when (filter) {
            BookFilter.ALL -> true
            BookFilter.IN_PROGRESS -> progress != null && !progress.finished && position > 0
            BookFilter.UNPLAYED -> progress == null || (!progress.finished && position == 0L)
            BookFilter.FINISHED -> progress?.finished == true
        }
        matchesStatus && listOf(
            summary.book.displayTitle,
            summary.book.displayAuthor,
            summary.book.displayNarrator,
            summary.book.displaySeries,
            summary.book.displayTranslator,
            summary.book.displaySeriesNumber
        ).any { it != null && librarySearchKey(it).contains(key) }
    }.sortedWith(when (sort) {
        BookSort.RECENT -> recentBookOrder
        BookSort.TITLE -> compareBy<BookSummary> { librarySearchKey(it.book.displayTitle) }.thenBy { it.book.id }
        BookSort.AUTHOR -> compareBy<BookSummary> { librarySearchKey(it.book.displayAuthor.orEmpty()) }
            .thenBy { librarySearchKey(it.book.displayTitle) }.thenBy { it.book.id }
        BookSort.ADDED -> compareByDescending<BookSummary> { it.book.addedAt }.thenBy { it.book.id }
    })
}
