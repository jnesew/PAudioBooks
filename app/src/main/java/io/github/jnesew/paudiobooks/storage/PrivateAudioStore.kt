package io.github.jnesew.paudiobooks.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.content.res.AssetFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CopiedAudio(val assetId: String, val sizeBytes: Long, val sha256: String)

/** Only generated UUIDs are accepted as names. Never interpret a provider URI as a path. */
class PrivateAudioStore(
    context: Context,
    private val copyTimeoutMs: Long = COPY_TIMEOUT_MS,
    internal val destinationStreamFactory: (File) -> FileOutputStream = { FileOutputStream(it) }
) {
    companion object {
        private const val COPY_TIMEOUT_MS = 30 * 60 * 1000L
        private val activeOperations = AtomicInteger(0)
        private val activeClosers = AtomicInteger(0)
        private val workers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "private-audio-copy").apply { isDaemon = true } })
        // Closing a broken provider's stream can itself block. Never block cancellation on it.
        private val closers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "private-audio-close").apply { isDaemon = true } })

        internal fun isReaderActive(): Boolean = activeOperations.get() > 0 || !workers.queue.isEmpty()
        internal fun isCloserActive(): Boolean = activeClosers.get() > 0 || !closers.queue.isEmpty()

        internal suspend fun awaitReaderIdle(timeoutMs: Long = 5000L): Boolean {
            require(timeoutMs > 0) { "timeoutMs must be positive" }
            val start = SystemClock.elapsedRealtime()
            while (isReaderActive() || isCloserActive()) {
                if (SystemClock.elapsedRealtime() - start >= timeoutMs) {
                    return !isReaderActive() && !isCloserActive()
                }
                delay(20)
            }
            return true
        }
    }
    private class CloseOnce(private val resource: Closeable) {
        private val claimed = AtomicBoolean(false)
        fun close() {
            if (claimed.compareAndSet(false, true)) {
                try { resource.close() } catch (_: Exception) { /* Continue releasing the other resources. */ }
            }
        }
    }

    private val root = File(context.noBackupFilesDir, "audiobooks")
    private val staging = File(root, "staging")
    private val media = File(root, "media")
    private val covers = File(root, "covers")

    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    private fun file(directory: File, id: String): File {
        require(validId(id))
        return File(directory, id)
    }
    fun stagedFile(id: String): File = file(staging, id)
    fun relativePath(id: String): String { require(validId(id)); return "media/$id" }
    suspend fun resolve(relativePath: String): File? = withContext(Dispatchers.IO) {
        val id = relativePath.removePrefix("media/")
        if (relativePath != "media/$id" || !validId(id)) return@withContext null
        val candidate = file(media, id)
        val expected = File(media.canonicalFile, id)
        if (candidate.canonicalFile != expected || !candidate.isFile) null else candidate
    }

    /** One bounded worker prevents a broken provider from creating unbounded stuck threads. */
    suspend fun copy(resolver: ContentResolver, uri: Uri, assetId: String, expectedSize: Long? = null,
                     maxBytes: Long = Long.MAX_VALUE, timeoutMs: Long = copyTimeoutMs,
                     onProgress: (Long) -> Unit = {}): CopiedAudio {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        require(expectedSize == null || expectedSize >= 0)
        require(maxBytes >= 0)
        require(timeoutMs > 0)
        if (expectedSize != null && expectedSize > maxBytes) throw IOException("Selected file is too large")
        val dest = stagedFile(assetId)
        val created = AtomicBoolean(false)
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    // Admit one operation until both its worker and cancellation cleanup return.
                    // A stuck provider must not accumulate queued copies or extra threads.
                    if (isCloserActive() || !activeOperations.compareAndSet(0, 1)) {
                        continuation.resumeWithException(SourceReaderBusyException())
                        return@suspendCancellableCoroutine
                    }
                    val signal = CancellationSignal()
                    val gate = Any()
                    var cancelled = false
                    val resources = mutableListOf<CloseOnce>()
                    val released = AtomicBoolean(false)
                    fun releaseOperation() {
                        if (released.compareAndSet(false, true)) activeOperations.decrementAndGet()
                    }
                    fun checkCancelled() {
                        synchronized(gate) { if (cancelled) throw IOException("Copy cancelled") }
                    }
                    fun <T : Closeable> own(resource: T): T {
                        synchronized(gate) { resources.add(CloseOnce(resource)) }
                        checkCancelled()
                        return resource
                    }
                    fun snapshotResources(): List<CloseOnce> = synchronized(gate) { resources.toList() }

                    val task = Runnable {
                        var result: CopiedAudio? = null
                        var failure: Exception? = null
                        try {
                            checkCancelled()
                            if (!staging.exists() && !staging.mkdirs()) throw IOException("Cannot create staging directory")
                            if (expectedSize != null && expectedSize > staging.usableSpace) throw IOException("Not enough space for selected file")
                            // Only local bookkeeping uses the gate. Provider open/stream construction,
                            // destination construction and every close can block and stay outside it.
                            if (!dest.createNewFile()) throw IOException("Staging file already exists")
                            created.set(true)
                            checkCancelled()
                            val descriptor = own(resolver.openAssetFileDescriptor(uri, "r", signal)
                                ?: throw IOException("Cannot open selected file"))
                            val input = own(descriptor.createInputStream())
                            val output = own(destinationStreamFactory(dest))
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (total > maxBytes - count) throw IOException("File too large")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                total += count
                                onProgress(total)
                            }
                            output.fd.sync()
                            result = CopiedAudio(assetId, total, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                        } catch (caught: Exception) {
                            failure = caught
                        } finally {
                            try {
                                snapshotResources().asReversed().forEach { it.close() }
                                // A blocked constructor can create the file after caller-side cleanup.
                                if (synchronized(gate) { cancelled } && created.get()) dest.delete()
                            } finally {
                                releaseOperation()
                            }
                        }
                        if (continuation.isActive) {
                            val error = failure
                            if (error != null) continuation.resumeWithException(error) else continuation.resume(requireNotNull(result))
                        }
                    }
                    continuation.invokeOnCancellation {
                        // Register cleanup before releasing a queued operation's token, so admission
                        // cannot mistake a still-running cancellation callback for an idle reader.
                        activeClosers.incrementAndGet()
                        val toClose = synchronized(gate) {
                            cancelled = true
                            resources.toList()
                        }
                        if (workers.remove(task)) releaseOperation()
                        try {
                            closers.execute {
                                try {
                                    try { signal.cancel() } finally { toClose.asReversed().forEach { it.close() } }
                                } finally {
                                    activeClosers.decrementAndGet()
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            activeClosers.decrementAndGet() // The worker still owns its resources.
                        }
                    }
                    try {
                        workers.execute(task)
                        // Cancellation can run before execute, when remove cannot yet find the task.
                        val shouldEvict = synchronized(gate) { cancelled }
                        if (shouldEvict && workers.remove(task)) releaseOperation()
                    } catch (_: RejectedExecutionException) {
                        releaseOperation()
                        if (continuation.isActive) continuation.resumeWithException(SourceReaderBusyException())
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { if (created.get()) dest.delete() }
            throw SourceReadTimeoutException()
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { if (created.get()) dest.delete() }
            throw failure
        }
    }
    suspend fun removeStaged(id: String): Unit = withContext(Dispatchers.IO) {
        val source = stagedFile(id)
        if (source.exists() && !source.delete()) throw IOException("Cannot remove staged file")
    }
    suspend fun cleanOrphanedStaging(excludeIds: Set<String> = emptySet()): Unit = withContext(Dispatchers.IO) {
        if (!staging.exists()) return@withContext
        val orphans = staging.listFiles() ?: return@withContext
        for (file in orphans) {
            if (validId(file.name) && file.name !in excludeIds) {
                if (file.exists() && !file.delete()) {
                    throw IOException("Cannot remove orphaned staging file ${file.name}")
                }
            }
        }
    }
    suspend fun commit(id: String): Unit = withContext(Dispatchers.IO) {
        if (!media.exists() && !media.mkdirs()) throw IOException("Cannot create media directory")
        val source = stagedFile(id)
        val target = file(media, id)
        if (target.exists() || !source.renameTo(target)) throw IOException("Cannot commit staged file")
    }
    /** Fingerprints in Room alone cannot establish that the current owned bytes are healthy. */
    suspend fun matches(relativePath: String, size: Long, sha256: String): Boolean = withContext(Dispatchers.IO) {
        val source = resolve(relativePath) ?: return@withContext false
        if (size < 0 || source.length() != size) return@withContext false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > size - total) return@withContext false
                    digest.update(buffer, 0, count)
                    total += count
                }
                total == size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == sha256
            }
        } catch (_: IOException) { false }
    }

    /** Clone into a separately journaled UUID so one selected file can repair shared fingerprints. */
    suspend fun cloneStaged(sourceId: String, destinationId: String): Unit = withContext(Dispatchers.IO) {
        val target = stagedFile(destinationId)
        if (!target.createNewFile()) throw IOException("Staging file already exists")
        try {
            destinationStreamFactory(target).use { output ->
                stagedFile(sourceId).inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    /** Same-filesystem rename replaces the private inode; book/track/asset identities stay intact. */
    suspend fun restore(stagedId: String, assetId: String): Unit = withContext(Dispatchers.IO) {
        if (!media.exists() && !media.mkdirs()) throw IOException("Cannot create media directory")
        val target = file(media, assetId)
        if (target.canonicalFile != File(media.canonicalFile, assetId)) throw IOException("Invalid owned asset")
        if (!stagedFile(stagedId).renameTo(target)) throw IOException("Cannot restore private audio")
    }
    suspend fun writeCover(id: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024)
        if (!covers.exists() && !covers.mkdirs()) throw IOException("Cannot create cover directory")
        val dest = file(covers, id)
        if (!dest.createNewFile()) throw IOException("Cover already exists")
        try { destinationStreamFactory(dest).use { it.write(bytes); it.fd.sync() } }
        catch (failure: Throwable) { dest.delete(); throw failure }
        "covers/$id"
    }
    suspend fun resolveCover(relativePath: String): File? = withContext(Dispatchers.IO) {
        val id = relativePath.removePrefix("covers/")
        if (relativePath != "covers/$id" || !validId(id)) return@withContext null
        val candidate = file(covers, id)
        if (candidate.canonicalFile != File(covers.canonicalFile, id) || !candidate.isFile) null else candidate
    }
    suspend fun discard(id: String) = withContext(Dispatchers.IO) {
        for (candidate in listOf(stagedFile(id), file(media, id), file(covers, id))) {
            if (candidate.exists() && !candidate.delete()) throw IOException("Cannot remove private file")
        }
    }
}

class SourceReadTimeoutException : IOException("The selected source did not respond in time")
class SourceReaderBusyException : IOException("A previous source has not stopped responding")
