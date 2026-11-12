package stride.app.glass

import java.awt.Component
import java.io.File
import java.util.logging.Logger

data class NativeGlassView(
    val key: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val cornerRadius: Double,
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
 * JNI bridge to `native/glass/stride_glass.mm`. Every entry point marshals onto the AppKit
 * main thread inside the library; callers may use any thread.
 */
internal object NativeGlass {
    private val log: Logger = Logger.getLogger("stride.app")
    private val loadFailure: String? = load()

    val available: Boolean get() = loadFailure == null

    val failure: String? get() = loadFailure

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

    /** Returns 0 when the window cannot be found or the material view is unavailable. */
    fun attach(component: Component): Long =
        if (!available) 0L else runCatching {
            nativeAttach(component, System.getProperty("java.home") ?: "")
        }.getOrElse {
            log.warning("native glass attach failed: ${it.message}")
            0L
        }

    fun detach(handle: Long) {
        if (available) runCatching { nativeDetach(handle) }
    }

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
        return runCatching {
            nativeSync(handle, keys, xs, ys, widths, heights, radii, styles, tints)
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
            val at = 4 + index * 5
            if (at + 4 >= values.size) break
            views += NativeGlassView(
                key = keys.getOrElse(index) { "" },
                x = values[at],
                y = values[at + 1],
                width = values[at + 2],
                height = values[at + 3],
                cornerRadius = values[at + 4],
            )
        }
        val layerAt = 4 + count * 5
        val layer = if (values.size >= layerAt + 9) {
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

    fun beginDrag(handle: Long) {
        if (available && handle != 0L) runCatching { nativeBeginDrag(handle) }
    }

    fun zoom(handle: Long) {
        if (available && handle != 0L) runCatching { nativeZoom(handle) }
    }

    private external fun nativeSupported(): Boolean

    private external fun nativeAttach(component: Component, javaHome: String): Long

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
    ): Int

    private external fun nativeState(handle: Long): DoubleArray

    private external fun nativeStateKeys(handle: Long): Array<String>

    private external fun nativeLayerClass(handle: Long): String

    private external fun nativeLayerTree(handle: Long): String

    private external fun nativeBeginDrag(handle: Long)

    private external fun nativeZoom(handle: Long)
}
