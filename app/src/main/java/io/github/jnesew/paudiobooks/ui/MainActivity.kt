package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.preferences.ThemeChoice
import java.io.IOException
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val libraryModel by viewModels<LibraryViewModel>()

    override fun onStop() {
        if (!isChangingConfigurations) libraryModel.leaveForeground()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as AudiobookApplication).settings
        enableEdgeToEdge()
        setContent {
            var choice by rememberSaveable { mutableStateOf<ThemeChoice?>(null) }
            var readError by remember { mutableStateOf(false) }
            var writeError by remember { mutableStateOf(false) }
            var retry by remember { mutableIntStateOf(0) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(retry) {
                readError = false
                try { settings.theme.collect { choice = it } }
                catch (_: IOException) { readError = true }
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (choice) { ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true; else -> systemDark }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PaperTheme(dark) {
                if (choice == null && readError) {
                    StorageError { retry++ }
                } else if (choice != null) {
                    RootApp(choice!!, libraryModel) { requested ->
                        scope.launch {
                            try { settings.setTheme(requested) }
                            catch (_: IOException) { writeError = true }
                        }
                    }
                } else {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                if (readError && choice != null) AlertDialog(onDismissRequest = {},
                    title = { Text(stringResource(R.string.settings_read_error_title)) },
                    text = { Text(stringResource(R.string.settings_read_error)) },
                    confirmButton = { TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) } })
                if (writeError) AlertDialog(onDismissRequest = { writeError = false },
                    title = { Text(stringResource(R.string.settings_write_error_title)) },
                    text = { Text(stringResource(R.string.settings_write_error)) },
                    confirmButton = { TextButton(onClick = { writeError = false }) { Text(stringResource(R.string.ok)) } })
            }
        }
    }
}

private val lightColors = lightColorScheme(
    primary = Color(0xFF2D5D49), onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E8D5), onPrimaryContainer = Color(0xFF123929),
    secondary = Color(0xFF52695A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9D8), onSecondaryContainer = Color(0xFF263D2E),
    tertiary = Color(0xFF596A50), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE7D0), onTertiaryContainer = Color(0xFF273D23),
    background = Color(0xFFFAF7F0), onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFAF7F0), onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFEDEAE2), onSurfaceVariant = Color(0xFF444942),
    surfaceTint = Color(0xFF2D5D49), surfaceContainerLowest = Color(0xFFFFFCF6),
    surfaceContainerLow = Color(0xFFF5F2EA), surfaceContainer = Color(0xFFF0EDE5),
    surfaceContainerHigh = Color(0xFFEAE8DF), surfaceContainerHighest = Color(0xFFE4E2D9),
    outline = Color(0xFF747C72), outlineVariant = Color(0xFFC3CABF))
private val darkColors = darkColorScheme(
    primary = Color(0xFFAEDCBA), onPrimary = Color(0xFF123929),
    primaryContainer = Color(0xFF315A40), onPrimaryContainer = Color(0xFFD1F4D6),
    secondary = Color(0xFFB4CEB8), onSecondary = Color(0xFF203729),
    secondaryContainer = Color(0xFF354C3C), onSecondaryContainer = Color(0xFFD0EBD2),
    tertiary = Color(0xFFBECFAE), onTertiary = Color(0xFF2C3B25),
    tertiaryContainer = Color(0xFF42523A), onTertiaryContainer = Color(0xFFDAEBC8),
    background = Color(0xFF121C17), onBackground = Color(0xFFF0F2ED),
    surface = Color(0xFF121C17), onSurface = Color(0xFFF0F2ED),
    surfaceVariant = Color(0xFF253129), onSurfaceVariant = Color(0xFFC2CEC3),
    surfaceTint = Color(0xFFAEDCBA), surfaceContainerLowest = Color(0xFF0D1712),
    surfaceContainerLow = Color(0xFF18231C), surfaceContainer = Color(0xFF1C2720),
    surfaceContainerHigh = Color(0xFF263129), surfaceContainerHighest = Color(0xFF313C34),
    outline = Color(0xFF8D9A8D), outlineVariant = Color(0xFF414E43))
internal fun paperColorScheme(dark: Boolean): ColorScheme = if (dark) darkColors else lightColors

@Composable private fun PaperTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = paperColorScheme(dark), content = content)
}

@Composable private fun StorageError(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.settings_read_error_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_read_error))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
