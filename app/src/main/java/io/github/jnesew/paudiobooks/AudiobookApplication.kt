package io.github.jnesew.paudiobooks

import android.app.Application
import androidx.room.Room
import io.github.jnesew.paudiobooks.library.LibraryDatabase
import io.github.jnesew.paudiobooks.library.LibraryRepository
import io.github.jnesew.paudiobooks.importing.ImportCoordinator
import io.github.jnesew.paudiobooks.metadata.MetadataInspector
import io.github.jnesew.paudiobooks.preferences.SettingsRepository
import io.github.jnesew.paudiobooks.storage.PrivateAudioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AudiobookApplication : Application() {
    val settings by lazy { SettingsRepository(this) }
    val library by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").build() }
    val repository by lazy { LibraryRepository(library) }
    val audioStore by lazy { PrivateAudioStore(this) }
    internal val sourceGrants by lazy { io.github.jnesew.paudiobooks.storage.SourceGrantStore(library, contentResolver, cleanupScope) }
    internal val documentAccess by lazy { io.github.jnesew.paudiobooks.storage.BoundedDocumentAccess(contentResolver) }
    val importer by lazy { ImportCoordinator(library, audioStore, contentResolver) { sourceGrants.collectUnused() } }
    internal val linker by lazy { io.github.jnesew.paudiobooks.importing.LinkedImportCoordinator(library,audioStore,importer,sourceGrants,documentAccess,inspector,io.github.jnesew.paudiobooks.importing.DocumentInventory(contentResolver)) }
    internal val transferDocuments by lazy { io.github.jnesew.paudiobooks.portability.TransferDocumentAccess(contentResolver) }
    val inspector by lazy { MetadataInspector(this) }
    // Lazy: the isolated inspection process must not initialize the main library.
    val cleanupScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
