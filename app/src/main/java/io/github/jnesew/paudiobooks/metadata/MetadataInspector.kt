package io.github.jnesew.paudiobooks.metadata

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The caller owns the staged file and must keep it unchanged until inspection completes. */
@UnstableApi
class MetadataInspector(context: Context) {
    private val app = context.applicationContext
    suspend fun inspect(file: File): InspectedAudio = inspectionMutex.withLock {
        withTimeoutOrNull(InspectionLimits.DEADLINE_MS + 5_000) {
            withContext(Dispatchers.IO) {
                if (!file.isFile) throw InspectionException(InspectionException.Reason.UNAVAILABLE)
                val descriptor = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
                catch (_: IOException) { throw InspectionException(InspectionException.Reason.UNAVAILABLE) }
                catch (_: SecurityException) { throw InspectionException(InspectionException.Reason.UNAVAILABLE) }
                descriptor.use { decode(inspectDescriptor(it, MODE_AUDIO)) }
            }
        } ?: throw InspectionException(InspectionException.Reason.TIMEOUT)
    }
    /** Caller retains the read-only range/grant lease until this disposable process is stopped. */
    internal suspend fun inspectLinked(source: android.content.res.AssetFileDescriptor, byteBudget: Long, onProgress: (Long) -> Unit = {}): LinkedInspection = inspectionMutex.withLock {
        require(byteBudget in 1..InspectionLimits.MAX_SOURCE_BYTES)
        require(source.startOffset >= 0 && source.declaredLength >= -1)
        withTimeoutOrNull(InspectionLimits.DEADLINE_MS + 5_000) {
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.dup(source.fileDescriptor).use { fd ->
                    val result = inspectDescriptor(fd, MODE_AUDIO, source.startOffset, source.declaredLength, byteBudget, onProgress)
                    LinkedInspection(if (result.getBoolean(BUDGET_EXHAUSTED)) null else decode(result), result.getLong(SOURCE_BYTES))
                }
            }
        } ?: throw InspectionException(InspectionException.Reason.TIMEOUT)
    }
    /** Image parsing shares the disposable isolated process; only a bounded JPEG returns. */
    suspend fun inspectArtwork(file: File): ByteArray = inspectionMutex.withLock {
        withTimeoutOrNull(InspectionLimits.DEADLINE_MS + 5_000) {
            withContext(Dispatchers.IO) {
                if (!file.isFile || file.length() !in 1..InspectionLimits.ART_BYTES.toLong())
                    throw InspectionException(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT)
                val descriptor = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
                catch (_: IOException) { throw InspectionException(InspectionException.Reason.UNAVAILABLE) }
                catch (_: SecurityException) { throw InspectionException(InspectionException.Reason.UNAVAILABLE) }
                descriptor.use {
                    val bytes = inspectDescriptor(it, MODE_ARTWORK).getByteArray(ART)
                    if (bytes == null || bytes.isEmpty() || bytes.size > InspectionLimits.THUMB_BYTES)
                        throw InspectionException(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT)
                    bytes
                }
            }
        } ?: throw InspectionException(InspectionException.Reason.TIMEOUT)
    }
    private suspend fun inspectDescriptor(fd: ParcelFileDescriptor, mode: Int, base: Long = 0, length: Long = -1, byteBudget: Long = Long.MAX_VALUE, onProgress: (Long) -> Unit = {}): android.os.Bundle {
        var remote: Messenger? = null
        var connection: ServiceConnection? = null
        var bound = false
        val death = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
        var reportedBytes = 0L
        fun report(data: android.os.Bundle) {
            val bytes = data.getLong(SOURCE_BYTES)
            if (bytes > reportedBytes && bytes <= byteBudget) { reportedBytes = bytes; onProgress(bytes) }
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (continuation.isActive) {
                try {
                    when (message.what) {
                        PROGRESS -> report(message.data)
                        RESULT -> { report(message.data); continuation.resume(message.data) }
                        FAILED -> {
                            report(message.data)
                            if (message.data.getBoolean(BUDGET_EXHAUSTED)) continuation.resume(message.data)
                            else continuation.resumeWithException(InspectionException(InspectionException.Reason.UNSUPPORTED_OR_CORRUPT, message.data.getLong(SOURCE_BYTES)))
                        }
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!continuation.isActive) return
                remote = Messenger(binder)
                try {
                    binder.linkToDeath({ death.complete(Unit) }, 0)
                    remote?.send(Message.obtain(null, REQUEST).apply {
                        replyTo = reply
                        data.putParcelable(FD, fd)
                        data.putInt(MODE, mode)
                        data.putLong(SOURCE_BASE, base); data.putLong(SOURCE_LENGTH, length); data.putLong(SOURCE_BUDGET, byteBudget)
                    }) }
                catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(InspectionException(InspectionException.Reason.PROCESS_DIED)) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (continuation.isActive) continuation.resumeWithException(InspectionException(InspectionException.Reason.PROCESS_DIED))
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        bound = try { app.bindService(Intent(app, InspectionService::class.java), connection!!, Context.BIND_AUTO_CREATE) }
            catch (_: SecurityException) { false }
        if (!bound) continuation.resumeWithException(InspectionException(InspectionException.Reason.UNAVAILABLE))
            }
        } finally {
            if (remote != null) {
                try { remote?.send(Message.obtain(null, CANCEL)) } catch (_: Exception) { death.complete(Unit) }
                val stopped = withContext(NonCancellable) { withTimeoutOrNull(2_000) { death.await(); true } } ?: false
                if (bound) app.unbindService(connection!!)
                if (!stopped) throw InspectionException(InspectionException.Reason.PROCESS_DIED)
            } else if (bound) app.unbindService(connection!!)
        }
    }
    companion object { private val inspectionMutex = Mutex() }
}

private fun decode(bundle: android.os.Bundle): InspectedAudio {
    val starts = requireNotNull(bundle.getLongArray(STARTS))
    val ends = requireNotNull(bundle.getLongArray(ENDS))
    val titles = requireNotNull(bundle.getStringArrayList(TITLES))
    require(starts.size <= InspectionLimits.CHAPTERS && starts.size == ends.size && starts.size == titles.size)
    require(titles.sumOf { it.length } <= InspectionLimits.CHAPTER_TEXT_CHARS)
    val duration = bundle.getLong(DURATION, -1L).takeIf { it >= 0 }
    val chapters = normalizeChapters(starts.indices.map { RawChapter(starts[it], ends[it].takeIf { n -> n >= 0 }, titles[it], false) }, duration)
    val art = bundle.getByteArray(ART)?.takeIf { it.size <= InspectionLimits.THUMB_BYTES }
    val fields = requireNotNull(bundle.getStringArrayList(BOOK_FIELDS))
    require(fields.size == 15 && fields.all { it.length <= InspectionLimits.FIELD_CHARS })
    fun field(i: Int) = normalizeField(fields[i])
    val book = BookMetadata(field(0), field(1), field(2), field(3), field(4), field(5), field(6),
        field(7), field(8), field(9), field(10), field(11), field(12), field(13), field(14))
    return InspectedAudio(normalizeField(bundle.getString(TITLE)), book, duration, chapters, art,
        discNumber = bundle.getInt(DISC).takeIf { it in 1..100_000 },
        trackNumber = bundle.getInt(TRACK).takeIf { it in 1..100_000 },
        mimeType = normalizeField(bundle.getString(AUDIO_MIME)))
}
