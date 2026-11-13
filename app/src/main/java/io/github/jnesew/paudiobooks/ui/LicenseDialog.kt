package io.github.jnesew.paudiobooks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jnesew.paudiobooks.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LicenseItem(
    val titleRes: Int,
    val descriptionRes: Int,
    val assetPath: String
)

private val LICENSE_ITEMS = listOf(
    LicenseItem(
        titleRes = R.string.about_license_apache_title,
        descriptionRes = R.string.about_license_apache_desc,
        assetPath = "licenses/APACHE-2.0.txt"
    ),
    LicenseItem(
        titleRes = R.string.about_license_protobuf_title,
        descriptionRes = R.string.about_license_protobuf_desc,
        assetPath = "licenses/PROTOBUF-LICENSE.txt"
    ),
    LicenseItem(
        titleRes = R.string.about_license_datastore_title,
        descriptionRes = R.string.about_license_datastore_desc,
        assetPath = "licenses/DATASTORE-BSD-LICENSE.txt"
    ),
    LicenseItem(
        titleRes = R.string.about_license_inventory_title,
        descriptionRes = R.string.about_license_inventory_desc,
        assetPath = "licenses/selected-runtime-licenses.json"
    )
)

/** License browsing and selection are local to the About dialog. */
@Composable
internal fun LicenseDialog(onDismiss: () -> Unit) {
    var viewingAssetPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedItem = LICENSE_ITEMS.firstOrNull { it.assetPath == viewingAssetPath }
    AdaptiveLicenseDialog(
        title = stringResource(
            selectedItem?.titleRes ?: R.string.about_licenses_dialog_title
        ),
        onDismiss = {
            viewingAssetPath = null
            onDismiss()
        },
        actions = {
            if (selectedItem != null) {
                TextButton(
                    onClick = { viewingAssetPath = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.back))
                }
            }
            TextButton(
                onClick = {
                    viewingAssetPath = null
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.about_dialog_close))
            }
        }
    ) {
        if (selectedItem != null) {
            BackHandler { viewingAssetPath = null }
            LicenseDetailView(item = selectedItem)
        } else {
            BackHandler { onDismiss() }
            LicenseListView(
                items = LICENSE_ITEMS,
                onSelect = { item -> viewingAssetPath = item.assetPath }
            )
        }
    }
}

@Composable
private fun AdaptiveLicenseDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    content()
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun LicenseListView(
    items: List<LicenseItem>,
    onSelect: (LicenseItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.about_licenses_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onSelect(item) }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(item.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LicenseDetailView(item: LicenseItem) {
    val context = LocalContext.current
    val loadState by produceState<LicenseContentResult>(
        initialValue = LicenseContentResult.Loading,
        key1 = item.assetPath
    ) {
        value = withContext(Dispatchers.IO) {
            loadBoundedLicenseAsset(context, item.assetPath)
        }
    }

    when (val result = loadState) {
        is LicenseContentResult.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.about_license_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is LicenseContentResult.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.about_license_load_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        is LicenseContentResult.TextSuccess -> {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                SelectionContainer {
                    Text(
                        text = result.text,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is LicenseContentResult.CatalogSuccess -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                result.components.forEachIndexed { index, component ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = component.coordinate,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(
                                R.string.about_catalog_item_format,
                                component.version,
                                component.license
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
