package io.github.jnesew.paudiobooks.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal enum class SaveResult {
    SUCCESS,
    ERROR_IO,
    ERROR_UNKNOWN
}

internal data class ProgressSnapshot(
    val bookId: String,
    val trackId: String,
    val position: Long,
    val speed: Float,
    val finished: Boolean,
    val sequence: Long
)

internal class ProgressCoordinator(
    private val scope: CoroutineScope,
    private val saveSink: suspend (ProgressSnapshot) -> Boolean,
    private val onStateChanged: (failedBookId: String?, saveFailed: Boolean) -> Unit = { _, _ -> }
) {
    private val writeLock = Any()
    private val wakeWriter = Channel<Unit>(Channel.CONFLATED)
    private val pendingWrites = LinkedHashMap<String, ProgressSnapshot>()
    private val latest = LinkedHashMap<String, ProgressSnapshot>()
    private val committed = LinkedHashMap<String, Long>()
    private val failedBooks = mutableSetOf<String>()
    private val retryingBooks = mutableSetOf<String>()
    private val saveWaiters = mutableListOf<SaveWaiter>()
    private val drainWaiters = mutableListOf<SettableFuture<SaveResult>>()
    private val closeWaiters = mutableListOf<SettableFuture<SaveResult>>()

    private data class SaveWaiter(
        val bookId: String,
        val sequence: Long,
        val future: SettableFuture<SaveResult>
    )

    private var sequence = 0L
    @Volatile private var inFlight = false
    @Volatile private var inFlightBookId: String? = null
    @Volatile private var inFlightSequence: Long? = null
    @Volatile private var inFlightEvicted = false
    @Volatile private var saveFailed = false
    @Volatile private var unloading = false
    @Volatile private var closed = false
    @Volatile private var terminated = false
    @Volatile private var finalCloseResult: SaveResult? = null

    val isSaveFailed: Boolean
        get() = synchronized(writeLock) { failedBooks.isNotEmpty() }

    val isUnloading: Boolean
        get() = unloading

    val isClosed: Boolean
        get() = synchronized(writeLock) { closed }

    val isTerminated: Boolean
        get() = synchronized(writeLock) { terminated }

    init {
        scope.launch {
            try {
                for (signal in wakeWriter) {
                    processBatch()
                }
            } catch (_: Exception) {
            } finally {
                // Channel closed: perform best-effort final drain of any remaining pending writes
                processBatch()
                synchronized(writeLock) {
                    inFlight = false
                    inFlightBookId = null
                    inFlightSequence = null
                    terminated = true
                    val result = if (failedBooks.isNotEmpty()) SaveResult.ERROR_IO else SaveResult.SUCCESS
                    finalCloseResult = result
                    closeWaiters.forEach { it.set(result) }
                    closeWaiters.clear()
                    saveWaiters.forEach { it.future.set(SaveResult.ERROR_UNKNOWN) }
                    saveWaiters.clear()
                    drainWaiters.forEach { it.set(SaveResult.ERROR_UNKNOWN) }
                    drainWaiters.clear()
                    pendingWrites.clear()
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun processBatch() {
        while (true) {
            val state = synchronized(writeLock) {
                // A failure removes its retry token. A later explicit retry/unload
                // can grant a new token even before this writer batch has ended.
                val entry = pendingWrites.entries.firstOrNull { (bookId, _) ->
                    bookId !in failedBooks || bookId in retryingBooks
                }
                if (entry == null) {
                    saveFailed = failedBooks.isNotEmpty()
                    unloading = false
                    val result = if (failedBooks.isEmpty()) SaveResult.SUCCESS else SaveResult.ERROR_IO
                    drainWaiters.forEach { it.set(result) }
                    drainWaiters.clear()
                    null
                } else {
                    inFlight = true
                    inFlightBookId = entry.key
                    inFlightSequence = entry.value.sequence
                    pendingWrites.remove(entry.key)
                    entry.value
                }
            }

            if (state == null) {
                break
            }

            val saved = runCatching { saveSink(state) }.getOrDefault(false)

            if (!saved) {
                var bookTransitionedToFailed = false
                synchronized(writeLock) {
                    val wasEvicted = inFlightEvicted
                    inFlightEvicted = false
                    inFlight = false
                    inFlightBookId = null
                    inFlightSequence = null

                    if (!wasEvicted) {
                        retryingBooks.remove(state.bookId)
                        val newlyFailed = failedBooks.add(state.bookId)
                        saveFailed = failedBooks.isNotEmpty()
                        if (newlyFailed) {
                            bookTransitionedToFailed = true
                        }

                        if (!closed && PlaybackPolicy.shouldRestoreFailedState(
                                failedSequence = state.sequence,
                                pendingSequence = pendingWrites[state.bookId]?.sequence,
                                committedSequence = committed[state.bookId],
                                latestSequence = latest[state.bookId]?.sequence
                            )) {
                            pendingWrites.putIfAbsent(state.bookId, state)
                        }

                        saveWaiters.filter { it.bookId == state.bookId }.forEach {
                            it.future.set(SaveResult.ERROR_IO)
                        }
                        saveWaiters.removeAll { it.bookId == state.bookId }

                        unloading = false
                        drainWaiters.forEach { it.set(SaveResult.ERROR_IO) }
                        drainWaiters.clear()
                    }
                }

                if (bookTransitionedToFailed) {
                    onStateChanged(state.bookId, true)
                }

                val hasOtherEligible = synchronized(writeLock) {
                    pendingWrites.keys.any { it !in failedBooks || it in retryingBooks }
                }
                if (!hasOtherEligible) {
                    break
                }
            } else {
                var bookTransitionedToRecovered = false
                synchronized(writeLock) {
                    inFlightEvicted = false
                    inFlight = false
                    inFlightBookId = null
                    inFlightSequence = null
                    retryingBooks.remove(state.bookId)
                    val wasInFailed = failedBooks.remove(state.bookId)
                    saveFailed = failedBooks.isNotEmpty()
                    if (wasInFailed) {
                        bookTransitionedToRecovered = true
                    }
                    committed.remove(state.bookId)
                    committed[state.bookId] = state.sequence
                    if (committed.size > 64) committed.remove(committed.keys.first())

                    saveWaiters.filter { it.bookId == state.bookId && it.sequence <= state.sequence }
                        .forEach { it.future.set(SaveResult.SUCCESS) }
                    saveWaiters.removeAll { it.bookId == state.bookId && it.sequence <= state.sequence }
                }

                if (bookTransitionedToRecovered) {
                    onStateChanged(state.bookId, false)
                }
            }
        }
    }

    fun checkpoint(
        bookId: String,
        trackId: String,
        position: Long,
        speed: Float,
        finished: Boolean
    ): ProgressSnapshot? {
        val state: ProgressSnapshot
        synchronized(writeLock) {
            if (closed || terminated) return null
            state = ProgressSnapshot(
                bookId = bookId,
                trackId = trackId,
                position = position.coerceAtLeast(0),
                speed = speed,
                finished = finished,
                sequence = ++sequence
            )
            pendingWrites[bookId] = state
            latest.remove(bookId)
            latest[bookId] = state
            if (latest.size > 64) latest.remove(latest.keys.first())
        }
        wakeWriter.trySend(Unit)
        return state
    }

    fun awaitSave(snapshot: ProgressSnapshot): ListenableFuture<SaveResult> {
        val future = SettableFuture.create<SaveResult>()
        synchronized(writeLock) {
            if ((committed[snapshot.bookId] ?: 0) >= snapshot.sequence) {
                future.set(SaveResult.SUCCESS)
            } else if (failedBooks.contains(snapshot.bookId)) {
                future.set(SaveResult.ERROR_IO)
            } else if (terminated) {
                future.set(SaveResult.ERROR_UNKNOWN)
            } else if (closed) {
                val isPendingOrInFlight = (inFlightBookId == snapshot.bookId && (inFlightSequence ?: 0) >= snapshot.sequence) ||
                    ((pendingWrites[snapshot.bookId]?.sequence ?: 0) >= snapshot.sequence)
                if (isPendingOrInFlight) {
                    saveWaiters.add(SaveWaiter(snapshot.bookId, snapshot.sequence, future))
                } else {
                    future.set(SaveResult.ERROR_UNKNOWN)
                }
            } else {
                saveWaiters.add(SaveWaiter(snapshot.bookId, snapshot.sequence, future))
            }
        }
        return future
    }

    fun evictBook(bookId: String) {
        var bookTransitionedFromFailed = false
        var shouldWake = false
        synchronized(writeLock) {
            if (inFlight && inFlightBookId == bookId) {
                inFlightEvicted = true
            }
            pendingWrites.remove(bookId)
            latest.remove(bookId)
            retryingBooks.remove(bookId)
            val wasInFailed = failedBooks.remove(bookId)
            saveFailed = failedBooks.isNotEmpty()
            if (wasInFailed) {
                bookTransitionedFromFailed = true
            }
            saveWaiters.filter { it.bookId == bookId }.forEach {
                it.future.set(SaveResult.ERROR_UNKNOWN)
            }
            saveWaiters.removeAll { it.bookId == bookId }
            if (unloading && pendingWrites.isEmpty() && !inFlight) {
                shouldWake = true
            }
        }
        if (shouldWake) {
            wakeWriter.trySend(Unit)
        }
        if (bookTransitionedFromFailed) {
            onStateChanged(bookId, false)
        }
    }

    fun retrySave() {
        if (!closed && !terminated) {
            synchronized(writeLock) {
                retryingBooks.addAll(failedBooks)
            }
            wakeWriter.trySend(Unit)
        }
    }

    fun unloadAndSave(): ListenableFuture<SaveResult> {
        val future = SettableFuture.create<SaveResult>()
        synchronized(writeLock) {
            if (closed || terminated) {
                future.set(SaveResult.ERROR_UNKNOWN)
                return future
            }
            latest.clear()
            retryingBooks.addAll(failedBooks)
            if (pendingWrites.isEmpty() && !inFlight) {
                val result = if (failedBooks.isNotEmpty()) SaveResult.ERROR_IO else SaveResult.SUCCESS
                future.set(result)
            } else {
                unloading = true
                drainWaiters.add(future)
            }
        }
        wakeWriter.trySend(Unit)
        return future
    }

    fun canLoadBook(currentBookId: String?): Boolean {
        synchronized(writeLock) {
            if (closed || terminated) return false
            if (unloading) return false
            if (currentBookId != null && failedBooks.contains(currentBookId)) return false
            if (pendingWrites.size >= 64) return false
            return true
        }
    }

    fun getLatest(bookId: String): ProgressSnapshot? {
        return synchronized(writeLock) { latest[bookId] }
    }

    fun getCommitted(bookId: String): Long? {
        return synchronized(writeLock) { committed[bookId] }
    }

    fun isBookFailed(bookId: String): Boolean {
        return synchronized(writeLock) { failedBooks.contains(bookId) }
    }

    fun getPendingCount(): Int {
        return synchronized(writeLock) { pendingWrites.size }
    }

    fun getWaitersCount(): Int {
        return synchronized(writeLock) { saveWaiters.size }
    }

    fun getCloseWaitersCount(): Int {
        return synchronized(writeLock) { closeWaiters.size }
    }

    fun close(): ListenableFuture<SaveResult> {
        val future = SettableFuture.create<SaveResult>()
        synchronized(writeLock) {
            if (terminated) {
                future.set(finalCloseResult ?: if (failedBooks.isNotEmpty()) SaveResult.ERROR_IO else SaveResult.SUCCESS)
                return future
            }
            if (closed) {
                closeWaiters.add(future)
                return future
            }
            closed = true
            retryingBooks.addAll(failedBooks)
            if (pendingWrites.isEmpty() && !inFlight) {
                terminated = true
                val result = if (failedBooks.isNotEmpty()) SaveResult.ERROR_IO else SaveResult.SUCCESS
                finalCloseResult = result
                future.set(result)
            } else {
                closeWaiters.add(future)
            }
        }
        wakeWriter.close()
        return future
    }
}
