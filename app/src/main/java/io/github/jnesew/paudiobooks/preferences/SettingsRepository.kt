package io.github.jnesew.paudiobooks.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore(name = "settings")
enum class ThemeChoice { SYSTEM, LIGHT, DARK }
data class PlaybackSettings(val skipBackSeconds: Int = 10, val skipForwardSeconds: Int = 30,
    val smartRewind: Boolean = false, val showLockScreenDetails: Boolean = true,
    val defaultSpeed: Float = PlaybackSpeed.DEFAULT_SPEED)
data class LibrarySettings(val sort: String = "RECENT", val filter: String = "ALL", val grid: Boolean = false,
    val genres: Set<String> = emptySet(), val authors: Set<String> = emptySet())
class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("theme")
    val theme = context.preferences.data.map { preferences ->
        ThemeChoice.entries.firstOrNull { it.name == preferences[key] } ?: ThemeChoice.SYSTEM
    }
    suspend fun setTheme(choice: ThemeChoice) { context.preferences.edit { it[key] = choice.name } }
    private val librarySortKey = stringPreferencesKey("library_sort")
    private val libraryFilterKey = stringPreferencesKey("library_filter")
    private val libraryGridKey = booleanPreferencesKey("library_grid")
    private val libraryGenresKey = stringSetPreferencesKey("library_genres")
    private val libraryAuthorsKey = stringSetPreferencesKey("library_authors")
    val library = context.preferences.data.map { prefs -> LibrarySettings(
        prefs[librarySortKey] ?: "RECENT", prefs[libraryFilterKey] ?: "ALL", prefs[libraryGridKey] ?: false,
        prefs[libraryGenresKey].orEmpty(), prefs[libraryAuthorsKey].orEmpty()) }
    suspend fun setLibrarySort(value: String) { context.preferences.edit { it[librarySortKey] = value } }
    suspend fun setLibraryFilter(value: String) { context.preferences.edit { it[libraryFilterKey] = value } }
    suspend fun setLibraryGrid(value: Boolean) { context.preferences.edit { it[libraryGridKey] = value } }
    suspend fun toggleLibraryFacet(genre: Boolean, value: String) {
        require(value.length in 1..8198)
        val key = if (genre) libraryGenresKey else libraryAuthorsKey
        context.preferences.edit { prefs ->
            val old = prefs[key].orEmpty()
            prefs[key] = if (value in old) old - value else old + value
        }
    }
    suspend fun setLibraryFacets(genres: Set<String>, authors: Set<String>) {
        require((genres + authors).all { it.length in 1..8198 })
        context.preferences.edit { it[libraryGenresKey] = genres; it[libraryAuthorsKey] = authors }
    }
    private val skipBackKey = intPreferencesKey("skip_back_seconds")
    private val skipForwardKey = intPreferencesKey("skip_forward_seconds")
    private val smartRewindKey = booleanPreferencesKey("smart_rewind")
    private val lockScreenDetailsKey = booleanPreferencesKey("lock_screen_details")
    private val defaultSpeedKey = floatPreferencesKey("default_playback_speed")
    val playback = context.preferences.data.map { prefs ->
        PlaybackSettings(
            prefs[skipBackKey]?.takeIf { it in 1..300 } ?: 10,
            prefs[skipForwardKey]?.takeIf { it in 1..300 } ?: 30,
            prefs[smartRewindKey] ?: false,
            prefs[lockScreenDetailsKey] ?: true,
            prefs[defaultSpeedKey]?.takeIf { PlaybackSpeed.isValid(it) } ?: PlaybackSpeed.DEFAULT_SPEED,
        )
    }
    suspend fun setSkipBackSeconds(seconds: Int) { require(seconds in 1..300); context.preferences.edit { it[skipBackKey] = seconds } }
    suspend fun setSkipForwardSeconds(seconds: Int) { require(seconds in 1..300); context.preferences.edit { it[skipForwardKey] = seconds } }
    suspend fun setSmartRewind(enabled: Boolean) { context.preferences.edit { it[smartRewindKey] = enabled } }
    suspend fun setShowLockScreenDetails(enabled: Boolean) { context.preferences.edit { it[lockScreenDetailsKey] = enabled } }
    suspend fun setDefaultSpeed(speed: Float) {
        require(PlaybackSpeed.isValid(speed)) { "Playback speed must be finite and between 0.50 and 3.00" }
        context.preferences.edit { it[defaultSpeedKey] = speed }
    }
}
