package io.github.jnesew.paudiobooks.portability

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.ui.ProgressTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import io.github.jnesew.paudiobooks.R
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressTransferCancellationTest {
    @Test fun cancelRestoresUiAndLateProviderResultCannotRevivePreviewOrChangeLibrary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AudiobookApplication>()
        assumeFalse("Preserve a preexisting incomplete export", app.getSharedPreferences("transfer_journal", 0).getBoolean("export_in_progress", false))
        val before = app.library.books().all()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val errors = CopyOnWriteArrayList<Int>()
        lateinit var model: ProgressTransfer
        try {
            withContext(Dispatchers.Main) {
                model = ProgressTransfer(app, scope) { errors.add(it) }
                model.previewProgress(Uri.parse("content://io.github.jnesew.paudiobooks.test.transferfixture/slow-open"))
                assertTrue(model.transfer.value.cancellable)
            }
            delay(50)
            withContext(Dispatchers.Main) { model.cancelTransfer() }
            withTimeout(1_000) { while (model.transfer.value.busy) delay(10) }
            assertFalse(model.transfer.value.cancellable)
            delay(800)
            assertNull(model.transfer.value.preview)
            assertEquals(before, app.library.books().all())
            val uri = Uri.parse("content://io.github.jnesew.paudiobooks.test.transferfixture/document")
            val bytes = app.transferDocuments.read(uri)
            assertTrue(TransferJson.read(bytes.inputStream()).isEmpty())
            // A preserved acceptance library can contain deliberately unmatchable rows.
            // The UI must give the same result as a fresh preview on that catalog.
            val catalogCanMatch = runCatching { ProgressPortability(app.library).preview(bytes.inputStream()) }.isSuccess
            errors.clear()
            withContext(Dispatchers.Main) { model.previewProgress(uri) }
            withTimeout(3_000) { while (model.transfer.value.busy) delay(10) }
            if (catalogCanMatch) assertNotNull(model.transfer.value.preview)
            else { assertNull(model.transfer.value.preview); assertEquals(listOf(R.string.portability_failed), errors.toList()) }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putBoolean("preservedCatalogCanMatch", catalogCanMatch)
                putInt("preservedBookCount", before.size)
            })
            assertEquals(before, app.library.books().all())
        } finally { scope.cancel() }
    }
}
