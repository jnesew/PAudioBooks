package io.github.jnesew.paudiobooks.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.BookSummary
import io.github.jnesew.paudiobooks.portability.*

private class LocalTransferDocument(private val create: Boolean) : androidx.activity.result.contract.ActivityResultContract<String, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        android.content.Intent(if (create) android.content.Intent.ACTION_CREATE_DOCUMENT else android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = if (create) "application/json" else "*/*"
            putExtra(android.content.Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(if (create) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (create) putExtra(android.content.Intent.EXTRA_TITLE, input)
            // Local providers can label a valid JSON transfer as octet-stream.
            // Preview validates bounded UTF-8/JSON before any library mutation.
        }
    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? =
        intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK && it.scheme == android.content.ContentResolver.SCHEME_CONTENT }
}

@Composable
internal fun PortabilityControls(books: List<BookSummary>, playback: PlaybackConnection, model: ProgressTransfer) {
    val transfer by model.transfer.collectAsState()
    val preview = transfer.preview
    var explain by remember { mutableStateOf<Boolean?>(null) }
    val selected = remember(preview) { mutableStateMapOf<Int, String>() }
    val export = rememberLauncherForActivityResult(LocalTransferDocument(true)) { uri ->
        if (uri != null) model.exportProgress(uri, playback.controller, playback.state.trackId != null)
    }
    val import = rememberLauncherForActivityResult(LocalTransferDocument(false)) { uri ->
        if (uri != null) model.previewProgress(uri)
    }
    Text(stringResource(R.string.progress_transfer), style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = { explain = true }) { Text(stringResource(R.string.export_progress)) }
    TextButton(onClick = { explain = false }) { Text(stringResource(R.string.import_progress)) }
    if (explain != null) AlertDialog(onDismissRequest = { explain = null }, title = { Text(stringResource(if (explain == true) R.string.export_progress else R.string.import_progress)) },
        text = { Text(stringResource(if (explain == true) R.string.export_explanation else R.string.import_explanation)) },
        confirmButton = { TextButton(onClick = { val exporting = explain == true; explain = null; if (exporting) export.launch("paudiobooks-progress.json") else import.launch("") }) { Text(stringResource(R.string.continue_label)) } },
        dismissButton = { TextButton(onClick = { explain = null }) { Text(stringResource(R.string.cancel)) } })
    if (transfer.busy) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.working)) }, text = { CircularProgressIndicator() }, confirmButton = {})
    if (transfer.complete) AlertDialog(onDismissRequest = model::dismissTransferComplete, title = { Text(stringResource(R.string.transfer_complete)) }, confirmButton = { TextButton(onClick = model::dismissTransferComplete) { Text(stringResource(R.string.ok)) } })
    preview?.let { data -> AlertDialog(onDismissRequest = model::dismissTransferPreview, title = { Text(stringResource(R.string.review_progress)) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.replace_explanation))
            data.entries.forEach { entry ->
                Spacer(Modifier.height(12.dp)); Text(entry.displayTitle, style = MaterialTheme.typography.titleMedium)
                val candidates = books.filter { it.book.id in entry.matchingBookIds }
                if (candidates.isEmpty()) Text(stringResource(R.string.no_matching_copy))
                Column(Modifier.selectableGroup()) { candidates.forEach { candidate -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected[entry.index] == candidate.book.id, onClick = { selected[entry.index] = candidate.book.id }, role = Role.RadioButton)) {
                    RadioButton(selected = selected[entry.index] == candidate.book.id, onClick = null)
                    Column { Text(candidate.book.displayTitle); candidate.book.displayAuthor?.let { Text(it) } }
                } } }
                if (selected.containsKey(entry.index)) TextButton(onClick = { selected.remove(entry.index) }) { Text(stringResource(R.string.skip_this_book)) }
            }
        }
    }, confirmButton = { TextButton(enabled = selected.isNotEmpty() && selected.values.toSet().size == selected.size, onClick = {
        val choices = selected.map { PortabilitySelection(it.key, it.value, true) }
        model.applyProgress(choices, playback.controller, playback.state.bookId, playback.state.trackId != null)
    }) { Text(stringResource(R.string.replace_selected)) } }, dismissButton = { TextButton(onClick = model::dismissTransferPreview) { Text(stringResource(R.string.cancel)) } }) }
}
