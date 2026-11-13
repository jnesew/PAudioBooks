package io.github.jnesew.paudiobooks.bookmarks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.Bookmark
import io.github.jnesew.paudiobooks.library.BookTrack
import io.github.jnesew.paudiobooks.library.LibraryDatabase
import io.github.jnesew.paudiobooks.library.LibraryRepository
import io.github.jnesew.paudiobooks.library.ReadingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkNoteRepositoryTest {

    private fun setupTestDatabase(): Pair<LibraryDatabase, LibraryRepository> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repo = LibraryRepository(db)
        return db to repo
    }

    private suspend fun insertTestBook(db: LibraryDatabase, bookId: String, title: String) {
        db.books().insert(Book(bookId, title))
        db.library().insertAssets(listOf(
            AudioAsset("asset_$bookId", "media/$bookId.mp3", 1024, "a".repeat(64), 60_000)
        ))
        db.library().insertTracks(listOf(
            BookTrack("track_$bookId", bookId, "asset_$bookId", 0, "Track 1")
        ))
        db.library().saveProgress(
            ReadingState(bookId, "track_$bookId", 12_345, 1.25f, false, 1_000_000L)
        )
    }

    @Test
    fun validEditUpdatesNoteAndPreservesIdentity() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book_1", "Test Book")
            val originalCreatedAt = 987_654_321L
            val originalOffsetMs = 5_000L
            val bookmark = Bookmark("mark_1", "book_1", "track_book_1", originalOffsetMs, "Initial note", originalCreatedAt)
            db.library().insertBookmark(bookmark)

            val success = repo.updateBookmarkNote("book_1", "mark_1", "Updated note text")
            assertTrue("Expected updateBookmarkNote to return true on success", success)

            val updated = db.library().bookmarks("book_1").single()
            assertEquals("mark_1", updated.id)
            assertEquals("book_1", updated.bookId)
            assertEquals("track_book_1", updated.trackId)
            assertEquals(originalOffsetMs, updated.offsetMs)
            assertEquals(originalCreatedAt, updated.createdAt)
            assertEquals("Updated note text", updated.note)
        } finally {
            db.close()
        }
    }

    @Test
    fun blankAndWhitespaceClearsNoteToNullWithoutDeletingBookmark() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book_1", "Test Book")
            val bookmark = Bookmark("mark_1", "book_1", "track_book_1", 3_000L, "Non-empty note", 100L)
            db.library().insertBookmark(bookmark)

            // Test whitespace-only string
            val successBlank = repo.updateBookmarkNote("book_1", "mark_1", "   \t \n  ")
            assertTrue(successBlank)

            var marks = db.library().bookmarks("book_1")
            assertEquals("Bookmark must not be deleted when cleared", 1, marks.size)
            assertNull("Blank note must be cleared to null", marks.single().note)

            // Test empty string
            db.library().updateBookmarkNote("book_1", "mark_1", "Another note")
            assertEquals("Another note", db.library().bookmarks("book_1").single().note)

            val successEmpty = repo.updateBookmarkNote("book_1", "mark_1", "")
            assertTrue(successEmpty)
            marks = db.library().bookmarks("book_1")
            assertEquals(1, marks.size)
            assertNull("Empty string note must be cleared to null", marks.single().note)

            // Test null
            db.library().updateBookmarkNote("book_1", "mark_1", "Yet another note")
            val successNull = repo.updateBookmarkNote("book_1", "mark_1", null)
            assertTrue(successNull)
            marks = db.library().bookmarks("book_1")
            assertEquals(1, marks.size)
            assertNull("Null note must be cleared to null", marks.single().note)
        } finally {
            db.close()
        }
    }

    @Test
    fun boundary4096CharactersAcceptedAndPreserved() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book_1", "Test Book")
            db.library().insertBookmark(Bookmark("mark_1", "book_1", "track_book_1", 1_000L, null, 100L))

            val boundaryText = "x".repeat(4096)
            val success = repo.updateBookmarkNote("book_1", "mark_1", boundaryText)
            assertTrue(success)

            val mark = db.library().bookmarks("book_1").single()
            assertEquals(boundaryText, mark.note)
            assertEquals(4096, mark.note?.length)
        } finally {
            db.close()
        }
    }

    @Test
    fun oversizedInputThrowsBeforeTrimAndLeavesBookmarkUntouched() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book_1", "Test Book")
            db.library().insertBookmark(Bookmark("mark_1", "book_1", "track_book_1", 1_000L, "Keep me", 100L))

            val oversized = "a".repeat(4097)
            try {
                repo.updateBookmarkNote("book_1", "mark_1", oversized)
                fail("Expected IllegalArgumentException for note exceeding 4096 chars")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            // Verify before-trim check: 4096 characters plus leading whitespace = 4097 chars
            val oversizedWithSpace = " " + "b".repeat(4096)
            try {
                repo.updateBookmarkNote("book_1", "mark_1", oversizedWithSpace)
                fail("Length validation must occur before trim")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            // Original bookmark note must remain unchanged
            val mark = db.library().bookmarks("book_1").single()
            assertEquals("Keep me", mark.note)
        } finally {
            db.close()
        }
    }

    @Test
    fun wrongBookOrMissingIdFailsSafely() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book_1", "Book One")
            insertTestBook(db, "book_2", "Book Two")
            db.library().insertBookmark(Bookmark("mark_1", "book_1", "track_book_1", 2_000L, "Book 1 note", 100L))

            // Non-existent bookmark ID
            val missingSuccess = repo.updateBookmarkNote("book_1", "non_existent_mark", "New note")
            assertFalse("Non-existent bookmark ID must fail safely", missingSuccess)

            // Wrong book ID for existing bookmark
            val wrongBookSuccess = repo.updateBookmarkNote("book_2", "mark_1", "Cross-book edit")
            assertFalse("Wrong book ID must fail safely", wrongBookSuccess)

            // Book 1 bookmark note remains untouched
            assertEquals("Book 1 note", db.library().bookmarks("book_1").single().note)
            assertTrue("Book 2 must have no bookmarks", db.library().bookmarks("book_2").isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun progressAndOtherBookmarksUnaffectedByNoteEdit() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book_1", "Book One")
            val originalProgress = db.library().progress("book_1")
            assertNotNull(originalProgress)

            db.library().insertBookmark(Bookmark("mark_1", "book_1", "track_book_1", 1_000L, "Note 1", 100L))
            db.library().insertBookmark(Bookmark("mark_2", "book_1", "track_book_1", 2_000L, "Note 2", 200L))

            val success = repo.updateBookmarkNote("book_1", "mark_1", "Edited Note 1")
            assertTrue(success)

            // Check reading progress
            val currentProgress = db.library().progress("book_1")
            assertEquals(originalProgress!!.bookId, currentProgress!!.bookId)
            assertEquals(originalProgress.trackId, currentProgress.trackId)
            assertEquals(originalProgress.offsetMs, currentProgress.offsetMs)
            assertEquals(originalProgress.speed, currentProgress.speed, 0.001f)
            assertEquals(originalProgress.finished, currentProgress.finished)
            assertEquals(originalProgress.updatedAt, currentProgress.updatedAt)

            // Check other bookmark
            val mark2 = db.library().bookmarks("book_1").first { it.id == "mark_2" }
            assertEquals("Note 2", mark2.note)
            assertEquals(2_000L, mark2.offsetMs)
            assertEquals(200L, mark2.createdAt)
        } finally {
            db.close()
        }
    }
    @Test fun retriedNewBookmarkKeepsIdentityAnchorAndTimestampWithoutMergingDistinctDrafts() = runBlocking {
        val (db, repo) = setupTestDatabase()
        try {
            insertTestBook(db, "book", "Book")
            val id = java.util.UUID.randomUUID().toString()
            assertEquals(id, repo.addBookmark("book", "track_book", 1234, "Draft", id))
            val original = db.library().bookmark(id)
            assertNotNull(original)
            assertEquals(id, repo.addBookmark("book", "track_book", 1234, "Draft", id))
            assertEquals(listOf(original), db.library().bookmarks("book"))
            assertNull(repo.addBookmark("book", "track_book", 1235, "Draft", id))
            assertNull(repo.addBookmark("book", "track_book", 1234, "Changed", id))
            assertEquals(original, db.library().bookmark(id))
            assertNotNull(repo.addBookmark("book", "track_book", 1234, "Draft"))
            assertEquals(2, db.library().bookmarks("book").size)
        } finally { db.close() }
    }

}
