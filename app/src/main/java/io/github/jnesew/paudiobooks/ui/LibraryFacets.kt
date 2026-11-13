package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.library.BookSummary
import java.text.Normalizer
import java.util.Locale

internal enum class LibraryFacet { GENRE, AUTHOR }
internal data class LibraryFacetOption(val key: String, val label: String?, val count: Int)
internal const val MISSING_FACET = "missing"
internal const val MAX_GENRES_PER_BOOK = 64
internal const val MAX_AUTHORS_PER_BOOK = 32
private val spaces = "\\s+".toRegex()

/** Normalize display labels without rewriting the imported metadata. */
internal fun facetLabel(value: String?): String? = value?.trim()?.replace(spaces, " ")?.takeIf { it.isNotEmpty() }
internal fun facetKey(value: String?): String = facetLabel(value)?.let {
    "value:" + Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
} ?: MISSING_FACET
/** Flat metadata cannot distinguish every surname-first credit from an author list. */
private fun authorLabels(value: String?): List<String?> {
    val label = facetLabel(value) ?: return listOf(null)
    val parts = label.splitToSequence(',').mapNotNull(::facetLabel).toList()
    if (parts.isEmpty()) return listOf(null)
    val names = if (parts.size > 1 && parts.all { it.split(' ').size >= 2 }) parts else listOf(label)
    return names.distinctBy(::facetKey).take(MAX_AUTHORS_PER_BOOK)
}
internal fun BookSummary.facetValues(field: LibraryFacet): List<String?> = when (field) {
    LibraryFacet.GENRE -> book.importedGenre.orEmpty().splitToSequence(',').mapNotNull(::facetLabel)
        .distinctBy(::facetKey).take(MAX_GENRES_PER_BOOK).toList().takeIf { it.isNotEmpty() } ?: listOf(null)
    LibraryFacet.AUTHOR -> authorLabels(book.displayAuthor)
}
private fun BookSummary.facetKeys(field: LibraryFacet) = facetValues(field).map(::facetKey).toSet()
internal fun filterBookFacets(books: List<BookSummary>, genres: Set<String>, authors: Set<String>): List<BookSummary> =
    books.filter { (genres.isEmpty() || it.facetKeys(LibraryFacet.GENRE).any(genres::contains)) &&
        (authors.isEmpty() || it.facetKeys(LibraryFacet.AUTHOR).any(authors::contains)) }

/** Choices come from the whole library; counts respect search/status and the other facet. */
internal fun libraryFacetOptions(
    books: List<BookSummary>, base: List<BookSummary>, field: LibraryFacet,
    genres: Set<String>, authors: Set<String>
): List<LibraryFacetOption> {
    val labels = linkedMapOf<String, String?>()
    books.forEach { book ->
        book.facetValues(field).forEach { value ->
            val key = facetKey(value)
            if (key !in labels || value != null && value < labels[key].orEmpty()) labels[key] = value
        }
    }
    val selected = if (field == LibraryFacet.GENRE) genres else authors
    // A removed book must not leave an invisible filter that cannot be unchecked.
    selected.forEach { if (it !in labels) labels[it] = it.takeIf { it != MISSING_FACET }?.removePrefix("value:") }
    val eligible = filterBookFacets(base, if (field == LibraryFacet.GENRE) emptySet() else genres,
        if (field == LibraryFacet.AUTHOR) emptySet() else authors)
    val counts = mutableMapOf<String, Int>()
    eligible.forEach { book -> book.facetKeys(field).forEach { key -> counts[key] = (counts[key] ?: 0) + 1 } }
    return labels.map { (key, label) -> LibraryFacetOption(key, label, counts[key] ?: 0) }
        .sortedWith(compareBy<LibraryFacetOption> { it.key == MISSING_FACET }
            .thenBy { librarySearchKey(it.label.orEmpty()) }.thenBy { it.key })
}
