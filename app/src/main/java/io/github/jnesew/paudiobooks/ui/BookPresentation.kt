package io.github.jnesew.paudiobooks.ui

import io.github.jnesew.paudiobooks.library.Book

val Book.displayTitle get() = titleOverride ?: title
val Book.displayAuthor get() = (authorOverride ?: importedAuthor)?.takeIf { it.isNotBlank() }
val Book.displayNarrator get() = (narratorOverride ?: importedNarrator)?.takeIf { it.isNotBlank() }
val Book.displaySeries get() = (seriesOverride ?: importedSeries)?.takeIf { it.isNotBlank() }
val Book.displayTranslator get() = (translatorOverride ?: importedTranslator)?.takeIf { it.isNotBlank() }
val Book.displaySeriesNumber get() = (seriesNumberOverride ?: importedSeriesNumber)?.takeIf { it.isNotBlank() }

fun mediaTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
