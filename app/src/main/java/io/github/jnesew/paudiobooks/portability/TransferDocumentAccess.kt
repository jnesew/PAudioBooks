package io.github.jnesew.paudiobooks.portability

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.io.ByteArrayOutputStream

/** Provider code runs only on the bounded worker; database/JSON work never owns its streams. */
internal class TransferDocumentAccess(private val resolver: ContentResolver, timeoutMs: Long = 30_000) {
    private val worker = BoundedTransferWorker(timeoutMs)
    val busy get() = worker.busy
    suspend fun read(uri: Uri): ByteArray {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank())
        val signal = CancellationSignal()
        return worker.run(onCancel = { signal.cancel() }) { resources ->
            val descriptor = resources.own(requireNotNull(resolver.openAssetFileDescriptor(uri, "r", signal)))
            val input = resources.own(descriptor.createInputStream())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                resources.checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "Transfer read made no progress" }
                require(count <= ProgressPortability.MAX_BYTES - output.size())
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    suspend fun write(uri: Uri, bytes: ByteArray) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank())
        require(bytes.size <= ProgressPortability.MAX_BYTES)
        val signal = CancellationSignal()
        worker.run(onCancel = { signal.cancel() }) { resources ->
            val descriptor = resources.own(requireNotNull(resolver.openAssetFileDescriptor(uri, "wt", signal)))
            val output = resources.own(descriptor.createOutputStream())
            var position = 0
            while (position < bytes.size) {
                resources.checkActive()
                val count = minOf(8192, bytes.size - position)
                output.write(bytes, position, count)
                position += count
            }
            resources.checkActive()
            output.flush()
        }
    }
}
