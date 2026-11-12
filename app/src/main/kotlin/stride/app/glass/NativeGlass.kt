package stride.app.glass

import java.awt.Component
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

data class NativeGlassView(
    val key: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val cornerRadius: Double,
    val style: Int,
    val tint: Int?,
    /** The shadow sibling's opacity: popups carry their drop shadow on that unclipped view. */
    val shadowOpacity: Double,
    /** Bounds of the sibling's rounded shadow path; it must match the piece's frame size. */
    val shadowPathWidth: Double,
    val shadowPathHeight: Double,
    /** Whether the shadow sibling is ordered below its glass view in the container. */
    val shadowBelow: Boolean,
)

/**
 * The transparency chain under the content view. [drawingLayer] is the Skiko layer (a
 * CAMetalLayer on the Metal path); [ancestorsClear] checks every layer above it.
 */
data class NativeLayerState(
    val drawingLayer: Boolean,
    val className: String,
    val opaque: Boolean,
    val backgroundAlpha: Double,
    val ancestorsClear: Boolean,
    val ancestorCount: Int,
    val windowBackgroundAlpha: Double,
    val containerBelowContent: Boolean,
    val contentViewOpaque: Boolean,
    val containerOpaque: Boolean,
    /** Layers whose original opaque flag and background a detach restored. */
    val restoredLayers: Int,
    /** Shadow siblings under the container; zero once the shadowed pieces are gone. */
    val shadowViewCount: Int,
)

/** The debug view of one attached window: what the native side really has. */
data class NativeGlassState(
    val opaque: Boolean,
    val windowCount: Int,
    val baseView: Boolean,
    val views: List<NativeGlassView>,
    val layer: NativeLayerState?,
)

/**
 * JNI bridge to `native/glass/stride_glass.mm`.
 *
 * Threading: the AppKit thread may be inside an accessibility or menu call that waits for the
 * AWT event dispatch thread, so no Java thread may ever block on the AppKit thread in a way that
 * only the GCD main queue would service (that is the v1.4.0 launch deadlock). Nothing here does.
 * [supported] reads only the OS version and a class, cached and lock-free. Mutations (attach,
 * sync, drag regions, detach, the appearance observer) are enqueued on the AppKit run loop and
 * return immediately, in the order they were called. Queries that must answer synchronously
 * (state reads, the appearance query) wait on that same run loop with a bound, then fall back.
 * The window lookup in [attach] stays on the calling thread because JAWT requires its lock there.
 */
internal object NativeGlass {
    private val log: Logger = Logger.getLogger("stride.app")
    private val loadFailure: String? = load()
    private val handles = AtomicLong(0)

    val available: Boolean get() = loadFailure == null

    val failure: String? get() = loadFailure

    /** Values per piece in [NativeGlassState]: frame, radius, style, tint and the shadow block. */
    private const val VIEW_VALUES = 11

    /** Values in the transparency block that follows the pieces. */
    private const val LAYER_VALUES = 11

    private fun load(): String? {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return "not macOS"
        val file = libraryFile() ?: return "libstrideglass.dylib not found"
        return try {
            System.load(file.absolutePath)
            null
        } catch (error: Throwable) {
            error.message ?: error.javaClass.simpleName
        }
    }

