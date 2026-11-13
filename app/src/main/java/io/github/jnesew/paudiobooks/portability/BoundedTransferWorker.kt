package io.github.jnesew.paudiobooks.portability

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class TransferIoException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { BUSY, TIMEOUT, UNAVAILABLE }
}

/** One process-owned admission until the worker AND all abandoned cleanup actually return. */
internal class BoundedTransferWorker(private val timeoutMs: Long = 30_000) {
    init { require(timeoutMs > 0) }
    private val active = AtomicBoolean(false)
    private fun executor(name: String) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), { task -> Thread(task, name).apply { isDaemon = true } })
    private val worker = executor("transfer-document")
    private val closer = executor("transfer-document-close")
    private val canceller = executor("transfer-document-cancel")
    internal val busy get() = active.get()

    internal class Resources {
        private class Owned(private val resource: Closeable) {
            private val claimed = AtomicBoolean(false)
            fun close() { if (claimed.compareAndSet(false, true)) resource.close() }
        }
        private val resources = mutableListOf<Owned>()
        private var abandoned = false
        @Synchronized fun <T : Closeable> own(resource: T): T {
            resources.add(Owned(resource))
            checkActive()
            return resource
        }
        @Synchronized fun checkActive() { if (abandoned) throw IOException("Transfer cancelled") }
        @Synchronized fun abandon() { abandoned = true }
        fun close(): Boolean {
            val snapshot = synchronized(this) { resources.asReversed().toList() }
            var success = true
            snapshot.forEach { try { it.close() } catch (_: Exception) { success = false } }
            return success
        }
    }

    suspend fun <T : Any> run(onCancel: () -> Unit = {}, work: (Resources) -> T): T {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                if (!active.compareAndSet(false, true)) {
                    continuation.resumeWithException(TransferIoException(TransferIoException.Reason.BUSY))
                    return@suspendCancellableCoroutine
                }
                val resources = Resources()
                // The worker starts with one reference. Cancellation reserves both cleanup
                // references before returning, so late open/close work cannot reopen admission.
                val completionGate = Any()
                var finished = false
                val references = AtomicInteger(1)
                fun release() { if (references.decrementAndGet() == 0) active.set(false) }
                continuation.invokeOnCancellation {
                    val cleanup = synchronized(completionGate) {
                        if (finished) false else { references.addAndGet(2); resources.abandon(); true }
                    }
                    if (!cleanup) return@invokeOnCancellation
                    canceller.execute { try { onCancel() } catch (_: Exception) { } finally { release() } }
                    closer.execute { try { resources.close() } finally { release() } }
                }
                worker.execute {
                    var value: T? = null
                    var failure: Throwable? = null
                    try {
                        resources.checkActive()
                        value = work(resources)
                        resources.checkActive()
                    } catch (error: Throwable) { failure = error }
                    finally {
                        try {
                            if (!resources.close() && failure == null)
                                failure = TransferIoException(TransferIoException.Reason.UNAVAILABLE)
                        } finally {
                            synchronized(completionGate) { finished = true }
                            // A resumed caller may immediately start another operation.
                            // All owned work is finished; release its reference first.
                            release()
                            if (continuation.isActive) {
                                if (failure == null) continuation.resume(requireNotNull(value))
                                else continuation.resumeWithException(if (failure is Error) failure!!
                                    else TransferIoException(TransferIoException.Reason.UNAVAILABLE))
                            }
                        }
                    }
                }
            }
        } ?: throw TransferIoException(TransferIoException.Reason.TIMEOUT)
    }
}
