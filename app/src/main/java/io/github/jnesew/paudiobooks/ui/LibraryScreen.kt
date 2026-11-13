package io.github.jnesew.paudiobooks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jnesew.paudiobooks.AudiobookApplication
import io.github.jnesew.paudiobooks.R
import io.github.jnesew.paudiobooks.library.BookDetails
import io.github.jnesew.paudiobooks.library.BookSummary
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun LibraryPage(books: List<BookSummary>, modifier: Modifier, activeBookId: String?, onAdd: () -> Unit, onBook: (String) -> Unit, onContinue: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as AudiobookApplication
    val recent = books.filter { it.progress != null && !it.progress.finished }.maxByOrNull { it.progress!!.updatedAt }
    val recentDetails by produceState<BookDetails?>(null, recent?.book?.id) {
        value = null
        recent?.book?.id?.let { id -> app.repository.observeBookDetails(id).catch { }.collect { value = it } }
    }
    val settings = (LocalContext.current.applicationContext as AudiobookApplication).settings
    val librarySettings by settings.library.collectAsStateWithLifecycle(initialValue = io.github.jnesew.paudiobooks.preferences.LibrarySettings())
    val scope = rememberCoroutineScope()
    val filter = BookFilter.entries.firstOrNull { it.name == librarySettings.filter } ?: BookFilter.ALL
    val sort = BookSort.entries.firstOrNull { it.name == librarySettings.sort } ?: BookSort.RECENT
    var sorting by remember { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var filterError by remember { mutableStateOf(false) }
    fun saveFilters(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { filterError = true }
    } }
    val base = remember(books, query, filter, sort) {
        filterBooks(books, query, filter, sort)
    }
    val genres = librarySettings.genres
    val authors = librarySettings.authors
    val selectedCount = genres.size + authors.size
    val shown = remember(base, genres, authors) { filterBookFacets(base, genres, authors) }
    if (filtersOpen) {
        val genreOptions = remember(books, base, genres, authors) { libraryFacetOptions(books, base, LibraryFacet.GENRE, genres, authors) }
        val authorOptions = remember(books, base, genres, authors) { libraryFacetOptions(books, base, LibraryFacet.AUTHOR, genres, authors) }
        LibraryFilterSheet(genres, authors, genreOptions, authorOptions, shown.size, { filtersOpen = false },
            { field, key -> saveFilters { settings.toggleLibraryFacet(field == LibraryFacet.GENRE, key) } },
            { saveFilters { settings.setLibraryFacets(emptySet(), emptySet()) } })
    }
    if (filterError) AlertDialog(onDismissRequest = { filterError = false }, title = { Text(stringResource(R.string.settings_error)) },
        text = { Text(stringResource(R.string.settings_write_error)) },
        confirmButton = { TextButton(onClick = { filterError = false }) { Text(stringResource(R.string.ok)) } })
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            if (books.isNotEmpty()) IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(Icons.Default.Search, stringResource(R.string.search_books)) }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, stringResource(R.string.add_books)) }
        } }
        if (books.isEmpty()) {
            item { Column(Modifier.padding(vertical = 40.dp)) {
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.empty_description))
                if (selectedCount > 0) LibraryFiltersButton(selectedCount) { filtersOpen = true }
                Spacer(Modifier.height(24.dp)); Button(onClick = onAdd) { Text(stringResource(R.string.add_books)) }
            } }
        } else {
            if (recent != null && !searchOpen && activeBookId == null) item(key = "continue-listening") { OutlinedCard(onClick = { onContinue(recent.book.id) }) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BookCover(recent.book, Modifier.size(80.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.continue_listening), color = MaterialTheme.colorScheme.primary)
                        Text(recent.book.displayTitle, style = MaterialTheme.typography.titleLarge)
                        recent.book.displayAuthor?.let { Text(it) }
                        val chapter = recentDetails?.chapters?.lastOrNull { it.trackId == recent.progress?.trackId && it.startMs <= recent.progress.offsetMs && (it.endMs == null || recent.progress.offsetMs < it.endMs) }
                        chapter?.title?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                        LibraryProgress(recent)
                    }
                }
            } }
            if (searchOpen) item { OutlinedTextField(query, { query = it.take(512) }, label = { Text(stringResource(R.string.search_books)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    IconAction(R.drawable.ic_sort, stringResource(R.string.sort_books), { sorting = true })
                    DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                        BookSort.entries.forEach { item -> DropdownMenuItem(text = { Text(stringResource(when (item) { BookSort.RECENT -> R.string.sort_recent; BookSort.TITLE -> R.string.book_title; BookSort.AUTHOR -> R.string.book_author; BookSort.ADDED -> R.string.sort_added })) }, onClick = { scope.launch { settings.setLibrarySort(item.name) }; sorting = false }) }
                    }
                }
                IconAction(if (librarySettings.grid) R.drawable.ic_list else R.drawable.ic_grid,
                    stringResource(if (librarySettings.grid) R.string.list_view else R.string.grid_view),
                    { scope.launch { settings.setLibraryGrid(!librarySettings.grid) } })
                LibraryFiltersButton(selectedCount) { filtersOpen = true }
            } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookFilter.entries.forEach { item -> FilterChip(selected = item == filter, onClick = { scope.launch { settings.setLibraryFilter(item.name) } }, label = {
                    Text(stringResource(when (item) { BookFilter.ALL -> R.string.all_books; BookFilter.IN_PROGRESS -> R.string.library_in_progress; BookFilter.UNPLAYED -> R.string.unplayed; BookFilter.FINISHED -> R.string.finished }))
                }) }
            } }
            if (selectedCount > 0) item { Text(pluralStringResource(R.plurals.library_book_count, shown.size, shown.size)) }
            if (shown.isEmpty()) item { Text(stringResource(R.string.no_matches)) }
            if (librarySettings.grid) {
                items(shown.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { summary ->
                            Column(Modifier.weight(1f).clickable { onBook(summary.book.id) }.padding(vertical = 4.dp)) {
                                BookCover(summary.book, Modifier.fillMaxWidth().aspectRatio(1f))
                                Spacer(Modifier.height(8.dp))
                                Text(summary.book.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                summary.book.displayAuthor?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                LibraryProgress(summary)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else items(shown, key = { it.book.id }) { summary ->
                Row(Modifier.fillMaxWidth().clickable { onBook(summary.book.id) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(summary.book, Modifier.size(68.dp))
                    Column(Modifier.weight(1f)) {
                        Text(summary.book.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        summary.book.displayAuthor?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(8.dp))
                        LibraryProgress(summary)
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun LibraryFiltersButton(selectedCount: Int, onOpen: () -> Unit) {
    val selectionState = pluralStringResource(R.plurals.library_filters_selected, selectedCount, selectedCount)
    FilterChip(selected = selectedCount > 0, onClick = onOpen,
        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = selectedCount > 0; stateDescription = selectionState },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary, selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary),
        leadingIcon = { Icon(painterResource(R.drawable.ic_filter), null) },
        label = { Text(stringResource(R.string.library_filters)) },
        trailingIcon = if (selectedCount > 0) { { Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer) { Text(stringResource(R.string.count_value, selectedCount)) } } } else null)
}

@Composable
private fun LibraryProgress(summary: BookSummary) {
    BookTime(summary.durationMs, summary.positionMs, summary.progress?.speed ?: 1f,
        summary.progress?.finished == true, labelDuration = false)

}
