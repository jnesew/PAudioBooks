package io.github.jnesew.paudiobooks.ui

import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeParseException

internal data class BookReleaseDate(val date: LocalDate, val skeleton: String)

/** Explicit release tags only. Keep precision and reject ambiguous or invalid values. */
internal fun bookReleaseDate(value: String?): BookReleaseDate? {
    if (value == null || !value.matches(Regex("[0-9]{4}(-[0-9]{2}){0,2}"))) return null
    return try {
        val parsed = when (value.length) {
            4 -> BookReleaseDate(Year.parse(value).atDay(1), "y")
            7 -> BookReleaseDate(YearMonth.parse(value).atDay(1), "yMMMM")
            10 -> BookReleaseDate(LocalDate.parse(value), "yMMMd")
            else -> return null
        }
        parsed.takeIf { it.date.year in 1..9999 }
    } catch (_: DateTimeParseException) { null }
}
