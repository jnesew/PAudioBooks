package io.github.jnesew.paudiobooks.importing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import io.github.jnesew.paudiobooks.metadata.BookMetadata
import io.github.jnesew.paudiobooks.metadata.agreedBookMetadata
import io.github.jnesew.paudiobooks.library.AudioAsset
import io.github.jnesew.paudiobooks.library.Book
import io.github.jnesew.paudiobooks.library.BookTrack
import io.github.jnesew.paudiobooks.library.Chapter
import io.github.jnesew.paudiobooks.library.ImportFile
import io.github.jnesew.paudiobooks.library.ImportJob
import io.github.jnesew.paudiobooks.library.LibraryDatabase
import io.github.jnesew.paudiobooks.storage.CopiedAudio
import io.github.jnesew.paudiobooks.storage.PrivateAudioStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Preview can be inspected and reordered before commit; original sources are never modified. */
data class ImportChapter(val startMs: Long, val endMs: Long?, val title: String?)
data class ImportMetadata(
    val title: String?, val durationMs: Long?, val chapters: List<ImportChapter>,
    val bookMetadata: BookMetadata = BookMetadata(), val artworkThumbnail: ByteArray? = null, val mimeType: String? = null
) {
    constructor(title: String?, author: String?, durationMs: Long?, chapters: List<ImportChapter>,
        narrator: String? = null, series: String? = null, artworkThumbnail: ByteArray? = null,
        translator: String? = null, seriesNumber: String? = null, mimeType: String? = null) :
        this(title, durationMs, chapters, BookMetadata(author=author, narrator=narrator, translator=translator,
            series=series, seriesNumber=seriesNumber), artworkThumbnail, mimeType)
    val author get() = bookMetadata.author
    val narrator get() = bookMetadata.narrator
    val translator get() = bookMetadata.translator
    val series get() = bookMetadata.series
    val seriesNumber get() = bookMetadata.seriesNumber
}
data class ImportProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val currentFileBytes: Long,
    val currentFileExpectedBytes: Long?,
    val totalCopiedBytes: Long = 0L,
    val totalExpectedBytes: Long? = null
)
data class ImportPreviewFile(
    val sourceName: String,
    val copy: CopiedAudio,
    val duplicateAssetIds: List<String>,
    val subfolder: String? = null,
    val orderKey: String? = null
)
data class ImportPreview(val jobId: String, val files: List<ImportPreviewFile>)
data class ImportTrack(val file: ImportPreviewFile, val metadata: ImportMetadata)

enum class DuplicateAction {
    REUSE,      // Reuse existing AudioAsset by SHA-256 and size (saves storage, references existing asset)
    SKIP,       // Skip duplicate tracks (if all skipped, creates no book and cleans staging)
    MAKE_COPY,  // Store an independent physical copy
    REJECT      // Throw DuplicateImportException if duplicates exist
}

class ImportCoordinator(private val db: LibraryDatabase, private val store: PrivateAudioStore, private val resolver: ContentResolver, private val afterCleanup: suspend () -> Unit = {}) {
    companion object {
        const val MAX_BOOK_METADATA_TEXT = 512 * 1024L
        const val MAX_BOOK_CHAPTERS = 10_000
        const val MAX_BOOK_CHAPTER_TEXT = 32_768L
    }
    private val mutex = Mutex()
    private fun id() = UUID.randomUUID().toString()

