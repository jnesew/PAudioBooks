package io.github.jnesew.paudiobooks.importing

/** Use track tags only when every file has a unique usable disc/track pair; otherwise use subfolder/name order. */
data class TrackOrderHint(
    val name: String,
    val discNumber: Int?,
    val trackNumber: Int?,
    val subfolder: String? = null,
    val orderKey: String? = null
)
fun orderTrackIndices(hints: List<TrackOrderHint>): List<Int> {
    val indices = hints.indices.toList()
    val keys = hints.map { hint ->
        val track = hint.trackNumber?.takeIf { it in 1..100_000 }
        val disc = if (hint.discNumber == null) 1 else hint.discNumber.takeIf { it in 1..100_000 }
        if (track != null && disc != null) disc to track else null
    }
    return if (keys.all { it != null } && keys.toSet().size == hints.size) {
        indices.sortedWith(compareBy({ keys[it]!!.first }, { keys[it]!!.second }))
    } else indices.sortedWith { a, b ->
        compareDocumentOrder(
            hints[a].subfolder,
            hints[a].name,
            hints[b].subfolder,
            hints[b].name,
            hints[a].orderKey,
            hints[b].orderKey
        )
    }
}
