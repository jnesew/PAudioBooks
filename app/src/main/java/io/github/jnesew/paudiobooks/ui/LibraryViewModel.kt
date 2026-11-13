package io.github.jnesew.paudiobooks.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.session.MediaController
import io.github.jnesew.paudiobooks.playback.PlaybackService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.importing.DocumentInventory
import io.github.jnesew.paudiobooks.importing.ImportPreview
import io.github.jnesew.paudiobooks.importing.ImportProgress
import io.github.jnesew.paudiobooks.importing.SelectedDocument
import io.github.jnesew.paudiobooks.importing.TrackOrderHint
import io.github.jnesew.paudiobooks.importing.TreeGroup
import io.github.jnesew.paudiobooks.importing.orderTrackIndices
import io.github.jnesew.paudiobooks.metadata.suggestedBookTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceWorkUi(val busy: Boolean = false, val completedFiles: Int = 0, val totalFiles: Int = 0,
    val bytes: Long = 0, val complete: Boolean = false, val metadata: Boolean = false, val pendingFiles: Int = 0)

data class PreparedLinkedImport(val preview: io.github.jnesew.paudiobooks.importing.LinkedImportPreview,
    val tracks: List<io.github.jnesew.paudiobooks.importing.LinkedPreviewTrack>, val title: String, val bookId: String, val appendToBookId: String? = null)
data class ImportUi(
    val selection: SourceSelection? = null,
    val review: List<LinkedBookReview>? = null,
    val editingReviewId: String? = null,
    val busy: Boolean = false,
    val preparing: Boolean = false,
    val inspecting: Boolean = false,
    val progress: ImportProgress? = null,
    val interruptedJobs: List<String> = emptyList(),
    val repairedFiles: Int? = null,
    val linkedRecovery: Boolean = false,
    val batch: ImportBatch? = null,
    val linkedPrepared: PreparedLinkedImport? = null,
    val sourceBytesRead: Long = 0,
    val verifyingSource: Boolean = false,
    val scanning: Boolean = false,
    val rescan: io.github.jnesew.paudiobooks.importing.LinkedRescan? = null,
    val selectedNewUris: Set<Uri> = emptySet(),
)

