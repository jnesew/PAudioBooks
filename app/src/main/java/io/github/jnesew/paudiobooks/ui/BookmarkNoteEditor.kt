package io.github.jnesew.paudiobooks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.Bookmark
import io.github.jnesew.paudiobooks.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BookmarkNoteDialog(
    bookmark: Bookmark,
    bookId: String,
    drafts: BookmarkDrafts,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AudiobookApplication
    val scope = rememberCoroutineScope()

    var noteText by rememberSaveable(bookId, bookmark.id) { mutableStateOf(drafts.note(bookmark.id, bookmark.note.orEmpty())) }
    var saveErrorMessageRes by rememberSaveable(bookId, bookmark.id) { mutableStateOf<Int?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val isOverLimit = noteText.length > LibraryRepository.MAX_BOOKMARK_NOTE_LENGTH

    InputDialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        title = stringResource(R.string.edit_bookmark_note),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = {
                        noteText = it
                        drafts.changeNote(bookmark.id, it)
                        saveErrorMessageRes = null
                    },
                    label = { Text(stringResource(R.string.bookmark_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving,
                    maxLines = 6,
                    isError = isOverLimit,
                    supportingText = {
                        if (isOverLimit) {
                            Text(
                                text = stringResource(R.string.bookmark_note_too_long),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
                if (isSaving) Text(stringResource(R.string.saving))
                saveErrorMessageRes?.let { errorRes ->
                    Text(
                        text = stringResource(errorRes),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        actions = {
            TextButton(onClick = { drafts.clearNote(bookmark.id); onDismiss() }, enabled = !isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(
                onClick = {
                    if (isSaving || isOverLimit) return@TextButton
                    isSaving = true
                    saveErrorMessageRes = null
                    scope.launch {
                        try {
                            val success = app.repository.updateBookmarkNote(bookId, bookmark.id, noteText)
                            if (success) {
                                drafts.clearNote(bookmark.id)
                                onDismiss()
                            } else {
                                saveErrorMessageRes = R.string.bookmark_note_update_failed
                                isSaving = false
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            saveErrorMessageRes = R.string.bookmark_note_update_failed
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && !isOverLimit,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    )
}
