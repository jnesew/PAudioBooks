package io.github.jnesew.paudiobooks.ui

import android.os.Bundle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Resolves unoverridden roles from the actually linked Material3 artifact. */
@RunWith(AndroidJUnit4::class)
class ThemeContrastTest {
    @Test fun actualLightAndDarkRolesMeetActiveContrastThresholds() {
        for (dark in listOf(false, true)) {
            val s = paperColorScheme(dark)
            val roles = linkedMapOf(
                "primary" to s.primary, "onPrimary" to s.onPrimary,
                "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
                "secondary" to s.secondary, "onSecondary" to s.onSecondary,
                "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
                "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
                "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
                "surface" to s.surface, "onSurface" to s.onSurface,
                "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
                "background" to s.background, "onBackground" to s.onBackground,
                "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "error" to s.error, "onError" to s.onError,
                "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer,
                "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
                "inversePrimary" to s.inversePrimary, "outline" to s.outline,
                "outlineVariant" to s.outlineVariant
            )
            val pairs = mutableListOf<Triple<String, String, Double>>()
            listOf("primary", "secondary", "tertiary", "error").forEach { role ->
                val on = "on" + role.replaceFirstChar { it.uppercase() }
                pairs.add(Triple(on, role, 4.5))
                pairs.add(Triple(on + "Container", role + "Container", 4.5))
                pairs.add(Triple(role, "surface", 4.5))
            }
            val surfaces = listOf("surface", "surfaceVariant", "surfaceContainerLowest", "surfaceContainerLow",
                "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest")
            for (surface in surfaces) {
                pairs.add(Triple("onSurface", surface, 4.5))
                pairs.add(Triple("onSurfaceVariant", surface, 4.5))
                pairs.add(Triple("primary", surface, 4.5))
                pairs.add(Triple("outline", surface, 3.0))
            }
            pairs.add(Triple("onBackground", "background", 4.5))
            pairs.add(Triple("inverseOnSurface", "inverseSurface", 4.5))
            pairs.add(Triple("inversePrimary", "inverseSurface", 4.5))
            pairs.add(Triple("primary", "secondaryContainer", 3.0))
            val rows = JSONArray()
            for ((fg, bg, threshold) in pairs) {
                val ratio = contrast(roles.getValue(fg), roles.getValue(bg))
                rows.put(JSONObject().put("foreground", fg).put("background", bg)
                    .put("threshold", threshold).put("ratio", ratio))
                assertTrue("${if (dark) "dark" else "light"}: $fg/$bg = $ratio < $threshold", ratio >= threshold)
            }
            // Decorative separators are explicitly reported, never counted as active passes.
            val decorative = JSONObject().put("foreground", "outlineVariant").put("background", "surface")
                .put("ratio", contrast(s.outlineVariant, s.surface)).put("classification", "decorative only")
            val colors = JSONObject()
            roles.forEach { (name, color) -> colors.put(name, "#%08X".format(color.toArgb())) }
            val result = JSONObject().put("theme", if (dark) "dark" else "light")
                .put("roles", colors).put("activePairs", rows).put("decorative", decorative)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("contrast", result.toString()) })
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
