package io.github.jnesew.paudiobooks.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.BookDetails
import io.github.jnesew.paudiobooks.playback.Timeline
import io.github.jnesew.paudiobooks.playback.TrackSpan
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle

@Composable
internal fun BookInformationWindow(
    details: BookDetails, audioFilesOpen: Boolean, available: Boolean?, controllerReady: Boolean,
    onDismiss: () -> Unit, onAudioFiles: () -> Unit, onBack: () -> Unit,
    onReadMetadata: () -> Unit, onVerify: () -> Unit, onRescan: () -> Unit
) {
    val dismiss = if (audioFilesOpen) onBack else onDismiss
    InputDialog(stringResource(if (audioFilesOpen) R.string.audio_files else R.string.book_information), dismiss, content = {
        if (audioFilesOpen) AudioFilesContent(details, available, controllerReady, onReadMetadata, onVerify, onRescan)
        else BookInformationContent(details, onAudioFiles)
    }, actions = {
        TextButton(onClick = dismiss) { Text(stringResource(if (audioFilesOpen) R.string.back else R.string.done)) }
    })
}

@Composable
private fun BookInformationContent(details: BookDetails, onAudioFiles: () -> Unit) {
    val book = details.book
    val locale = LocalConfiguration.current.locales[0]
    book.importedDescription?.takeIf { it.isNotBlank() }?.let {
        Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
    BookFact(stringResource(R.string.book_genre), book.importedGenre)
    BookFact(stringResource(R.string.book_publisher), book.importedPublisher)
    bookReleaseDate(book.importedReleaseDate)?.let { released ->
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, released.skeleton)
        BookFact(stringResource(R.string.book_released), released.date.format(DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale))))
    }
    val duration = Timeline.total(details.tracks.map { TrackSpan(it.track.id, it.asset.durationMs) })
    val label = duration?.let { stringResource(R.string.total_duration, listeningTime(it)) } ?: stringResource(R.string.unknown_duration)
    val spoken = duration?.let { stringResource(R.string.total_duration, listeningTime(it, spoken = true)) } ?: label
    Text(label, Modifier.semantics { contentDescription = spoken }, style = MaterialTheme.typography.bodyMedium)
    OverviewRow(stringResource(R.string.audio_files), null, onClick = onAudioFiles)
}

@Composable
private fun AudioFilesContent(
    details: BookDetails, available: Boolean?, controllerReady: Boolean,
    onReadMetadata: () -> Unit, onVerify: () -> Unit, onRescan: () -> Unit
) {
    val context = LocalContext.current
    val linked = details.tracks.any { it.documentSource != null }
    Text(stringResource(if (linked) R.string.linked_audio else R.string.private_copy), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (linked) R.string.linked_audio_help else R.string.private_audio_help), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(if (available == null) R.string.audio_unchecked else if (available) R.string.audio_available else R.string.audio_unavailable))
    details.tracks.mapNotNull { it.asset.mimeType }.distinct().forEach { Text(it) }
    Text(pluralStringResource(R.plurals.audio_file_count, details.tracks.size, details.tracks.size))
    Text(if (details.tracks.any { it.documentSource != null && it.documentSource.sizeHint == null }) stringResource(R.string.size_unknown)
        else stringResource(R.string.size_label, Formatter.formatShortFileSize(context, details.tracks.sumOf { it.asset.sizeBytes })))
    if (details.tracks.any { it.documentSource?.metadataPending == true }) {
        Text(stringResource(R.string.link_metadata_pending))
        OutlinedButton(onClick = onReadMetadata, enabled = controllerReady, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_metadata)) }
    }
    if (linked) OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.verify_audio)) }
    if (details.linkedRoot != null) OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rescan_folder)) }
}

@Composable
private fun BookFact(label: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
