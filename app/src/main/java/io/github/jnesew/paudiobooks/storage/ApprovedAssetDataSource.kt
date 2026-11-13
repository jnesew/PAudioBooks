package io.github.jnesew.paudiobooks.storage

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import io.github.jnesew.paudiobooks.library.DocumentSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking

internal sealed interface ApprovedAudioSource {
    data class PrivateFile(val file: File) : ApprovedAudioSource
    data class Document(val source: DocumentSource) : ApprovedAudioSource
}

/** No network/default fallback. Only an approved internal alias can open either local source type. */
@UnstableApi
internal class ApprovedAssetDataSource(private val approved: () -> Map<String, ApprovedAudioSource>,
    private val access: BoundedDocumentAccess, private val grants: SourceGrantStore,
    private val unavailable: (String, DocumentSourceException.Reason) -> Unit = { _, _ -> },
    private val available: (String) -> Unit = {}) : BaseDataSource(false) {
    private var file: FileDataSource? = null
    private var document: BoundedDocumentAccess.Handle? = null
    private var position = 0L
    private var remaining: Long? = null
    private var alias: Uri? = null
    private var assetId: String? = null
    private var started = false
    override fun open(dataSpec: DataSpec): Long {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(alias == null)
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val id = uri.pathSegments.singleOrNull()
        val source = if (uri.scheme == "paudio" && uri.host == "asset" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null && id != null) approved()[id] else null
        if (source == null) throw IOException("Unknown local asset")
        alias = uri; assetId = id
        try {
            val length = when (source) {
                is ApprovedAudioSource.PrivateFile -> {
                    val opened = FileDataSource()
                    file = opened
                    opened.open(dataSpec.withUri(Uri.fromFile(source.file)))
                }
                is ApprovedAudioSource.Document -> {
                    val lease = runBlocking { grants.leaseForSource(source.source) }
                    val opened = access.open(Uri.parse(source.source.documentUri), lease, source.source.sizeHint, source.source.modifiedHint)
                    document = opened
                    if (opened.length?.let { dataSpec.position > it } == true) throw DocumentSourceException(DocumentSourceException.Reason.CHANGED)
                    position = dataSpec.position
                    val available = opened.length?.minus(position)
                    remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else available?.let { minOf(it,dataSpec.length) } ?: dataSpec.length
                    remaining ?: C.LENGTH_UNSET.toLong()
                }
            }
            if (source is ApprovedAudioSource.Document) available(requireNotNull(id))
            started = true; transferStarted(dataSpec)
            return length
        } catch (e: Exception) {
            if (e is DocumentSourceException) unavailable(requireNotNull(id),e.reason)
            close()
            throw if (e is DocumentSourceException) e else IOException("Local asset unavailable")
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        try {
            val count = file?.read(buffer,offset,length) ?: run {
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val opened = document ?: throw IOException("Closed local asset")
                val chunk = opened.read(position,minOf(length.toLong(),remaining ?: Long.MAX_VALUE).toInt())
                if (chunk.count > 0) {
                    chunk.bytes.copyInto(buffer,offset,0,chunk.count)
                    position += chunk.count; remaining = remaining?.minus(chunk.count)
                }
                chunk.count
            }
            if (count > 0) bytesTransferred(count)
            return count
        } catch (e: Exception) {
            if (e is DocumentSourceException) assetId?.let { unavailable(it,e.reason) }
            close()
            throw if (e is DocumentSourceException) e else IOException("Local asset read failed")
        }
    }
    override fun getUri(): Uri? = alias
    override fun close() {
        val local = file; val remote = document
        file = null; document = null; alias = null; assetId = null
        try { local?.close() } finally {
            remote?.close()
            if (started) { started = false; transferEnded() }
        }
    }
}
