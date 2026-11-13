package io.github.jnesew.paudiobooks.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.library.ImportJob
import io.github.jnesew.paudiobooks.library.LibraryDatabase
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real public persisted permissions; a small injected budget avoids touching platform limits. */
@RunWith(AndroidJUnit4::class)
class SourceGrantBudgetTest {
    @Test fun newGrantsStopBeforeMutationWhileSharedReadsAndRecoveryRetainExistingAccess() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>();val resolver=context.contentResolver
        val before=resolver.persistedUriPermissions.map { it.uri }.toSet()
        check(before.size<90) { "Use the dedicated acceptance AVD with room for a small synthetic grant test" }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        val store=SourceGrantStore(db,resolver,scope,additionBudget=before.size+2,recoveryBudget=before.size+3)
        val nonce=UUID.randomUUID().toString()
        val ids=(0..3).map { "linked-test-budget-safe-$nonce-$it-root" }
        val roots=ids.map { DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",it) }
        val jobs=(0..4).map { UUID.randomUUID().toString() }
        fun persisted()=resolver.persistedUriPermissions.map { it.uri }.toSet()
        try {
            for (id in ids) requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",id,null))
            for (job in jobs) db.library().insertJob(ImportJob(job,"LINKING",123))
            store.reserve(jobs[0],listOf(roots[0]));store.reserve(jobs[1],listOf(roots[1]))
            assertEquals(before+roots.take(2),persisted())
            val records=db.sources().grants()
            try { store.reserve(jobs[2],listOf(roots[2]));fail("Normal reservation exceeded its budget") } catch (_:SourceGrantBudgetException) { }
            assertEquals(records,db.sources().grants());assertEquals(0,db.sources().reservation(jobs[2],roots[2].toString()))
            assertEquals(before+roots.take(2),persisted())
            // Many books/jobs can share existing read access even when addition slots are full.
            store.reserve(jobs[2],listOf(roots[0],roots[1],roots[0]))
            assertEquals(1,db.sources().reservation(jobs[2],roots[0].toString()))
            assertEquals(1,db.sources().reservation(jobs[2],roots[1].toString()))
            assertEquals(before+roots.take(2),persisted())
            store.reserve(jobs[3],listOf(roots[2]),recovering=true)
            assertEquals(before+roots.take(3),persisted())
            try { store.reserve(jobs[4],listOf(roots[3]),recovering=true);fail("Recovery exceeded its reserved headroom") } catch (_:SourceGrantBudgetException) { }
            assertNull(db.sources().grants().firstOrNull { it.uri==roots[3].toString() })
            assertEquals(0,db.sources().reservation(jobs[4],roots[3].toString()))
            assertEquals(before+roots.take(3),persisted())
        } finally {
            for (job in jobs) db.library().deleteJob(job)
            store.collectUnused()
            assertEquals("Unrelated existing read access must survive cleanup",before,persisted())
            for (id in ids) requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-revoke",id,null))
            scope.cancel();db.close()
        }
    }
    @Test fun preexistingReadStaysUnownedButNewReadAfterRevocationIsOwnedAndReleased() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>();val resolver=context.contentResolver
        val before=resolver.persistedUriPermissions.map { it.uri }.toSet()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        val store=SourceGrantStore(db,resolver,scope)
        val nonce=UUID.randomUUID().toString()
        val ids=(0..1).map { "linked-test-budget-ownership-$nonce-$it-root" }
        val roots=ids.map { DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",it) }
        val jobs=(0..2).map { UUID.randomUUID().toString() }
        fun has(uri:Uri)=resolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
        try {
            for ((index,id) in ids.withIndex()) {
                requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",id,null))
                resolver.takePersistableUriPermission(roots[index],android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            for (job in jobs) db.library().insertJob(ImportJob(job,"LINKING",123))
            store.reserve(jobs[0],listOf(roots[0]));store.reserve(jobs[1],listOf(roots[1]))
            assertTrue(db.sources().grants().all { !it.owned })
            db.library().deleteJob(jobs[0]);store.collectUnused()
            assertTrue("Reused preexisting read must not be released",has(roots[0]))
            resolver.releasePersistableUriPermission(roots[1],android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertFalse(has(roots[1]))
            // Real reauthorization returns a fresh picker/provider grant. API26
            // does not retain the transient take opportunity after READ release.
            requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-grant",ids[1],null))
            store.reserve(jobs[2],listOf(roots[1]),recovering=true)
            assertTrue("A newly acquired read after revocation belongs to this app",db.sources().grants().single { it.uri==roots[1].toString() }.owned)
            db.library().deleteJob(jobs[1]);db.library().deleteJob(jobs[2]);store.collectUnused()
            assertFalse("The newly owned replacement read must be released",has(roots[1]))
            assertTrue("Unrelated preexisting read must remain",has(roots[0]))
        } finally {
            for (job in db.library().jobs()) db.library().deleteJob(job.id)
            store.collectUnused()
            // These two roots were explicitly created by this test; never sweep other permissions.
            for ((index,id) in ids.withIndex()) {
                runCatching { resolver.releasePersistableUriPermission(roots[index],android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                requireNotNull(resolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),"linked-revoke",id,null))
            }
            assertEquals(before,resolver.persistedUriPermissions.map { it.uri }.toSet())
            scope.cancel();db.close()
        }
    }

}
