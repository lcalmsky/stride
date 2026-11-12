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
 */
class GlassRegistry(
    private val sync: (Long, List<GlassPiece>) -> Int = { handle, pieces -> NativeGlass.sync(handle, pieces) },
) {
    private var handle = 0L
    private val pieces = LinkedHashMap<String, GlassPiece>()
    private var synced: List<GlassPiece> = emptyList()
    private var dirty = false

    /** Snapshot state the frame loop observes; every report bumps it. */
    var revision by mutableIntStateOf(0)
        private set

    val attached: Boolean get() = handle != 0L

    val reported: List<GlassPiece> get() = pieces.values.toList()

    val lastSynced: List<GlassPiece> get() = synced

    fun attach(handle: Long) {
        this.handle = handle
        markDirty()
    }

    fun detach() {
        handle = 0L
        pieces.clear()
        synced = emptyList()
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

    /** Differences against the last sync; returns true when the native side was called. */
    fun flush(): Boolean {
        if (!dirty) return false
        dirty = false
        if (handle == 0L) return false
        val next = pieces.values.toList()
        if (glassPiecesEqual(synced, next)) return false
        synced = next
        sync(handle, next)
        return true
    }

    private fun markDirty() {
        dirty = true
        revision++
    }

    companion object {
        val shared = GlassRegistry()
    }
}
