package io.github.jnesew.paudiobooks.importing

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SelectedDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long?,
    val subfolder: String? = null,
    val orderKey: String? = null
)

data class TreeGroup(
    val name: String,
    val audioFiles: List<SelectedDocument>,
    val companionCover: SelectedDocument? = null,
    val isDiscSubfolder: Boolean = false,
    val id: String = name
)

data class TreeInventory(
    val audioFiles: List<SelectedDocument>,
    val companionCover: SelectedDocument? = null,
    val groups: List<TreeGroup> = emptyList()
)

private val COMPANION_COVER_NAMES = listOf(
    "cover.jpg",
    "cover.jpeg",
    "cover.png",
    "folder.jpg",
    "folder.jpeg",
    "folder.png"
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "m4p", "aac", "flac", "ogg", "oga", "opus", "wav", "wave", "wma", "aif", "aiff"
)

private val DISC_FOLDER_PATTERN = Regex(
    """^(?i)(cd|disc|disk|part|vol|volume)\s*\d+.*$|^\d{1,2}$"""
)

internal fun isDiscFolderName(name: String): Boolean {
    return DISC_FOLDER_PATTERN.matches(name.trim())
}

internal fun isAudioFile(name: String, mime: String?): Boolean {
    if (mime != null && (mime.startsWith("audio/") || mime == "application/ogg" || mime == "application/x-flac")) {
        return true
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in AUDIO_EXTENSIONS
}

internal fun companionCoverRank(name: String, depth: Int): Int? {
    val index = COMPANION_COVER_NAMES.indexOf(name.lowercase())
    if (index < 0) return null
    return depth * 100 + index
}

private data class PendingDirectory(
    val id: String,
    val depth: Int,
    val parentId: String?,
    val relativeDisplayPath: String?,
    val orderKey: String?
)

private class DirectoryNode(
    val id: String,
    val displayName: String,
    val depth: Int,
    val parentId: String?,
    val relativeDisplayPath: String,
    val orderKey: String,
    val directAudioFiles: MutableList<SelectedDocument> = mutableListOf(),
    val directCompanionCovers: MutableList<Pair<SelectedDocument, Int>> = mutableListOf(),
    val childDirIds: MutableList<String> = mutableListOf()
)

internal fun compareDocumentOrder(
    folderA: String?,
    nameA: String,
    folderB: String?,
    nameB: String,
    keyA: String? = null,
    keyB: String? = null
): Int {
    val folderComp = when {
        folderA == null && folderB == null -> 0
        folderA == null -> -1
        folderB == null -> 1
        else -> NaturalOrder.compare(folderA, folderB)
    }
    if (folderComp != 0) return folderComp
    if (keyA != null && keyB != null) {
        val keyComp = keyA.compareTo(keyB)
        if (keyComp != 0) return keyComp
    }
    return NaturalOrder.compare(nameA, nameB)
}

internal fun computeTotalExpectedBytes(sizes: Iterable<Long?>): Long? {
    val nonNull = mutableListOf<Long>()
    for (s in sizes) {
        if (s == null || s < 0) return null
        nonNull.add(s)
    }
    return try {
        nonNull.fold(0L) { acc, size -> Math.addExact(acc, size) }
    } catch (_: ArithmeticException) {
        null
    }
}

/** Enumerates only the selected tree, with bounded traversal and provider work. */
class DocumentInventory(private val resolver: ContentResolver, private val timeoutMs: Long = 30_000) {
    companion object {
        // A provider ignoring cancellation can occupy at most one worker and one queued request.
        private val workers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "document-inventory").apply { isDaemon = true } })
        // CancellationSignal.cancel can block in a provider callback; never run it on the caller.
        private val cancellers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "document-cancel").apply { isDaemon = true } })
    }

    suspend fun treeInventory(treeUri: Uri, maxDepth: Int = 16, maxEntries: Int = 5000, rootDocument: Uri? = null): TreeInventory {
        require(treeUri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(treeUri))
        require(maxDepth in 0..32 && maxEntries in 1..10000)
        require(rootDocument == null || rootDocument.authority == treeUri.authority && DocumentsContract.isTreeUri(rootDocument) &&
            DocumentsContract.getTreeDocumentId(rootDocument)==DocumentsContract.getTreeDocumentId(treeUri))
        val rootId=rootDocument?.let { DocumentsContract.getDocumentId(it) } ?: DocumentsContract.getTreeDocumentId(treeUri)
        return runProvider { signal -> enumerate(treeUri, maxDepth, maxEntries, signal, rootId) }
    }

    suspend fun tree(treeUri: Uri, maxDepth: Int = 16, maxEntries: Int = 5000): List<SelectedDocument> =
        treeInventory(treeUri, maxDepth, maxEntries).audioFiles

    suspend fun describe(uri: Uri): SelectedDocument {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        return runProvider { signal ->
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)
            cursor?.use {
                if (it.moveToFirst()) {
                    signal.throwIfCanceled()
                    return@runProvider SelectedDocument(uri, it.getString(0)?.take(512).orEmpty(),
                        if (it.isNull(1)) null else it.getLong(1).takeIf { size -> size >= 0 })
                }
            }
            SelectedDocument(uri, "", null)
        }
    }

    private suspend fun <T> runProvider(operation: (CancellationSignal) -> T): T {
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
                    continuation.invokeOnCancellation {
                        cancelled.set(true)
                        try { cancellers.execute { signal.cancel() } }
                        catch (_: RejectedExecutionException) { /* The provider work remains bounded. */ }
                    }
                    try {
                        workers.execute {
                            try {
                                if (cancelled.get()) return@execute
                                signal.throwIfCanceled()
                                val result = operation(signal)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        continuation.resumeWithException(IOException("A previous folder is still responding"))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IOException("The selected source did not respond in time")
        }
    }

    private fun enumerate(treeUri: Uri, maxDepth: Int, maxEntries: Int, signal: CancellationSignal, rootId: String): TreeInventory {
        val seen = mutableSetOf(rootId)
        val pending = ArrayDeque<PendingDirectory>()
        pending.add(PendingDirectory(rootId, 0, null, null, null))
        val dirMap = mutableMapOf<String, DirectoryNode>()
        dirMap[rootId] = DirectoryNode(rootId, "", 0, null, "", "0000")
        val allAudioFiles = mutableListOf<SelectedDocument>()
        var visited = 0
        var dirCounter = 0
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        while (pending.isNotEmpty()) {
            signal.throwIfCanceled()
            val (parentDirId, depth, _, parentRelPath, parentOrderKey) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDirId)
            val cursor = resolver.query(children, columns, null, null, null, signal)
                ?: throw IOException("Cannot read selected folder")
            cursor.use {
                while (it.moveToNext()) {
                    signal.throwIfCanceled()
                    if (++visited > maxEntries) throw IOException("Selected folder contains too many items")
                    val id = it.getString(0) ?: throw IOException("Document has no ID")
                    if (id.length > 4096) throw IOException("Document ID exceeds supported length")
                    if (!seen.add(id)) continue
                    val name = it.getString(1)?.take(512) ?: ""
                    val mime = it.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth >= maxDepth) throw IOException("Selected folder is too deeply nested")
                        val childRelPath = if (parentRelPath == null) name else "$parentRelPath/$name"
                        val dirToken = "%04d".format(++dirCounter)
                        val childOrderKey = if (parentOrderKey == null) dirToken else "$parentOrderKey/$dirToken"
                        val childNode = DirectoryNode(id, name, depth + 1, parentDirId, childRelPath, childOrderKey)
                        dirMap[id] = childNode
                        dirMap[parentDirId]?.childDirIds?.add(id)
                        pending.add(PendingDirectory(id, depth + 1, parentDirId, childRelPath, childOrderKey))
                    } else {
                        val size = if (it.isNull(3)) null else it.getLong(3).takeIf { n -> n >= 0 }
                        val doc = SelectedDocument(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name,
                            size,
                            parentRelPath,
                            parentOrderKey
                        )
                        val coverRank = companionCoverRank(name, depth)
                        if (coverRank != null) {
                            dirMap[parentDirId]?.directCompanionCovers?.add(doc to coverRank)
                        } else if (isAudioFile(name, mime)) {
                            allAudioFiles.add(doc)
                            dirMap[parentDirId]?.directAudioFiles?.add(doc)
                        }
                    }
                }
            }
        }

        val rootCover = dirMap[rootId]?.directCompanionCovers?.minByOrNull { it.second }?.first

        fun collectSubtreeAudio(nodeId: String): List<SelectedDocument> {
            val node = dirMap[nodeId] ?: return emptyList()
            val list = mutableListOf<SelectedDocument>()
            list.addAll(node.directAudioFiles)
            for (childId in node.childDirIds) {
                list.addAll(collectSubtreeAudio(childId))
            }
            return list
        }

        fun findBestCoverForNode(nodeId: String): SelectedDocument? {
            val node = dirMap[nodeId] ?: return null
            val direct = node.directCompanionCovers.minByOrNull { it.second }?.first
            if (direct != null) return direct
            for (childId in node.childDirIds) {
                val childCover = findBestCoverForNode(childId)
                if (childCover != null) return childCover
            }
            return null
        }

        fun collectBookGroups(nodeId: String): List<TreeGroup> {
            val node = dirMap[nodeId] ?: return emptyList()
            val audioChildren = node.childDirIds.filter { collectSubtreeAudio(it).isNotEmpty() }
            return if (audioChildren.isNotEmpty()) {
                val allChildrenAreDiscs = audioChildren.all { isDiscFolderName(dirMap[it]!!.displayName) }
                if (allChildrenAreDiscs) {
                    val bookAudio = collectSubtreeAudio(node.id).sortedWith { a, b ->
                        compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
                    }
                    val bookCover = findBestCoverForNode(node.id)
                    listOf(TreeGroup(node.relativeDisplayPath, bookAudio, bookCover, isDiscSubfolder = false, id = node.id))
                } else {
                    val result = mutableListOf<TreeGroup>()
                    if (node.directAudioFiles.isNotEmpty()) {
                        val directAudio = node.directAudioFiles.sortedWith { a, b ->
                            compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
                        }
                        val directCover = node.directCompanionCovers.minByOrNull { it.second }?.first
                        result.add(TreeGroup(node.relativeDisplayPath, directAudio, directCover, isDiscSubfolder = false, id = node.id))
                    }
                    for (child in audioChildren) {
                        result.addAll(collectBookGroups(child))
                    }
                    result
                }
            } else if (node.directAudioFiles.isNotEmpty()) {
                val bookAudio = node.directAudioFiles.sortedWith { a, b ->
                    compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
                }
                val bookCover = findBestCoverForNode(node.id)
                listOf(TreeGroup(node.relativeDisplayPath, bookAudio, bookCover, isDiscSubfolder = isDiscFolderName(node.displayName), id = node.id))
            } else {
                emptyList()
            }
        }

        val groups = mutableListOf<TreeGroup>()
        val rootAudioChildren = dirMap[rootId]?.childDirIds?.filter { collectSubtreeAudio(it).isNotEmpty() } ?: emptyList()
        if (rootAudioChildren.isNotEmpty()) {
            val allRootChildrenAreDiscs = rootAudioChildren.all { isDiscFolderName(dirMap[it]!!.displayName) }
            if (allRootChildrenAreDiscs) {
                for (childId in rootAudioChildren) {
                    val childNode = dirMap[childId]!!
                    val childAudio = collectSubtreeAudio(childId).sortedWith { a, b ->
                        compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
                    }
                    val childCover = findBestCoverForNode(childId)
                    groups.add(TreeGroup(childNode.displayName, childAudio, childCover, isDiscSubfolder = true, id = childNode.id))
                }
            } else {
                if (dirMap[rootId]?.directAudioFiles?.isNotEmpty() == true) {
                    val directAudio = dirMap[rootId]!!.directAudioFiles.sortedWith { a, b ->
                        compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
                    }
                    groups.add(TreeGroup("Root", directAudio, rootCover, isDiscSubfolder = false, id = rootId))
                }
                for (childId in rootAudioChildren) {
                    groups.addAll(collectBookGroups(childId))
                }
            }
        } else if (dirMap[rootId]?.directAudioFiles?.isNotEmpty() == true && groups.isEmpty()) {
            val directAudio = dirMap[rootId]!!.directAudioFiles.sortedWith { a, b ->
                compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
            }
            groups.add(TreeGroup("Audiobook", directAudio, rootCover, isDiscSubfolder = false, id = rootId))
        }

        groups.sortWith { a, b ->
            val nameComp = NaturalOrder.compare(a.name, b.name)
            if (nameComp != 0) nameComp else a.id.compareTo(b.id)
        }

        val sortedAudio = allAudioFiles.sortedWith { a, b ->
            compareDocumentOrder(a.subfolder, a.name, b.subfolder, b.name, a.orderKey, b.orderKey)
        }

        return TreeInventory(sortedAudio, rootCover, groups)
    }
}
