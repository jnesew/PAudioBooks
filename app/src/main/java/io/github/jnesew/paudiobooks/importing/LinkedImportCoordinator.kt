package io.github.jnesew.paudiobooks.importing

import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import io.github.jnesew.paudiobooks.library.*
import io.github.jnesew.paudiobooks.metadata.MetadataInspector
import io.github.jnesew.paudiobooks.metadata.BookMetadata
import io.github.jnesew.paudiobooks.metadata.agreedBookMetadata
import io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess
import io.github.jnesew.paudiobooks.storage.DocumentSourceException
import io.github.jnesew.paudiobooks.storage.PrivateAudioStore
import io.github.jnesew.paudiobooks.storage.SourceGrantStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LinkedPreviewTrack(val assetId: String, val selected: SelectedDocument, val grantUri: String,
    val size: Long?, val modified: Long?, val metadata: ImportMetadata, val pending: Boolean, val fileBoundary: Boolean = false,
    val discNumber: Int? = null, val trackNumber: Int? = null)
data class LinkedRescan(val bookId: String, val title: String, val root: LinkedBookRoot,
    val newFiles: List<SelectedDocument>, val retainedTracks: Int, val missingTracks: Int, val changedTracks: Int)
data class LinkedMetadataResult(val completedFiles: Int, val pendingFiles: Int, val sourceBytesRead: Long)
data class LinkedImportPreview(val jobId: String, val tracks: List<LinkedPreviewTrack>, val sourceBytesRead: Long,
    val rootDocument: Uri? = null, val rootGrant: Uri? = null)

