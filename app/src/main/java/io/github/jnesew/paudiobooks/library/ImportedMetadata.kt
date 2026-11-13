package io.github.jnesew.paudiobooks.library

import io.github.jnesew.paudiobooks.importing.ImportCoordinator
import io.github.jnesew.paudiobooks.metadata.agreedBookMetadata

/** Caller owns a Room transaction. Recompute from durable per-track discoveries across scan passes. */
internal suspend fun LibraryDatabase.refreshImportedMetadata(bookId: String, cover: String? = null) {
    val book = requireNotNull(books().get(bookId))
    val fields = library().tracks(bookId).mapNotNull { it.metadata }
    require(fields.all { it.isBounded() } && fields.sumOf { it.textLength() } <= ImportCoordinator.MAX_BOOK_METADATA_TEXT)
    val agreed = agreedBookMetadata(fields)
    // Migrated libraries lack per-file provenance; retain their existing imported values as well as all edits.
    fun value(old: String?, incoming: String?) = if (book.metadataManaged) incoming else old ?: incoming
    books().updateMetadata(book.copy(
        importedTitle=value(book.importedTitle, agreed.title), importedAuthor=value(book.importedAuthor, agreed.author),
        importedNarrator=value(book.importedNarrator, agreed.narrator), importedTranslator=value(book.importedTranslator, agreed.translator),
        importedSeries=value(book.importedSeries, agreed.series), importedSeriesNumber=value(book.importedSeriesNumber, agreed.seriesNumber),
        importedGenre=value(book.importedGenre, agreed.genre), importedPublisher=value(book.importedPublisher, agreed.publisher),
        importedDate=value(book.importedDate, agreed.date), importedReleaseDate=value(book.importedReleaseDate, agreed.releaseDate),
        importedDescription=value(book.importedDescription, agreed.description), coverPath=book.coverPath ?: cover
    ))
}
