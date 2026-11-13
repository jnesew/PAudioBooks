package io.github.jnesew.paudiobooks.metadata

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.inspector.MetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One disposable job per isolated process. The watchdog also covers blocked native work. */
@UnstableApi
class InspectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var started = false
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CANCEL) { Process.killProcess(Process.myPid()); return@Handler true }
        if (message.what != REQUEST || started || message.replyTo == null) return@Handler true
        started = true
        @Suppress("DEPRECATION")
        val descriptor = message.data.getParcelable<ParcelFileDescriptor>(FD) ?: return@Handler true
        val reply = message.replyTo
        val mode = message.data.getInt(MODE, MODE_AUDIO)
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, InspectionLimits.DEADLINE_MS)
        // Read-driven updates only: at most five small IPC messages per second,
        // plus the first read. No timer/polling service or source names cross IPC.
        var lastProgressAt = Long.MIN_VALUE
        val budget = DescriptorReadBudget(message.data.getLong(SOURCE_BUDGET, Long.MAX_VALUE)) { bytes ->
            val now = SystemClock.elapsedRealtime()
            if (lastProgressAt == Long.MIN_VALUE || now - lastProgressAt >= 200) {
                lastProgressAt = now
                try { reply.send(Message.obtain(null, PROGRESS).apply { data = Bundle().apply { putLong(SOURCE_BYTES, bytes) } }) }
                catch (_: android.os.RemoteException) { }
            }
        }
        val base = message.data.getLong(SOURCE_BASE, 0)
        val length = message.data.getLong(SOURCE_LENGTH, -1)
        worker.execute {
            try {
                descriptor.use { fd ->
                    val result = when (mode) {
                        MODE_AUDIO -> inspectDescriptor(fd, base, length, budget)
                        MODE_ARTWORK -> inspectArtwork(fd)
                        else -> throw IllegalArgumentException("Unknown inspection mode")
                    }
                    result.putLong(SOURCE_BYTES, budget.bytesRead)
                    reply.send(Message.obtain(null, RESULT).apply { data = result })
                }
            } catch (_: Exception) {
                try { reply.send(Message.obtain(null, FAILED).apply { data = Bundle().apply {
                    putLong(SOURCE_BYTES, budget.bytesRead); putBoolean(BUDGET_EXHAUSTED, budget.exhausted)
                } }) } catch (_: Exception) { }
            } finally {
                // Do not reuse an instance that parsed untrusted input.
                handler.postDelayed({ Process.killProcess(Process.myPid()) }, 1000)
            }
        }
        true
    })
    override fun onBind(intent: Intent?) = incoming.binder
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun inspectArtwork(fd: ParcelFileDescriptor): Bundle {
        val stat = Os.fstat(fd.fileDescriptor)
        require(android.system.OsConstants.S_ISREG(stat.st_mode))
        require(stat.st_size in 1..InspectionLimits.ART_BYTES.toLong())
        val encoded = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() <= InspectionLimits.ART_BYTES - count)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val image = thumbnail(encoded) ?: throw IllegalArgumentException("Unsupported artwork")
        return Bundle().apply { putByteArray(ART, image) }
    }

    private fun inspectDescriptor(fd: ParcelFileDescriptor, base: Long, length: Long, budget: DescriptorReadBudget): Bundle {
        val factory = DataSource.Factory { DescriptorDataSource(fd, base, length, budget) }
        val extractors = DefaultExtractorsFactory().setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA)
        val source = DefaultMediaSourceFactory(factory, extractors)
        val item = MediaItem.fromUri(Uri.parse("local-inspection:///asset"))
        val metadataBuilder = MediaMetadata.Builder()
        val rawChapters = ArrayList<RawChapter>()
        var duration: Long? = null
        var hasAudio = false
        var mime: String? = null
        val credits = EmbeddedMetadataCollector()
        MetadataRetriever.Builder(this, item).setMediaSourceFactory(source).build().use { retriever ->
            val durationUs = retriever.retrieveDurationUs().get(25, TimeUnit.SECONDS)
            if (durationUs != C.TIME_UNSET && durationUs >= 0) duration = durationUs / 1000
            val tracks = retriever.retrieveTrackGroups().get(25, TimeUnit.SECONDS)
            for (i in 0 until tracks.length) {
                val group = tracks.get(i)
                for (j in 0 until group.length) {
                    val format = group.getFormat(j)
                    if (MimeTypes.isAudio(format.sampleMimeType)) { hasAudio = true; if (mime == null) mime = format.sampleMimeType }
                    val metadata = format.metadata ?: continue
                    for (k in 0 until metadata.length()) {
                        val entry = metadata.get(k)
                        entry.populateMediaMetadata(metadataBuilder)
                        credits.accept(entry)
                        if (entry is Chapter && rawChapters.size < InspectionLimits.CHAPTERS) {
                            rawChapters.add(RawChapter(entry.startTimeMs, entry.endTimeMs.takeUnless { it == C.TIME_UNSET }, entry.title?.value, entry.isHidden))
                        }
                    }
                }
            }
        }
        require(hasAudio)
        val metadata = metadataBuilder.build()
        val bookFields = credits.build().let { it.copy(description = plainDescription(it.description)) }
        val chapters = normalizeChapters(rawChapters, duration)
        return Bundle().apply {
            putString(TITLE, normalizeField(metadata.title))
            putStringArrayList(BOOK_FIELDS, ArrayList(bookFields.fields().map { it.orEmpty() }))
            putString(AUDIO_MIME, mime)
            putLong(DURATION, duration ?: -1L)
            putInt(DISC, metadata.discNumber?.takeIf { it in 1..100_000 } ?: 0)
            putInt(TRACK, metadata.trackNumber?.takeIf { it in 1..100_000 } ?: 0)
            putLongArray(STARTS, chapters.map { it.startMs }.toLongArray())
            putLongArray(ENDS, chapters.map { it.endMs ?: -1L }.toLongArray())
            putStringArrayList(TITLES, ArrayList(chapters.map { it.title ?: "" }))
            putByteArray(ART, thumbnail(metadata.artworkData))
        }
    }
}

