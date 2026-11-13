package io.github.jnesew.paudiobooks.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.BookDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookPage(
    details: BookDetails?,
    playback: PlaybackSnapshot,
    modifier: Modifier,
    drafts: BookmarkDrafts,
    controllerReady: Boolean,
    onSeek: suspend (String, String, Long) -> Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onRemove: (String) -> Unit,
    onError: (Int) -> Unit,
    onMark: (String, Boolean) -> Unit,
    onCover: (String, android.net.Uri) -> Unit,
    onResetCover: (String) -> Unit,
    onRepair: (String, List<android.net.Uri>) -> Unit,
    onVerify: (String) -> Unit,
    onRescan: (String) -> Unit,
    onReadMetadata: (String) -> Unit,
    onRetry: (String) -> Unit,
    onReauthorize: (String, android.net.Uri) -> Unit
) {
    var edit by rememberSaveable { mutableStateOf(false) }
    var chaptersOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    var readMetadata by rememberSaveable { mutableStateOf(false) }
    var informationOpen by rememberSaveable { mutableStateOf(false) }
    var audioFilesOpen by rememberSaveable { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var resetProgress by rememberSaveable { mutableStateOf(false) }
    var expandedCredits by rememberSaveable { mutableStateOf(false) }
    var draftBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var draftAuthor by rememberSaveable { mutableStateOf<String?>(null) }
    var draftNarrator by rememberSaveable { mutableStateOf<String?>(null) }
    var draftSeries by rememberSaveable { mutableStateOf<String?>(null) }
    var draftTranslator by rememberSaveable { mutableStateOf<String?>(null) }
    var draftSeriesNumber by rememberSaveable { mutableStateOf<String?>(null) }
    // Details are briefly null while Room reconnects after recreation. Keying saved
    // editor state to that transient value discards the restored draft on emission.
    var editingBookmarkId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as AudiobookApplication
    var remove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var coverTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val target = coverTarget
        coverTarget = null
        if (result.resultCode == android.app.Activity.RESULT_OK && uri?.scheme == android.content.ContentResolver.SCHEME_CONTENT && target != null) onCover(target, uri)
    }
    val linked = details?.tracks?.any { it.documentSource!=null }==true
    var verify by rememberSaveable { mutableStateOf(false) }
    var repair by rememberSaveable { mutableStateOf(false) }
    var repairTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val accessPicker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target=repairTarget
        repairTarget=null
        if (result.resultCode==android.app.Activity.RESULT_OK && target!=null) result.data?.data?.let { onReauthorize(target,it) }
    }
    val repairPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = repairTarget
        repairTarget = null
        if (result.resultCode == android.app.Activity.RESULT_OK && target != null) {
            val data = result.data
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data?.data)
            if (uris.isNotEmpty()) onRepair(target, uris)
        }
    }
    if (repair && details != null) AlertDialog(
        onDismissRequest = { repair = false },
        title = { Text(stringResource(if (linked) R.string.reconnect_audio else R.string.repair_audio)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Column {
                Text(stringResource(if (linked) R.string.reconnect_explanation else R.string.repair_explanation))
                details.linkedRoot?.let { root ->
                    OutlinedButton(onClick={
                        repair=false; repairTarget=details.book.id
                        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            putExtra(Intent.EXTRA_LOCAL_ONLY,true)
                            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,android.net.Uri.parse(root.grantUri))
                        }
                        try { accessPicker.launch(intent) } catch (_:android.content.ActivityNotFoundException) { onError(R.string.picker_unavailable) }
                    }) { Text(stringResource(R.string.grant_folder_access)) }
                }
            }
            if (playback.bookId == details.book.id) Text(stringResource(R.string.repair_pauses_book))
        } },
        confirmButton = { TextButton(onClick = {
            repair = false
            repairTarget = details.book.id
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            try { repairPicker.launch(intent) }
            catch (_: android.content.ActivityNotFoundException) { onError(R.string.picker_unavailable) }
        }) { Text(stringResource(R.string.choose_files)) } },
        dismissButton = { TextButton(onClick = { repair = false }) { Text(stringResource(R.string.cancel)) } }
    )
    fun editBook() {
        val book = details?.book ?: return
        draftBookId = book.id; draftTitle = book.displayTitle; draftAuthor = book.displayAuthor.orEmpty()
        draftNarrator = book.displayNarrator.orEmpty(); draftSeries = book.displaySeries.orEmpty()
        draftTranslator = book.displayTranslator.orEmpty(); draftSeriesNumber = book.displaySeriesNumber.orEmpty()
        edit = true
    }
    fun chooseCover() {
        val book = details?.book ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        coverTarget = book.id
        try { coverPicker.launch(intent) } catch (_: android.content.ActivityNotFoundException) { onError(R.string.picker_unavailable) }
    }
    val available by produceState<Boolean?>(null, details?.tracks) {
        val checks=details?.tracks?.map { track ->
            val source=track.documentSource
            if (source==null) app.audioStore.resolve(track.asset.relativePath)!=null
            else when (source.availability) { "AVAILABLE" -> true; "UNCHECKED" -> null; else -> false }
        }
        value=when { checks==null -> null; checks.any { it==false } -> false; checks.any { it==null } -> null; else -> true }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            if (details != null) Box {
                IconAction(R.drawable.ic_more, stringResource(R.string.book_actions), { actionsOpen = true })
                DropdownMenu(actionsOpen, { actionsOpen = false }) {
                    fun actionClick(action: () -> Unit) { actionsOpen = false; action() }
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_details)) }, onClick = { actionClick(::editBook) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.choose_cover)) }, onClick = { actionClick(::chooseCover) })
                    if (details.book.coverOverridePath != null) DropdownMenuItem(text = { Text(stringResource(R.string.reset_cover)) }, onClick = { actionClick { onResetCover(details.book.id) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mark_finished)) }, enabled = details.progress?.finished != true, onClick = { actionClick { onMark(details.book.id, true) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reset_progress)) }, enabled = details.progress != null, onClick = { actionClick { resetProgress = true } })
                    DropdownMenuItem(text = { Text(stringResource(if (linked) R.string.reconnect_audio else R.string.repair_audio)) }, onClick = { actionClick { repair = true } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_book)) }, onClick = { actionClick { remove = true } })
                }
            }
        } }
        if (details == null) item { CircularProgressIndicator() } else {
            item { Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BookCover(details.book, Modifier.sizeIn(maxWidth = 100.dp, maxHeight = 140.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(details.book.displayTitle, style = MaterialTheme.typography.headlineMedium)
                    details.book.displayAuthor?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    val credits = listOfNotNull(
                        details.book.displaySeries?.let { series -> stringResource(R.string.series_credit, details.book.displaySeriesNumber?.let { stringResource(R.string.series_number_credit, series, it) } ?: series) },
                        details.book.displayNarrator?.let { stringResource(R.string.narrator_credit, it) },
                        details.book.displayTranslator?.let { stringResource(R.string.translator_credit, it) })
                    credits.forEach { Text(it, maxLines = if (expandedCredits) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis) }
                    if (credits.any { it.length > 160 }) TextButton(onClick = { expandedCredits = !expandedCredits }) { Text(stringResource(if (expandedCredits) R.string.show_less else R.string.show_more)) }
                }
            } }
            item {
                val spans = details.tracks.map { io.github.jnesew.paudiobooks.playback.TrackSpan(it.track.id, it.asset.durationMs) }
                val duration = io.github.jnesew.paudiobooks.playback.Timeline.total(spans)
                val live = playback.takeIf { it.bookId == details.book.id && it.trackId != null }
                val index = if (live != null) details.tracks.indexOfFirst { it.track.id == live.trackId }
                    else details.tracks.indexOfFirst { it.track.id == details.progress?.trackId }
                val position = io.github.jnesew.paudiobooks.playback.Timeline.toBook(spans,
                    io.github.jnesew.paudiobooks.playback.TrackPosition(index, live?.positionMs ?: details.progress?.offsetMs ?: 0L))
                BookTime(duration, position, live?.speed ?: details.progress?.speed ?: 1f,
                    details.progress?.finished == true, labelDuration = true)

            }
            item { Button(onClick = onPlay, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = controllerReady) {
                Text(stringResource(when { details.progress?.finished == true -> R.string.replay_book; playback.bookId == details.book.id -> R.string.open_player; details.progress == null -> R.string.play; else -> R.string.resume }))
            } }
            if (available == false || playback.bookId == details.book.id && playback.failed) item { Column {
                OutlinedButton(onClick={ onRetry(details.book.id) },Modifier.fillMaxWidth(),enabled=controllerReady) { Text(stringResource(R.string.retry_audio)) }
                OutlinedButton(onClick = { repair = true }, Modifier.fillMaxWidth()) { Text(stringResource(if (linked) R.string.reconnect_audio else R.string.repair_audio)) }
            } }
            item {
                val chapterIndex=details.chapters.indexOfLast { it.trackId==details.progress?.trackId && it.startMs<=(details.progress?.offsetMs ?: 0L) }
                val label=details.chapters.getOrNull(chapterIndex)?.let { it.title ?: stringResource(R.string.chapter_number,chapterIndex+1) }
                OverviewRow(stringResource(R.string.chapters), stringResource(R.string.count_value, details.chapters.size),label) { chaptersOpen = true }
            }
            item { OverviewRow(stringResource(R.string.bookmarks), stringResource(R.string.count_value, details.bookmarks.size)) { bookmarksOpen = true } }
            item { OverviewRow(stringResource(R.string.book_information), null) { informationOpen = true } }
        }
    }
    if (details != null && resetProgress) AlertDialog(onDismissRequest = { resetProgress = false },
        title = { Text(stringResource(R.string.reset_progress)) }, text = { Text(stringResource(R.string.reset_progress_explanation)) },
        confirmButton = { TextButton(onClick = { resetProgress = false; onMark(details.book.id, false) }) { Text(stringResource(R.string.reset)) } },
        dismissButton = { TextButton(onClick = { resetProgress = false }) { Text(stringResource(R.string.cancel)) } })
    if (details != null && informationOpen) {
        BookInformationWindow(details, audioFilesOpen, available, controllerReady,
            onDismiss = { informationOpen = false }, onAudioFiles = { audioFilesOpen = true },
            onBack = { audioFilesOpen = false },
            onReadMetadata = { readMetadata = true }, onVerify = { verify = true },
            onRescan = { informationOpen = false; audioFilesOpen = false; onRescan(details.book.id) })
    }
    if (details != null && chaptersOpen) {
        val live = playback.takeIf { it.bookId == details.book.id && it.trackId != null }
        val trackId = live?.trackId ?: details.progress?.trackId
        val position = live?.positionMs ?: details.progress?.offsetMs ?: 0L
        val current = details.chapters.indexOfLast { it.trackId == trackId && it.startMs <= position && (it.endMs == null || position < it.endMs) }
        ChapterSheet(details.chapters, current, position, details.tracks.associate { it.track.id to it.asset.durationMs },
            controllerReady, onDismiss = { chaptersOpen = false },
            onSelect = { onSeek(details.book.id, it.trackId, it.startMs) })
    }

    if (details != null && bookmarksOpen) ModalBottomSheet(onDismissRequest = { bookmarksOpen = false }) {
        Text(stringResource(R.string.bookmarks), Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(details.bookmarks, key = { it.id }) { mark ->
                var menuOpen by remember { mutableStateOf(false) }
                val chapterIndex = details.chapters.indexOfLast { it.trackId == mark.trackId && it.startMs <= mark.offsetMs }
                val chapterTitle = details.chapters.getOrNull(chapterIndex)?.let { it.title ?: stringResource(R.string.chapter_number, chapterIndex + 1) }
                val bookmarkLabel = stringResource(R.string.jump_to_bookmark, chapterTitle.orEmpty(), mediaTime(mark.offsetMs))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(enabled = controllerReady, onClickLabel = bookmarkLabel) {
                        scope.launch { if (onSeek(details.book.id, mark.trackId, mark.offsetMs)) bookmarksOpen = false }
                    }.padding(16.dp)) {
                        chapterTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        Text(mediaTime(mark.offsetMs))
                        mark.note?.let { Text(it) }
                    }
                    Box {
                        IconAction(R.drawable.ic_more, stringResource(R.string.bookmark_actions, chapterTitle.orEmpty(), mediaTime(mark.offsetMs)), { menuOpen = true })
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit_bookmark_note)) }, onClick = { menuOpen = false; editingBookmarkId = mark.id })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    try {
                                        if (!app.repository.deleteBookmark(details.book.id, mark.id)) onError(R.string.bookmark_failed)
                                        else drafts.clearNote(mark.id)
                                    } catch (c: CancellationException) { throw c }
                                    catch (_: Exception) { onError(R.string.bookmark_failed) }
                                }
                            })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (edit && details != null) {
        if (draftBookId != details.book.id) {
            draftBookId = details.book.id
            draftTitle = details.book.displayTitle
            draftAuthor = details.book.displayAuthor.orEmpty()
            draftNarrator = (details.book.narratorOverride ?: details.book.importedNarrator).orEmpty()
            draftSeries = details.book.displaySeries.orEmpty()
            draftTranslator = details.book.displayTranslator.orEmpty()
            draftSeriesNumber = details.book.displaySeriesNumber.orEmpty()
        }
        val currentTitle = draftTitle ?: details.book.displayTitle
        val currentAuthor = draftAuthor ?: details.book.displayAuthor.orEmpty()
        val currentNarrator = draftNarrator ?: (details.book.narratorOverride ?: details.book.importedNarrator).orEmpty()
        val currentSeries = draftSeries ?: details.book.displaySeries.orEmpty()
        val currentTranslator = draftTranslator ?: details.book.displayTranslator.orEmpty()
        val currentSeriesNumber = draftSeriesNumber ?: details.book.displaySeriesNumber.orEmpty()

        fun dismissEdit() {
            edit = false
            draftBookId = null
            draftTitle = null
            draftAuthor = null
            draftNarrator = null
            draftSeries = null
            draftTranslator = null
            draftSeriesNumber = null
        }

        Dialog(
            onDismissRequest = { dismissEdit() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { dismissEdit() } }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                    modifier = Modifier
                        .pointerInput(Unit) { detectTapGestures { } }
                        .widthIn(min = 280.dp, max = 560.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.edit_details),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        OutlinedTextField(
                            value = currentTitle,
                            onValueChange = { draftTitle = it.take(512) },
                            label = { Text(stringResource(R.string.book_title)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = currentAuthor,
                            onValueChange = { draftAuthor = it.take(512) },
                            label = { Text(stringResource(R.string.book_author)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = currentNarrator,
                            onValueChange = { draftNarrator = it.take(512) },
                            label = { Text(stringResource(R.string.book_narrator)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = currentSeries,
                            onValueChange = { draftSeries = it.take(512) },
                            label = { Text(stringResource(R.string.book_series)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(currentSeriesNumber, { draftSeriesNumber = it.take(512) }, label = { Text(stringResource(R.string.book_series_number)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(currentTranslator, { draftTranslator = it.take(512) }, label = { Text(stringResource(R.string.book_translator)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        var isSaving by remember { mutableStateOf(false) }
                        TextButton(
                            enabled = !isSaving,
                            onClick = {
                                scope.launch {
                                    isSaving = true
                                    val success = try {
                                        app.repository.updateOverrides(details.book.id, null, null, null, null)
                                    } catch (c: CancellationException) {
                                        isSaving = false
                                        throw c
                                    } catch (_: Exception) {
                                        false
                                    }
                                    isSaving = false
                                    if (success) {
                                        dismissEdit()
                                    } else {
                                        onError(R.string.edit_failed)
                                    }
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.reset_overrides))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                enabled = !isSaving,
                                onClick = { dismissEdit() },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                enabled = !isSaving && currentTitle.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        val success = try {
                                            app.repository.updateOverrides(
                                                details.book.id,
                                                metadataOverride(currentTitle, details.book.displayTitle, details.book.titleOverride),
                                                metadataOverride(currentAuthor, details.book.displayAuthor, details.book.authorOverride),
                                                metadataOverride(currentNarrator, details.book.displayNarrator, details.book.narratorOverride),
                                                metadataOverride(currentSeries, details.book.displaySeries, details.book.seriesOverride),
                                                metadataOverride(currentTranslator, details.book.displayTranslator, details.book.translatorOverride),
                                                metadataOverride(currentSeriesNumber, details.book.displaySeriesNumber, details.book.seriesNumberOverride)
                                            )
                                        } catch (c: CancellationException) {
                                            isSaving = false
                                            throw c
                                        } catch (_: Exception) {
                                            false
                                        }
                                        isSaving = false
                                        if (success) {
                                            dismissEdit()
                                        } else {
                                            onError(R.string.edit_failed)
                                        }
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }
        }
    }
    if (remove && details != null) AlertDialog(onDismissRequest = { remove = false },
        title = { Text(stringResource(R.string.remove_book)) }, text = { Text(if (linked) stringResource(R.string.remove_linked_explanation) else stringResource(R.string.remove_explanation,
            Formatter.formatShortFileSize(context, details.tracks.sumOf { it.asset.sizeBytes }))) },
        confirmButton = { TextButton(onClick = { remove = false; onRemove(details.book.id) }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.cancel)) } })
    if (readMetadata && details!=null) AlertDialog(onDismissRequest={ readMetadata=false },title={ Text(stringResource(R.string.read_metadata)) },text={ Text(stringResource(R.string.read_metadata_explanation)) },
        confirmButton={ TextButton(onClick={ readMetadata=false; informationOpen=false; audioFilesOpen=false; onReadMetadata(details.book.id) }) { Text(stringResource(R.string.continue_label)) } },
        dismissButton={ TextButton(onClick={ readMetadata=false }) { Text(stringResource(R.string.cancel)) } })
    if (verify && details!=null) AlertDialog(onDismissRequest={ verify=false },title={ Text(stringResource(R.string.verify_audio)) },text={ Text(stringResource(R.string.verify_audio_explanation)) },
        confirmButton={ TextButton(onClick={ verify=false; onVerify(details.book.id) }) { Text(stringResource(R.string.continue_label)) } },
        dismissButton={ TextButton(onClick={ verify=false }) { Text(stringResource(R.string.cancel)) } })
    val editingBookmark = details?.bookmarks?.firstOrNull { it.id == editingBookmarkId }
    if (editingBookmarkId != null && editingBookmark != null) {
        BookmarkNoteDialog(
            bookmark = editingBookmark,
            bookId = details.book.id,
            drafts = drafts,
            onDismiss = { editingBookmarkId = null }
        )
    }
}

@Composable
internal fun OverviewRow(label: String, value: String?, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleMedium); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
        value?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium) }
        Icon(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_chevron), contentDescription = null, modifier = Modifier.size(16.dp))
    }
    HorizontalDivider()
}