    private fun libraryFile(): File? {
        val explicit = System.getProperty("stride.glass.library")
        if (explicit != null) {
            File(explicit).takeIf { it.isFile }?.let { return it }
        }
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources != null) {
            File(resources, "libstrideglass.dylib").takeIf { it.isFile }?.let { return it }
        }
        val home = System.getProperty("java.home")
        if (home != null) {
            File(home, "../Resources/libstrideglass.dylib").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    fun supported(): Boolean = available && runCatching { nativeSupported() }.getOrDefault(false)

    /** A new native window handle; handles are generated here, never returned from the main thread. */
    internal fun nextHandle(): Long = handles.incrementAndGet()

    /**
     * Enqueues the attach and returns immediately. False means the AWT window was not found, so
     * the caller can retry; the view insertion itself happens on the AppKit run loop.
     */
    internal fun attach(handle: Long, component: Component): Boolean =
        available && runCatching {
            nativeAttach(handle, component, System.getProperty("java.home") ?: "")
        }.getOrElse {
            log.warning("native glass attach failed: ${it.message}")
            false
        }

    fun detach(handle: Long) {
        if (available) runCatching { nativeDetach(handle) }
    }

    /** Enqueues one sync; the return value is the number of pieces accepted for that run. */
    fun sync(handle: Long, pieces: List<GlassPiece>): Int {
        if (!available) return 0
        val count = pieces.size
        val keys = Array(count) { pieces[it].key }
        val xs = FloatArray(count) { pieces[it].x }
        val ys = FloatArray(count) { pieces[it].y }
        val widths = FloatArray(count) { pieces[it].width }
        val heights = FloatArray(count) { pieces[it].height }
        val radii = FloatArray(count) { pieces[it].cornerRadius }
        val styles = IntArray(count) { if (pieces[it].style == GlassStyle.CLEAR) 1 else 0 }
        val tints = IntArray(count) { pieces[it].tint ?: 0 }
        val shadows = IntArray(count) { if (pieces[it].shadow) 1 else 0 }
        return runCatching {
            nativeSync(handle, keys, xs, ys, widths, heights, radii, styles, tints, shadows)
        }.getOrElse {
            log.warning("native glass sync failed: ${it.message}")
            0
        }
    }

    fun state(handle: Long): NativeGlassState? {
        if (!available || handle == 0L) return null
        val values = runCatching { nativeState(handle) }.getOrNull() ?: return null
        if (values.size < 4) return null
        val keys = runCatching { nativeStateKeys(handle) }.getOrDefault(emptyArray())
        val count = values[3].toInt()
        val views = ArrayList<NativeGlassView>(count)
        for (index in 0 until count) {
            val at = 4 + index * VIEW_VALUES
            if (at + VIEW_VALUES - 1 >= values.size) break
            val tint = values[at + 6].toLong()
            views += NativeGlassView(
                key = keys.getOrElse(index) { "" },
                x = values[at],
                y = values[at + 1],
                width = values[at + 2],
                height = values[at + 3],
                cornerRadius = values[at + 4],
                style = values[at + 5].toInt(),
                tint = if (tint == 0L) null else tint.toInt(),
                shadowOpacity = values[at + 7],
                shadowPathWidth = values[at + 8],
                shadowPathHeight = values[at + 9],
                shadowBelow = values[at + 10] != 0.0,
            )
        }
        val layerAt = 4 + count * VIEW_VALUES
        val layer = if (values.size >= layerAt + LAYER_VALUES) {
            NativeLayerState(
                drawingLayer = values[layerAt] != 0.0,
                className = runCatching { nativeLayerClass(handle) }.getOrDefault(""),
                opaque = values[layerAt + 1] != 0.0,
                backgroundAlpha = values[layerAt + 2],
                ancestorsClear = values[layerAt + 3] != 0.0,
                ancestorCount = values[layerAt + 4].toInt(),
                windowBackgroundAlpha = values[layerAt + 5],
                containerBelowContent = values[layerAt + 6] != 0.0,
                contentViewOpaque = values[layerAt + 7] != 0.0,
                containerOpaque = values[layerAt + 8] != 0.0,
                restoredLayers = values[layerAt + 9].toInt(),
                shadowViewCount = values[layerAt + 10].toInt(),
            )
        } else {
            null
        }
        return NativeGlassState(
            opaque = values[0] != 0.0,
            windowCount = values[1].toInt(),
            baseView = values[2] != 0.0,
            views = views,
            layer = layer,
        )
    }

    /** The CALayer tree under the content view, for diagnosing the transparency chain. */
    fun layerTree(handle: Long): String =
        if (!available || handle == 0L) "" else runCatching { nativeLayerTree(handle) }.getOrDefault("")

    /** Enqueues the window's draggable rectangles; count of rectangles accepted for that run. */
    fun dragRegions(handle: Long, rects: List<DragRect>): Int {
        if (!available || handle == 0L) return 0
        val count = rects.size
        val xs = FloatArray(count) { rects[it].x }
        val ys = FloatArray(count) { rects[it].y }
        val widths = FloatArray(count) { rects[it].width }
        val heights = FloatArray(count) { rects[it].height }
        return runCatching {
            nativeSetDragRects(handle, xs, ys, widths, heights)
        }.getOrElse {
            log.warning("native glass drag regions failed: ${it.message}")
            0
        }
    }

    /** Whether a window-relative point (from the top-left) is inside a reported drag rectangle. */
    fun hitTestDragRegion(handle: Long, x: Double, y: Double): Boolean =
        available && handle != 0L && runCatching { nativeHitTestDragRegion(handle, x, y) }.getOrDefault(false)

    /** Installed local mouse-down monitors; one per attached window while it is attached. */
    fun dragMonitorCount(): Int = if (!available) 0 else runCatching { nativeDragMonitorCount() }.getOrDefault(0)

    /** Test-only: holds the AppKit thread in AWT's nested run-loop mode until released. */
    internal fun testHoldMainThread(): Boolean =
        available && runCatching { nativeTestHoldMainThread() }.getOrDefault(false)

    internal fun testReleaseMainThread() {
        if (available) runCatching { nativeTestReleaseMainThread() }
    }

    private external fun nativeSupported(): Boolean

    private external fun nativeAttach(handle: Long, component: Component, javaHome: String): Boolean

    private external fun nativeDetach(handle: Long)

    private external fun nativeSync(
        handle: Long,
        keys: Array<String>,
        xs: FloatArray,
        ys: FloatArray,
        widths: FloatArray,
        heights: FloatArray,
        radii: FloatArray,
        styles: IntArray,
        tints: IntArray,
        shadows: IntArray,
    ): Int

    private external fun nativeState(handle: Long): DoubleArray

    private external fun nativeStateKeys(handle: Long): Array<String>

    private external fun nativeLayerClass(handle: Long): String

    private external fun nativeLayerTree(handle: Long): String

    private external fun nativeSetDragRects(
        handle: Long,
        xs: FloatArray,
        ys: FloatArray,
        widths: FloatArray,
        heights: FloatArray,
    ): Int

    private external fun nativeHitTestDragRegion(handle: Long, x: Double, y: Double): Boolean

    private external fun nativeDragMonitorCount(): Int

    private external fun nativeTestHoldMainThread(): Boolean

    private external fun nativeTestReleaseMainThread()
}
