package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.importing.SelectedDocument
import io.github.jnesew.paudiobooks.importing.TreeGroup

/** A picker selection; book identity is assigned only when its review starts. */
data class SourceSelection(
    val files: List<SelectedDocument>,
    val title: String,
    val companionCover: SelectedDocument? = null,
    val groups: List<TreeGroup> = emptyList(),
    val combined: Boolean = groups.size <= 1 || groups.all { it.isDiscSubfolder },
    val selectedGroupIds: Set<String> = groups.map { it.id }.toSet(),
    val reviewedTitle: String? = null,
    val keepFileOrder: Boolean = false,
    val grantUris: List<android.net.Uri> = emptyList(),
    val rootDocument: android.net.Uri? = null,
    val rootGrant: android.net.Uri? = null,
    val appendToBookId: String? = null,
) {
    fun books(): List<SourceSelection> {
        if (groups.isEmpty()) return if (files.isEmpty()) emptyList() else listOf(this)
        val chosen = groups.filter { it.id in selectedGroupIds }
        if (chosen.isEmpty()) return emptyList()
        if (combined) {
            val uris = chosen.flatMap { it.audioFiles }.map { it.uri }.toSet()
            return listOf(copy(files = files.filter { it.uri in uris }, groups = emptyList(), selectedGroupIds = emptySet()))
        }
        return chosen.map { group ->
            copy(files = group.audioFiles, title = group.name.substringAfterLast('/').ifBlank { title }, companionCover = group.companionCover,
                groups = emptyList(), selectedGroupIds = emptySet(), rootDocument = rootGrant?.let {
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(it,group.id)
                })
        }
    }
}

enum class BookImportStatus { ADDED, SKIPPED, FAILED, PENDING }

data class BookImportResult(
    val source: SourceSelection,
    val status: BookImportStatus,
    val bookId: String? = null,
    val errorMessage: Int? = null,
)

/** Only the active book owns staging. Earlier results are already durable. */
data class ImportBatch(
    val pending: List<SourceSelection>,
    val total: Int = pending.size,
    val active: SourceSelection? = null,
    val activeBookId: String? = null,
    val results: List<BookImportResult> = emptyList(),
) {
    val finished get() = active == null && pending.isEmpty()
    val unfinished get() = results.filter { it.status == BookImportStatus.FAILED || it.status == BookImportStatus.PENDING }
}

/** Prepared metadata and edits remain owned by the review until one bulk approval. */
data class LinkedBookReview(
    val source: SourceSelection,
    val prepared: PreparedLinkedImport? = null,
    val alreadyLinked: Boolean = false,
    val selected: Boolean = prepared != null && !alreadyLinked,
    val errorMessage: Int? = null,
) {
    val id get() = prepared?.bookId ?: source.files.first().uri.toString()
    fun reviewedSource(): SourceSelection = prepared?.let {
        source.copy(reviewedTitle = it.title.trim(), keepFileOrder = true, files = it.tracks.map { track -> track.selected })
    } ?: source
}
