package io.github.jnesew.paudiobooks.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.importing.computeTotalExpectedBytes

@Composable
fun ImportDialogs(state: ImportUi, model: LibraryViewModel, controller: androidx.media3.session.MediaController? = null) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val deferredInterrupted by model.deferredInterrupted.collectAsState()
    when {
        state.interruptedJobs.any { it !in deferredInterrupted } -> AlertDialog(
            onDismissRequest = model::deferInterrupted,
            title = { Text(stringResource(R.string.unfinished_imports)) },
            text = { Text(stringResource(R.string.unfinished_explanation)) },
            confirmButton = {
                TextButton(onClick = model::discardInterrupted, enabled = !state.busy) {
                    Text(stringResource(R.string.discard_copies))
                }
            },
            dismissButton = { TextButton(onClick = model::deferInterrupted, enabled = !state.busy) { Text(stringResource(R.string.later)) } }
        )

        state.repairedFiles != null -> AlertDialog(
            onDismissRequest = model::dismissRepairComplete,
            title = { Text(stringResource(if (state.linkedRecovery) R.string.source_access_restored else R.string.repair_complete)) },
            text = { Text(pluralStringResource(if (state.linkedRecovery) R.plurals.restored_source_access else R.plurals.repaired_audio_files, state.repairedFiles, state.repairedFiles)) },
            confirmButton = { TextButton(onClick = model::dismissRepairComplete) { Text(stringResource(R.string.ok)) } }
        )

        state.rescan != null -> {
            val scan=state.rescan
            InputDialog(title=stringResource(R.string.review_rescan),onDismissRequest=model::dismissRescan,content={
                Text(scan.title,style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.rescan_retained,scan.retainedTracks))
                if (scan.missingTracks>0) Text(stringResource(R.string.rescan_missing,scan.missingTracks))
                if (scan.changedTracks>0) Text(stringResource(R.string.rescan_changed,scan.changedTracks))
                Text(stringResource(R.string.rescan_preservation))
                if (scan.newFiles.isEmpty()) Text(stringResource(R.string.rescan_no_new_files)) else {
                    Text(stringResource(R.string.new_files),style=MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick={ model.selectAllRescanFiles(true) }) { Text(stringResource(R.string.select_all)) }
                        TextButton(onClick={ model.selectAllRescanFiles(false) }) { Text(stringResource(R.string.select_none)) }
                    }
                    scan.newFiles.forEach { file ->
                        val checked=file.uri in state.selectedNewUris
                        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(checked,role=Role.Checkbox,onValueChange={ model.selectRescanFile(file.uri,it) }),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked,onCheckedChange=null); Text(file.name)
                        }
                    }
                }
            },actions={
                TextButton(onClick=model::dismissRescan) { Text(stringResource(R.string.done)) }
                if (scan.newFiles.isNotEmpty()) TextButton(onClick=model::reviewRescanFiles,enabled=state.selectedNewUris.isNotEmpty()) { Text(stringResource(R.string.review_new_files)) }
            })
        }

        state.selection != null -> {
            val selection = state.selection
            AlertDialog(
                onDismissRequest = model::cancelImport,
                title = { Text(stringResource(R.string.choose_books)) },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {

                        if (selection.groups.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.selectableGroup()) {
                                listOf(false to R.string.grouping_separate_books, true to R.string.grouping_combine_all).forEach { (combined, label) ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .selectable(selection.combined == combined, role = Role.RadioButton,
                                            onClick = { model.setCombinedBooks(combined) }),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selection.combined == combined, onClick = null)
                                        Text(stringResource(label))
                                    }
                                }
                            }
                            Row {
                                TextButton(onClick = { model.selectAllGroups(true) }) { Text(stringResource(R.string.select_all)) }
                                TextButton(onClick = { model.selectAllGroups(false) }) { Text(stringResource(R.string.select_none)) }
                            }
                            selection.groups.forEach { group ->
                                val checked = group.id in selection.selectedGroupIds
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(checked, role = Role.Checkbox, onValueChange = { model.selectGroup(group.id, it) }),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked, onCheckedChange = null)
                                    Column {
                                        Text(pluralStringResource(R.plurals.book_folder_files, group.audioFiles.size, group.name, group.audioFiles.size))
                                        computeTotalExpectedBytes(group.audioFiles.map { it.sizeBytes })?.let {
                                            Text(Formatter.formatShortFileSize(context, it), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        val books = selection.books()
                        Text(pluralStringResource(R.plurals.books_selected, books.size, books.size), style = MaterialTheme.typography.titleMedium)
                        val files = books.flatMap { it.files }
                        Text(pluralStringResource(R.plurals.files_selected, files.size, files.size))
                        computeTotalExpectedBytes(files.map { it.sizeBytes })?.let {
                            Text(stringResource(R.string.size_label, Formatter.formatShortFileSize(context, it)))
                        }
                        Spacer(Modifier.height(4.dp))
                        if (books.size == 1) files.forEach { file ->
                            val label = file.subfolder?.let { stringResource(R.string.import_file_in_folder, it, file.name) } ?: file.name
                            Text(label, Modifier.padding(vertical = 2.dp))
                        }
                    }
                },
                confirmButton = {
                    val count = selection.books().size
                    TextButton(onClick = model::reviewSelection, enabled = count > 0) {
                        Text(if (count > 1) pluralStringResource(R.plurals.review_books, count, count) else stringResource(R.string.review_book))
                    }
                },
                dismissButton = {
                    TextButton(onClick = model::cancelImport) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        state.busy -> AlertDialog(
            onDismissRequest = {},
            title = {
                Text(stringResource(if (state.scanning) R.string.rescanning_folder else if (state.verifyingSource) R.string.verifying_audio else if (state.inspecting) R.string.reading_metadata else if (state.preparing) R.string.finding_books else R.string.adding_books))
            },
            text = {
                Column {
                    state.batch?.takeIf { it.total > 1 && it.active != null }?.let { batch ->
                        Text(stringResource(R.string.batch_current_book, batch.total - batch.pending.size, batch.total,
                            batch.active?.reviewedTitle ?: batch.active?.title.orEmpty()))
                        if (!state.preparing && batch.results.any { it.status == BookImportStatus.ADDED })
                            Text(stringResource(R.string.batch_keep_added), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    // D04: Determinate byte progress when sizes are known, honest indeterminate state during inspection or unknown size
                    if (state.verifyingSource) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.progress?.let { Text(stringResource(R.string.source_work_files,it.completedFiles,it.totalFiles)) }
                        if (state.sourceBytesRead > 0) Text(stringResource(R.string.source_bytes_read,Formatter.formatShortFileSize(context,state.sourceBytesRead)))
                    } else if (state.inspecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        state.progress?.let { Text(stringResource(R.string.reading_file,minOf(it.completedFiles+1,it.totalFiles),it.totalFiles)) }
                        if (state.sourceBytesRead > 0) Text(stringResource(R.string.source_bytes_read,Formatter.formatShortFileSize(context,state.sourceBytesRead)))
                    } else {
                        val p = state.progress
                        if (p != null && p.totalExpectedBytes != null && p.totalExpectedBytes > 0) {
                            val fraction = (p.totalCopiedBytes.toFloat() / p.totalExpectedBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(pluralStringResource(R.plurals.files_copied, p.totalFiles, p.completedFiles, p.totalFiles))
                            Text(stringResource(R.string.bytes_copied_of_total, Formatter.formatShortFileSize(context, p.totalCopiedBytes), Formatter.formatShortFileSize(context, p.totalExpectedBytes)))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            if (p != null) {
                                Text(pluralStringResource(R.plurals.files_copied, p.totalFiles, p.completedFiles, p.totalFiles))
                                Text(stringResource(R.string.bytes_copied, Formatter.formatShortFileSize(context, p.totalCopiedBytes)))
                            }
                        }
                    }
                    // D03: Honest foreground notification
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.keep_screen_on_notice), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = model::cancelImport) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )

        state.review != null -> {
            val review = state.review
            val editing = review.firstOrNull { it.id == state.editingReviewId }
            if (editing?.prepared != null) {
                val prepared = editing.prepared
                InputDialog(title = stringResource(R.string.edit_import_details), onDismissRequest = { model.editReviewBook(null) }, content = {
                    PreviewCover(prepared.tracks.firstNotNullOfOrNull { it.metadata.artworkThumbnail }, prepared.bookId)
                    OutlinedTextField(prepared.title, { model.updateReviewTitle(editing.id, it) },
                        label = { Text(stringResource(R.string.book_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (prepared.tracks.any { it.pending }) Text(stringResource(R.string.link_metadata_pending))
                    Text(stringResource(R.string.audio_files), style = MaterialTheme.typography.titleSmall)
                    prepared.tracks.forEachIndexed { index, track ->
                        val fileLabel = track.selected.subfolder?.let { stringResource(R.string.import_file_in_folder,it,track.selected.name) } ?: track.selected.name
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(fileLabel, Modifier.weight(1f))
                            TextButton(onClick = { model.reorderReviewFile(editing.id, index, -1) }, enabled = index > 0,
                                modifier = Modifier.semantics { contentDescription = resources.getString(R.string.move_up, fileLabel) }) { Text(stringResource(R.string.up_arrow)) }
                            TextButton(onClick = { model.reorderReviewFile(editing.id, index, 1) }, enabled = index < prepared.tracks.lastIndex,
                                modifier = Modifier.semantics { contentDescription = resources.getString(R.string.move_down, fileLabel) }) { Text(stringResource(R.string.down_arrow)) }
                        }
                    }
                }, actions = { TextButton(onClick = { model.editReviewBook(null) }) { Text(stringResource(R.string.done)) } })
            } else {
                val count = review.count { it.selected }
                BulkReviewDialog(review, count, model)
            }
        }

        state.linkedPrepared != null -> key(state.linkedPrepared.preview.jobId) {
            val prepared = state.linkedPrepared
            var title by rememberSaveable(prepared.preview.jobId) { mutableStateOf(prepared.title) }
            InputDialog(title=stringResource(R.string.review_book),onDismissRequest=model::cancelImport,content={
                state.batch?.takeIf { it.total>1 }?.let { Text(stringResource(R.string.batch_current_book,it.results.size+1,it.total,prepared.title)) }
                if (prepared.appendToBookId!=null) {
                    Text(prepared.title,style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.append_explanation))
                } else {
                    PreviewCover(prepared.tracks.firstNotNullOfOrNull { it.metadata.artworkThumbnail },prepared.preview.jobId)
                    OutlinedTextField(title,{ title=it.take(512) },label={ Text(stringResource(R.string.book_title)) },singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.linked_audio),style=MaterialTheme.typography.titleSmall)
                if (prepared.tracks.any { it.pending }) Text(stringResource(R.string.link_metadata_pending))
                prepared.tracks.forEachIndexed { index,track ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(track.selected.name,Modifier.weight(1f))
                        TextButton(onClick={ model.reorderLinked(index,-1) },enabled=index>0,modifier=Modifier.semantics { contentDescription=resources.getString(R.string.move_up,track.selected.name) }) { Text(stringResource(R.string.up_arrow)) }
                        TextButton(onClick={ model.reorderLinked(index,1) },enabled=index<prepared.tracks.lastIndex,modifier=Modifier.semantics { contentDescription=resources.getString(R.string.move_down,track.selected.name) }) { Text(stringResource(R.string.down_arrow)) }
                    }
                }
            },actions={
                TextButton(onClick=model::cancelImport) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick={ model.commitLinked(title,controller) },enabled=title.isNotBlank() && (prepared.appendToBookId==null || controller!=null)) { Text(stringResource(if (prepared.appendToBookId!=null) R.string.add_files else if (state.batch?.pending?.isNotEmpty()==true) R.string.add_and_continue else R.string.add_to_library)) }
            })
        }

        state.batch?.finished == true -> {
            val batch = state.batch
            AlertDialog(onDismissRequest = model::dismissImportResults,
                title = { Text(stringResource(R.string.import_results)) },
                text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(batch.results) { result ->
                            Column {
                                Text(result.source.reviewedTitle ?: result.source.title, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(when (result.status) {
                                    BookImportStatus.ADDED -> R.string.batch_added
                                    BookImportStatus.SKIPPED -> R.string.batch_skipped
                                    BookImportStatus.FAILED -> R.string.batch_failed
                                    BookImportStatus.PENDING -> R.string.batch_pending
                                }))
                                result.errorMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = model::dismissImportResults) { Text(stringResource(R.string.done)) } },
                dismissButton = {
                    if (batch.unfinished.isNotEmpty()) Column {
                        TextButton(onClick = model::retryUnfinishedBooks) { Text(stringResource(R.string.retry_unfinished)) }

                    }
                })
        }
    }
}

@Composable private fun PreviewCover(encoded: ByteArray?, jobId: String, compact: Boolean = false) {
    val bitmap by produceState<ImageBitmap?>(null, jobId, encoded) {
        value = withContext(Dispatchers.IO) {
            encoded?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    bitmap?.let {
        Image(it, contentDescription = null,
            modifier = if (compact) Modifier.padding(end = 8.dp).size(56.dp) else Modifier.fillMaxWidth().padding(vertical = 8.dp).height(96.dp),
            contentScale = ContentScale.Fit)
    }
}


@Composable
private fun BulkReviewDialog(review: List<LinkedBookReview>, count: Int, model: LibraryViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    androidx.compose.ui.window.Dialog(onDismissRequest = model::cancelImport,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.review_selection), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.link_explanation), Modifier.padding(top = 8.dp))
                        FlowRow {
                            TextButton(onClick = { model.selectAllReviewBooks(true) }) { Text(stringResource(R.string.select_all)) }
                            TextButton(onClick = { model.selectAllReviewBooks(false) }) { Text(stringResource(R.string.select_none)) }
                        }
                    }
                    items(review, key = { it.id }) { entry ->
                        val prepared = entry.prepared
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(entry.selected, enabled = prepared != null, role = Role.Checkbox,
                                    onValueChange = { model.selectReviewBook(entry.id, it) }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(entry.selected, onCheckedChange = null, enabled = prepared != null)
                                if (prepared != null) PreviewCover(prepared.tracks.firstNotNullOfOrNull { it.metadata.artworkThumbnail }, prepared.bookId, compact = true)
                                Column(Modifier.weight(1f)) {
                                    Text(prepared?.title ?: entry.source.reviewedTitle ?: entry.source.title, style = MaterialTheme.typography.titleMedium)
                                    Text(pluralStringResource(R.plurals.audio_file_count, entry.source.files.size, entry.source.files.size))
                                    computeTotalExpectedBytes(entry.source.files.map { it.sizeBytes })?.let {
                                        Text(Formatter.formatShortFileSize(context, it), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (entry.alreadyLinked) Text(stringResource(R.string.audio_already_added), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (prepared != null) {
                                if (prepared.tracks.any { it.pending }) Text(stringResource(R.string.link_metadata_pending))
                                TextButton(onClick = { model.editReviewBook(entry.id) }, modifier = Modifier.semantics {
                                    contentDescription = resources.getString(R.string.edit_import_book, prepared.title)
                                }) { Text(stringResource(R.string.edit_import)) }
                            }
                            entry.errorMessage?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                            HorizontalDivider()
                        }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = model::cancelImport) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = model::approveReview, enabled = count > 0 && review.none { it.selected && it.prepared?.title.isNullOrBlank() }) {
                        Text(pluralStringResource(R.plurals.add_books_count, count, count))
                    }
                }
                if (review.none { it.prepared != null }) TextButton(onClick = model::finishFailedReview) { Text(stringResource(R.string.done)) }
            }
        }
    }
}
