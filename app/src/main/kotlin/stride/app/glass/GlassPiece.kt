package stride.app.glass

/** One native glass view: a rounded rectangle in window points from the window's top-left. */
data class GlassPiece(
    val key: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
    val style: GlassStyle = GlassStyle.REGULAR,
    val tint: Int? = null,
)

enum class GlassStyle { REGULAR, CLEAR }

data class GlassPieceDiff(
    val added: List<GlassPiece>,
    val updated: List<GlassPiece>,
    val removed: List<String>,
)

fun sameGlassPiece(first: GlassPiece, second: GlassPiece): Boolean =
    first.key == second.key &&
        first.x == second.x &&
        first.y == second.y &&
        first.width == second.width &&
        first.height == second.height &&
        first.cornerRadius == second.cornerRadius &&
        first.style == second.style &&
        first.tint == second.tint

/** Keyed create/update/remove diff between two reported piece sets. */
fun diffGlassPieces(previous: List<GlassPiece>, next: List<GlassPiece>): GlassPieceDiff {
    val before = previous.associateBy { it.key }
    val after = next.associateBy { it.key }
    val added = ArrayList<GlassPiece>()
    val updated = ArrayList<GlassPiece>()
    for (piece in next) {
        val old = before[piece.key]
        if (old == null) added += piece else if (!sameGlassPiece(old, piece)) updated += piece
    }
    val removed = before.keys.filter { it !in after }
    return GlassPieceDiff(added, updated, removed)
}

fun glassPiecesEqual(previous: List<GlassPiece>, next: List<GlassPiece>): Boolean {
    if (previous.size != next.size) return false
    val diff = diffGlassPieces(previous, next)
    return diff.added.isEmpty() && diff.updated.isEmpty() && diff.removed.isEmpty()
}

/** A corner radius larger than half the box is visually clamped, as the material view does. */
fun effectiveCornerRadius(value: Float, width: Float, height: Float): Float {
    if (!value.isFinite() || value <= 0f) return 0f
    return minOf(value, width / 2f, height / 2f)
}
