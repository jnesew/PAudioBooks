package io.github.jnesew.paudiobooks.portability

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferJsonTest {
    private val book = """{"title":"Book","tracks":[{"sha256":"${"a".repeat(64)}","size":20}],"overrides":{},"progress":null,"bookmarks":[]}"""
    private fun read(text: String) = TransferJson.read(ByteArrayInputStream(text.toByteArray()))
    private fun reject(text: String) {
        try { read(text); fail("Invalid transfer accepted") } catch (_: Exception) { }
    }

    @Test fun versionsAndFieldOrderRemainCompatible() {
        assertTrue(read("""{"version":2,"books":[]}""").isEmpty())
        val edited = book.replace("\"overrides\":{}", "\"overrides\":{\"translator\":\"Translator\",\"seriesNumber\":\"2.5\"}")
        val modern = read("""{"books":[$edited],"version":2}""").single()
        assertEquals("Translator", modern.translator)
        val legacy = read("""{"books":[$edited],"version":1}""").single()
        assertFalse(legacy.includesNewCredits)
        assertNull(legacy.translator)
        assertNull(legacy.seriesNumber)
    }

    @Test fun shallowAllocationAttackIsRejectedBeforeReadingTheDocument() {
        val attack = RepeatedDocument("""{"version":2,"books":[""", "{\"x\":0},", 1_200_000, "{}]}")
        try { TransferJson.read(attack); fail() } catch (_: Exception) { }
        assertTrue("Reject before reading the 9.6 MB attack", attack.bytesRead < 16_384)
    }

    @Test fun unknownWideAndDeepValuesUseTheSameBudgets() {
        val attack = RepeatedDocument("""{"version":2,"books":[],"unknown":[""", "{},", 1_200_000, "{}]}")
        try { TransferJson.read(attack); fail() } catch (_: Exception) { }
        assertTrue(attack.bytesRead < 1_000_000)
        reject("""{"version":2,"books":[],"unknown":""" + "[".repeat(33) + "0" + "]".repeat(33) + "}")
        reject("""{"version":2,"books":[],"unknown":""" + "\"" + "a".repeat(30_000) + "\"}")
    }

    @Test fun countsAreCheckedDuringArrayParsing() {
        assertEquals(1000, read("""{"version":2,"books":[${List(1000) { book }.joinToString(",") }]}""").size)
        reject("""{"version":2,"books":[${List(1001) { book }.joinToString(",") }]}""")
        val track = """{"sha256":"${"a".repeat(64)}","size":20}"""
        reject("""{"version":2,"books":[${book.replace("[$track]", "[${List(1001) { track }.joinToString(",")}]")}]}""")
        val mark = """{"track":0,"offset":0,"note":null,"created":0}"""
        reject("""{"version":2,"books":[${book.replace("\"bookmarks\":[]", "\"bookmarks\":[${List(10001) { mark }.joinToString(",")}]")}]}""")
    }

    @Test fun rejectsDuplicateKeysCoercionsInvalidNumbersAndTrailingContent() {
        for (text in listOf(
            """{"version":2,"version":1,"books":[]}""",
            """{"version":"2","books":[]}""",
            """{"version":2.0,"books":[]}""",
            """{"version":2,"books":[],"books":[]}""",
            """{"version":2,"books":[${book.replace("\"size\":20", "\"size\":9223372036854775808")}]}""",
            """{"version":2,"books":[${book.replace("\"size\":20", "\"size\":1.5")}]}""",
            """{"version":2,"books":[${book.replace("\"size\":20", "\"size\":-1")}]}"""
        )) reject(text)
        val doc = """{"version":2,"books":[]}"""
        for (suffix in listOf("{}", "[]", "null", "/* comment */", "\u0000", " \u0000 ")) reject(doc + suffix)
        assertTrue(read(" \t\r\n$doc \t\r\n").isEmpty())
    }

    @Test fun wholeDocumentByteCapIncludesWhitespaceAndZeroReadsFail() {
        val oversized = RepeatedDocument("""{"version":2,"books":[]}""", " ", ProgressPortability.MAX_BYTES, "")
        try { TransferJson.read(oversized); fail() } catch (_: Exception) { }
        assertTrue(oversized.bytesRead <= ProgressPortability.MAX_BYTES + 1)
        val zero = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        try { TransferJson.read(zero); fail() } catch (_: Exception) { }
    }

    @Test fun validatesUtf8TokenLengthAndProgressWithoutProviderWork() {
        try { TransferJson.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))); fail() } catch (_: Exception) { }
        val progress = """{"track":0,"offset":12,"speed":1.25,"finished":false,"updated":123}"""
        assertEquals(12L, read("""{"version":2,"books":[${book.replace("\"progress\":null", "\"progress\":$progress")}]}""").single().progress!!.offset)
        for (bad in listOf(progress.replace("1.25", "3.1"), progress.replace("\"track\":0", "\"track\":1"), progress.replace("false", "\"false\"")))
            reject("""{"version":2,"books":[${book.replace("\"progress\":null", "\"progress\":$bad")}]}""")
        reject("""{"version":2,"books":[${book.replace("Book", "a".repeat(513))}]}""")
    }

    /** Generates attacks without retaining a giant test fixture in the application heap. */
    private class RepeatedDocument(prefix: String, item: String, private val repeats: Int, suffix: String) : InputStream() {
        private val start = prefix.toByteArray(); private val repeated = item.toByteArray(); private val end = suffix.toByteArray()
        var bytesRead = 0; private set
        override fun read(): Int {
            val i = bytesRead
            val value = when {
                i < start.size -> start[i]
                i - start.size < repeats * repeated.size -> repeated[(i - start.size) % repeated.size]
                i - start.size - repeats * repeated.size < end.size -> end[i - start.size - repeats * repeated.size]
                else -> return -1
            }
            bytesRead++
            return value.toInt() and 255
        }
    }
}
