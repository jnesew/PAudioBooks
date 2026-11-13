package io.github.jnesew.paudiobooks.ui

/** Untouched imported text stays imported. Empty optional overrides explicitly hide a credit. */
internal fun metadataOverride(draft: String, displayed: String?, existingOverride: String?): String? =
    if (draft == displayed.orEmpty()) existingOverride else draft.trim()
