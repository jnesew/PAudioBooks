package io.github.jnesew.paudiobooks.metadata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jnesew.paudiobooks.library.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MetadataPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun schemaFiveUpgradePreservesInstalledLibraryAndInitializesNullableFields() = runBlocking {
        val name = "metadata-migration-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.jnesew.paudiobooks.library.LibraryDatabase/5.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO books (id,title,importedAuthor,titleOverride,addedAt) VALUES ('book','Keep title','Keep author','Keep override',123)")
            old.execSQL("INSERT INTO assets (id,relativePath,sizeBytes,sha256,durationMs,identityVerified) VALUES ('asset','media/asset',100,'hash',12000,1)")
            old.execSQL("INSERT INTO tracks (id,bookId,assetId,position,importedTitle) VALUES ('track','book','asset',0,'Chapter')")
            old.execSQL("INSERT INTO reading_state VALUES ('book','track',750,1.25,0,456)")
            old.execSQL("INSERT INTO bookmarks VALUES ('note','book','track',500,'Keep note',789)")
            old.version = 5
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val book = db.books().get("book")!!
            assertEquals("Keep author",book.importedAuthor); assertEquals("Keep override",book.titleOverride)
            assertNull(book.importedGenre); assertNull(book.importedPublisher); assertNull(book.importedDate)
            assertNull(book.importedReleaseDate); assertNull(book.importedDescription); assertFalse(book.metadataManaged)
            assertNull(db.library().tracks("book").single().metadata)
            assertEquals(750L, db.library().progress("book")!!.offsetMs)
            assertEquals("Keep note",db.library().bookmarks("book").single().note)
            assertEquals(6, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun durableLaterPassesReconcileConflictsAndKeepOverridesHistoryAndCatalog() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            db.books().insert(Book("book","Reviewed title",authorOverride="My author",metadataManaged=true))
            db.library().insertAssets(listOf(AudioAsset("a","a",1,"a",12000),AudioAsset("b","b",1,"b",12000)))
            db.library().insertTracks(listOf(BookTrack("t1","book","a",0),BookTrack("t2","book","b",1)))
            val progress = ReadingState("book","t1",700,1.25f,false,123)
            val note = Bookmark("note","book","t1",500,"Keep",456)
            db.library().saveProgress(progress); db.library().insertBookmark(note)
            val fields = BookMetadata(title="Shared Book",author="Later author",genre="Fiction",publisher="Press",date="2020-02",description="Description")
            db.withTransaction {
                val later = db.library().tracks("book")[1]
                db.library().updateTrackMetadata(listOf(later.copy(metadata=fields)))
                db.refreshImportedMetadata("book")
            }
            val discovered = db.books().get("book")!!
            assertEquals("Later author", discovered.importedAuthor); assertEquals("Shared Book", discovered.importedTitle)
            assertEquals("Fiction",discovered.importedGenre); assertEquals("Press",discovered.importedPublisher)
            assertEquals("2020-02",discovered.importedDate); assertEquals("Description",discovered.importedDescription)
            db.withTransaction {
                db.library().updateTrackMetadata(listOf(db.library().tracks("book")[0].copy(metadata=BookMetadata(author="Other author"))))
                db.refreshImportedMetadata("book")
            }
            assertNull(db.books().get("book")!!.importedAuthor)
            assertEquals("My author",db.books().get("book")!!.authorOverride)
            assertEquals("Reviewed title",db.books().get("book")!!.title)
            assertEquals(listOf("t1","t2"),db.library().tracks("book").map { it.id })
            assertEquals(progress, db.library().progress("book")); assertEquals(listOf(note),db.library().bookmarks("book"))
        } finally { db.close() }
    }
}
