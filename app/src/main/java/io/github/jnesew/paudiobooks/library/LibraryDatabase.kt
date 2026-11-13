package io.github.jnesew.paudiobooks.library

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Update
import io.github.jnesew.paudiobooks.metadata.BookMetadata
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// title is retained from schema 1; it is the display title, never a key.
@Entity(tableName = "books")
data class Book(@PrimaryKey val id: String, val title: String, val importedTitle: String? = null,
                val importedAuthor: String? = null, val importedNarrator: String? = null, val importedSeries: String? = null,
                val narratorOverride: String? = null, val seriesOverride: String? = null, val coverPath: String? = null,
                val coverOverridePath: String? = null,
                val titleOverride: String? = null,
                val authorOverride: String? = null, @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
                val importedTranslator: String? = null, val importedSeriesNumber: String? = null,
                val translatorOverride: String? = null, val seriesNumberOverride: String? = null,
                val importedGenre: String? = null, val importedPublisher: String? = null,
                val importedDate: String? = null, val importedReleaseDate: String? = null,
                val importedDescription: String? = null,
                @ColumnInfo(defaultValue = "0") val metadataManaged: Boolean = false)

@Entity(tableName = "assets", indices = [Index("sha256")])
data class AudioAsset(@PrimaryKey val id: String, val relativePath: String, val sizeBytes: Long,
                      val sha256: String, val durationMs: Long?, val mimeType: String? = null,
                      @ColumnInfo(defaultValue = "1") val identityVerified: Boolean = true)

@Entity(tableName = "tracks", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = AudioAsset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["bookId", "position"], unique = true), Index("assetId")])
data class BookTrack(@PrimaryKey val id: String, val bookId: String, val assetId: String,
                     val position: Int, val importedTitle: String? = null,
                     @Embedded(prefix = "metadata_") val metadata: BookMetadata? = null)

@Entity(tableName = "chapters", foreignKeys = [ForeignKey(entity = BookTrack::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["trackId", "position"], unique = true)])
data class Chapter(@PrimaryKey val id: String, val trackId: String, val position: Int,
                   val startMs: Long, val endMs: Long?, val title: String?, val origin: String)

@Entity(tableName = "reading_state", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BookTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("trackId")])
data class ReadingState(@PrimaryKey val bookId: String, val trackId: String, val offsetMs: Long,
                        val speed: Float, val finished: Boolean, val updatedAt: Long)

@Entity(tableName = "bookmarks", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BookTrack::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("bookId"), Index("trackId")])
data class Bookmark(@PrimaryKey val id: String, val bookId: String, val trackId: String,
                    val offsetMs: Long, val note: String?, val createdAt: Long)

// The journal is written before creating files. Paths derive only from these UUIDs.
@Entity(tableName = "import_jobs")
data class ImportJob(@PrimaryKey val id: String, val state: String, val createdAt: Long)
@Entity(tableName = "import_files", foreignKeys = [ForeignKey(entity = ImportJob::class,
    parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)], indices = [Index("jobId")])
data class ImportFile(@PrimaryKey val assetId: String, val jobId: String, val state: String)

