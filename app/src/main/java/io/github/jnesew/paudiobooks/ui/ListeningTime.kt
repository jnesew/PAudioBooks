package io.github.jnesew.paudiobooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.jnesew.paudiobooks.R

/** Whole listening minutes; a separate label handles values below one minute. */
internal fun listeningMinutes(ms: Long): Long = ms.coerceAtLeast(0) / 60_000

@Composable
internal fun listeningTime(ms: Long, spoken: Boolean = false): String {
    if (ms < 60_000) return stringResource(if (spoken) R.string.under_minute_spoken else R.string.under_minute)
    val total = listeningMinutes(ms)
    val hours = total / 60
    val minutes = total % 60
    fun quantity(value: Long) = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val h = if (spoken) pluralStringResource(R.plurals.hours_spoken, quantity(hours), hours)
        else stringResource(R.string.hours_short, hours)
    val m = if (spoken) pluralStringResource(R.plurals.minutes_spoken, quantity(minutes), minutes)
        else stringResource(R.string.minutes_short, minutes)
    return when { hours == 0L -> m; minutes == 0L -> h; else -> stringResource(R.string.duration_parts, h, m) }
}

@Composable
internal fun BookTime(total: Long?, position: Long?, speed: Float, finished: Boolean, labelDuration: Boolean) {
    val inProgress = !finished && total != null && total > 0 && position != null && position > 0
    val remaining = if (inProgress) ((total!! - position!!).coerceAtLeast(0) /
        io.github.jnesew.paudiobooks.preferences.PlaybackSpeed.sanitize(speed).toDouble()).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong() else null
    val text = when {
        finished -> stringResource(R.string.finished)
        remaining != null -> stringResource(R.string.listening_left, listeningTime(remaining))
        total == null -> stringResource(R.string.unknown_duration)
        labelDuration -> stringResource(R.string.total_duration, listeningTime(total))
        else -> listeningTime(total)
    }
    val spoken = when {
        remaining != null -> stringResource(R.string.listening_left, listeningTime(remaining, spoken = true))
        !finished && total != null -> stringResource(R.string.total_duration, listeningTime(total, spoken = true))
        else -> text
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, Modifier.semantics { contentDescription = spoken }, style = MaterialTheme.typography.bodyMedium)
        if (inProgress) LinearProgressIndicator(progress = { (position!!.toDouble() / total!!).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
