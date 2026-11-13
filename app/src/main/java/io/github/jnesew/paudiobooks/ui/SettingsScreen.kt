package io.github.jnesew.paudiobooks.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.BookSummary
import io.github.jnesew.paudiobooks.preferences.ThemeChoice
import io.github.jnesew.paudiobooks.preferences.PlaybackSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPage(choice: ThemeChoice, onTheme: (ThemeChoice) -> Unit, modifier: Modifier, onError: (Int) -> Unit, books: List<BookSummary>, connection: PlaybackConnection, model: LibraryViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as AudiobookApplication
    val settings = app.settings
    val storage by produceState<Long?>(null, books) {
        value = try { app.repository.audioStorageBytes() } catch (_: Exception) { null }
    }
    val playback by settings.playback.collectAsStateWithLifecycle(initialValue = PlaybackSettings())
    val scope = rememberCoroutineScope()
    var choosingBack by remember { mutableStateOf<Boolean?>(null) }
    var choosingSpeed by remember { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    fun update(block: suspend () -> Unit) { scope.launch { try { block() } catch (_: Exception) { onError(R.string.settings_write_error) } } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
        val label = stringResource(R.string.theme)
        Column(Modifier.selectableGroup().semantics { contentDescription = label }) {
            ThemeChoice.entries.forEach { item -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(item == choice, onClick = { onTheme(item) }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(item == choice, null); Spacer(Modifier.width(12.dp))
                Text(stringResource(when (item) { ThemeChoice.SYSTEM -> R.string.system; ThemeChoice.LIGHT -> R.string.light; ThemeChoice.DARK -> R.string.dark }))
            } }
        }
        Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.playback_settings), style = MaterialTheme.typography.titleMedium)
        SettingValueRow(stringResource(R.string.default_playback_speed), stringResource(R.string.speed_value, playback.defaultSpeed)) { choosingSpeed = true }
        SettingValueRow(stringResource(R.string.skip_back), pluralStringResource(R.plurals.seconds, playback.skipBackSeconds, playback.skipBackSeconds)) { choosingBack = true }
        SettingValueRow(stringResource(R.string.skip_forward), pluralStringResource(R.plurals.seconds, playback.skipForwardSeconds, playback.skipForwardSeconds)) { choosingBack = false }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.smart_rewind), Modifier.weight(1f))
            Switch(playback.smartRewind, { value -> update { settings.setSmartRewind(value) } })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lock_screen_details), Modifier.weight(1f))
            Switch(playback.showLockScreenDetails, { value -> update { settings.setShowLockScreenDetails(value) } })
        }
        storage?.takeIf { it > 0 }?.let {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.audio_storage, Formatter.formatShortFileSize(context, it)))
        }
        Spacer(Modifier.height(24.dp)); PortabilityControls(books, connection, model.progressTransfer)
        TextButton(onClick = { aboutOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.about_heading)) }
    }
    if (aboutOpen) InputDialog(title = stringResource(R.string.about_heading), onDismissRequest = { aboutOpen = false }, content = { AboutContent(showHeading = false) }, actions = { TextButton(onClick = { aboutOpen = false }) { Text(stringResource(R.string.done)) } })
    if (choosingBack != null) AlertDialog(onDismissRequest = { choosingBack = null }, title = { Text(stringResource(if (choosingBack == true) R.string.skip_back else R.string.skip_forward)) }, text = {
        Column { listOf(5, 10, 15, 30, 60, 120).forEach { seconds -> TextButton(onClick = { val back = choosingBack == true; choosingBack = null; update { if (back) settings.setSkipBackSeconds(seconds) else settings.setSkipForwardSeconds(seconds) } }) { Text(pluralStringResource(R.plurals.seconds, seconds, seconds)) } } }
    }, confirmButton = { TextButton(onClick = { choosingBack = null }) { Text(stringResource(R.string.cancel)) } })
    if (choosingSpeed) SpeedSheet(stringResource(R.string.default_playback_speed), playback.defaultSpeed,
        onDismiss = { choosingSpeed = false }, onSpeed = { value -> settings.setDefaultSpeed(value); true }, defaultSpeed = true)

}

@Composable
private fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}
