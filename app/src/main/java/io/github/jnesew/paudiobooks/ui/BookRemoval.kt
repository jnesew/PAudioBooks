package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.playback.PlaybackService
import kotlinx.coroutines.CancellationException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun executeBookRemoval(
    bookId: String,
    activeBookId: String?,
    controller: androidx.media3.session.MediaController?,
    removeBook: suspend (String) -> Boolean,
    showError: (Int) -> Unit,
    onSuccess: () -> Unit
): Boolean {
    if (activeBookId == bookId) {
        if (controller == null || !controller.command(PlaybackService.UNLOAD_AND_SAVE)) {
            showError(R.string.progress_save_failed)
            return false
        }
    }
    val removed = try {
        removeBook(bookId)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        false
    }
    if (!removed) {
        showError(R.string.removal_failed)
        return false
    }
    onSuccess()
    return true
}
