package io.github.jnesew.paudiobooks.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jnesew.paudiobooks.R

/** One native action/name, a tooltip for sighted users and a minimum 48dp target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit,
    enabled: Boolean = true, state: String? = null, active: Boolean = false,
    prominent: Boolean = false, seconds: Int? = null) {
    val description = state?.let { stringResource(R.string.action_state, label, it) } ?: label
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled,
            modifier = Modifier.size(if (prominent) 64.dp else 48.dp).semantics {
                contentDescription = label
                state?.let { stateDescription = it }
            }, colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (prominent) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current)) {
            Box(Modifier.size(if (prominent) 32.dp else 28.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.fillMaxSize())
                seconds?.let { Text(it.toString(), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (active) Box(Modifier.align(Alignment.BottomCenter).offset(y = 6.dp).size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}
