package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookSummary
import org.junit.Assert.*
import org.junit.Test

class LibraryFacetsTest {
    private fun book(id: String, genre: String?, author: String? = "Ada") =
        BookSummary(Book(id, id, importedGenre = genre, importedAuthor = author), null, null, 1, null)
    @Test fun genreUnionAndAuthorIntersectionPreserveOrder() {
        val books = listOf(book("b", "Fantasy"), book("c", "Mystery", "Bea"), book("a", "Mystery"), book("d", "History"))
        val genres = setOf(facetKey("Fantasy"), facetKey("Mystery"))
        assertEquals(listOf("b", "c", "a"), filterBookFacets(books, genres, emptySet()).map { it.book.id })
        assertEquals(listOf("b", "a"), filterBookFacets(books, genres, setOf(facetKey("Ada"))).map { it.book.id })
        assertEquals(books, filterBookFacets(books, emptySet(), emptySet()))
    }
    @Test fun labelsNormalizeWhitespaceCaseAndCanonicalUnicodeWithoutGuessingSeparators() {
        assertEquals(facetKey("  Fantasy \n"), facetKey("FANTASY"))
        assertEquals(facetKey("Mémoire"), facetKey("Me\u0301moire"))
        assertNotEquals(facetKey("Mémoire"), facetKey("Memoire"))
        assertNotEquals(facetKey("Fantasy, Mystery"), facetKey("Fantasy"))
        assertNotEquals(facetKey("Doe, Jane"), facetKey("Jane"))
    }
    @Test fun missingValuesCannotCollideWithLiteralNoGenreTag() {
        val books = listOf(book("blank", "  "), book("literal", "No genre"))
        assertEquals(listOf("blank"), filterBookFacets(books, setOf(MISSING_FACET), emptySet()).map { it.book.id })
        assertEquals(2, libraryFacetOptions(books, books, LibraryFacet.GENRE, emptySet(), emptySet()).size)
    }
    @Test fun newMetadataLabelsAppearAutomaticallyAndCaseDuplicatesCountBooksOnce() {
        val before = listOf(book("a", "Fantasy"), book("b", "fantasy"))
        val after = before + book("c", "New imported genre")
        val options = libraryFacetOptions(after, after, LibraryFacet.GENRE, emptySet(), emptySet())
        assertEquals(2, options.size)
        assertEquals(2, options.single { it.key == facetKey("Fantasy") }.count)
        assertEquals(1, options.single { it.key == facetKey("New imported genre") }.count)
    }
    @Test fun countsIgnoreOwnFacetAndRespectOtherFacetAndSearchStatusBase() {
        val books = listOf(book("a", "Fantasy"), book("b", "Mystery"), book("c", "Fantasy", "Bea"), book("d", "History"))
        val base = books.dropLast(1)
        val genres = setOf(facetKey("Fantasy")); val authors = setOf(facetKey("Ada"))
        val genreCounts = libraryFacetOptions(books, base, LibraryFacet.GENRE, genres, authors).associate { it.key to it.count }
        assertEquals(1, genreCounts[facetKey("Fantasy")]); assertEquals(1, genreCounts[facetKey("Mystery")]); assertEquals(0, genreCounts[facetKey("History")])
        val authorCounts = libraryFacetOptions(books, base, LibraryFacet.AUTHOR, genres, authors).associate { it.key to it.count }
        assertEquals(1, authorCounts[facetKey("Ada")]); assertEquals(1, authorCounts[facetKey("Bea")])
    }
    @Test fun removedSelectedChoiceRemainsVisibleWithZeroCount() {
        val books = listOf(book("a", "Fantasy"))
        val key = facetKey("Removed genre")
        assertEquals(0, libraryFacetOptions(books, books, LibraryFacet.GENRE, setOf(key), emptySet()).single { it.key == key }.count)
    }
    @Test fun authorOverridesSupplyChoicesAndMatching() {
        val old = book("a", "History", "Original")
        val edited = old.copy(book = old.book.copy(authorOverride = "Écho"))
        val options = libraryFacetOptions(listOf(edited), listOf(edited), LibraryFacet.AUTHOR, emptySet(), emptySet())
        assertEquals("Écho", options.single().label)
        assertEquals(listOf(edited), filterBookFacets(listOf(edited), emptySet(), setOf(facetKey("Écho"))))
        assertEquals("echo", librarySearchKey(options.single().label!!))
    }
    @Test fun commaSeparatedGenresPreservePeriodsAmpersandsAndAuthorNames() {
        val book = book("a", "Action & Adventure, Epic, Dark Fantasy, Historical, Sci.Fi.", "Doe, Jane")
        assertEquals(listOf("Action & Adventure", "Epic", "Dark Fantasy", "Historical", "Sci.Fi."), book.facetValues(LibraryFacet.GENRE))
        assertEquals(listOf("Doe, Jane"), book.facetValues(LibraryFacet.AUTHOR))
        assertEquals(listOf(book), filterBookFacets(listOf(book), setOf(facetKey("Epic")), setOf(facetKey("Doe, Jane"))))
    }
    @Test fun multigenreBooksCountOncePerGenreAndOnceInUnionResults() {
        val books = listOf(book("a", "Fantasy, fantasy, Mystery,, "), book("b", "Mystery"))
        val selected = setOf(facetKey("Fantasy"), facetKey("Mystery"))
        assertEquals(books, filterBookFacets(books, selected, emptySet()))
        val options = libraryFacetOptions(books, books, LibraryFacet.GENRE, selected, emptySet()).associate { it.key to it.count }
        assertEquals(2, options.size); assertEquals(1, options[facetKey("Fantasy")]); assertEquals(2, options[facetKey("Mystery")])
    }
    @Test fun emptyCommaPartsAreIgnoredAndDerivedGenresStayBounded() {
        assertEquals(listOf(null), book("empty", ", ,,, ").facetValues(LibraryFacet.GENRE))
        val many = book("many", (1..100).joinToString(",") { "Genre $it" }).facetValues(LibraryFacet.GENRE)
        assertEquals(MAX_GENRES_PER_BOOK, many.size)
        assertEquals("Genre 1", many.first()); assertEquals("Genre 64", many.last())
    }
    @Test fun commaSeparatedFullAuthorNamesSplitButSurnameFirstAndShortCreditsStayGrouped() {
        assertEquals(listOf("Jane Doe", "John Smith"), book("a", null, "Jane Doe, John Smith").facetValues(LibraryFacet.AUTHOR))
        assertEquals(listOf("Doe, Jane"), book("b", null, "Doe, Jane").facetValues(LibraryFacet.AUTHOR))
        assertEquals(listOf("Plato, Aristotle"), book("c", null, "Plato, Aristotle").facetValues(LibraryFacet.AUTHOR))
        assertEquals(listOf("J.R.R. Tolkien", "C.S. Lewis"), book("d", null, "J.R.R. Tolkien, C.S. Lewis").facetValues(LibraryFacet.AUTHOR))
        assertEquals(listOf("John Smith, Jr."), book("e", null, "John Smith, Jr.").facetValues(LibraryFacet.AUTHOR))
    }
    @Test fun multiauthorUnionCountsSharedBooksOnceAndRespectsTheVisibleOverride() {
        val books=listOf(book("a", "Fantasy", "Jane Doe, John Smith, jane doe"),book("b", "History", "Jane Doe"))
        val selected=setOf(facetKey("Jane Doe"),facetKey("John Smith"))
        assertEquals(books,filterBookFacets(books,emptySet(),selected))
        val options=libraryFacetOptions(books,books,LibraryFacet.AUTHOR,emptySet(),selected).associate { it.key to it.count }
        assertEquals(2,options.size);assertEquals(2,options[facetKey("Jane Doe")]);assertEquals(1,options[facetKey("John Smith")])
        val edited=books[0].copy(book=books[0].book.copy(authorOverride="Ada Reed, Mira Vale"))
        assertEquals(listOf("Ada Reed","Mira Vale"),edited.facetValues(LibraryFacet.AUTHOR))
        assertEquals(MAX_AUTHORS_PER_BOOK,book("many",null,(1..100).joinToString(",") { "Writer $it" }).facetValues(LibraryFacet.AUTHOR).size)
    }
}