private val descriptionMarkup = Regex("</?[A-Za-z][^>]*>|<!--|&(?:#[0-9]+|#x[0-9a-fA-F]+|[A-Za-z]+);")

/** Strip markup inside the disposable process, with no image loader, spans or HTML stored in Room. */
private fun plainDescription(text: String?): String? {
    val bounded = normalizeField(text) ?: return null
    if (!descriptionMarkup.containsMatchIn(bounded)) return bounded
    return normalizeField(android.text.Html.fromHtml(bounded, android.text.Html.FROM_HTML_MODE_LEGACY)
        .toString().replace("\uFFFC", ""))
}

private fun thumbnail(bytes: ByteArray?): ByteArray? {
    if (bytes == null || bytes.size > InspectionLimits.ART_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth; val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > InspectionLimits.ART_PIXELS) return null
    var sample = 1
    while (width / sample > InspectionLimits.THUMB_SIZE || height / sample > InspectionLimits.THUMB_SIZE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return try {
        ByteArrayOutputStream().use { output ->
            var result: ByteArray? = null
            for (quality in intArrayOf(75, 55, 35)) {
                output.reset()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) break
                if (output.size() <= InspectionLimits.THUMB_BYTES) {
                    result = output.toByteArray()
                    break
                }
            }
            result
        }
    } finally { bitmap.recycle() }
}

internal const val CANCEL = 4
internal const val PROGRESS = 5
internal const val REQUEST = 1
internal const val RESULT = 2
internal const val FAILED = 3
internal const val FD = "fd"
internal const val TITLE = "title"
internal const val BOOK_FIELDS = "book_fields"
internal const val DURATION = "duration"
internal const val STARTS = "starts"
internal const val ENDS = "ends"
internal const val TITLES = "titles"
internal const val ART = "art"
internal const val DISC = "disc"
internal const val TRACK = "track"
internal const val MODE = "mode"
internal const val MODE_AUDIO = 1
internal const val MODE_ARTWORK = 2


internal const val AUDIO_MIME = "audio_mime"

internal const val SOURCE_BASE = "source_base"
internal const val SOURCE_LENGTH = "source_length"
internal const val SOURCE_BUDGET = "source_budget"
internal const val SOURCE_BYTES = "source_bytes"
internal const val BUDGET_EXHAUSTED = "budget_exhausted"
