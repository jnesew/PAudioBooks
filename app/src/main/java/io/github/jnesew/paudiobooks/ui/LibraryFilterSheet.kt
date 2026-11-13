package io.github.jnesew.paudiobooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.github.jnesew.paudiobooks.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFilterSheet(
    genres: Set<String>, authors: Set<String>, genreOptions: List<LibraryFacetOption>,
    authorOptions: List<LibraryFacetOption>, matchingBooks: Int, onDismiss: () -> Unit,
    onToggle: (LibraryFacet, String) -> Unit, onClear: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var genreQuery by rememberSaveable { mutableStateOf("") }
    var authorQuery by rememberSaveable { mutableStateOf("") }
    val genreList = rememberLazyListState()
    val authorList = rememberLazyListState()
    val field = if (tab == 0) LibraryFacet.GENRE else LibraryFacet.AUTHOR
    val selected = if (tab == 0) genres else authors
    val query = if (tab == 0) genreQuery else authorQuery
    val missing = stringResource(if (tab == 0) R.string.no_genre else R.string.no_author)
    val choices = (if (tab == 0) genreOptions else authorOptions).filter {
        librarySearchKey(it.label ?: missing).contains(librarySearchKey(query))
    }
    val title = stringResource(R.string.library_filters)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(.92f).semantics { paneTitle = title }) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Keep the tabs and Close reachable even above a large landscape keyboard.
            val compact = maxHeight < 400.dp
            Column(Modifier.fillMaxSize()) {
                if (!compact) FilterSheetHeader(matchingBooks, onClear, onDismiss)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                        listOf(R.string.filter_genres to genres.size, R.string.filter_authors to authors.size).forEachIndexed { index, (label, count) ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(label))
                                    if (count > 0) Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) { Text(stringResource(R.string.count_value, count)) }
                                }
                            })
                        }
                    }
                    if (compact) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                if (!compact) FacetSearch(field, query) { if (tab == 0) genreQuery = it else authorQuery = it }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = if (tab == 0) genreList else authorList,
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (compact) item(key = "search") {
                        FilterSheetHeader(matchingBooks, onClear, null)
                        FacetSearch(field, query) { if (tab == 0) genreQuery = it else authorQuery = it }
                    }
                    if (choices.isEmpty()) item { Text(stringResource(R.string.no_matches), Modifier.padding(24.dp)) }
                    items(choices, key = { it.key }) { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(option.key in selected, role = Role.Checkbox, onValueChange = { onToggle(field, option.key) })
                            .padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Checkbox(checked = option.key in selected, onCheckedChange = null)
                            Text(option.label ?: missing, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.count_value, option.count), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheetHeader(matchingBooks: Int, onClear: () -> Unit, onDismiss: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.library_filters), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Text(pluralStringResource(R.plurals.library_book_count, matchingBooks, matchingBooks),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
        }
        TextButton(onClick = onClear) { Text(stringResource(R.string.clear_library_filters)) }
        if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
    }
}

@Composable
private fun FacetSearch(field: LibraryFacet, query: String, onQuery: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (field == LibraryFacet.GENRE) R.string.genres_match_any else R.string.authors_match_any),
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(query, { onQuery(it.take(512)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(if (field == LibraryFacet.GENRE) R.string.find_genre else R.string.find_author)) })
    }
}
