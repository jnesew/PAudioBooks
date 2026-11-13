package io.github.jnesew.paudiobooks.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.media3.session.MediaController
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.importing.UnverifiedLinkedAudioException
import io.github.jnesew.paudiobooks.playback.PlaybackService
import io.github.jnesew.paudiobooks.portability.PortabilityPreview
import io.github.jnesew.paudiobooks.portability.PortabilitySelection
import io.github.jnesew.paudiobooks.portability.ProgressPortability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TransferUi(
    val busy: Boolean = false,
    val preview: PortabilityPreview? = null,
    val complete: Boolean = false
)

/** Owns transfer preview, journal recovery and work for the library ViewModel's lifetime. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ProgressTransfer(
    private val app: AudiobookApplication,
    private val scope: CoroutineScope,
    private val onError: (Int) -> Unit
) {
    private val transferJournal = app.getSharedPreferences("transfer_journal", 0)
    private val transferService = ProgressPortability(app.library)
    private val _transfer = MutableStateFlow(TransferUi())
    val transfer = _transfer.asStateFlow()

    fun dismissTransferPreview() { _transfer.value = _transfer.value.copy(preview = null) }
    fun dismissTransferComplete() { _transfer.value = _transfer.value.copy(complete = false) }
    fun exportProgress(uri: Uri, controller: MediaController?, hasTrack: Boolean) {
        if (_transfer.value.busy) return
        scope.launch {
            _transfer.value = _transfer.value.copy(busy = true)
            try {
                require(uri.scheme == ContentResolver.SCHEME_CONTENT)
                if (hasTrack && controller?.pauseAndSave() != true) error("save")
                withContext(Dispatchers.IO) {
                    requireVerifiedAudio()
                    check(transferJournal.edit().putBoolean("export_in_progress", true).commit())
                    app.contentResolver.openOutputStream(uri, "wt")?.use { transferService.export(it) } ?: error("output")
                    check(transferJournal.edit().remove("export_in_progress").commit())
                }
                _transfer.value = _transfer.value.copy(complete = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                withContext(Dispatchers.IO) { transferJournal.edit().remove("export_in_progress").commit() }
                onError(if (failure is UnverifiedLinkedAudioException) {
                    R.string.transfer_needs_verification
                } else {
                    R.string.export_incomplete
                })
            }
            finally { _transfer.value = _transfer.value.copy(busy = false) }
        }
    }
    fun previewProgress(uri: Uri) {
        if (_transfer.value.busy) return
        scope.launch {
            _transfer.value = _transfer.value.copy(busy = true)
            try {
                require(uri.scheme == ContentResolver.SCHEME_CONTENT)
                val preview = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { transferService.preview(it) } ?: error("input")
                }
                _transfer.value = _transfer.value.copy(preview = preview)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { onError(R.string.portability_failed) }
            finally { _transfer.value = _transfer.value.copy(busy = false) }
        }
    }
    fun applyProgress(
        choices: List<PortabilitySelection>,
        controller: MediaController?,
        activeBook: String?,
        hasTrack: Boolean
    ) {
        val preview = _transfer.value.preview ?: return
        if (_transfer.value.busy) return
        scope.launch {
            _transfer.value = _transfer.value.copy(busy = true)
            var unloaded = false
            try {
                if (controller?.command(PlaybackService.UNLOAD_AND_SAVE) != true) error("save")
                unloaded = hasTrack
                transferService.apply(preview, choices)
                _transfer.value = _transfer.value.copy(preview = null, complete = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { onError(R.string.portability_failed) }
            finally {
                if (unloaded && activeBook != null && controller?.loadBook(activeBook) != true) onError(R.string.playback_failed)
                _transfer.value = _transfer.value.copy(busy = false)
            }
        }
    }
    private suspend fun requireVerifiedAudio() {
        val library = app.library.library()
        val unverified = app.library.books().all().any { book ->
            library.tracks(book.id).any { track ->
                !requireNotNull(library.asset(track.assetId)).identityVerified
            }
        }
        if (unverified) throw UnverifiedLinkedAudioException()
    }

    init {
        scope.launch(Dispatchers.IO) {
            if (transferJournal.getBoolean("export_in_progress", false)) {
                if (transferJournal.edit().remove("export_in_progress").commit()) onError(R.string.export_incomplete)
            }
        }
    }
}
