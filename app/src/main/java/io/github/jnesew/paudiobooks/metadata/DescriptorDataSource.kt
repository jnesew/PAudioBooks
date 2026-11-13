package io.github.jnesew.paudiobooks.metadata

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Shared by every extractor reopen/seek in one isolated request. */
internal class DescriptorReadBudget(private val limit: Long, private val onRead: (Long) -> Unit = {}) {
    init { require(limit > 0) }
    @Volatile var bytesRead = 0L; private set
    @Volatile var exhausted = false; private set
    @Synchronized fun read(requested: Int, action: (Int) -> Int): Int {
        if (bytesRead >= limit) { exhausted = true; throw IOException("Inspection byte budget") }
        val count = action(minOf(requested.toLong(), limit - bytesRead).toInt())
        if (count > 0) { bytesRead += count; onRead(bytesRead) }
        return count
    }
}

/** Reads only the passed descriptor range. Synthetic URIs are never dereferenced. */
@UnstableApi
internal class DescriptorDataSource(private val original: ParcelFileDescriptor, private val base: Long = 0,
    private val declaredLength: Long = -1, private val budget: DescriptorReadBudget = DescriptorReadBudget(Long.MAX_VALUE)) : BaseDataSource(false) {
    private var descriptor: ParcelFileDescriptor? = null
    private var position = 0L
    private var remaining: Long? = null
    private var currentUri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        check(descriptor == null)
        transferInitializing(dataSpec)
        val opened = ParcelFileDescriptor.dup(original.fileDescriptor)
        try {
            val stat = Os.fstat(opened.fileDescriptor)
            require(OsConstants.S_ISREG(stat.st_mode) && base >= 0 && declaredLength >= -1 && (stat.st_size == 0L || stat.st_size >= base))
            val length = if (declaredLength >= 0) declaredLength else (stat.st_size - base).takeIf { stat.st_size > 0 }
            require(length == null || length <= Long.MAX_VALUE - base && (stat.st_size == 0L || length <= stat.st_size - base))
            require(dataSpec.position <= Long.MAX_VALUE - base && (length == null || dataSpec.position <= length))
            position = dataSpec.position
            val available = length?.minus(position)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else available?.let { minOf(it, dataSpec.length) } ?: dataSpec.length
            descriptor = opened
            currentUri = dataSpec.uri
            transferStarted(dataSpec)
            return remaining ?: C.LENGTH_UNSET.toLong()
        } catch (_: Exception) {
            opened.close()
            throw IOException("Invalid local descriptor range")
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val fd = descriptor ?: throw IOException("Closed local descriptor")
        if (position > Long.MAX_VALUE - base) throw IOException("Invalid local descriptor position")
        val count = budget.read(minOf(length.toLong(), remaining ?: Long.MAX_VALUE).toInt()) { amount ->
            try { Os.pread(fd.fileDescriptor, buffer, offset, amount, base + position) }
            catch (_: android.system.ErrnoException) { throw IOException("Local descriptor read failed") }
        }
        if (count <= 0) return C.RESULT_END_OF_INPUT
        remaining = remaining?.minus(count)
        position += count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = currentUri
    override fun close() {
        val opened = descriptor ?: return
        descriptor = null; currentUri = null
        try { opened.close() } finally { transferEnded() }
    }
}
