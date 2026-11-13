package io.github.jnesew.paudiobooks.storage

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.importing.DocumentInventory
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit host phases on a new empty AVD only. Never run against a retained library. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PersistedGrantLimitProbe {
    private val app=ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private val prefs get()=app.getSharedPreferences("synthetic_grant_limit_probe",0)
    private fun enabled()=org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("dedicatedGrantProbe")=="true")
    private fun tree(id:String)=DocumentsContract.buildTreeDocumentUri("io.github.jnesew.paudiobooks.test.batchdocuments",id)
    private fun configure(method:String,id:String) { requireNotNull(app.contentResolver.call(Uri.parse("content://io.github.jnesew.paudiobooks.test.fixture"),method,id,null)) }
    private suspend fun snapshot(book:String):String {
        val tracks=app.library.library().tracks(book)
        val text=buildString {
            append(app.library.books().get(book)); append(tracks); append(app.library.sources().root(book))
            append(app.library.library().progress(book)); append(app.library.library().bookmarks(book))
            for (track in tracks) { append(app.library.library().asset(track.assetId)); append(app.library.sources().source(track.assetId)); append(app.library.library().chapters(track.id)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Test fun fillBeyondPlatformBudget() = runBlocking<Unit> {
        enabled()
        check(app.library.books().all().isEmpty() && app.contentResolver.persistedUriPermissions.isEmpty() && !prefs.contains("book")) { "Probe requires a new empty app with no prior grants" }
        val nonce=UUID.randomUUID().toString()
        val protectedId="linked-test-budget-$nonce-protected-root"
        configure("linked-grant",protectedId)
        val grant=tree(protectedId)
        val selected=DocumentInventory(app.contentResolver).describe(DocumentsContract.buildDocumentUriUsingTree(grant,"a-01"))
        val preview=app.linker.prepare(listOf(selected),listOf(grant),DocumentsContract.buildDocumentUriUsingTree(grant,"book-a"),grant)
        val book=UUID.randomUUID().toString(); app.linker.commit(preview,preview.tracks,"Protected synthetic grant baseline",book)
        val track=app.library.library().tracks(book).single()
        assertTrue(app.repository.saveProgress(book,track.id,500,1.25f))
        app.repository.addBookmark(book,track.id,400,"Keep grant budget probe note")
        val digest=snapshot(book)
        val roots=(0 until 600).map { "linked-test-budget-$nonce-$it-root" }
        check(prefs.edit().putString("book",book).putString("protected",protectedId).putString("snapshot",digest).putStringSet("roots",roots.toSet()).commit())
        // Deliberately bypass the app's grant store to measure the platform on this empty test UID.
        for (id in roots) {
            configure("linked-grant",id)
            app.contentResolver.takePersistableUriPermission(tree(id),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val persisted=app.contentResolver.persistedUriPermissions
        assertFalse("Oldest read access was not pruned within this bounded probe",persisted.any { it.uri==grant && it.isReadPermission })
        assertEquals(digest,snapshot(book))
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {
            putString("phase","PLATFORM_GRANTS_PRUNED");putInt("attemptedGrants",601);putInt("retainedGrants",persisted.size);putBoolean("protectedGrantPruned",true)
        })
    }
    @Test fun coldMissingGrantPreservesRecordsAndCanBeReauthorized() = runBlocking<Unit> {
        enabled()
        val book=requireNotNull(prefs.getString("book",null));val protectedId=requireNotNull(prefs.getString("protected",null));val grant=tree(protectedId)
        assertEquals(prefs.getString("snapshot",null),snapshot(book))
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri==grant })
        val track=app.library.library().tracks(book).single();val source=requireNotNull(app.library.sources().source(track.assetId))
        try {
            withContext(Dispatchers.IO) { app.documentAccess.open(Uri.parse(source.documentUri),app.sourceGrants.leaseForSource(source),source.sizeHint).close() }
            fail("Missing persisted access must fail after real reboot")
        } catch (failure:DocumentSourceException) { assertEquals(DocumentSourceException.Reason.UNAVAILABLE,failure.reason) }
        assertTrue(BoundedDocumentAccess.awaitIdle())
        assertEquals(prefs.getString("snapshot",null),snapshot(book))
        // Release only the exact temporary roots created by this probe, never unrelated grants.
        for (id in requireNotNull(prefs.getStringSet("roots",null))) {
            runCatching { app.contentResolver.releasePersistableUriPermission(tree(id),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            configure("linked-revoke",id)
        }
        configure("linked-grant",protectedId)
        assertEquals(1,app.linker.reauthorize(book,grant))
        withContext(Dispatchers.IO) {
            val handle=app.documentAccess.open(Uri.parse(source.documentUri),app.sourceGrants.leaseForSource(source),source.sizeHint)
            try { assertEquals("RIFF",handle.read(0,4).bytes.copyOf(4).toString(Charsets.US_ASCII)) } finally { handle.close() }
        }
        assertTrue(BoundedDocumentAccess.awaitIdle())
        assertEquals(prefs.getString("snapshot",null),snapshot(book))
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri==grant && it.isReadPermission })
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply { putString("phase","COLD_GRANT_RECOVERED");putBoolean("exactRecordsRetained",true) })
    }
    @Test fun prepareSafeNativeBudgetCheck() = runBlocking<Unit> {
        enabled()
        check(android.os.Build.VERSION.SDK_INT==30 && !prefs.contains("uiRoots") && !prefs.getBoolean("uiCompleted",false))
        val book=requireNotNull(prefs.getString("book",null));val grant=tree(requireNotNull(prefs.getString("protected",null)))
        check(app.library.books().all().map { it.id }==listOf(book)) { "Use only this dedicated protected synthetic fixture" }
        check(app.contentResolver.persistedUriPermissions.map { it.uri }.toSet()==setOf(grant))
        assertEquals(prefs.getString("snapshot",null),snapshot(book))
        val roots=(0 until 95).map { "linked-test-ui-budget-${UUID.randomUUID()}-$it-root" }
        check(prefs.edit().putStringSet("uiRoots",roots.toSet()).commit())
        for (id in roots) {
            configure("linked-grant",id)
            app.contentResolver.takePersistableUriPermission(tree(id),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals(96,app.contentResolver.persistedUriPermissions.size)
        assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri==grant })
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply { putString("phase","NATIVE_BUDGET_READY");putInt("persistedGrants",96) })
    }
    @Test fun cleanupNativeBudgetCheckAndVerifyFallbackCopy() = runBlocking<Unit> {
        enabled()
        val book=requireNotNull(prefs.getString("book",null))
        assertEquals(prefs.getString("snapshot",null),snapshot(book))
        val copy=app.library.books().all().single { it.title=="Capacity copy fallback" }
        val tracks=app.library.library().tracks(copy.id)
        assertEquals(2,tracks.size);assertNull(app.library.library().progress(copy.id))
        for (track in tracks) {
            val asset=requireNotNull(app.library.library().asset(track.assetId))
            assertNull(app.library.sources().source(asset.id));assertTrue(asset.identityVerified)
            assertTrue(app.audioStore.matches(asset.relativePath,asset.sizeBytes,asset.sha256))
        }
        assertTrue(app.library.library().jobs().isEmpty())
        for (id in requireNotNull(prefs.getStringSet("uiRoots",null))) {
            app.contentResolver.releasePersistableUriPermission(tree(id),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            configure("linked-revoke",id)
        }
        val grant=tree(requireNotNull(prefs.getString("protected",null)))
        assertEquals(setOf(grant),app.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
        assertEquals(prefs.getString("snapshot",null),snapshot(book))
        check(prefs.edit().remove("uiRoots").putBoolean("uiCompleted",true).commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply { putString("phase","NATIVE_BUDGET_FALLBACK_VERIFIED");putBoolean("exactProtectedRowsRetained",true);putInt("privateCopyTracks",tracks.size) })
    }

}