/** Addition reads bounded metadata only. Full byte identity is a separate explicit operation. */
@UnstableApi
internal class LinkedImportCoordinator(private val db: LibraryDatabase, private val store: PrivateAudioStore,
    private val importer: ImportCoordinator, private val grants: SourceGrantStore, private val access: BoundedDocumentAccess,
    private val inspector: MetadataInspector, private val inventory: DocumentInventory) {
    private val mutex = Mutex()
    // Bounded scheduling cache only; all discovered metadata stays durable in Room.
    private val metadataNext = LinkedHashMap<String,String>()
    private fun rememberMetadataStart(bookId: String, nextAsset: String?) {
        metadataNext.remove(bookId)
        if (nextAsset!=null) metadataNext[bookId]=nextAsset
        if (metadataNext.size>64) metadataNext.remove(metadataNext.keys.first())
    }
    companion object { const val FILE_INSPECTION_BYTES = 2L * 1024 * 1024; const val BOOK_INSPECTION_BYTES = 16L * 1024 * 1024
        const val METADATA_FILE_BYTES = 64L * 1024 * 1024; const val METADATA_BOOK_BYTES = 64L * 1024 * 1024 }
    private fun id() = UUID.randomUUID().toString()
    private fun owningGrant(uri: Uri, grants: List<Uri>): Uri = grants.firstOrNull { grant ->
        uri == grant || uri.authority == grant.authority && DocumentsContract.isTreeUri(grant) &&
            DocumentsContract.isTreeUri(uri) && runCatching { DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(grant) }.getOrDefault(false)
    } ?: throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)

    suspend fun prepare(files: List<SelectedDocument>, grantUris: List<Uri>, rootDocument: Uri? = null,
        rootGrant: Uri? = null, metadataForReview: Boolean = false, onProgress: (Int,Int,Long) -> Unit = { _,_,_ -> }): LinkedImportPreview = mutex.withLock {
        require(files.size in 1..1000 && files.map { it.uri }.distinct().size == files.size)
        files.forEach { owningGrant(it.uri,grantUris) }
        if (rootDocument != null) require(rootGrant != null && owningGrant(rootDocument,grantUris)==rootGrant)
        val job = id()
        db.library().insertJob(ImportJob(job,"LINKING",System.currentTimeMillis()))
        try {
            grants.reserve(job,grantUris)
            val tracks = ArrayList<LinkedPreviewTrack>()
            var bytes = 0L
            var artworkRetained = false
            onProgress(0,files.size,0)
            for (selected in files) {
                currentCoroutineContext().ensureActive()
                val grant = owningGrant(selected.uri,grantUris).toString()
                val holder = AtomicReference<BoundedDocumentAccess.Handle?>()
                try {
                    withContext(Dispatchers.IO) {
                        holder.set(access.open(selected.uri,grants.leaseForJob(job,grant),selected.sizeBytes))
                    }
                    val handle = requireNotNull(holder.get())
                    // The fast inventory budget can discard an entire metadata result, including
                    // chapters/art, when a large tag exceeds 2MiB. A user-visible review
                    // gets the same larger bounded pass as explicit metadata recovery.
                    val budget = if (metadataForReview) minOf(METADATA_FILE_BYTES,METADATA_BOOK_BYTES-bytes)
                        else minOf(FILE_INSPECTION_BYTES,BOOK_INSPECTION_BYTES-bytes)
                    val result = if (budget > 0) inspector.inspectLinked(handle.descriptor,budget) { read -> onProgress(tracks.size,files.size,bytes+read) } else null
                    bytes += result?.sourceBytesRead ?: 0
                    val metadata = result?.metadata
                    val duration = metadata?.durationMs
                    val chapters = metadata?.chapters?.map { ImportChapter(it.startMs,it.endMs,it.title) }
                        ?.takeIf { it.isNotEmpty() } ?: listOf(ImportChapter(0,duration,null))
                    val art = metadata?.artworkThumbnail?.takeUnless { artworkRetained }
                    if (art != null) artworkRetained = true
                    val assetId=id()
                    db.library().insertImportFile(ImportFile(assetId,job,"LINKING"))
                    tracks.add(LinkedPreviewTrack(assetId,selected,grant,handle.length ?: selected.sizeBytes,handle.modified,
                        ImportMetadata(metadata?.title,duration,chapters,metadata?.bookMetadata ?: BookMetadata(),art,metadata?.mimeType),metadata==null,metadata?.chapters.isNullOrEmpty(),metadata?.discNumber,metadata?.trackNumber))
                    require(tracks.all { it.metadata.bookMetadata.isBounded() })
                    if (tracks.sumOf { it.metadata.bookMetadata.textLength() } > ImportCoordinator.MAX_BOOK_METADATA_TEXT) throw ImportMetadataLimitException()
                    if (tracks.sumOf { it.metadata.chapters.size } > ImportCoordinator.MAX_BOOK_CHAPTERS ||
                        tracks.sumOf { it.metadata.chapters.sumOf { c -> c.title?.length?.toLong() ?: 0L } } > ImportCoordinator.MAX_BOOK_CHAPTER_TEXT)
                        throw ImportMetadataLimitException()
                    onProgress(tracks.size,files.size,bytes)
                } finally { holder.get()?.close() }
                holder.get()?.awaitClosed()
            }
            LinkedImportPreview(job,tracks,bytes,rootDocument,rootGrant)
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { importer.cancel(job) } } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun commit(preview: LinkedImportPreview, ordered: List<LinkedPreviewTrack>, title: String,
        bookId: String): String = mutex.withLock {
        require(title.isNotBlank() && title.length <= 512 && UUID.fromString(bookId).toString()==bookId)
        require(ordered.size == preview.tracks.size && ordered.map { it.assetId }.toSet()==preview.tracks.map { it.assetId }.toSet())
        require(ordered.all { track -> preview.tracks.single { it.assetId==track.assetId }==track && track.metadata.bookMetadata.isBounded() })
        require(ordered.sumOf { it.metadata.bookMetadata.textLength() } <= ImportCoordinator.MAX_BOOK_METADATA_TEXT)
        require(db.library().jobs().any { it.id==preview.jobId && it.state=="LINKING" })
        try {
            val thumbnail = ordered.firstNotNullOfOrNull { it.metadata.artworkThumbnail }
            val cover = thumbnail?.let {
                val coverId = id()
                db.library().insertImportFile(ImportFile(coverId,preview.jobId,"STAGED"))
                store.writeCover(coverId,it)
                "covers/$coverId"
            }
            db.withTransaction {
                val first = agreedBookMetadata(ordered.map { it.metadata.bookMetadata })
                db.books().insert(Book(bookId,title,importedTitle=first.title,importedAuthor=first.author,
                    importedNarrator=first.narrator,importedSeries=first.series,importedTranslator=first.translator,
                    importedSeriesNumber=first.seriesNumber,coverPath=cover,addedAt=System.currentTimeMillis(),
                    importedGenre=first.genre,importedPublisher=first.publisher,importedDate=first.date,
                    importedReleaseDate=first.releaseDate,importedDescription=first.description,metadataManaged=true))
                db.library().insertAssets(ordered.map { AudioAsset(it.assetId,"",it.size ?: 0,"",it.metadata.durationMs,it.metadata.mimeType,false) })
                db.sources().insertSources(ordered.map { DocumentSource(it.assetId,it.selected.uri.toString(),it.grantUri,it.size,it.modified,it.pending,"AVAILABLE") })
                val tracks = ordered.mapIndexed { index,item -> BookTrack(id(),bookId,item.assetId,index,item.metadata.title,item.metadata.bookMetadata) }
                db.library().insertTracks(tracks)
                db.library().insertChapters(ordered.flatMapIndexed { index,item -> item.metadata.chapters.mapIndexed { position,chapter ->
                    Chapter(id(),tracks[index].id,position,chapter.startMs,chapter.endMs,chapter.title,if (item.fileBoundary) "file" else "embedded")
                } })
                if (preview.rootDocument != null) db.sources().insertRoot(LinkedBookRoot(bookId,preview.rootDocument.toString(),requireNotNull(preview.rootGrant).toString()))
            }
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { importer.cancel(preview.jobId) } } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        withContext(NonCancellable) { runCatching { importer.cancel(preview.jobId) } }
        bookId
    }

    /** Only a completed bounded inventory may change availability; absent tracks are never deleted. */
    suspend fun rescan(bookId: String, maxEntries: Int = 5000): LinkedRescan = mutex.withLock {
        val book=requireNotNull(db.books().get(bookId))
        val root=requireNotNull(db.sources().root(bookId))
        val tracks=db.library().tracks(bookId)
        val sources=tracks.mapNotNull { db.sources().source(it.assetId) }.distinctBy { it.assetId }
        val scanned=inventory.treeInventory(Uri.parse(root.grantUri),maxEntries=maxEntries,rootDocument=Uri.parse(root.documentUri))
        val byUri=scanned.audioFiles.associateBy { it.uri.toString() }
        val known=sources.map { it.documentUri }.toSet()
        var missing=0; var changed=0
        db.withTransaction {
            require(db.sources().root(bookId)==root && db.library().tracks(bookId)==tracks)
            for (source in sources) {
                require(db.sources().source(source.assetId)==source)
                val selected=byUri[source.documentUri]
                when {
                    selected==null -> { missing++; db.sources().setAvailability(source.assetId,"MISSING") }
                    selected.sizeBytes!=null && source.sizeHint!=null && selected.sizeBytes!=source.sizeHint -> {
                        changed++; db.sources().setAvailability(source.assetId,"CHANGED")
                    }
                    source.availability=="MISSING" -> db.sources().setAvailability(source.assetId,"UNCHECKED")
                }
            }
        }
        LinkedRescan(bookId,book.titleOverride ?: book.title,root,scanned.audioFiles.filter { it.uri.toString() !in known },tracks.size,missing,changed)
    }

    /** Append reviewed new documents without rebuilding the book, old tracks or saved state. */
    suspend fun append(preview: LinkedImportPreview, ordered: List<LinkedPreviewTrack>, bookId: String): String = mutex.withLock {
        require(ordered.isNotEmpty() && ordered.size==preview.tracks.size && ordered.map { it.assetId }.toSet()==preview.tracks.map { it.assetId }.toSet())
        require(ordered.all { item -> preview.tracks.single { it.assetId==item.assetId }==item && item.metadata.bookMetadata.isBounded() })
        require(ordered.sumOf { it.metadata.bookMetadata.textLength() } <= ImportCoordinator.MAX_BOOK_METADATA_TEXT)
        require(db.library().jobs().any { it.id==preview.jobId && it.state=="LINKING" })
        try {
            val root=requireNotNull(db.sources().root(bookId))
            require(preview.rootDocument?.toString()==root.documentUri && preview.rootGrant?.toString()==root.grantUri)
            val currentFiles=inventory.treeInventory(Uri.parse(root.grantUri),rootDocument=Uri.parse(root.documentUri)).audioFiles.map { it.uri.toString() }.toSet()
            require(ordered.all { it.selected.uri.toString() in currentFiles })
            db.withTransaction {
                require(db.sources().root(bookId)==root && db.books().get(bookId)!=null)
                val existing=db.library().tracks(bookId)
                require(existing.size+ordered.size<=1000)
                val known=existing.mapNotNull { db.sources().source(it.assetId)?.documentUri }.toSet()
                require(ordered.none { it.selected.uri.toString() in known })
                val oldChapters=existing.flatMap { db.library().chapters(it.id) }
                require(oldChapters.size.toLong()+ordered.sumOf { it.metadata.chapters.size.toLong() }<=ImportCoordinator.MAX_BOOK_CHAPTERS)
                require(oldChapters.sumOf { it.title?.length?.toLong() ?: 0L }+ordered.sumOf { it.metadata.chapters.sumOf { c -> c.title?.length?.toLong() ?: 0L } }<=ImportCoordinator.MAX_BOOK_CHAPTER_TEXT)
                db.library().insertAssets(ordered.map { AudioAsset(it.assetId,"",it.size ?: 0,"",it.metadata.durationMs,it.metadata.mimeType,false) })
                db.sources().insertSources(ordered.map { DocumentSource(it.assetId,it.selected.uri.toString(),it.grantUri,it.size,it.modified,it.pending,"AVAILABLE") })
                val start=(existing.maxOfOrNull { it.position } ?: -1)+1
                val added=ordered.mapIndexed { i,item -> BookTrack(id(),bookId,item.assetId,start+i,item.metadata.title,item.metadata.bookMetadata) }
                db.library().insertTracks(added)
                db.refreshImportedMetadata(bookId)
                db.library().insertChapters(ordered.flatMapIndexed { i,item -> item.metadata.chapters.mapIndexed { position,chapter ->
                    Chapter(id(),added[i].id,position,chapter.startMs,chapter.endMs,chapter.title,if (item.fileBoundary) "file" else "embedded")
                } })
            }
        } finally { withContext(NonCancellable) { runCatching { importer.cancel(preview.jobId) } } }
        bookId
    }

    /** Explicit bounded catch-up. Inspection/cancellation failure leaves all old metadata intact. */
    suspend fun readPendingMetadata(bookId: String, progress: (Int,Int,Long) -> Unit = { _,_,_ -> }): LinkedMetadataResult = mutex.withLock {
        val tracks=db.library().tracks(bookId)
        require(tracks.isNotEmpty() && tracks.size<=1000)
        val pending=tracks.mapNotNull { db.sources().source(it.assetId) }.distinctBy { it.assetId }.filter { it.metadataPending }
        val start=pending.indexOfFirst { it.assetId==metadataNext[bookId] }.coerceAtLeast(0)
        val sources=pending.drop(start)+pending.take(start)
        var nextAsset: String?=null
        val results=linkedMapOf<String,io.github.jnesew.paudiobooks.metadata.InspectedAudio>()
        var bytes=0L
        var coverBytes: ByteArray?=null
        progress(0,sources.size,0)
        for ((index,source) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            val budget=minOf(METADATA_FILE_BYTES,METADATA_BOOK_BYTES-bytes)
            if (budget<=0) break
            val holder=AtomicReference<BoundedDocumentAccess.Handle?>()
            try {
                withContext(Dispatchers.IO) { holder.set(access.open(Uri.parse(source.documentUri),grants.leaseForSource(source),source.sizeHint,source.modifiedHint)) }
                val result=inspector.inspectLinked(requireNotNull(holder.get()).descriptor,budget) { read -> progress(index,sources.size,bytes+read) }
                bytes+=result.sourceBytesRead
                result.metadata?.let { metadata ->
                    if (coverBytes==null) coverBytes=metadata.artworkThumbnail
                    results[source.assetId]=metadata.copy(artworkThumbnail=null)
                }
                if (results.values.sumOf { it.bookMetadata.textLength() }>ImportCoordinator.MAX_BOOK_METADATA_TEXT ||
                    results.values.sumOf { it.chapters.size.toLong() }>ImportCoordinator.MAX_BOOK_CHAPTERS ||
                    results.values.sumOf { it.chapters.sumOf { c -> c.title?.length?.toLong() ?: 0L } }>ImportCoordinator.MAX_BOOK_CHAPTER_TEXT) throw ImportMetadataLimitException()
                nextAsset=sources.getOrNull(index+1)?.assetId ?: sources.firstOrNull()?.assetId
                progress(index+1,sources.size,bytes)
            } finally { holder.get()?.close() }
            holder.get()?.awaitClosed()
        }
        if (results.isEmpty()) {
            rememberMetadataStart(bookId,nextAsset)
            return@withLock LinkedMetadataResult(0,sources.size,bytes)
        }
        var cover: String?=null
        var job: String?=null
        try {
            if (coverBytes!=null && db.books().get(bookId)?.coverPath==null) {
                job=id(); db.library().insertJob(ImportJob(job,"METADATA",System.currentTimeMillis()))
                val coverId=id(); db.library().insertImportFile(ImportFile(coverId,job,"STAGED"))
                store.writeCover(coverId,requireNotNull(coverBytes)); cover="covers/$coverId"
            }
            db.withTransaction {
                require(db.library().tracks(bookId)==tracks && db.books().get(bookId)!=null)
                for (source in sources) {
                    val live=requireNotNull(db.sources().source(source.assetId))
                    // Availability may be refreshed by an already queued player open callback.
                    require(live.copy(availability=source.availability)==source)
                }
                val chapters=tracks.associate { track ->
                    val old=db.library().chapters(track.id)
                    val metadata=results[track.assetId]
                    val updated=if (metadata!=null && old.all { it.origin=="file" }) {
                        val incoming=metadata.chapters.takeIf { it.isNotEmpty() }
                        if (incoming!=null) incoming.mapIndexed { i,c -> Chapter(if (i==0) old.firstOrNull()?.id ?: id() else id(),track.id,i,c.startMs,c.endMs,c.title,"embedded") }
                        else if (old.isNotEmpty()) old.map { it.copy(endMs=metadata.durationMs ?: it.endMs) }
                        else emptyList()
                    } else old
                    track.id to updated
                }
                require(chapters.values.sumOf { it.size.toLong() }<=ImportCoordinator.MAX_BOOK_CHAPTERS)
                require(chapters.values.sumOf { it.sumOf { c -> c.title?.length?.toLong() ?: 0L } }<=ImportCoordinator.MAX_BOOK_CHAPTER_TEXT)
                for (track in tracks) results[track.assetId]?.let { metadata ->
                    db.library().fillMetadata(track.assetId,metadata.durationMs,metadata.mimeType)
                    db.library().fillTrackTitle(track.id,metadata.title)
                    val old=db.library().chapters(track.id)
                    if (old.all { it.origin=="file" } && old!=chapters[track.id]) {
                        db.library().deleteFileChapters(track.id)
                        db.library().insertChapters(requireNotNull(chapters[track.id]))
                    }
                    db.sources().metadataRead(track.assetId)
                }
                db.library().updateTrackMetadata(tracks.mapNotNull { track ->
                    results[track.assetId]?.let { track.copy(importedTitle=track.importedTitle ?: it.title,metadata=it.bookMetadata) }
                })
                db.refreshImportedMetadata(bookId,cover)
            }
        } finally { job?.let { withContext(NonCancellable) { runCatching { importer.cancel(it) } } } }
        rememberMetadataStart(bookId,nextAsset.takeIf { results.size<sources.size })
        LinkedMetadataResult(results.size,sources.size-results.size,bytes)
    }

    /** Explicit full read; never matches an unverified asset by URI/name/size/sample. */
    suspend fun verifyBook(bookId: String, progress: (Int,Int,Long) -> Unit = { _,_,_ -> }) = mutex.withLock {
        val assets = db.library().tracks(bookId).map { requireNotNull(db.library().asset(it.assetId)) }.distinctBy { it.id }
        require(assets.isNotEmpty())
        for ((index,asset) in assets.withIndex()) {
            val source = db.sources().source(asset.id) ?: continue
            val holder = AtomicReference<BoundedDocumentAccess.Handle?>()
            try {
                withContext(Dispatchers.IO) { holder.set(access.open(Uri.parse(source.documentUri),grants.leaseForSource(source),source.sizeHint,source.modifiedHint)) }
                val handle = requireNotNull(holder.get())
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val chunk = withContext(Dispatchers.IO) { handle.read(count,64*1024) }
                    if (chunk.count < 0) break
                    require(chunk.count > 0 && count <= Long.MAX_VALUE-chunk.count)
                    digest.update(chunk.bytes,0,chunk.count); count += chunk.count
                    progress(index,assets.size,count)
                }
                require(handle.length == null || count==handle.length)
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (asset.identityVerified && (asset.sizeBytes!=count || asset.sha256!=hash))
                    throw DocumentSourceException(DocumentSourceException.Reason.CHANGED)
                // Recheck provider hints after the full read, then update only this same source record.
                val checkHolder = AtomicReference<BoundedDocumentAccess.Handle?>()
                try { withContext(Dispatchers.IO) { checkHolder.set(access.open(Uri.parse(source.documentUri),grants.leaseForSource(source),count,source.modifiedHint)) } }
                finally { checkHolder.get()?.close() }
                db.withTransaction {
                    require(db.sources().source(asset.id)==source)
                    db.library().verifyIdentity(asset.id,hash,count)
                }
                progress(index+1,assets.size,count)
            } finally { holder.get()?.close() }
        }
    }
    /** Renew the exact originally selected tree; this is access recovery, not new-location identity. */
    suspend fun reauthorize(bookId: String, grantUri: Uri): Int = mutex.withLock {
        val root=requireNotNull(db.sources().root(bookId))
        if (Uri.parse(root.grantUri)!=grantUri) throw DocumentSourceException(DocumentSourceException.Reason.CHANGED)
        val sources=db.library().tracks(bookId).map { requireNotNull(db.sources().source(it.assetId)) }.distinctBy { it.assetId }
        require(sources.all { it.grantUri==root.grantUri })
        val job=id()
        db.library().insertJob(ImportJob(job,"REAUTHORIZING",System.currentTimeMillis()))
        try {
            grants.reserve(job,listOf(grantUri),recovering=true)
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                val holder=AtomicReference<BoundedDocumentAccess.Handle?>()
                try { withContext(Dispatchers.IO) { holder.set(access.open(Uri.parse(source.documentUri),grants.leaseForJob(job,source.grantUri),source.sizeHint,source.modifiedHint)) } }
                finally { holder.get()?.close() }
                holder.get()?.awaitClosed()
            }
            db.withTransaction {
                for (source in sources) {
                    require(db.sources().source(source.assetId)==source)
                    db.sources().setAvailability(source.assetId,"AVAILABLE")
                }
            }
            sources.size
        } finally { withContext(NonCancellable) { runCatching { importer.cancel(job) } } }
    }

    /** Different locations are accepted only after full equality with a previously verified identity. */
    suspend fun reconnect(bookId: String, selected: List<SelectedDocument>, progress: (Int,Int,Long) -> Unit = { _,_,_ -> }): Int = mutex.withLock {
        val assets = db.library().tracks(bookId).map { requireNotNull(db.library().asset(it.assetId)) }.distinctBy { it.id }
        require(assets.isNotEmpty() && selected.size in 1..1000)
        if (assets.any { !it.identityVerified }) throw UnverifiedLinkedAudioException()
        val old = assets.associate { it.id to requireNotNull(db.sources().source(it.id)) }
        val job = id()
        db.library().insertJob(ImportJob(job,"RECONNECTING",System.currentTimeMillis()))
        try {
            grants.reserve(job,selected.map { it.uri },recovering=true)
            val matches = mutableMapOf<String,DocumentSource>()
            for ((index,item) in selected.withIndex()) {
                val holder = AtomicReference<BoundedDocumentAccess.Handle?>()
                try {
                    withContext(Dispatchers.IO) { holder.set(access.open(item.uri,grants.leaseForJob(job,item.uri.toString()),item.sizeBytes)) }
                    val handle = requireNotNull(holder.get())
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val chunk = withContext(Dispatchers.IO) { handle.read(bytes,64*1024) }
                        if (chunk.count < 0) break
                        require(chunk.count>0 && bytes<=Long.MAX_VALUE-chunk.count)
                        digest.update(chunk.bytes,0,chunk.count); bytes+=chunk.count
                        progress(index,selected.size,bytes)
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    val matching = assets.filter { it.sizeBytes==bytes && it.sha256==hash }
                    if (matching.isEmpty()) throw DocumentSourceException(DocumentSourceException.Reason.CHANGED)
                    val check = AtomicReference<BoundedDocumentAccess.Handle?>()
                    try { withContext(Dispatchers.IO) { check.set(access.open(item.uri,grants.leaseForJob(job,item.uri.toString()),bytes,handle.modified)) } }
                    finally { check.get()?.close() }
                    for (asset in matching) matches[asset.id]=requireNotNull(old[asset.id]).copy(documentUri=item.uri.toString(),grantUri=item.uri.toString(),sizeHint=bytes,modifiedHint=handle.modified,availability="AVAILABLE")
                    progress(index+1,selected.size,bytes)
                } finally { holder.get()?.close() }
            }
            require(matches.isNotEmpty())
            db.withTransaction {
                require(db.books().get(bookId)!=null)
                for ((assetId,source) in matches) {
                    require(db.sources().source(assetId)==old[assetId])
                    db.sources().reconnect(assetId,source.documentUri,source.grantUri,source.sizeHint,source.modifiedHint)
                }
                // Individual replacement files no longer describe the old folder's inventory root.
                db.sources().deleteRoot(bookId)
            }
            matches.size
        } finally { withContext(NonCancellable) { runCatching { importer.cancel(job) } } }
    }

}

internal class UnverifiedLinkedAudioException : IllegalStateException("Linked identity is unverified")
