package io.github.jnesew.paudiobooks.portability

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jnesew.paudiobooks.AudiobookApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferDocumentAccessTest {
    private val app get() = ApplicationProvider.getApplicationContext<AudiobookApplication>()
    private fun uri(name: String) = Uri.parse("content://io.github.jnesew.paudiobooks.test.transferfixture/$name")

    @Test fun realProviderReadWriteAndJsonPreviewRoundTrip() = runBlocking {
        val access = TransferDocumentAccess(app.contentResolver)
        val document = """{"version":2,"books":[]}""".toByteArray()
        access.write(uri("document"), document)
        val actual = access.read(uri("document"))
        assertArrayEquals(document, actual)
        assertTrue(TransferJson.read(actual.inputStream()).isEmpty())
    }

    @Test fun providerIgnoringOpenCancellationTimesOutThenRecovers() = runBlocking {
        val access = TransferDocumentAccess(app.contentResolver, 100)
        try { access.read(uri("slow-open")); fail() }
        catch (error: TransferIoException) { assertEquals(TransferIoException.Reason.TIMEOUT, error.reason) }
        try { access.read(uri("document")); fail() }
        catch (error: TransferIoException) { assertEquals(TransferIoException.Reason.BUSY, error.reason) }
        delay(800)
        assertTrue(access.read(uri("document")).isNotEmpty())
    }

    @Test fun denialAndNonContentUrisCannotLeakProviderMessages() = runBlocking {
        val access = TransferDocumentAccess(app.contentResolver)
        try { access.read(uri("denied")); fail() }
        catch (error: TransferIoException) { assertEquals("UNAVAILABLE", error.message); assertNull(error.cause) }
        for (uri in listOf("https://example.org/book", "file:///private/book", "content:///missing")) {
            try { access.read(Uri.parse(uri)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
