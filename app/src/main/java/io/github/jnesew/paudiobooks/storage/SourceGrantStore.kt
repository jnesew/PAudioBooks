package io.github.jnesew.paudiobooks.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import io.github.jnesew.paudiobooks.library.DocumentSource
import io.github.jnesew.paudiobooks.library.JobGrant
import io.github.jnesew.paudiobooks.library.LibraryDatabase
import io.github.jnesew.paudiobooks.library.SourceGrant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One app-wide owner serializes durable reservations, live leases and read-grant collection. */
internal class SourceGrantStore(private val db: LibraryDatabase, private val resolver: ContentResolver,
    private val cleanupScope: CoroutineScope,
    private val additionBudget: Int = 96, private val recoveryBudget: Int = 128) {
    init { require(additionBudget in 1..96 && recoveryBudget in additionBudget..128) }
    private val mutex = Mutex()
    private val leases = mutableMapOf<String, Int>()
    suspend fun reserve(jobId: String, grantUris: List<Uri>, recovering: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock {
        require(grantUris.isNotEmpty() && grantUris.size <= 1000)
        require(grantUris.all { it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrBlank() })
        val persisted = resolver.persistedUriPermissions
        val alreadyRead = persisted.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val requested = grantUris.distinct()
        val taking = requested.filter { it.toString() !in alreadyRead }
        val prospective = persisted.map { it.uri.toString() }.toSet() + requested.map { it.toString() }
        // Android can silently prune older grants. Count every persisted mode, including
        // unrelated/unknown grants, before any ownership record or platform mutation.
        if (taking.isNotEmpty() && prospective.size > if (recovering) recoveryBudget else additionBudget)
            throw SourceGrantBudgetException()
        // Journal the ownership before taking the grant. A crash then leaves recoverable reservations.
        db.withTransaction {
            for (uri in requested) {
                db.sources().insertGrant(SourceGrant(uri.toString(), uri.toString() !in alreadyRead))
                // Reacquiring a revoked preexisting read creates newly owned access.
                // Keep a still-valid preexisting read unowned; never sweep it on removal.
                if (uri.toString() !in alreadyRead) db.sources().claimReadGrant(uri.toString())
                db.sources().reserve(JobGrant(jobId, uri.toString()))
            }
        }
        for (uri in taking) try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { throw SourceGrantException() }
    } }
    suspend fun leaseForJob(jobId: String, grantUri: String): AutoCloseable = mutex.withLock {
        if (db.sources().reservation(jobId, grantUri) != 1) throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
        lease(grantUri)
    }
    suspend fun leaseForSource(source: DocumentSource): AutoCloseable = mutex.withLock {
        val current = db.sources().source(source.assetId)
        if (current == null || current.documentUri != source.documentUri || current.grantUri != source.grantUri ||
            current.sizeHint != source.sizeHint || current.modifiedHint != source.modifiedHint) throw DocumentSourceException(DocumentSourceException.Reason.UNAVAILABLE)
        lease(source.grantUri)
    }
    private fun lease(uri: String): AutoCloseable {
        leases[uri] = (leases[uri] ?: 0) + 1
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false,true)) cleanupScope.launch(Dispatchers.IO) { mutex.withLock {
                val count = requireNotNull(leases[uri])
                if (count == 1) leases.remove(uri) else leases[uri] = count - 1
                collectLocked()
            } }
        }
    }
    suspend fun collectUnused() = withContext(Dispatchers.IO) { mutex.withLock { collectLocked() } }
    private suspend fun collectLocked() {
        for (grant in db.sources().grants()) {
            if ((leases[grant.uri] ?: 0) != 0 || db.sources().references(grant.uri) != 0) continue
            if (grant.owned) {
                try { resolver.releasePersistableUriPermission(Uri.parse(grant.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* Already revoked; durable references were checked. */ }
                catch (_: Exception) { continue } // Retain ownership for an explicit cleanup retry.
            }
            db.sources().deleteGrant(grant.uri)
        }
    }
}

internal class SourceGrantException : java.io.IOException("Persistent read access unavailable")

internal class SourceGrantBudgetException : java.io.IOException("Source permission capacity reached")
