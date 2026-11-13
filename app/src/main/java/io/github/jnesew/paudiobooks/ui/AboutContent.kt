package io.github.jnesew.paudiobooks.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About, privacy, permissions, and open source licenses content for Settings.
 */
@Composable
fun AboutContent(modifier: Modifier = Modifier, showHeading: Boolean = true) {
    val context = LocalContext.current
    val versionState by produceState<String?>(initialValue = null, context) {
        value = resolveAppVersion(context)
    }

    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeading) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.about_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = stringResource(R.string.about_app_description),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (versionState != null) {
                stringResource(R.string.about_version_format, versionState!!)
            } else {
                stringResource(R.string.about_version_unavailable)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_privacy_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_privacy_text),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_permissions_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        PermissionExplanationItem(
            title = stringResource(R.string.about_perm_playback_title),
            description = stringResource(R.string.about_perm_playback_desc)
        )
        Spacer(Modifier.height(8.dp))
        PermissionExplanationItem(
            title = stringResource(R.string.about_perm_wakelock_title),
            description = stringResource(R.string.about_perm_wakelock_desc)
        )
        Spacer(Modifier.height(8.dp))
        PermissionExplanationItem(
            title = stringResource(R.string.about_perm_storage_title),
            description = stringResource(R.string.about_perm_storage_desc)
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_licenses_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showLicensesDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { role = Role.Button }
        ) {
            Text(stringResource(R.string.about_licenses_button))
        }
    }

    if (showLicensesDialog) LicenseDialog(onDismiss = { showLicensesDialog = false })
}

@Composable
private fun PermissionExplanationItem(title: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private suspend fun resolveAppVersion(context: Context): String? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName
        }.getOrNull()
    }
}
