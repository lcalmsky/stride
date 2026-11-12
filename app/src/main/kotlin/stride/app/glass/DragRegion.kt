package stride.app.glass

/** A draggable rectangle in window points from the window's top-left corner. */
data class DragRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * The draggable parts of [regions]: each region minus the horizontal spans its [blockers] cover
 * (the capsules on the merged title row keep their own clicks). The title row is one horizontal
 * band, so subtracting the blockers' x spans is the exact complement.
 */
fun dragRectangles(regions: List<DragRect>, blockers: List<DragRect>): List<DragRect> {
    val result = ArrayList<DragRect>()
    for (region in regions) {
        if (region.width <= 0f || region.height <= 0f) continue
        val right = region.x + region.width
        val cuts = blockers
            .filter { it.width > 0f && it.height > 0f && it.y < region.y + region.height && it.y + it.height > region.y }
            .mapNotNull { blocker ->
                val start = maxOf(region.x, blocker.x)
                val end = minOf(right, blocker.x + blocker.width)
                if (start < end) start to end else null
            }
            .sortedBy { it.first }
        var cursor = region.x
        for ((start, end) in cuts) {
            if (start > cursor) result += DragRect(cursor, region.y, start - cursor, region.height)
            cursor = maxOf(cursor, end)
        }
        if (cursor < right) result += DragRect(cursor, region.y, right - cursor, region.height)
    }
    return result
}
