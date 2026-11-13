package io.github.jnesew.paudiobooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterSheet(chapters: List<Chapter>, currentIndex: Int, positionMs: Long,
    trackDurations: Map<String, Long?>, enabled: Boolean, onDismiss: () -> Unit,
    onSelect: suspend (Chapter) -> Boolean, footer: @Composable () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    var seeking by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !seeking })
    ModalBottomSheet(onDismissRequest = { if (!seeking) onDismiss() }, sheetState = sheetState) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chapters), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(if (currentIndex >= 0) stringResource(R.string.chapter_count_position, currentIndex + 1, chapters.size)
                    else stringResource(R.string.count_value, chapters.size))
            }
            TextButton(onClick = onDismiss, enabled = !seeking, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
            itemsIndexed(chapters, key = { _, item -> item.id }) { index, item ->
                val current = index == currentIndex
                val title = item.title ?: stringResource(R.string.chapter_number, index + 1)
                val end = item.endMs ?: chapters.getOrNull(index + 1)?.takeIf { it.trackId == item.trackId }?.startMs ?: trackDurations[item.trackId]
                val duration = end?.minus(item.startMs)?.takeIf { it > 0 }
                val elapsed = (positionMs - item.startMs).coerceIn(0L, duration ?: Long.MAX_VALUE)
                val action = stringResource(R.string.jump_to_chapter, index + 1, title)
                val currentLabel = stringResource(R.string.current_chapter)
                Column(Modifier.fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable(enabled = enabled && !seeking, onClickLabel = action) {
                        seeking = true
                        scope.launch {
                            try { if (onSelect(item)) onDismiss() }
                            catch (cancel: CancellationException) { throw cancel }
                            finally { seeking = false }
                        }
                    }.semantics { contentDescription = action; if (current) stateDescription = currentLabel }
                    .heightIn(min = 56.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.title != null) Text((index + 1).toString(), style = MaterialTheme.typography.bodyMedium)
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(duration?.let(::mediaTime) ?: stringResource(R.string.unknown_duration), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (current) {
                        Text(if (duration != null) stringResource(R.string.current_chapter_times, mediaTime(elapsed), mediaTime(duration - elapsed))
                            else currentLabel, style = MaterialTheme.typography.bodySmall)
                        if (duration != null) LinearProgressIndicator(progress = { (elapsed.toDouble() / duration).toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
                HorizontalDivider()
            }
        }
        footer()
    }
}