    /** Caller owns picker grant; pass its URI only if this operation took a persistable read grant. */
    suspend fun prepare(files: List<SelectedDocument>, persistedGrant: Uri? = null, onProgress: (ImportProgress) -> Unit = {}): ImportPreview = mutex.withLock {
        require(files.isNotEmpty() && files.size <= 1000)
        val jobId = id()
        db.library().insertJob(ImportJob(jobId, "COPYING", System.currentTimeMillis()))
        try {
            val totalExpectedBytes = computeTotalExpectedBytes(files.map { it.sizeBytes })
            var totalCopiedBytes = 0L
            val result = mutableListOf<ImportPreviewFile>()
            files.forEachIndexed { index, selected ->
                val assetId = id()
                db.library().insertImportFile(ImportFile(assetId, jobId, "COPYING"))
                val copied = store.copy(resolver, selected.uri, assetId, selected.sizeBytes) { bytes ->
                    onProgress(ImportProgress(index, files.size, bytes, selected.sizeBytes, totalCopiedBytes + bytes, totalExpectedBytes))
                }
                db.library().updateImportFile(assetId, "STAGED")
                totalCopiedBytes += copied.sizeBytes
                onProgress(ImportProgress(index + 1, files.size, copied.sizeBytes, selected.sizeBytes, totalCopiedBytes, totalExpectedBytes))
                val duplicates = db.library().duplicates(copied.sha256, copied.sizeBytes).map { it.id } +
                    result.filter { it.copy.sha256 == copied.sha256 && it.copy.sizeBytes == copied.sizeBytes }.map { it.copy.assetId }
                result.add(ImportPreviewFile(selected.name, copied, duplicates, selected.subfolder, selected.orderKey))
            }
            ImportPreview(jobId, result.toList())
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { discardJob(jobId) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            if (persistedGrant != null) {
                try { resolver.releasePersistableUriPermission(persistedGrant, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* Grant may have been revoked or not persistable. */ }
            }
        }
    }

    suspend fun commit(
        preview: ImportPreview,
        ordered: List<ImportTrack>,
        title: String,
        allowDuplicates: Boolean
    ): String = commit(
        preview,
        ordered,
        title,
        if (allowDuplicates) DuplicateAction.MAKE_COPY else DuplicateAction.REJECT
    )

    /** Commits prepared import with the selected duplicate action (REUSE, MAKE_COPY, or REJECT). */
    suspend fun commit(
        preview: ImportPreview,
        ordered: List<ImportTrack>,
        title: String,
        duplicateAction: DuplicateAction = DuplicateAction.REJECT,
        bookId: String = id()
    ): String = mutex.withLock {
        require(runCatching { UUID.fromString(bookId).toString() == bookId }.getOrDefault(false))
        val unusedStagedIds = mutableListOf<String>()

        try {
            require(title.isNotBlank() && title.length <= 512)
            require(ordered.isNotEmpty() && ordered.size == preview.files.size)
            val ids = preview.files.map { it.copy.assetId }.toSet()
            require(ids.size == preview.files.size && ordered.map { it.file.copy.assetId }.toSet() == ids)
            val journal = db.library().importFiles(preview.jobId)
            require(journal.map { it.assetId }.toSet() == ids && journal.all { it.state == "STAGED" })

            val hasDuplicates = ordered.map { it.file.copy.sha256 to it.file.copy.sizeBytes }.toSet().size != ordered.size ||
                ordered.any { db.library().duplicates(it.file.copy.sha256, it.file.copy.sizeBytes).isNotEmpty() }

            if (hasDuplicates && duplicateAction == DuplicateAction.REJECT) {
                throw DuplicateImportException()
            }

            if (ordered.sumOf { it.metadata.chapters.size.toLong() } > MAX_BOOK_CHAPTERS ||
                ordered.sumOf { it.metadata.chapters.sumOf { chapter -> chapter.title?.length?.toLong() ?: 0L } } > MAX_BOOK_CHAPTER_TEXT) {
                throw ImportMetadataLimitException()
            }

            require(ordered.sumOf { it.metadata.bookMetadata.textLength() } <= MAX_BOOK_METADATA_TEXT)
            ordered.forEach { track ->
                require(track.metadata.bookMetadata.isBounded())
                require(track.metadata.durationMs == null || track.metadata.durationMs >= 0)
                require(track.metadata.chapters.size <= 10000)
                require(listOf(track.metadata.title, track.metadata.author, track.metadata.narrator, track.metadata.series, track.metadata.translator, track.metadata.seriesNumber).all { it == null || it.length <= 4096 })
                require(track.metadata.chapters.sumOf { it.title?.length?.toLong() ?: 0L } <= 32768L)
                var previous = -1L
                track.metadata.chapters.forEach { chapter ->
                    require(chapter.startMs >= 0 && chapter.startMs > previous && (chapter.endMs == null || chapter.endMs > chapter.startMs))
                    require(track.metadata.durationMs == null || chapter.startMs < track.metadata.durationMs)
                    require(chapter.endMs == null || track.metadata.durationMs == null || chapter.endMs <= track.metadata.durationMs)
                    previous = chapter.startMs
                }
            }

            // Map each track's staged copy to the resolved assetId (either newly committed or reused)
            val tracksToCommit = mutableListOf<ImportTrack>()
            val resolvedTrackAssetIds = mutableListOf<String>()
            val newlyCommittedAssets = mutableListOf<AudioAsset>()
            val committedFingerprintsInThisImport = mutableMapOf<Pair<String, Long>, String>()

            for (track in ordered) {
                val sha = track.file.copy.sha256
                val size = track.file.copy.sizeBytes
                val stagedId = track.file.copy.assetId

                when (duplicateAction) {
                    DuplicateAction.REUSE -> {
                        val existingDbAsset = db.library().duplicates(sha, size).firstOrNull { candidate ->
                            store.matches(candidate.relativePath, size, sha)
                        }
                        val existingThisImport = committedFingerprintsInThisImport[sha to size]
                        val reusableId = existingDbAsset?.id ?: existingThisImport

                        if (reusableId != null) {
                            resolvedTrackAssetIds.add(reusableId)
                            unusedStagedIds.add(stagedId)
                        } else {
                            store.commit(stagedId)
                            val newAsset = AudioAsset(stagedId, store.relativePath(stagedId), size, sha, track.metadata.durationMs, track.metadata.mimeType)
                            newlyCommittedAssets.add(newAsset)
                            committedFingerprintsInThisImport[sha to size] = stagedId
                            resolvedTrackAssetIds.add(stagedId)
                        }
                        tracksToCommit.add(track)
                    }
                    DuplicateAction.SKIP -> {
                        val existingDbAsset = db.library().duplicates(sha, size).firstOrNull { candidate ->
                            store.matches(candidate.relativePath, size, sha)
                        }
                        val isDuplicate = existingDbAsset != null || committedFingerprintsInThisImport.containsKey(sha to size)

                        if (isDuplicate) {
                            unusedStagedIds.add(stagedId)
                        } else {
                            store.commit(stagedId)
                            val newAsset = AudioAsset(stagedId, store.relativePath(stagedId), size, sha, track.metadata.durationMs, track.metadata.mimeType)
                            newlyCommittedAssets.add(newAsset)
                            committedFingerprintsInThisImport[sha to size] = stagedId
                            resolvedTrackAssetIds.add(stagedId)
                            tracksToCommit.add(track)
                        }
                    }
                    DuplicateAction.MAKE_COPY, DuplicateAction.REJECT -> {
                        store.commit(stagedId)
                        val newAsset = AudioAsset(stagedId, store.relativePath(stagedId), size, sha, track.metadata.durationMs, track.metadata.mimeType)
                        newlyCommittedAssets.add(newAsset)
                        resolvedTrackAssetIds.add(stagedId)
                        tracksToCommit.add(track)
                    }
                }
            }

            if (tracksToCommit.isEmpty()) {
                throw AllTracksSkippedException()
            }

            // Write or resolve cover (journaled in job so failures/cancellations clean candidate cover file)
            val thumb = tracksToCommit.firstNotNullOfOrNull { it.metadata.artworkThumbnail }
            val cover = if (thumb != null) {
                val candidateCoverId = id()
                db.library().insertImportFile(ImportFile(candidateCoverId, preview.jobId, "STAGED"))
                store.writeCover(candidateCoverId, thumb)
                "covers/$candidateCoverId"
            } else null

            val metadata = agreedBookMetadata(tracksToCommit.map { it.metadata.bookMetadata })
            db.withTransaction {
                db.books().insert(
                    Book(
                        bookId,
                        title,
                        metadata.title,
                        metadata.author,
                        importedNarrator = metadata.narrator,
                        importedSeries = metadata.series,
                        importedTranslator = metadata.translator,
                        importedSeriesNumber = metadata.seriesNumber,
                        coverPath = cover,
                        addedAt = System.currentTimeMillis(),
                        importedGenre=metadata.genre, importedPublisher=metadata.publisher, importedDate=metadata.date,
                        importedReleaseDate=metadata.releaseDate, importedDescription=metadata.description, metadataManaged=true
                    )
                )
                if (newlyCommittedAssets.isNotEmpty()) {
                    db.library().insertAssets(newlyCommittedAssets)
                }
                val tracks = tracksToCommit.mapIndexed { index, track ->
                    BookTrack(id(), bookId, resolvedTrackAssetIds[index], index, track.metadata.title, track.metadata.bookMetadata)
                }
                db.library().insertTracks(tracks)
                val chapters = tracksToCommit.flatMapIndexed { index, track ->
                    track.metadata.chapters.mapIndexed { position, chapter ->
                        Chapter(id(), tracks[index].id, position, chapter.startMs, chapter.endMs, chapter.title, "embedded")
                    }
                }
                db.library().insertChapters(chapters)

                // Mark unused staged files as REMOVING so if process dies before removal, reconcileInterrupted cleans them
                for (stagedId in unusedStagedIds) {
                    db.library().updateImportFile(stagedId, "REMOVING")
                }
            }
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { discardJob(preview.jobId) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }

        // Durably remove unused staged files under NonCancellable; delete job only when ALL cleanup passes.
        // Already-committed book remains successfully imported and referenced assets preserved; do not throw outer failure.
        withContext(NonCancellable) {
            var allCleanupPassed = true
            for (stagedId in unusedStagedIds) {
                try {
                    store.removeStaged(stagedId)
                } catch (_: Exception) {
                    allCleanupPassed = false
                }
            }
            if (allCleanupPassed) {
                try {
                    db.library().deleteJob(preview.jobId)
                    afterCleanup()
                } catch (_: Exception) {
                    // Journal deletion failure leaves the job as pending recovery,
                    // preserving the durable journal without masking the successful book import.
                }
            }
        }
        return bookId
    }

    /** Exact original bytes repair owned assets without replacing books, tracks, progress or notes. */
    suspend fun repair(bookId: String, preview: ImportPreview): Int = mutex.withLock {
        try {
            require(db.books().get(bookId) != null)
            val assets = db.library().tracks(bookId).map { track ->
                requireNotNull(db.library().asset(track.assetId))
            }.distinctBy { it.id }
            require(assets.isNotEmpty() && preview.files.isNotEmpty())
            val journal = db.library().importFiles(preview.jobId)
            require(journal.size == preview.files.size && journal.all { it.state == "STAGED" } &&
                journal.map { it.assetId }.toSet() == preview.files.map { it.copy.assetId }.toSet())
            // Reject every unrelated selection before replacing any bytes.
            require(preview.files.all { selected -> assets.any { asset ->
                asset.sha256 == selected.copy.sha256 && asset.sizeBytes == selected.copy.sizeBytes
            } })
            val matching = assets.mapNotNull { asset ->
                preview.files.firstOrNull { it.copy.sha256 == asset.sha256 && it.copy.sizeBytes == asset.sizeBytes }
                    ?.let { asset to it }
            }
            require(matching.isNotEmpty())
            for ((asset, selected) in matching) {
                require(asset.relativePath == store.relativePath(asset.id))
                val cloneId = id()
                db.library().insertImportFile(ImportFile(cloneId, preview.jobId, "COPYING"))
                store.cloneStaged(selected.copy.assetId, cloneId)
                db.library().updateImportFile(cloneId, "STAGED")
                // Rename has no suspension point inside its I/O operation. If cancellation races,
                // the journal only discards the temporary UUID, never the referenced target.
                store.restore(cloneId, asset.id)
                db.library().updateImportFile(cloneId, "REMOVING")
            }
            withContext(NonCancellable) { discardJob(preview.jobId) }
            matching.size
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { discardJob(preview.jobId) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun coverId(path: String?): String? {
        if (path == null || !path.startsWith("covers/")) return null
        val id = path.removePrefix("covers/")
        return id.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    }

    /** Copies an optional companion cover image temporarily with bounded read time; returns sanitized thumbnail or null. */
    suspend fun stageAndInspectCover(sourceUri: Uri, timeoutMs: Long = 15_000L, decode: suspend (java.io.File) -> ByteArray): ByteArray? = mutex.withLock {
        require(sourceUri.scheme == ContentResolver.SCHEME_CONTENT)
        require(timeoutMs > 0)
        val jobId = id()
        val coverId = id()
        db.library().insertJob(ImportJob(jobId, "COVER", System.currentTimeMillis()))
        try {
            db.library().insertImportFile(ImportFile(coverId, jobId, "COPYING"))
            store.copy(resolver, sourceUri, coverId, maxBytes = 16L * 1024 * 1024, timeoutMs = timeoutMs)
            db.library().updateImportFile(coverId, "STAGED")
            val thumbnail = try {
                decode(store.stagedFile(coverId))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            withContext(NonCancellable) { discardJob(jobId) }
            thumbnail
        } catch (cancel: CancellationException) {
            try { withContext(NonCancellable) { discardJob(jobId) } }
            catch (cleanup: Exception) { cancel.addSuppressed(cleanup) }
            throw cancel
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { discardJob(jobId) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            null
        }
    }

    /** Copies a user-selected image temporarily; only the bounded sanitized thumbnail is retained. */
    suspend fun setCoverOverride(bookId: String, sourceUri: Uri, decode: suspend (java.io.File) -> ByteArray): Boolean = mutex.withLock {
        require(sourceUri.scheme == ContentResolver.SCHEME_CONTENT)
        if (db.books().get(bookId) == null) return@withLock false
        val jobId = id()
        val coverId = id()
        db.library().insertJob(ImportJob(jobId, "COVER", System.currentTimeMillis()))
        try {
            db.library().insertImportFile(ImportFile(coverId, jobId, "COPYING"))
            store.copy(resolver, sourceUri, coverId, maxBytes = 16L * 1024 * 1024, timeoutMs = 15_000L)
            val thumbnail = decode(store.stagedFile(coverId))
            store.writeCover(coverId, thumbnail)
            store.removeStaged(coverId)
            val updated = db.withTransaction {
                val book = db.books().get(bookId) ?: return@withTransaction false
                coverId(book.coverOverridePath)?.let { db.library().insertImportFile(ImportFile(it, jobId, "REMOVING")) }
                db.books().setCoverOverride(bookId, "covers/$coverId") == 1
            }
            withContext(NonCancellable) { discardJob(jobId) }
            updated
        } catch (cancel: CancellationException) {
            try { withContext(NonCancellable) { discardJob(jobId) } }
            catch (cleanup: Exception) { cancel.addSuppressed(cleanup) }
            throw cancel
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { discardJob(jobId) } }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun resetCoverOverride(bookId: String): Boolean = mutex.withLock {
        val jobId = id()
        val updated = db.withTransaction {
            val book = db.books().get(bookId) ?: return@withTransaction false
            val oldId = coverId(book.coverOverridePath)
            if (oldId != null) {
                db.library().insertJob(ImportJob(jobId, "COVER", System.currentTimeMillis()))
                db.library().insertImportFile(ImportFile(oldId, jobId, "REMOVING"))
            }
            db.books().setCoverOverride(bookId, null) == 1
        }
        if (updated) withContext(NonCancellable) { discardJob(jobId) }
        updated
    }

    /** Removes a book and its saved progress/bookmarks; originals are never touched. */
    suspend fun removeBook(bookId: String): Boolean = mutex.withLock {
        val jobId = id()
        val removed = db.withTransaction {
            val book = db.books().get(bookId) ?: return@withTransaction false
            val tracks = db.library().tracks(bookId)
            db.library().insertJob(ImportJob(jobId, "REMOVING", System.currentTimeMillis()))
            db.books().delete(bookId)
            val overrideId = coverId(book.coverOverridePath)
            if (overrideId != null && tracks.none { it.assetId == overrideId }) {
                db.library().insertImportFile(ImportFile(overrideId, jobId, "REMOVING"))
            }
            val mainCoverId = coverId(book.coverPath)
            if (mainCoverId != null && tracks.none { it.assetId == mainCoverId }) {
                db.library().insertImportFile(ImportFile(mainCoverId, jobId, "REMOVING"))
            }
            for (assetId in tracks.map { it.assetId }.distinct()) {
                if (db.library().trackReferences(assetId) == 0) {
                    db.library().insertImportFile(ImportFile(assetId, jobId, "REMOVING"))
                    db.library().deleteAsset(assetId)
                }
            }
            true
        }
        if (removed) {
            withContext(NonCancellable) {
                try {
                    discardJob(jobId)
                } catch (_: Exception) {
                    // Post-commit cleanup failed; job is preserved in journal as REMOVING for future reconciliation.
                }
            }
        }
        removed
    }

    suspend fun interruptedJobs(): List<ImportJob> = mutex.withLock { db.library().jobs() }
    suspend fun reconcileInterrupted(targetJobIds: Collection<String>? = null): List<String> = mutex.withLock {
        val allJobs = db.library().jobs()
        val toDiscard = if (targetJobIds == null) allJobs else allJobs.filter { it.id in targetJobIds }
        val discarded = mutableListOf<String>()
        for (job in toDiscard) {
            try {
                discardJob(job.id)
                discarded.add(job.id)
            } catch (_: Exception) {
                // Cleanup incomplete for this job; preserved in journal for future reconciliation
            }
        }
        val remainingActive = db.library().jobs().flatMap { db.library().importFiles(it.id) }.map { it.assetId }.toSet()
        store.cleanOrphanedStaging(remainingActive)
        discarded
    }
    suspend fun cancel(jobId: String) = mutex.withLock { discardJob(jobId) }

    private suspend fun discardJob(jobId: String) {
        var failure: Exception? = null
        for (file in db.library().importFiles(jobId)) {
            // Never remove a file that a committed asset references.
            if (db.library().assetCount(file.assetId) == 0 && db.books().coverReferences("covers/${file.assetId}") == 0) {
                try {
                    store.discard(file.assetId)
                } catch (e: Exception) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
        }
        if (failure != null) {
            throw failure
        }
        db.library().deleteJob(jobId)
        afterCleanup()
    }
}
class DuplicateImportException : IllegalStateException("A selected file matches an existing copy")
class AllTracksSkippedException : IllegalStateException("All selected tracks already exist in library")

class ImportMetadataLimitException : IllegalArgumentException("Selected book metadata exceeds bounded chapter limits")
