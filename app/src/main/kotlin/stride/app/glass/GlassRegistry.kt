package stride.app.glass

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** True on the native macOS glass path; every Skia glass surface falls back to its Skia pass. */
val LocalNativeGlass = staticCompositionLocalOf { false }

/**
 * Collects the bounds each glass element reports during layout and pushes one native sync per
 * changed frame. Elements report on placement; the flush runs on the next Compose frame.
 * Drag regions are the title-row strips the native mouse monitor drags the window from; the
 * capsules reported as pieces block them.
 */
class GlassRegistry(
    private val sync: (Long, List<GlassPiece>) -> Int = { handle, pieces -> NativeGlass.sync(handle, pieces) },
    private val dragSync: (Long, List<DragRect>) -> Int = { handle, rects -> NativeGlass.dragRegions(handle, rects) },
) {
    private var handle = 0L
    private val pieces = LinkedHashMap<String, GlassPiece>()
    private val dragRegions = LinkedHashMap<String, DragRect>()
    private var synced: List<GlassPiece> = emptyList()
    private var syncedDragRects: List<DragRect> = emptyList()
    private var dirty = false

    /** Snapshot state the frame loop observes; every report bumps it. */
    var revision by mutableIntStateOf(0)
        private set

    val attached: Boolean get() = handle != 0L

    val reported: List<GlassPiece> get() = pieces.values.toList()

    val lastSynced: List<GlassPiece> get() = synced

    val dragRectangles: List<DragRect> get() = syncedDragRects

    fun attach(handle: Long) {
        this.handle = handle
        markDirty()
    }

    fun detach() {
        handle = 0L
        pieces.clear()
        dragRegions.clear()
        synced = emptyList()
        syncedDragRects = emptyList()
        markDirty()
    }

    fun report(piece: GlassPiece) {
        val previous = pieces[piece.key]
        if (previous == piece) return
        pieces[piece.key] = piece
        markDirty()
    }

    fun remove(key: String) {
        if (pieces.remove(key) != null) markDirty()
    }

    fun reportDragRegion(key: String, rect: DragRect) {
        if (dragRegions[key] == rect) return
        dragRegions[key] = rect
        markDirty()
    }

    fun removeDragRegion(key: String) {
        if (dragRegions.remove(key) != null) markDirty()
    }

    /** Differences against the last sync; returns true when the native side was called. */
    fun flush(): Boolean {
        if (!dirty) return false
        dirty = false
        if (handle == 0L) return false
        var syncedAnything = false
        val next = pieces.values.toList()
        if (!glassPiecesEqual(synced, next)) {
            synced = next
            sync(handle, next)
            syncedAnything = true
        }
        // The drag strips are the reported rows minus every glass piece on that band.
        val blockers = next.map { DragRect(it.x, it.y, it.width, it.height) }
        val rects = dragRectangles(dragRegions.values.toList(), blockers)
        if (rects != syncedDragRects) {
            syncedDragRects = rects
            dragSync(handle, rects)
            syncedAnything = true
        }
        return syncedAnything
    }

    private fun markDirty() {
        dirty = true
        revision++
    }

    companion object {
        val shared = GlassRegistry()
    }
}
