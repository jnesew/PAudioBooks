package io.github.jnesew.paudiobooks.ui

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jnesew.paudiobooks.preferences.PlaybackSettings
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.library.BookDetails
import io.github.jnesew.paudiobooks.playback.PlaybackService
import io.github.jnesew.paudiobooks.playback.Timeline
import io.github.jnesew.paudiobooks.playback.TrackSpan
import io.github.jnesew.paudiobooks.playback.TrackPosition
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(playback: PlaybackConnection, onOpen: () -> Unit, onError: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as AudiobookApplication
    val settings by app.settings.playback.collectAsStateWithLifecycle(initialValue = PlaybackSettings())
    val details by produceState<BookDetails?>(null, playback.state.bookId) {
        val id = playback.state.bookId ?: return@produceState
        app.repository.observeBookDetails(id).collect { value = it }
    }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            details?.book?.let { BookCover(it, Modifier.padding(start = 8.dp).size(48.dp)) }
            Column(Modifier.weight(1f).padding(12.dp)) {
                Text(details?.book?.displayTitle ?: playback.state.title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                (details?.book?.displayAuthor ?: playback.state.author)?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
            }
            }
            IconAction(if (playback.state.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (playback.state.playing) R.string.pause else R.string.play), { scope.launch { val c = playback.controller ?: return@launch; if (playback.state.playing) { if (!c.pauseAndSave()) onError(R.string.progress_save_failed) } else c.play() } }, enabled = playback.controller != null)
            IconAction(R.drawable.ic_forward_skip, pluralStringResource(R.plurals.forward_by_seconds, settings.skipForwardSeconds, settings.skipForwardSeconds), { playback.controller?.seekTo(forwardPosition(playback.state.positionMs, settings.skipForwardSeconds, playback.state.durationMs)) }, enabled = playback.controller != null && playback.state.ready, seconds = settings.skipForwardSeconds)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreen(details: BookDetails?, playback: PlaybackConnection, drafts: BookmarkDrafts, bookScope: Boolean, setBookScope: (Boolean) -> Unit, onClose: () -> Unit, onError: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as AudiobookApplication
    val settings by app.settings.playback.collectAsStateWithLifecycle(initialValue = PlaybackSettings())
    val state = playback.state
    val controller = playback.controller
    var chaptersOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarkOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarkDraftBook by rememberSaveable { mutableStateOf<String?>(null) }
    val bookmarkDrafts by drafts.newBookmarks.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val tracks = details?.tracks.orEmpty()
    val chapters = details?.chapters.orEmpty()
    val currentChapter = chapters.indexOfLast { it.trackId == state.trackId && it.startMs <= state.positionMs && (it.endMs == null || state.positionMs < it.endMs) }
    val chapter = chapters.getOrNull(currentChapter)
    val spans = tracks.map { TrackSpan(it.track.id, it.asset.durationMs) }
    val duration = Timeline.total(spans)
    val bookPosition = Timeline.toBook(spans, TrackPosition(state.trackIndex, state.positionMs))
    val chapterStart = chapter?.startMs ?: 0L
    val chapterEnd = chapter?.endMs ?: chapters.drop(currentChapter + 1).firstOrNull { it.trackId == state.trackId }?.startMs ?: state.durationMs
    val range = if (bookScope) duration else chapterEnd?.minus(chapterStart)
    val elapsed = if (bookScope) bookPosition ?: 0L else (state.positionMs - chapterStart).coerceAtLeast(0)
    fun seekBook(position: Long) {
        scope.launch { if (controller?.command(PlaybackService.SEEK_BOOK_POSITION, Bundle().apply { putLong(PlaybackService.BOOK_POSITION_MS, position) }) != true) onError(R.string.playback_failed) }
    }
    suspend fun seekChapterCommand(index: Int): Boolean {
        val target = chapters.getOrNull(index) ?: return false
        val trackIndex = tracks.indexOfFirst { it.track.id == target.trackId }
        val success = trackIndex >= 0 && controller?.command(PlaybackService.SEEK_CHAPTER, Bundle().apply { putString(PlaybackService.CHAPTER_ID, target.id) }) == true
        if (!success) onError(R.string.playback_failed)
        return success
    }
    fun seekChapter(index: Int) { scope.launch { seekChapterCommand(index) } }
    fun toggle() { scope.launch { if (controller != null) { if (state.playing) { if (!controller.pauseAndSave()) onError(R.string.progress_save_failed) } else controller.play() } } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val coverWidth = minOf((maxWidth - 40.dp).coerceAtLeast(0.dp) * 0.9f, 360.dp)
            val coverHeight = minOf(300.dp, maxHeight * if (maxWidth >= 600.dp) 0.35f else 0.32f)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_collapse, stringResource(R.string.collapse_player), onClose)
                    Text(stringResource(R.string.player), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(48.dp))
                }
                details?.book?.let { BookCover(it, Modifier.sizeIn(maxWidth = coverWidth, maxHeight = coverHeight)) }
                Spacer(Modifier.height(16.dp))
                Text(details?.book?.displayTitle ?: state.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                (details?.book?.displayAuthor ?: state.author)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Spacer(Modifier.height(12.dp))
                chapter?.let { Text(it.title ?: stringResource(R.string.chapter_number, currentChapter + 1), style = MaterialTheme.typography.titleMedium) }
                val seekLabel = stringResource(R.string.seek_scope)
                Row(Modifier.semantics { contentDescription = seekLabel }, horizontalArrangement = Arrangement.Center) {
                    FilterChip(!bookScope, { setBookScope(false) }, label = { Text(stringResource(R.string.chapter_scope)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(bookScope, { setBookScope(true) }, enabled = duration != null, label = { Text(stringResource(R.string.book_scope)) })
                    if (state.undoAvailable) IconAction(R.drawable.ic_undo, stringResource(R.string.undo_seek), {
                        scope.launch { if (controller?.command(PlaybackService.UNDO_SEEK) != true) onError(R.string.playback_failed) }
                    }, enabled = state.ready)
                }
                Slider(value = dragging ?: if (range != null && range > 0) (elapsed.toDouble() / range).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it }, onValueChangeFinished = {
                        val fraction = dragging; dragging = null
                        if (fraction != null && range != null) { val position = (fraction * range).toLong(); if (bookScope) seekBook(position) else controller?.seekTo(chapterStart + position) }
                    }, enabled = controller != null && state.ready && state.trackId != null && range != null && range > 0,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = resources.getString(R.string.seek_position)
                        stateDescription = resources.getString(R.string.seek_accessible_value, mediaTime(elapsed), range?.let { mediaTime(it) } ?: resources.getString(R.string.unknown_duration))
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(mediaTime(elapsed)); Text(range?.let { "−" + mediaTime((it - elapsed).coerceAtLeast(0)) } ?: stringResource(R.string.unknown_duration)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_previous, stringResource(R.string.previous_chapter), { seekChapter(currentChapter - 1) }, enabled = controller != null && state.ready && currentChapter > 0)
                    IconAction(R.drawable.ic_back_skip, pluralStringResource(R.plurals.back_by_seconds, settings.skipBackSeconds, settings.skipBackSeconds), { controller?.seekTo((state.positionMs - settings.skipBackSeconds * 1_000L).coerceAtLeast(0)) }, enabled = controller != null && state.ready, seconds = settings.skipBackSeconds)
                    IconAction(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (state.playing) R.string.pause else R.string.play), ::toggle, enabled = controller != null, prominent = true)
                    IconAction(R.drawable.ic_forward_skip, pluralStringResource(R.plurals.forward_by_seconds, settings.skipForwardSeconds, settings.skipForwardSeconds), { controller?.seekTo(forwardPosition(state.positionMs, settings.skipForwardSeconds, state.durationMs)) }, enabled = controller != null && state.ready, seconds = settings.skipForwardSeconds)
                    IconAction(R.drawable.ic_next, stringResource(R.string.next_chapter), { seekChapter(currentChapter + 1) }, enabled = controller != null && state.ready && currentChapter >= 0 && currentChapter < chapters.lastIndex)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconAction(R.drawable.ic_sleep, stringResource(R.string.sleep_timer), { sleepOpen = true }, enabled = controller != null && state.trackId != null,
                        state = stringResource(if (state.sleepActive) R.string.sleep_active else R.string.sleep_inactive), active = state.sleepActive)
                    val speedLabel = stringResource(R.string.speed)
                    val speedValue = stringResource(R.string.speed_value, state.speed)
                    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.action_state, speedLabel, speedValue)) } }, state = rememberTooltipState()) {
                        TextButton(onClick = { speedOpen = true }, enabled = controller != null && state.trackId != null,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = speedLabel; stateDescription = speedValue }) { Text(speedValue) }
                    }
                    IconAction(R.drawable.ic_bookmark, stringResource(R.string.add_bookmark), {
                        val bookId = state.bookId; val trackId = state.trackId
                        if (bookId != null && trackId != null) {
                            drafts.begin(bookId, trackId, state.positionMs)
                            bookmarkDraftBook = bookId
                            bookmarkOpen = true
                        }
                    }, enabled = state.bookId != null && state.trackId != null && state.ready)
                    IconAction(R.drawable.ic_chapters, stringResource(R.string.chapters), { chaptersOpen = true }, enabled = chapters.isNotEmpty())
                }
                if (state.failed || state.saveFailed) {
                    Text(stringResource(if (state.saveFailed) R.string.progress_save_failed else R.string.playback_failed), color = MaterialTheme.colorScheme.error)
                    if (state.failed) TextButton(onClick={ scope.launch { if (state.bookId==null || controller?.loadBook(state.bookId)!=true) onError(R.string.playback_failed) } }) { Text(stringResource(R.string.retry_audio)) }
                    if (state.saveFailed) TextButton(onClick = { scope.launch { if (controller?.command(PlaybackService.RETRY_SAVE) != true) onError(R.string.progress_save_failed) } }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
    val bookmarkDraft = bookmarkDrafts[bookmarkDraftBook]
    if (bookmarkOpen && bookmarkDraft != null) {
        val draft = bookmarkDraft
        var saving by remember { mutableStateOf(false) }
        var saveError by rememberSaveable(draft.id) { mutableStateOf(false) }
        val overLimit = draft.note.length > io.github.jnesew.paudiobooks.library.LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH
        InputDialog(title = stringResource(R.string.add_bookmark), onDismissRequest = { if (!saving) bookmarkOpen = false },
            content = {
                OutlinedTextField(draft.note, { drafts.change(draft.bookId, it); saveError = false },
                    label = { Text(stringResource(R.string.bookmark_note)) }, maxLines = 6, enabled = !saving,
                    isError = overLimit, modifier = Modifier.fillMaxWidth())
                if (overLimit) Text(stringResource(R.string.bookmark_note_too_long), color = MaterialTheme.colorScheme.error)
                if (saveError) Text(stringResource(R.string.bookmark_failed), color = MaterialTheme.colorScheme.error)
                if (saving) Text(stringResource(R.string.saving))
            },
            actions = {
                TextButton(onClick = { drafts.clear(draft.bookId, draft.id); bookmarkOpen = false }, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    if (saving || overLimit) return@TextButton
                    saving = true
                    saveError = false
                    scope.launch {
                        try {
                            if (app.repository.addBookmark(draft.bookId, draft.trackId, draft.positionMs,
                                    draft.note.trim().ifEmpty { null }, draft.id) != null) {
                                drafts.clear(draft.bookId, draft.id)
                                bookmarkOpen = false
                            } else saveError = true
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { saveError = true }
                        finally { saving = false }
                    }
                }, enabled = !saving && !overLimit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
            })
    }

    if (sleepOpen) {
        var customMinutes by rememberSaveable { mutableStateOf("") }
        var hasSubmitted by rememberSaveable { mutableStateOf(false) }
        val parsedDuration = SleepTimerInput.parseMinutesToMillis(customMinutes)
        val isError = (hasSubmitted && parsedDuration == null) || (customMinutes.isNotBlank() && parsedDuration == null)
        fun startCustomTimer() {
            if (parsedDuration != null) {
                scope.launch {
                    if (controller?.command(PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                        putLong(PlaybackService.SLEEP_DURATION_MS, parsedDuration)
                    }) != true) onError(R.string.playback_failed)
                }
                sleepOpen = false
            } else {
                hasSubmitted = true
            }
        }
        InputDialog(
            onDismissRequest = { sleepOpen = false },
            title = stringResource(R.string.sleep_timer),
            content = {
                Column(Modifier.fillMaxWidth()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = {
                            scope.launch {
                                if (controller?.command(PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                                    putLong(PlaybackService.SLEEP_DURATION_MS, minutes * 60_000L)
                                }) != true) onError(R.string.playback_failed)
                            }
                            sleepOpen = false
                        }) {
                            Text(pluralStringResource(R.plurals.minutes, minutes, minutes))
                        }
                    }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            if (controller?.command(PlaybackService.SET_SLEEP_TIMER, Bundle().apply {
                                putBoolean(PlaybackService.SLEEP_END_CHAPTER, true)
                            }) != true) onError(R.string.playback_failed)
                        }
                        sleepOpen = false
                    }) {
                        Text(stringResource(R.string.end_of_chapter))
                    }
                    if (state.sleepActive) TextButton(onClick = {
                        scope.launch { if (controller?.command(PlaybackService.CANCEL_SLEEP_TIMER) != true) onError(R.string.playback_failed) }
                        sleepOpen = false
                    }) {
                        Text(stringResource(R.string.cancel_sleep_timer))
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = {
                                customMinutes = it
                                if (hasSubmitted && SleepTimerInput.parseMinutesToMillis(it) != null) {
                                    hasSubmitted = false
                                }
                            },
                            label = { Text(stringResource(R.string.custom_sleep_minutes_label)) },
                            singleLine = true,
                            isError = isError,
                            supportingText = if (isError) {
                                { Text(stringResource(R.string.custom_sleep_range_error)) }
                            } else null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { startCustomTimer() }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { startCustomTimer() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.custom_sleep_start))
                        }
                    }
                }
            },
            actions = { TextButton(onClick = { sleepOpen = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (speedOpen) SpeedSheet(stringResource(R.string.speed), state.speed, onDismiss = { speedOpen = false },
        onSpeed = { value -> controller?.command(PlaybackService.SET_SPEED, Bundle().apply { putFloat(PlaybackService.SPEED, value) }) == true })
    if (chaptersOpen) ChapterSheet(chapters, currentChapter, state.positionMs,
        tracks.associate { it.track.id to it.asset.durationMs }, state.ready && controller != null,
        onDismiss = { chaptersOpen = false }, onSelect = { seekChapterCommand(chapters.indexOf(it)) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconAction(R.drawable.ic_back_skip, pluralStringResource(R.plurals.back_by_seconds, settings.skipBackSeconds, settings.skipBackSeconds), { controller?.seekTo((state.positionMs - settings.skipBackSeconds * 1_000L).coerceAtLeast(0)) }, enabled = state.ready && controller != null, seconds = settings.skipBackSeconds)
            IconAction(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (state.playing) R.string.pause else R.string.play), ::toggle, enabled = controller != null)
            IconAction(R.drawable.ic_forward_skip, pluralStringResource(R.plurals.forward_by_seconds, settings.skipForwardSeconds, settings.skipForwardSeconds), { controller?.seekTo(forwardPosition(state.positionMs, settings.skipForwardSeconds, state.durationMs)) }, enabled = state.ready && controller != null, seconds = settings.skipForwardSeconds)
        }
    }
}

private fun forwardPosition(position: Long, seconds: Int, duration: Long?): Long {
    val amount = seconds.toLong().coerceAtLeast(0) * 1_000L
    return (if (position > Long.MAX_VALUE - amount) Long.MAX_VALUE else position + amount)
        .coerceAtMost(duration ?: Long.MAX_VALUE)
}
