package io.github.jnesew.paudiobooks.storage

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/** Opaque failures: provider exception messages/causes can contain private source URIs. */
internal class DocumentSourceException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { UNAVAILABLE, CHANGED, NON_SEEKABLE, BUSY, TIMEOUT }
}

/** Blocking API for IO/Media3 loader threads. Every open transfers its lease, even on failure. */
internal class BoundedDocumentAccess(private val resolver: ContentResolver, private val timeoutMs: Long = 5_000,
    private val closeDescriptor: (AssetFileDescriptor) -> Unit = { it.close() }) {
    init { require(timeoutMs > 0) }
    companion object {
        private const val MAX_HANDLES = 4
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
            { task -> Thread(task, "document-source-read").apply { isDaemon = true } })
        // At most four tickets, each with one cancellation and one descriptor close.
        // A ticket survives both tasks, so a stuck closer cannot accumulate new opens.
        private val closer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
            { task -> Thread(task, "document-source-close").apply { isDaemon = true } })
        private val tickets = AtomicInteger(0)
        private val operations = AtomicInteger(0)
        private val stalled = AtomicInteger(0)
        private val closes = AtomicInteger(0)
        internal val sourceBytesRead = AtomicLong(0)
        internal val sourceOpens = AtomicLong(0)
        internal suspend fun awaitIdle(timeoutMs: Long = 5_000): Boolean {
            val start = SystemClock.elapsedRealtime()
            while (tickets.get() != 0 || operations.get() != 0 || closes.get() != 0) {
                if (SystemClock.elapsedRealtime() - start >= timeoutMs) return false
                delay(10)
            }
            return true
        }
        private fun closeLater(close: () -> Unit) {
            closes.incrementAndGet()
            closer.execute { try { close() } catch (_: Exception) { } finally { closes.decrementAndGet() } }
        }
    }
    internal class Ticket(private val lease: AutoCloseable) {
        private val references = AtomicInteger(1)
        fun retain() { check(references.incrementAndGet() > 1) }
        fun release() {
            if (references.decrementAndGet() == 0) {
                try { lease.close() } catch (_: Exception) { } finally { tickets.decrementAndGet() }
            }
        }
        fun cancel(signal: CancellationSignal) {
            retain()
            closeLater { try { signal.cancel() } finally { release() } }
        }
    }
    data class Chunk(val bytes: ByteArray, val count: Int)

    fun open(uri: Uri, lease: AutoCloseable, expectedSize: Long? = null, expectedModified: Long? = null): Handle {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank() ||
            expectedSize?.let { it < 0 } == true || expectedModified?.let { it < 0 } == true) {
            lease.close()
            throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
        }
        if (tickets.incrementAndGet() > MAX_HANDLES) {
            tickets.decrementAndGet(); lease.close()
            throw DocumentSourceException(DocumentSourceException.Reason.BUSY)
        }
        val ticket = Ticket(lease)
        val signal = CancellationSignal()
        return operation(onNotStarted = { ticket.release() }, onFinished = { ticket.release() },
            onRunningAbandoned = { ticket.cancel(signal) }, dispose = { it.close() }) {
            var descriptor: AssetFileDescriptor? = null
            try {
                val hints = hints(uri, signal)
                if (expectedSize != null && hints.first != null && expectedSize != hints.first ||
                    expectedModified != null && hints.second != null && expectedModified != hints.second)
                    throw DocumentSourceException(DocumentSourceException.Reason.CHANGED)
                descriptor = resolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
                sourceOpens.incrementAndGet()
                val stat = Os.fstat(descriptor.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) throw DocumentSourceException(DocumentSourceException.Reason.NON_SEEKABLE)
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
                val base = descriptor.startOffset
                val declared = descriptor.declaredLength
                if (base < 0 || declared < -1 || stat.st_size > 0 && stat.st_size < base)
                    throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
                val length = if (declared >= 0) declared else (stat.st_size - base).takeIf { stat.st_size > 0 }
                if (length != null && (length > Long.MAX_VALUE - base || stat.st_size > 0 && length > stat.st_size - base))
                    throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
                if (expectedSize != null && length != null && expectedSize != length)
                    throw DocumentSourceException(DocumentSourceException.Reason.CHANGED)
                ticket.retain()
                Handle(descriptor, base, length, hints.second, ticket)
            } catch (e: Throwable) {
                descriptor?.let { opened ->
                    ticket.retain()
                    closeLater { try { closeDescriptor(opened) } finally { ticket.release() } }
                }
                throw e
            }
        }
    }

    inner class Handle internal constructor(val descriptor: AssetFileDescriptor, val start: Long,
        val length: Long?, val modified: Long?, private val ticket: Ticket) : AutoCloseable {
        private val closing = AtomicBoolean(false)
        private val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun read(position: Long, requested: Int): Chunk {
            require(position >= 0 && requested >= 0)
            if (closing.get()) throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
            if (requested == 0) return Chunk(ByteArray(0), 0)
            if (length != null && position >= length) return Chunk(ByteArray(0), -1)
            if (position > Long.MAX_VALUE - start) throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
            val amount = minOf(requested.toLong(), 64 * 1024L, length?.minus(position) ?: Long.MAX_VALUE).toInt()
            // Worker-owned buffer: abandoned reads cannot mutate Media3's reused caller buffer.
            return operation {
                val bytes = ByteArray(amount)
                val count = Os.pread(descriptor.fileDescriptor, bytes, 0, amount, start + position)
                if (count > 0) sourceBytesRead.addAndGet(count.toLong())
                Chunk(bytes, if (count <= 0) -1 else count)
            }
        }
        override fun close() {
            if (closing.compareAndSet(false, true)) closeLater {
                try { closeDescriptor(descriptor) } finally { try { ticket.release() } finally { closed.complete(Unit) } }
            }
        }
        suspend fun awaitClosed() {
            if (kotlinx.coroutines.withTimeoutOrNull(5_000) { closed.await(); true } != true)
                throw DocumentSourceException(DocumentSourceException.Reason.TIMEOUT)
        }
    }

    private fun hints(uri: Uri, signal: CancellationSignal): Pair<Long?, Long?> {
        fun query(columns: Array<String>): Pair<Long?, Long?> = resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
            fun long(name: String): Long? {
                val index = cursor.getColumnIndex(name)
                return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index).takeIf { it >= 0 }
            }
            long(OpenableColumns.SIZE) to long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }
        } ?: throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
        return try { query(arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)) }
        catch (_: IllegalArgumentException) { query(arrayOf(OpenableColumns.SIZE)) }
    }

    /** Running timeouts stay busy until the actual worker exits; queued timeouts clean once. */
    private fun <T : Any> operation(onNotStarted: () -> Unit = {}, onFinished: () -> Unit = {},
        onRunningAbandoned: () -> Unit = {}, dispose: (T) -> Unit = {}, work: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (stalled.get() > 0) { onNotStarted(); throw DocumentSourceException(DocumentSourceException.Reason.BUSY) }
        val gate = Any()
        val ready = CompletableFuture<Unit>()
        var abandoned = false
        var started = false
        var done = false
        var result: T? = null
        var failure: Throwable? = null
        var countedStall = false
        val skipped = AtomicBoolean(false)
        fun skipOnce() { if (skipped.compareAndSet(false, true)) { try { onNotStarted() } finally { operations.decrementAndGet() } } }
        val task = Runnable {
            val run = synchronized(gate) { if (abandoned) false else { started = true; true } }
            if (!run) { skipOnce(); return@Runnable }
            var value: T? = null
            try {
                try { value = work() } catch (e: Throwable) { synchronized(gate) { failure = e } }
                val discard = synchronized(gate) {
                    done = true
                    if (countedStall) { countedStall = false; stalled.decrementAndGet() }
                    if (abandoned) true else { result = value; ready.complete(Unit); false }
                }
                if (discard && value != null) dispose(value)
            } finally { try { onFinished() } finally { operations.decrementAndGet() } }
        }
        operations.incrementAndGet()
        try { worker.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) {
            skipOnce(); throw DocumentSourceException(DocumentSourceException.Reason.BUSY)
        }
        try {
            ready.get(timeoutMs, TimeUnit.MILLISECONDS)
            return synchronized(gate) {
                failure?.let { throw it }
                requireNotNull(result).also { result = null }
            }
        } catch (e: Throwable) {
            val late = synchronized(gate) {
                abandoned = true
                if (!started && worker.remove(task)) skipOnce()
                else if (started && !done) {
                    countedStall = true; stalled.incrementAndGet()
                    onRunningAbandoned()
                }
                result.also { result = null }
            }
            late?.let(dispose)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw when (e) {
                is DocumentSourceException -> e
                is TimeoutException, is InterruptedException -> DocumentSourceException(DocumentSourceException.Reason.TIMEOUT)
                else -> DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
            }
        }
    }
}
