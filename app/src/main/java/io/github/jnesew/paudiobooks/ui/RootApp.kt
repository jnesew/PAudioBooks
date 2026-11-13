package io.github.jnesew.paudiobooks.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.BookDetails
import io.github.jnesew.paudiobooks.playback.PlaybackService
import io.github.jnesew.paudiobooks.preferences.ThemeChoice
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private enum class Destination { LIBRARY, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun RootApp(choice: ThemeChoice, model: LibraryViewModel, onTheme: (ThemeChoice) -> Unit) {
    val app = LocalContext.current.applicationContext as AudiobookApplication
    var destination by rememberSaveable { mutableStateOf(Destination.LIBRARY) }
    var selectedBook by rememberSaveable { mutableStateOf<String?>(null) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    val playback = rememberPlaybackConnection()
    val scope = rememberCoroutineScope()
    val bookScope by model.seekScopeBook.collectAsStateWithLifecycle()
    LaunchedEffect(playback.state.bookId) { model.syncSeekScope(playback.state.bookId) }
    val books by model.summaries.collectAsStateWithLifecycle()
    val import by model.importing.collectAsStateWithLifecycle()
    val coverBusy by model.coverBusy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val opened by model.openedBook.collectAsStateWithLifecycle()
    LaunchedEffect(opened) {
        if (opened != null) { selectedBook = opened; destination = Destination.LIBRARY; model.openedBookHandled() }
    }
    val activeId = playback.state.bookId ?: selectedBook
    val details by produceState<BookDetails?>(null, selectedBook, activeId, fullPlayer) {
        val id = if (fullPlayer) activeId else selectedBook
        value = null
        if (id != null) app.repository.observeBookDetails(id).catch { model.showError(R.string.library_read_error) }.collect { value = it }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data?.data)
            if (uris.isNotEmpty()) model.select(uris)
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let { model.select(emptyList(), it) }
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    fun picker(folder: Boolean, other: Boolean = false) {
        adding = false
        val intent = Intent(if (folder) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (!folder) { addCategory(Intent.CATEGORY_OPENABLE); type = if (other) "*/*" else "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        }
        try { if (folder) pickFolder.launch(intent) else pickFiles.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { model.showError(R.string.picker_unavailable) }
    }
    val play: (String) -> Unit = { bookId ->
        scope.launch {
            val controller = playback.controller
            if (controller == null) { model.showError(R.string.playback_failed); return@launch }
            val replay = details?.takeIf { it.book.id == bookId }?.progress?.finished == true
            if (playback.state.bookId != bookId && !controller.loadBook(bookId)) { model.showError(R.string.playback_failed); return@launch }
            if (replay) {
                val firstTrack = details?.tracks?.firstOrNull()?.track?.id
                if (firstTrack == null || !controller.command(PlaybackService.SEEK_TRACK_POSITION, Bundle().apply {
                    putString(PlaybackService.BOOK_ID, bookId); putString(PlaybackService.TRACK_ID, firstTrack)
                    putLong(PlaybackService.TRACK_POSITION_MS, 0)
                })) { model.showError(R.string.playback_failed); return@launch }
            }
            if (replay || playback.state.bookId != bookId) controller.play()
            selectedBook = bookId
            fullPlayer = true
        }
    }
    BackHandler(fullPlayer || selectedBook != null) {
        if (fullPlayer) fullPlayer = false else selectedBook = null
    }
    if (fullPlayer) {
        PlayerScreen(details, playback, model.bookmarkDrafts, bookScope, model::setSeekScope, onClose = { fullPlayer = false }, onError = model::showError)
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) NavigationRail {
                    Destination.entries.forEach { item -> NavigationRailItem(selected = destination == item,
                        onClick = { destination = item; selectedBook = null }, icon = { DestinationIcon(item) }, label = { DestinationText(item) }) }
                }
                Scaffold(Modifier.weight(1f), bottomBar = {
                    Column(if (wide) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier) {
                        if (playback.state.trackId != null) MiniPlayer(playback,
                            onOpen = { fullPlayer = true }, onError = model::showError)
                        if (!wide) NavigationBar {
                            Destination.entries.forEach { item -> NavigationBarItem(selected = destination == item,
                                onClick = { destination = item; selectedBook = null }, icon = { DestinationIcon(item) }, label = { DestinationText(item) }) }
                        }
                    }
                }) { padding ->
                    when {
                        destination == Destination.SETTINGS -> SettingsPage(choice, onTheme, Modifier.padding(padding), model::showError, books, playback, model)
                        selectedBook != null -> BookPage(details, playback.state, Modifier.padding(padding), model.bookmarkDrafts,
                            controllerReady = playback.controller != null,
                            onSeek = { id, trackId, offset ->
                                val c = playback.controller
                                val success = c != null && (playback.state.bookId == id || c.loadBook(id)) &&
                                    c.command(PlaybackService.SEEK_TRACK_POSITION, Bundle().apply {
                                        putString(PlaybackService.BOOK_ID, id); putString(PlaybackService.TRACK_ID, trackId)
                                        putLong(PlaybackService.TRACK_POSITION_MS, offset)
                                    })
                                if (!success) model.showError(R.string.playback_failed)
                                success
                            },
                            onMark = { id, finished -> model.setFinished(id, finished, playback.controller) },
                            onCover = model::setCover, onResetCover = model::resetCover,
                            onRepair = { id, uris -> model.repairBook(id, uris, playback.controller, playback.state.bookId) },
                            onVerify = { id -> model.verifyLinkedBook(id,playback.controller) },
                            onRescan = model::rescanBook,
                            onReadMetadata = { id -> model.readLinkedMetadata(id,playback.controller) },
                            onRetry = { id -> scope.launch { if (playback.controller?.loadBook(id)==true) fullPlayer=true else model.showError(R.string.playback_failed) } },
                            onReauthorize = { id,uri -> model.reauthorizeBook(id,uri,playback.controller,playback.state.bookId) },
                            onBack = { selectedBook = null }, onPlay = { selectedBook?.let(play) },
                            onRemove = { id -> scope.launch {
                                executeBookRemoval(
                                    bookId = id,
                                    activeBookId = playback.state.bookId,
                                    controller = playback.controller,
                                    removeBook = model::removeBook,
                                    showError = model::showError,
                                    onSuccess = { selectedBook = null }
                                )
                            } }, onError = model::showError)
                        else -> LibraryPage(books, Modifier.padding(padding), onAdd = { adding = true },
                            activeBookId = playback.state.bookId, onBook = { selectedBook = it }, onContinue = { id ->
                                selectedBook = id
                                if (playback.state.bookId == id) fullPlayer = true else scope.launch {
                                    if (playback.controller?.loadBook(id) == true) fullPlayer = true
                                    else model.showError(R.string.playback_failed)
                                }
                            })
                    }
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.add_books)) },
        text = { Column {
            Button(onClick = { picker(false) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_files)) }
            OutlinedButton(onClick = { picker(false, true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.other_files)) }
            OutlinedButton(onClick = { picker(true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_folder)) }
            if (import.interruptedJobs.isNotEmpty()) TextButton(onClick = { adding = false; model.reviewInterrupted() }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unfinished_imports))
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } })
    ImportDialogs(import, model,playback.controller)
    val sourceWork by model.sourceWork.collectAsStateWithLifecycle()
    if (sourceWork.busy) AlertDialog(onDismissRequest={},title={ Text(stringResource(if (sourceWork.metadata) R.string.reading_metadata else R.string.verifying_audio)) },text={ Column {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        if (sourceWork.totalFiles > 0) Text(stringResource(if (sourceWork.metadata) R.string.reading_file else R.string.source_work_files,
            if (sourceWork.metadata) minOf(sourceWork.completedFiles+1,sourceWork.totalFiles) else sourceWork.completedFiles,sourceWork.totalFiles))
        if (sourceWork.bytes > 0) Text(stringResource(R.string.source_bytes_read,Formatter.formatShortFileSize(LocalContext.current,sourceWork.bytes)))
    } },confirmButton={},dismissButton={ TextButton(onClick=model::cancelSourceWork) { Text(stringResource(R.string.cancel)) } })
    if (sourceWork.complete) AlertDialog(onDismissRequest=model::dismissSourceComplete,title={ Text(stringResource(if (sourceWork.metadata) R.string.metadata_read_complete else R.string.source_work_complete)) },text={ if (sourceWork.metadata && sourceWork.pendingFiles>0) Text(pluralStringResource(R.plurals.metadata_still_pending,sourceWork.pendingFiles,sourceWork.pendingFiles)) },confirmButton={ TextButton(onClick=model::dismissSourceComplete) { Text(stringResource(R.string.ok)) } })
    if (coverBusy) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.working)) }, text = { CircularProgressIndicator() }, confirmButton = {})
    if (error != null) AlertDialog(onDismissRequest = model::dismissError,
        title = { Text(stringResource(R.string.settings_error)) }, text = { Text(stringResource(error!!)) },
        confirmButton = { TextButton(onClick = { if (error == R.string.library_read_error) model.retryLibrary() else model.dismissError() }) { Text(stringResource(R.string.ok)) } })
}

@Composable private fun DestinationIcon(destination: Destination) {
    Icon(if (destination == Destination.LIBRARY) Icons.Default.Home else Icons.Default.Settings, null)
}
@Composable private fun DestinationText(destination: Destination) {
    Text(stringResource(if (destination == Destination.LIBRARY) R.string.library else R.string.settings))
}
