package io.github.jnesew.paudiobooks.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class ComponentEntry(
    val coordinate: String,
    val version: String,
    val license: String
)

internal sealed interface LicenseContentResult {
    data object Loading : LicenseContentResult
    data class TextSuccess(val text: String) : LicenseContentResult
    data class CatalogSuccess(val components: List<ComponentEntry>) : LicenseContentResult
    data object Error : LicenseContentResult
}

private const val MAX_ASSET_BYTES = 512 * 1024 // 512 KiB bound

internal fun loadBoundedLicenseAsset(context: Context, assetPath: String): LicenseContentResult {
    return try {
        context.assets.open(assetPath).use { input ->
            val buffer = ByteArray(MAX_ASSET_BYTES)
            var totalRead = 0
            while (totalRead < buffer.size) {
                val read = input.read(buffer, totalRead, buffer.size - totalRead)
                if (read == -1) break
                totalRead += read
            }
            if (input.read() != -1) {
                // Asset exceeded maximum expected bound
                return LicenseContentResult.Error
            }
            val content = String(buffer, 0, totalRead, Charsets.UTF_8)
            if (assetPath.endsWith(".json")) {
                parseComponentCatalog(content)
            } else {
                LicenseContentResult.TextSuccess(content)
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        LicenseContentResult.Error
    }
}

private fun parseComponentCatalog(jsonString: String): LicenseContentResult {
    return try {
        val root = JSONObject(jsonString)
        val array = root.optJSONArray("unique_selected_components")
            ?: return LicenseContentResult.Error
        val list = mutableListOf<ComponentEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val coord = obj.optString("coordinate", "")
            val ver = obj.optString("version", "")
            val lic = obj.optString("license", "")
            if (coord.isNotBlank()) {
                list.add(ComponentEntry(coordinate = coord, version = ver, license = lic))
            }
        }
        LicenseContentResult.CatalogSuccess(list)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        LicenseContentResult.Error
    }
}
