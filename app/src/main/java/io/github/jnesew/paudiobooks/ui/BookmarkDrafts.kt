package io.github.jnesew.paudiobooks.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class NewBookmarkDraft(
    val id: String, val bookId: String, val trackId: String, val positionMs: Long,
    val note: String = ""
)

/** Session-only drafts: dismissal preserves them; only Save writes library data. */
internal class BookmarkDrafts {
    private val pending = MutableStateFlow<Map<String, NewBookmarkDraft>>(emptyMap())
    val newBookmarks = pending.asStateFlow()
    private val notes = mutableMapOf<String, String>()

    fun begin(bookId: String, trackId: String, positionMs: Long) {
        if (bookId !in pending.value) pending.value += bookId to
            NewBookmarkDraft(UUID.randomUUID().toString(), bookId, trackId, positionMs)
    }
    fun change(bookId: String, note: String) {
        val draft = pending.value[bookId] ?: return
        pending.value += bookId to draft.copy(note = note)
    }
    fun clear(bookId: String, id: String) {
        if (pending.value[bookId]?.id == id) pending.value -= bookId
    }
    fun note(id: String, original: String) = notes[id] ?: original
    fun changeNote(id: String, text: String) { notes[id] = text }
    fun clearNote(id: String) { notes.remove(id) }
}
