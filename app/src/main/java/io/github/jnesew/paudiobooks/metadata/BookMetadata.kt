package io.github.jnesew.paudiobooks.metadata

/** Bounded embedded book fields. Dates retain their tagged precision; description is inert text. */
data class BookMetadata(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val translator: String? = null,
    val series: String? = null,
    val seriesNumber: String? = null,
    val genre: String? = null,
    val publisher: String? = null,
    val date: String? = null,
    val releaseDate: String? = null,
    val description: String? = null,
    val authorSource: String? = null,
    val narratorSource: String? = null,
    val translatorSource: String? = null,
    val seriesSource: String? = null,
) {
    fun fields(): List<String?> = listOf(title, author, narrator, translator, series, seriesNumber,
        genre, publisher, date, releaseDate, description, authorSource, narratorSource, translatorSource, seriesSource)
    fun textLength(): Long = fields().sumOf { it?.length?.toLong() ?: 0 }
    fun isBounded(): Boolean = fields().all { it == null || it.length <= InspectionLimits.FIELD_CHARS }
}

/** Missing fields do not vote. Conflicting nonempty values stay unknown, independent of file order. */
fun agreedBookMetadata(input: List<BookMetadata>): BookMetadata {
    fun agree(field: (BookMetadata) -> String?): String? = input.mapNotNull(field).distinct().singleOrNull()
    return BookMetadata(agree { it.title }, agree { it.author }, agree { it.narrator }, agree { it.translator },
        agree { it.series }, agree { it.seriesNumber }, agree { it.genre }, agree { it.publisher },
        agree { it.date }, agree { it.releaseDate }, agree { it.description })
}

fun suggestedBookTitle(metadata: List<BookMetadata>, trackTitles: List<String?>, fallback: String): String =
    (agreedBookMetadata(metadata).title ?: trackTitles.mapNotNull(::normalizeField).distinct().singleOrNull() ?: fallback).take(512)