/** Owns foreground import work across rotation; disk journals handle process death. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    internal val bookmarkDrafts = BookmarkDrafts()
    private val app = application as AudiobookApplication
    private val _deferredInterrupted = MutableStateFlow<Set<String>>(emptySet())
    val deferredInterrupted = _deferredInterrupted.asStateFlow()
    fun deferInterrupted() { if (!_import.value.busy) _deferredInterrupted.value = _import.value.interruptedJobs.toSet() }
    fun reviewInterrupted() { _deferredInterrupted.value = emptySet() }
    private val _seekScopeBook = MutableStateFlow(false)
    val seekScopeBook = _seekScopeBook.asStateFlow()
    private var seekScopeBookId: String? = null
    fun syncSeekScope(bookId: String?) {
        if (bookId != null && bookId != seekScopeBookId) { seekScopeBookId = bookId; _seekScopeBook.value = false }
    }
    fun setSeekScope(book: Boolean) { _seekScopeBook.value = book }
    private val _sourceWork = MutableStateFlow(SourceWorkUi())
    val sourceWork = _sourceWork.asStateFlow()
    private var sourceJob: Job? = null
    fun cancelSourceWork() { sourceJob?.cancel() }
    fun dismissSourceComplete() { _sourceWork.value=SourceWorkUi() }
    fun verifyLinkedBook(bookId: String, controller: MediaController?) {
        if (_sourceWork.value.busy || _import.value.busy) return
        _sourceWork.value=SourceWorkUi(busy=true)
        sourceJob=viewModelScope.launch {
            try {
                if (controller?.pauseAndSave()!=true) error("save")
                app.linker.verifyBook(bookId) { done,total,bytes -> _sourceWork.value=_sourceWork.value.copy(completedFiles=done,totalFiles=total,bytes=bytes) }
                _sourceWork.value=_sourceWork.value.copy(complete=true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { _error.value=sourceFailureMessage(failure,R.string.link_failed) }
            finally { _sourceWork.value=_sourceWork.value.copy(busy=false) }
        }
    }
    fun readLinkedMetadata(bookId: String, controller: MediaController?) {
        if (_sourceWork.value.busy || _import.value.busy || controller==null) return
        _sourceWork.value=SourceWorkUi(busy=true,metadata=true)
        sourceJob=viewModelScope.launch {
            var unloaded=false
            var pending: Int?=null
            try {
                if (controller.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.BOOK_ID)==bookId) {
                    check(controller.command(PlaybackService.UNLOAD_AND_SAVE)); unloaded=true
                }
                val result=app.linker.readPendingMetadata(bookId) { done,total,bytes ->
                    _sourceWork.value=_sourceWork.value.copy(completedFiles=done,totalFiles=total,bytes=bytes)
                }
                pending=result.pendingFiles
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { _error.value=sourceFailureMessage(failure,R.string.read_metadata_failed) }
            finally {
                if (unloaded) withContext(NonCancellable) { if (!controller.loadBook(bookId)) _error.value=R.string.playback_failed }
                _sourceWork.value=_sourceWork.value.copy(busy=false,complete=pending!=null,pendingFiles=pending ?: 0)
            }
        }
    }
    private val _coverBusy = MutableStateFlow(false)
    val coverBusy = _coverBusy.asStateFlow()
    private var coverWork: Job? = null

    private val reload = MutableStateFlow(0)
    private val _error = MutableStateFlow<Int?>(null)
    val error = _error.asStateFlow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val summaries = reload.flatMapLatest { app.repository.summaries.catch { _error.value = R.string.library_read_error } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _import = MutableStateFlow(ImportUi())
    val importing = _import.asStateFlow()
    private var work: Job? = null
    private var cancellingImport = false
    private val reviewJobs = linkedSetOf<String>()
    private var staged: ImportPreview? = null
    private var stagedLink: io.github.jnesew.paudiobooks.importing.LinkedImportPreview? = null
    private val _openedBook = MutableStateFlow<String?>(null)
    val openedBook = _openedBook.asStateFlow()

    internal val progressTransfer = ProgressTransfer(app, viewModelScope, ::showError)

    init {
        refreshInterrupted()
    }

    fun dismissError() { _error.value = null }
    fun retryLibrary() { _error.value = null; reload.value++ }
    fun openedBookHandled() { _openedBook.value = null }
    fun showError(message: Int) { _error.value = message }

    fun select(uris: List<Uri>, folder: Uri? = null) {
        if (_import.value.busy || staged != null || stagedLink != null || _import.value.batch != null || _import.value.review != null) return
        work = viewModelScope.launch {
            _import.value = _import.value.copy(busy = true, preparing = true)
            try {
                val inventory = DocumentInventory(app.contentResolver)
                val (allFiles, companionCover, groups) = if (folder != null) {
                    val tree = inventory.treeInventory(folder)
                    Triple(tree.audioFiles, tree.companionCover, tree.groups)
                } else {
                    Triple(uris.distinct().take(1_001).map { inventory.describe(it) }, null, emptyList())
                }
                require(allFiles.isNotEmpty() && allFiles.size <= 1_000)
                val root = folder?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }
                val name = root?.let { inventory.describe(it).name } ?: allFiles.first().name.substringBeforeLast('.')
                val title = name.ifBlank { app.getString(R.string.untitled_book) }.take(512)

                // Keep direct root files selectable even alongside disc folders.
                val groupedUris = groups.flatMap { it.audioFiles }.map { it.uri }.toSet()
                val ungrouped = allFiles.filter { it.uri !in groupedUris }
                val choices = if (groups.isNotEmpty() && ungrouped.isNotEmpty()) {
                    listOf(TreeGroup(title, ungrouped, companionCover, id = DocumentsContract.getTreeDocumentId(requireNotNull(folder)))) + groups
                } else groups

                _import.value = _import.value.copy(
                    selection = SourceSelection(allFiles, title, companionCover, choices, grantUris = folder?.let { listOf(it) } ?: uris.distinct(), rootDocument = root, rootGrant = folder),
                    busy = false, preparing = false
                )
            } catch (cancel: CancellationException) {
                _import.value = _import.value.copy(busy = false, preparing = false)
                throw cancel
            } catch (_: Exception) {
                _import.value = _import.value.copy(busy = false, preparing = false)
                _error.value = R.string.selection_failed
            }
        }
    }

    fun setCombinedBooks(combined: Boolean) {
        val selection = _import.value.selection ?: return
        if (!_import.value.busy) _import.value = _import.value.copy(selection = selection.copy(combined = combined))
    }

    fun selectGroup(id: String, selected: Boolean) {
        val selection = _import.value.selection ?: return
        if (_import.value.busy || selection.groups.none { it.id == id }) return
        val ids = if (selected) selection.selectedGroupIds + id else selection.selectedGroupIds - id
        _import.value = _import.value.copy(selection = selection.copy(selectedGroupIds = ids))
    }

    fun selectAllGroups(selected: Boolean) {
        val selection = _import.value.selection ?: return
        if (!_import.value.busy) _import.value = _import.value.copy(selection = selection.copy(
            selectedGroupIds = if (selected) selection.groups.map { it.id }.toSet() else emptySet()))
    }

    private fun sourceFailureMessage(failure: Exception, fallback: Int): Int = when (failure) {
        is io.github.jnesew.paudiobooks.storage.DocumentSourceException -> when (failure.reason) {
            io.github.jnesew.paudiobooks.storage.DocumentSourceException.Reason.NON_SEEKABLE -> R.string.link_nonseekable
            io.github.jnesew.paudiobooks.storage.DocumentSourceException.Reason.BUSY -> R.string.source_reader_busy
            io.github.jnesew.paudiobooks.storage.DocumentSourceException.Reason.TIMEOUT -> R.string.source_read_timeout
            else -> R.string.link_failed
        }
        is io.github.jnesew.paudiobooks.importing.UnverifiedLinkedAudioException -> R.string.unverified_audio
        is io.github.jnesew.paudiobooks.storage.SourceGrantBudgetException -> R.string.source_permission_capacity
        is io.github.jnesew.paudiobooks.storage.SourceGrantException -> R.string.link_grant_failed
        is io.github.jnesew.paudiobooks.storage.SourceReaderBusyException -> R.string.source_reader_busy
        is io.github.jnesew.paudiobooks.storage.SourceReadTimeoutException -> R.string.source_read_timeout
        is io.github.jnesew.paudiobooks.importing.ImportMetadataLimitException -> R.string.import_metadata_limit
        else -> fallback
    }

    fun dismissRepairComplete() { _import.value = _import.value.copy(repairedFiles = null) }

    fun repairBook(bookId: String, uris: List<Uri>, controller: MediaController?, activeBookId: String?) {
        if (_import.value.busy || uris.isEmpty()) return
        work = viewModelScope.launch {
            _import.value = _import.value.copy(busy = true)
            var unloaded = false
            try {
                if (activeBookId == bookId) {
                    check(controller?.command(PlaybackService.UNLOAD_AND_SAVE) == true)
                    unloaded = true
                }
                val inventory = DocumentInventory(app.contentResolver)
                val files = uris.distinct().take(1001).map { inventory.describe(it) }
                require(files.size in 1..1000)
                val linked = app.library.library().tracks(bookId).any { app.library.sources().source(it.assetId)!=null }
                if (linked) {
                    _import.value=_import.value.copy(verifyingSource=true)
                    val count = app.linker.reconnect(bookId,files) { done,total,bytes ->
                        _import.value=_import.value.copy(inspecting=false,verifyingSource=true,sourceBytesRead=bytes,
                            progress=ImportProgress(done,total,bytes,null))
                    }
                    _import.value=ImportUi(interruptedJobs=_import.value.interruptedJobs,repairedFiles=count,linkedRecovery=true)
                    return@launch
                }
                val preview = app.importer.prepare(files) { progress ->
                    _import.value = _import.value.copy(progress = progress)
                }
                staged = preview
                val count = app.importer.repair(bookId, preview)
                staged = null
                _import.value = ImportUi(interruptedJobs = _import.value.interruptedJobs, repairedFiles = count)
            } catch (cancel: CancellationException) {
                cleanupStaged()
                _import.value = ImportUi()
                throw cancel
            } catch (failure: Exception) {
                cleanupStaged()
                _import.value = ImportUi(interruptedJobs = _import.value.interruptedJobs)
                _error.value = sourceFailureMessage(failure, R.string.repair_failed)
                refreshInterrupted()
            } finally {
                if (unloaded && controller?.loadBook(bookId) != true) _error.value = R.string.playback_failed
            }
        }
    }

    fun reauthorizeBook(bookId: String, uri: Uri, controller: MediaController?, activeBookId: String?) {
        if (_import.value.busy) return
        _import.value=_import.value.copy(busy=true)
        work=viewModelScope.launch {
            var unloaded=false
            try {
                if (activeBookId==bookId) { check(controller?.command(PlaybackService.UNLOAD_AND_SAVE)==true); unloaded=true }
                val count=app.linker.reauthorize(bookId,uri)
                _import.value=ImportUi(interruptedJobs=stoppedImportJobs(),repairedFiles=count,linkedRecovery=true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { _error.value=sourceFailureMessage(failure,R.string.link_failed) }
            finally {
                _import.value=_import.value.copy(busy=false)
                if (unloaded && controller?.loadBook(bookId)!=true) _error.value=R.string.playback_failed
            }
        }
    }

    fun rescanBook(bookId: String) {
        if (_import.value.busy || _import.value.batch!=null || _import.value.selection!=null || _sourceWork.value.busy) return
        _import.value=_import.value.copy(busy=true,scanning=true)
        work=viewModelScope.launch {
            try {
                val scan=app.linker.rescan(bookId)
                _import.value=_import.value.copy(rescan=scan,selectedNewUris=scan.newFiles.map { it.uri }.toSet())
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { _error.value=sourceFailureMessage(failure,R.string.rescan_failed) }
            finally { _import.value=_import.value.copy(busy=false,scanning=false) }
        }
    }
    fun selectRescanFile(uri: Uri, selected: Boolean) {
        val scan=_import.value.rescan ?: return
        if (_import.value.busy || scan.newFiles.none { it.uri==uri }) return
        _import.value=_import.value.copy(selectedNewUris=if (selected) _import.value.selectedNewUris+uri else _import.value.selectedNewUris-uri)
    }
    fun selectAllRescanFiles(selected: Boolean) {
        val scan=_import.value.rescan ?: return
        _import.value=_import.value.copy(selectedNewUris=if (selected) scan.newFiles.map { it.uri }.toSet() else emptySet())
    }
    fun dismissRescan() { if (!_import.value.busy) _import.value=_import.value.copy(rescan=null,selectedNewUris=emptySet()) }
    fun reviewRescanFiles() {
        val scan=_import.value.rescan ?: return
        if (_import.value.busy) return
        val files=scan.newFiles.filter { it.uri in _import.value.selectedNewUris }
        if (files.isEmpty()) return
        val source=SourceSelection(files,scan.title,reviewedTitle=scan.title,
            grantUris=listOf(Uri.parse(scan.root.grantUri)),rootDocument=Uri.parse(scan.root.documentUri),
            rootGrant=Uri.parse(scan.root.grantUri),appendToBookId=scan.bookId)
        _import.value=_import.value.copy(rescan=null,selectedNewUris=emptySet(),batch=ImportBatch(listOf(source)),busy=true)
        work=viewModelScope.launch { prepareNextBook() }
    }
    private suspend fun appended(preview: io.github.jnesew.paudiobooks.importing.LinkedImportPreview?, bookId: String): Boolean {
        if (preview==null || preview.tracks.isEmpty() || !app.repository.bookExists(bookId)) return false
        val assets=app.library.library().tracks(bookId).map { it.assetId }.toSet()
        return preview.tracks.all { it.assetId in assets }
    }

    fun reviewSelection() {
        val selection = _import.value.selection ?: return
        if (_import.value.busy) return
        startBulkReview(selection.books())
    }

    private fun startBulkReview(books: List<SourceSelection>, retained: List<BookImportResult> = emptyList()) {
        if (books.isEmpty()) return
        _import.value = _import.value.copy(selection = null, review = emptyList(), editingReviewId = null,
            batch = ImportBatch(books, total = books.size + retained.size, results = retained), busy = true, preparing = true)
        work = viewModelScope.launch {
            val knownDocuments = try { app.library.sources().documentUris().toSet() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                _import.value = _import.value.copy(busy = false, preparing = false, review = null, batch = null)
                _error.value = R.string.library_read_error
                return@launch
            }
            var retainedText = 0L
            var retainedChapters = 0L
            for (source in books) {
                val batch = requireNotNull(_import.value.batch)
                val bookId = java.util.UUID.randomUUID().toString()
                _import.value = _import.value.copy(batch = batch.copy(pending = batch.pending.drop(1), active = source, activeBookId = bookId),
                    inspecting = true, sourceBytesRead = 0, progress = null)
                try {
                    var preview = app.linker.prepare(source.files, source.grantUris, source.rootDocument, source.rootGrant,
                        metadataForReview = true) { done, total, bytes -> _import.value = _import.value.copy(sourceBytesRead = bytes, progress = ImportProgress(done,total,bytes,null)) }
                    stagedLink = preview
                    if (preview.tracks.none { it.metadata.artworkThumbnail != null } && source.companionCover != null) {
                        // A broken optional image must not prevent adding otherwise healthy audio.
                        val art = try { app.importer.stageAndInspectCover(source.companionCover.uri) { app.inspector.inspectArtwork(it) } }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                        if (art != null) preview = preview.copy(tracks = preview.tracks.mapIndexed { i, track ->
                            if (i == 0) track.copy(metadata = track.metadata.copy(artworkThumbnail = art)) else track })
                    }
                    val ordered = if (source.keepFileOrder) preview.tracks else orderTrackIndices(preview.tracks.map {
                        TrackOrderHint(it.selected.name, it.discNumber, it.trackNumber, it.selected.subfolder, it.selected.orderKey)
                    }).map { preview.tracks[it] }
                    val title = source.reviewedTitle ?: suggestedBookTitle(ordered.map { it.metadata.bookMetadata }, ordered.map { it.metadata.title }, source.title)
                    val entryText = ordered.sumOf { it.metadata.bookMetadata.textLength() + it.metadata.chapters.sumOf { c -> c.title?.length?.toLong() ?: 0L } }
                    val entryChapters = ordered.sumOf { it.metadata.chapters.size.toLong() }
                    if (retainedText + entryText > 8L * 1024 * 1024 || retainedChapters + entryChapters > 100_000) throw io.github.jnesew.paudiobooks.importing.ImportMetadataLimitException()
                    retainedText += entryText
                    retainedChapters += entryChapters
                    reviewJobs += preview.jobId
                    stagedLink = null
                    val prepared = PreparedLinkedImport(preview, ordered, title, bookId)
                    _import.value = _import.value.copy(review = _import.value.review.orEmpty() + LinkedBookReview(source, prepared, alreadyLinked = source.files.all { it.uri.toString() in knownDocuments }))
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    cleanupStaged()
                    _import.value = _import.value.copy(review = _import.value.review.orEmpty() + LinkedBookReview(source,
                        errorMessage = sourceFailureMessage(failure, R.string.link_failed)))
                }
            }
            _import.value = _import.value.copy(busy = false, preparing = false, inspecting = false,
                batch = _import.value.batch?.copy(active = null, activeBookId = null))
        }
    }

    fun finishFailedReview() {
        val review = _import.value.review ?: return
        if (_import.value.busy || review.any { it.prepared != null }) return
        val batch = _import.value.batch ?: return
        _import.value = _import.value.copy(review = null, batch = batch.copy(results = batch.results + review.map {
            BookImportResult(it.source, BookImportStatus.FAILED, errorMessage = it.errorMessage)
        }))
    }

    fun selectReviewBook(id: String, selected: Boolean) {
        if (_import.value.busy) return
        _import.value = _import.value.copy(review = _import.value.review?.map {
            if (it.id == id && it.prepared != null) it.copy(selected = selected) else it
        })
    }
    fun selectAllReviewBooks(selected: Boolean) {
        if (_import.value.busy) return
        _import.value = _import.value.copy(review = _import.value.review?.map { it.copy(selected = selected && it.prepared != null) })
    }
    fun editReviewBook(id: String?) {
        if (!_import.value.busy) _import.value = _import.value.copy(editingReviewId = id)
    }
    fun updateReviewTitle(id: String, title: String) {
        if (_import.value.busy) return
        _import.value = _import.value.copy(review = _import.value.review?.map {
            if (it.id == id) it.copy(prepared = it.prepared?.copy(title = title.take(512))) else it
        })
    }
    fun reorderReviewFile(id: String, index: Int, delta: Int) {
        if (_import.value.busy) return
        _import.value = _import.value.copy(review = _import.value.review?.map { entry ->
            val prepared = entry.prepared
            if (entry.id != id || prepared == null || index !in prepared.tracks.indices || index + delta !in prepared.tracks.indices) entry
            else {
                val tracks = prepared.tracks.toMutableList()
                tracks.add(index + delta, tracks.removeAt(index))
                entry.copy(prepared = prepared.copy(tracks = tracks))
            }
        })
    }

    fun approveReview() {
        val review = _import.value.review ?: return
        if (_import.value.busy || review.none { it.selected } || review.any { it.selected && it.prepared?.title.isNullOrBlank() }) return
        val retained = _import.value.batch?.results.orEmpty()
        _import.value = _import.value.copy(review = null, editingReviewId = null, busy = true, preparing = false, progress = null, sourceBytesRead = 0,
            batch = ImportBatch(review.map { it.reviewedSource() }, total = review.size + retained.size, results = retained))
        work = viewModelScope.launch {
            for (entry in review) {
                val prepared = entry.prepared
                val source = entry.reviewedSource()
                val batch = requireNotNull(_import.value.batch)
                _import.value = _import.value.copy(batch = batch.copy(pending = batch.pending.drop(1), active = source,
                    activeBookId = prepared?.bookId), inspecting = false)
                if (prepared == null) { finishActiveBook(BookImportStatus.FAILED, errorMessage = entry.errorMessage); continue }
                stagedLink = prepared.preview
                reviewJobs -= prepared.preview.jobId
                if (!entry.selected) {
                    cleanupStaged(); finishActiveBook(BookImportStatus.SKIPPED); continue
                }
                try {
                    val id = app.linker.commit(prepared.preview, prepared.tracks, source.reviewedTitle!!, prepared.bookId)
                    stagedLink = null
                    finishActiveBook(BookImportStatus.ADDED, id)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    val committed = app.repository.bookExists(prepared.bookId)
                    cleanupStaged()
                    finishActiveBook(if (committed) BookImportStatus.ADDED else BookImportStatus.FAILED,
                        prepared.bookId.takeIf { committed }, sourceFailureMessage(failure, R.string.link_failed).takeIf { !committed })
                }
            }
            _import.value = _import.value.copy(busy = false, interruptedJobs = stoppedImportJobs())
        }
    }

    private suspend fun prepareNextBook() {
        while (true) {
            val batch = _import.value.batch ?: return
            val selected = batch.pending.firstOrNull()
            if (selected == null) {
                val jobs = stoppedImportJobs()
                _import.value = _import.value.copy(busy = false, inspecting = false, progress = null, linkedPrepared = null, interruptedJobs = jobs)
                return
            }
            val bookId = java.util.UUID.randomUUID().toString()
            _import.value = _import.value.copy(busy = true, inspecting = false, progress = null, linkedPrepared = null,
                batch = batch.copy(pending = batch.pending.drop(1), active = selected, activeBookId = bookId))
            try {
                    _import.value = _import.value.copy(inspecting = true, sourceBytesRead = 0)
                    var preview = app.linker.prepare(selected.files,selected.grantUris,selected.rootDocument,selected.rootGrant,metadataForReview=true) { done,total,bytes ->
                        _import.value = _import.value.copy(sourceBytesRead = bytes, progress = ImportProgress(done,total,bytes,null))
                    }
                    stagedLink = preview
                    if (preview.tracks.none { it.metadata.artworkThumbnail != null } && selected.companionCover != null) {
                        val art = app.importer.stageAndInspectCover(selected.companionCover.uri) { app.inspector.inspectArtwork(it) }
                        if (art != null) preview = preview.copy(tracks = preview.tracks.mapIndexed { i,track -> if (i==0) track.copy(metadata=track.metadata.copy(artworkThumbnail=art)) else track })
                        stagedLink = preview
                    }
                    val ordered = if (selected.keepFileOrder) preview.tracks else orderTrackIndices(preview.tracks.map { track ->
                        TrackOrderHint(track.selected.name,track.discNumber,track.trackNumber,track.selected.subfolder,track.selected.orderKey)
                    }).map { preview.tracks[it] }
                    val title = selected.reviewedTitle ?: suggestedBookTitle(ordered.map { it.metadata.bookMetadata },ordered.map { it.metadata.title },selected.title)
                    _import.value = _import.value.copy(busy=false, inspecting=false,linkedPrepared=PreparedLinkedImport(preview,ordered,title,bookId,selected.appendToBookId))
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                cleanupStaged()
                finishActiveBook(BookImportStatus.FAILED, errorMessage = sourceFailureMessage(failure, R.string.import_failed))
            }
        }
    }

    private fun finishActiveBook(status: BookImportStatus, bookId: String? = null, errorMessage: Int? = null) {
        val batch = _import.value.batch ?: return
        val source = batch.active ?: return
        _import.value = _import.value.copy(linkedPrepared = null,
            batch = batch.copy(active = null, activeBookId = null,
                results = batch.results + BookImportResult(source, status, bookId, errorMessage)))
    }

    fun dismissImportResults() {
        if (!_import.value.busy && _import.value.batch?.finished == true) {
            _import.value = _import.value.copy(batch = null)
        }
    }

    fun retryUnfinishedBooks() {
        val batch = _import.value.batch ?: return
        if (_import.value.busy || !batch.finished || batch.unfinished.isEmpty()) return
        val retained = batch.results.filter { it.status == BookImportStatus.ADDED || it.status == BookImportStatus.SKIPPED }
        if (batch.unfinished.any { it.source.appendToBookId != null }) {
            _import.value = _import.value.copy(busy = true, batch = ImportBatch(batch.unfinished.map { it.source }, total = batch.total, results = retained))
            work = viewModelScope.launch { prepareNextBook() }
        } else startBulkReview(batch.unfinished.map { it.source }, retained)
    }

    fun reorderLinked(index: Int, delta: Int) {
        val prepared = _import.value.linkedPrepared ?: return
        if (_import.value.busy || index+delta !in prepared.tracks.indices) return
        val tracks = prepared.tracks.toMutableList()
        tracks.add(index+delta,tracks.removeAt(index))
        _import.value = _import.value.copy(linkedPrepared=prepared.copy(tracks=tracks))
    }
    fun commitLinked(title: String, controller: MediaController? = null) {
        val prepared = _import.value.linkedPrepared ?: return
        val batch = _import.value.batch ?: return
        val active = batch.active ?: return
        if (_import.value.busy || title.isBlank()) return
        if (prepared.appendToBookId!=null && controller==null) { _error.value=R.string.playback_failed; return }
        val reviewed = active.copy(reviewedTitle=title.trim().take(512),keepFileOrder=true,files=prepared.tracks.map { it.selected })
        _import.value = _import.value.copy(busy=true,batch=batch.copy(active=reviewed))
        work = viewModelScope.launch {
            val target=prepared.appendToBookId ?: prepared.bookId
            var unloaded=false
            try {
                if (prepared.appendToBookId!=null && controller?.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.BOOK_ID)==target) {
                    check(controller.command(PlaybackService.UNLOAD_AND_SAVE)); unloaded=true
                }
                val id=if (prepared.appendToBookId!=null) app.linker.append(prepared.preview,prepared.tracks,target)
                    else app.linker.commit(prepared.preview,prepared.tracks,requireNotNull(reviewed.reviewedTitle),target)
                if (unloaded) {
                    if (controller?.loadBook(target)!=true) _error.value=R.string.playback_failed
                    unloaded=false
                }
                stagedLink = null
                finishActiveBook(BookImportStatus.ADDED,id)
                if (batch.total==1) { _import.value=ImportUi(interruptedJobs=stoppedImportJobs()); _openedBook.value=id }
                else prepareNextBook()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                val committed=if (prepared.appendToBookId!=null) appended(prepared.preview,target) else app.repository.bookExists(target)
                cleanupStaged()
                finishActiveBook(if (committed) BookImportStatus.ADDED else BookImportStatus.FAILED,
                    target.takeIf { committed },sourceFailureMessage(failure,R.string.link_failed).takeIf { !committed })
                prepareNextBook()
            } finally {
                if (unloaded) withContext(NonCancellable) { if (controller?.loadBook(target)!=true) _error.value=R.string.playback_failed }
            }
        }
    }

    fun cancelImport() {
        if (cancellingImport || (!_import.value.busy && _import.value.selection == null && _import.value.linkedPrepared == null && _import.value.review == null)) return
        cancellingImport = true
        _import.value = _import.value.copy(busy = true)
        val previous = work
        previous?.cancel()
        work = viewModelScope.launch {
            try {
                previous?.join()
                val batch = _import.value.batch
                val appendTarget=batch?.active?.appendToBookId
                val committedId=if (appendTarget!=null) appendTarget.takeIf { appended(stagedLink,it) }
                    else batch?.activeBookId?.takeIf { app.repository.bookExists(it) }
                val review = _import.value.review.orEmpty()
                cleanupStaged()
                cleanupReviewJobs()
                val reviewedPending = review.map { BookImportResult(it.reviewedSource(), if (it.selected) BookImportStatus.PENDING else if (it.prepared == null) BookImportStatus.FAILED else BookImportStatus.SKIPPED, errorMessage = it.errorMessage) }
                val stopped = batch?.copy(active = null, activeBookId = null, pending = emptyList(),
                    results = batch.results + reviewedPending + listOfNotNull(batch.active?.takeIf { active -> review.none { it.source == active } }?.let {
                        BookImportResult(it, if (committedId != null) BookImportStatus.ADDED else BookImportStatus.PENDING, committedId)
                    }) + batch.pending.map { BookImportResult(it, BookImportStatus.PENDING) })
                _import.value = _import.value.copy(selection = null, review = null, editingReviewId = null, linkedPrepared = null, busy = false, preparing = false,
                    inspecting = false, scanning=false, rescan=null, selectedNewUris=emptySet(), progress = null, batch = stopped, interruptedJobs = stoppedImportJobs())
            } finally { cancellingImport = false }
        }
    }

    fun leaveForeground() {
        if (_sourceWork.value.busy) sourceJob?.cancel()
        if (_import.value.busy) {
            cancelImport()
            _error.value = R.string.import_interrupted_foreground
        }
        if (_coverBusy.value) { coverWork?.cancel(); _error.value = R.string.cover_interrupted }
    }

    fun discardInterrupted() {
        if (_import.value.busy) return
        val targets = _import.value.interruptedJobs
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _import.value = _import.value.copy(busy = true)
            try {
                app.importer.reconcileInterrupted(targets)
                refreshInterrupted()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _error.value = R.string.cleanup_failed
            } finally {
                _import.value = _import.value.copy(busy = false)
            }
        }
    }

    fun setCover(id: String, uri: Uri) {
        if (_coverBusy.value) return
        _coverBusy.value = true
        coverWork = viewModelScope.launch {
            try {
                require(uri.scheme == ContentResolver.SCHEME_CONTENT)
                check(app.importer.setCoverOverride(id, uri) { app.inspector.inspectArtwork(it) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _error.value = R.string.cover_failed }
            finally { _coverBusy.value = false }
        }
    }
    fun resetCover(id: String) {
        if (_coverBusy.value) return
        _coverBusy.value = true
        coverWork = viewModelScope.launch {
            try { check(app.importer.resetCoverOverride(id)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _error.value = R.string.cover_failed }
            finally { _coverBusy.value = false }
        }
    }
    fun setFinished(id: String, finished: Boolean, controller: MediaController?) {
        viewModelScope.launch {
            try {
                // Serialize the explicit mutation through the existing service writer.
                // Its cached checkpoint must not later undo a reset of an inactive book.
                check(controller?.command(PlaybackService.SET_BOOK_FINISHED, android.os.Bundle().apply {
                    putString(PlaybackService.BOOK_ID, id)
                    putBoolean(PlaybackService.FINISHED, finished)
                }) == true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _error.value = R.string.edit_failed }
        }
    }
    suspend fun removeBook(id: String): Boolean = try {
        app.importer.removeBook(id)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    }
    fun refreshInterrupted() {
        viewModelScope.launch {
            try {
                val jobs = app.importer.interruptedJobs().map { it.id }.filter { it !in reviewJobs && it != stagedLink?.jobId && it != staged?.jobId }
                _import.value = _import.value.copy(interruptedJobs = jobs)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _error.value = R.string.library_read_error
            }
        }
    }
    /** Snapshot only after this batch worker stopped: never classify a new active job as interrupted. */
    private suspend fun stoppedImportJobs(): List<String> = try {
        app.importer.interruptedJobs().map { it.id }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        _error.value = R.string.library_read_error
        _import.value.interruptedJobs
    }
    private suspend fun cleanupStaged() = withContext(NonCancellable) {
        val linked = stagedLink
        stagedLink = null
        if (linked != null) try { app.importer.cancel(linked.jobId) } catch (_: Exception) { _error.value = R.string.cleanup_failed }
        val preview = staged
        staged = null
        if (preview != null) try { app.importer.cancel(preview.jobId) }
        catch (_: Exception) { _error.value = R.string.cleanup_failed }
    }

    private suspend fun cleanupReviewJobs() = withContext(NonCancellable) {
        for (job in reviewJobs.toList()) {
            try { app.importer.cancel(job) } catch (_: Exception) { _error.value = R.string.cleanup_failed }
            reviewJobs -= job
        }
    }

    override fun onCleared() {
        val jobs = reviewJobs.toList()
        app.cleanupScope.launch { jobs.forEach { runCatching { app.importer.cancel(it) } } }
        val linked = stagedLink
        if (linked != null) app.cleanupScope.launch { runCatching { app.importer.cancel(linked.jobId) } }
        val preview = staged
        if (preview != null) app.cleanupScope.launch { runCatching { app.importer.cancel(preview.jobId) } }
        super.onCleared()
    }
}
