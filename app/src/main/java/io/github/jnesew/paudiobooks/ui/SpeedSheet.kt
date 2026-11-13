package io.github.jnesew.paudiobooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.preferences.PlaybackSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedSheet(title: String, speed: Float, onDismiss: () -> Unit,
    onSpeed: suspend (Float) -> Boolean, defaultSpeed: Boolean = false) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableFloatStateOf(PlaybackSpeed.sanitize(speed)) }
    var dragging by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !applying })
    LaunchedEffect(speed) { if (!dragging && !applying) selected = PlaybackSpeed.sanitize(speed) }
    fun apply(value: Float) {
        if (applying) return
        applying = true
        failed = false
        scope.launch {
            try {
                if (onSpeed(value)) selected = value
                else { selected = PlaybackSpeed.sanitize(speed); failed = true }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { selected = PlaybackSpeed.sanitize(speed); failed = true }
            finally { applying = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!applying) onDismiss() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            if (defaultSpeed) Text(stringResource(R.string.default_speed_help), style = MaterialTheme.typography.bodyMedium)
            val decreaseLabel = stringResource(R.string.decrease_speed_label)
            val increaseLabel = stringResource(R.string.increase_speed_label)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { apply(((selected * 20).roundToInt() - 1).coerceAtLeast(10) / 20f) },
                    enabled = !applying && selected > PlaybackSpeed.MIN_SPEED,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                        contentDescription = decreaseLabel
                    }) { Text(stringResource(R.string.decrease_speed), modifier = Modifier.clearAndSetSemantics {}) }
                Text(stringResource(R.string.speed_value, selected), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { apply(((selected * 20).roundToInt() + 1).coerceAtMost(60) / 20f) },
                    enabled = !applying && selected < PlaybackSpeed.MAX_SPEED,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = increaseLabel }) {
                    Text(stringResource(R.string.increase_speed), modifier = Modifier.clearAndSetSemantics {})
                }
            }
            val speedLabel = stringResource(R.string.speed)
            val speedValue = stringResource(R.string.speed_value, selected)
            Slider(value = selected, onValueChange = { dragging = true; selected = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { dragging = false; apply(selected) },
                valueRange = PlaybackSpeed.MIN_SPEED..PlaybackSpeed.MAX_SPEED, steps = 49, enabled = !applying,
                track = { SliderDefaults.Track(it, drawTick = { _, _ -> }) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = speedLabel; stateDescription = speedValue })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.speed_value, PlaybackSpeed.MIN_SPEED))
                Text(stringResource(R.string.speed_value, PlaybackSpeed.MAX_SPEED))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { value ->
                    FilterChip(selected = kotlin.math.abs(selected - value) < 0.001f,
                        onClick = { apply(value) }, enabled = !applying,
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text(stringResource(R.string.speed_value, value)) })
                }
            }
            if (failed) Text(stringResource(R.string.speed_update_failed), color = MaterialTheme.colorScheme.error)
            if (applying) Text(stringResource(R.string.saving))
            TextButton(onClick = onDismiss, enabled = !applying, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.done))
            }
        }
    }
}
