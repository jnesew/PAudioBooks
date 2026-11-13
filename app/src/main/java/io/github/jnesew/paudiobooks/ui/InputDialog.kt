package io.github.jnesew.paudiobooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Keep input, heading and actions reachable together at large fonts with the IME open. */
@Composable
internal fun InputDialog(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
    actions: @Composable FlowRowScope.() -> Unit
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }.semantics { paneTitle = title },
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                key(title) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        content()
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                    }
                }
            }
        }
    }
}