/** Document locations are private storage records, never playable/public IDs. */
@Entity(tableName = "source_grants")
data class SourceGrant(@PrimaryKey val uri: String, val owned: Boolean)
@Entity(tableName = "job_grants", primaryKeys = ["jobId", "grantUri"], foreignKeys = [
    ForeignKey(entity = ImportJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SourceGrant::class, parentColumns = ["uri"], childColumns = ["grantUri"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("grantUri")])
data class JobGrant(val jobId: String, val grantUri: String)
@Entity(tableName = "document_sources", foreignKeys = [
    ForeignKey(entity = AudioAsset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SourceGrant::class, parentColumns = ["uri"], childColumns = ["grantUri"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("grantUri")])
data class DocumentSource(@PrimaryKey val assetId: String, val documentUri: String, val grantUri: String,
    val sizeHint: Long?, val modifiedHint: Long?, val metadataPending: Boolean,
    val availability: String = "UNCHECKED")
@Entity(tableName = "linked_book_roots", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SourceGrant::class, parentColumns = ["uri"], childColumns = ["grantUri"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("grantUri")])
data class LinkedBookRoot(@PrimaryKey val bookId: String, val documentUri: String, val grantUri: String)

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGrant(grant: SourceGrant)
    @Query("UPDATE source_grants SET owned = 1 WHERE uri = :uri AND owned = 0") suspend fun claimReadGrant(uri: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun reserve(grant: JobGrant)
    @Insert suspend fun insertSources(sources: List<DocumentSource>)
    @Insert suspend fun insertRoot(root: LinkedBookRoot)
    @Query("SELECT * FROM document_sources WHERE assetId = :assetId") suspend fun source(assetId: String): DocumentSource?
    @Query("SELECT documentUri FROM document_sources") suspend fun documentUris(): List<String>
    @Query("SELECT * FROM document_sources") fun observeSources(): Flow<List<DocumentSource>>
    @Query("SELECT * FROM linked_book_roots WHERE bookId = :bookId") fun observeRoot(bookId: String): Flow<LinkedBookRoot?>
    @Query("SELECT * FROM linked_book_roots WHERE bookId = :bookId") suspend fun root(bookId: String): LinkedBookRoot?
    @Query("DELETE FROM linked_book_roots WHERE bookId = :bookId") suspend fun deleteRoot(bookId: String)
    @Query("SELECT * FROM source_grants") suspend fun grants(): List<SourceGrant>
    @Query("SELECT COUNT(*) FROM job_grants WHERE jobId = :jobId AND grantUri = :uri") suspend fun reservation(jobId: String, uri: String): Int
    @Query("SELECT (SELECT COUNT(*) FROM document_sources WHERE grantUri = :uri) + (SELECT COUNT(*) FROM linked_book_roots WHERE grantUri = :uri) + (SELECT COUNT(*) FROM job_grants WHERE grantUri = :uri)") suspend fun references(uri: String): Int
    @Query("DELETE FROM source_grants WHERE uri = :uri") suspend fun deleteGrant(uri: String)
    @Query("UPDATE document_sources SET availability = :status WHERE assetId = :id AND availability != :status") suspend fun setAvailability(id: String, status: String)
    @Query("UPDATE document_sources SET metadataPending = 0 WHERE assetId = :id") suspend fun metadataRead(id: String)
    @Query("UPDATE document_sources SET documentUri = :uri, grantUri = :grantUri, sizeHint = :size, modifiedHint = :modified, availability = 'AVAILABLE' WHERE assetId = :id") suspend fun reconnect(id: String, uri: String, grantUri: String, size: Long?, modified: Long?)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE, id") fun observeBooks(): Flow<List<Book>>
    @Query("SELECT * FROM books WHERE id = :id") fun observe(id: String): Flow<Book?>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun get(id: String): Book?
    @Query("SELECT * FROM books ORDER BY id") suspend fun all(): List<Book>
    @Query("SELECT * FROM books ORDER BY id LIMIT :limit") suspend fun limited(limit: Int): List<Book>
    @Insert suspend fun insert(book: Book)
    @Query("UPDATE books SET titleOverride = :title, authorOverride = :author, narratorOverride = :narrator, seriesOverride = :series, translatorOverride = :translator, seriesNumberOverride = :seriesNumber WHERE id = :id")
    suspend fun setOverrides(id: String, title: String?, author: String?, narrator: String?, series: String?, translator: String? = null, seriesNumber: String? = null): Int
    @Update suspend fun updateMetadata(book: Book)
    @Query("UPDATE books SET coverOverridePath = :path WHERE id = :id") suspend fun setCoverOverride(id: String, path: String?): Int
    @Query("SELECT COUNT(*) FROM books WHERE coverPath = :path OR coverOverridePath = :path") suspend fun coverReferences(path: String): Int
    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: String)
}
@Dao
interface LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY bookId, position") fun observeAllTracks(): Flow<List<BookTrack>>
    @Query("SELECT * FROM assets") fun observeAssets(): Flow<List<AudioAsset>>
    @Query("SELECT * FROM reading_state") fun observeAllProgress(): Flow<List<ReadingState>>
    @Query("SELECT * FROM chapters WHERE trackId = :trackId ORDER BY position") suspend fun chapters(trackId: String): List<Chapter>
    @Query("SELECT * FROM chapters WHERE trackId IN (SELECT id FROM tracks WHERE bookId = :bookId) ORDER BY trackId, position") fun observeChapters(bookId: String): Flow<List<Chapter>>
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt, id") fun observeBookmarks(bookId: String): Flow<List<Bookmark>>
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY position") suspend fun tracks(bookId: String): List<BookTrack>
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY position LIMIT :limit") suspend fun limitedTracks(bookId: String, limit: Int): List<BookTrack>
    @Query("SELECT * FROM assets WHERE id = :id") suspend fun asset(id: String): AudioAsset?
    @Query("SELECT sizeBytes FROM assets WHERE id NOT IN (SELECT assetId FROM document_sources)") suspend fun assetSizes(): List<Long>
    @Query("SELECT * FROM assets WHERE identityVerified = 1 AND sha256 = :hash AND sizeBytes = :size AND id NOT IN (SELECT assetId FROM document_sources)") suspend fun duplicates(hash: String, size: Long): List<AudioAsset>
    @Query("SELECT * FROM reading_state WHERE bookId = :bookId") suspend fun progress(bookId: String): ReadingState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(state: ReadingState)
    @Query("DELETE FROM reading_state WHERE bookId = :bookId") suspend fun deleteProgress(bookId: String)
    @Query("UPDATE assets SET sha256 = :hash, sizeBytes = :size, identityVerified = 1 WHERE id = :id") suspend fun verifyIdentity(id: String, hash: String, size: Long)
    @Query("UPDATE assets SET durationMs = :duration WHERE id = :id AND durationMs IS NULL") suspend fun fillDuration(id: String, duration: Long)
    @Query("UPDATE assets SET durationMs = COALESCE(durationMs, :duration), mimeType = COALESCE(mimeType, :mime) WHERE id = :id") suspend fun fillMetadata(id: String, duration: Long?, mime: String?)
    @Query("UPDATE tracks SET importedTitle = :title WHERE id = :id AND importedTitle IS NULL") suspend fun fillTrackTitle(id: String, title: String?)
    @Query("DELETE FROM chapters WHERE trackId = :trackId AND origin = 'file'") suspend fun deleteFileChapters(trackId: String)
    @Insert suspend fun insertAssets(assets: List<AudioAsset>)
    @Insert suspend fun insertTracks(tracks: List<BookTrack>)
    @Update suspend fun updateTrackMetadata(tracks: List<BookTrack>)
    @Insert suspend fun insertChapters(chapters: List<Chapter>)
    @Insert suspend fun insertBookmark(bookmark: Bookmark)
    @Query("SELECT * FROM bookmarks WHERE id = :id") suspend fun bookmark(id: String): Bookmark?
    @Query("UPDATE bookmarks SET note = :note WHERE id = :bookmarkId AND bookId = :bookId") suspend fun updateBookmarkNote(bookId: String, bookmarkId: String, note: String?): Int
    @Query("DELETE FROM bookmarks WHERE id = :id AND bookId = :bookId") suspend fun deleteBookmark(bookId: String, id: String): Int
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt, id") suspend fun bookmarks(bookId: String): List<Bookmark>
    @Query("SELECT COALESCE(SUM(length(note)), 0) FROM bookmarks WHERE bookId = :bookId") suspend fun bookmarkTextLength(bookId: String): Long
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt, id LIMIT :limit") suspend fun limitedBookmarks(bookId: String, limit: Int): List<Bookmark>
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId") suspend fun deleteBookmarks(bookId: String)
    @Insert suspend fun insertJob(job: ImportJob)
    @Insert suspend fun insertImportFile(file: ImportFile)
    @Query("UPDATE import_files SET state = :state WHERE assetId = :id") suspend fun updateImportFile(id: String, state: String)
    @Query("SELECT COUNT(*) FROM tracks WHERE assetId = :id") suspend fun trackReferences(id: String): Int
    @Query("DELETE FROM assets WHERE id = :id") suspend fun deleteAsset(id: String)
    @Query("SELECT COUNT(*) FROM assets WHERE id = :id") suspend fun assetCount(id: String): Int
    @Query("SELECT * FROM import_jobs") suspend fun jobs(): List<ImportJob>
    @Query("SELECT * FROM import_files WHERE jobId = :jobId") suspend fun importFiles(jobId: String): List<ImportFile>
    @Query("DELETE FROM import_jobs WHERE id = :id") suspend fun deleteJob(id: String)
}
@Database(entities = [Book::class, AudioAsset::class, BookTrack::class, Chapter::class, ReadingState::class,
    Bookmark::class, ImportJob::class, ImportFile::class, SourceGrant::class, JobGrant::class, DocumentSource::class, LinkedBookRoot::class], version = 6, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)])
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun library(): LibraryDao
    abstract fun sources(): SourceDao
}
