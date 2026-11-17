package stride.app.editor

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** Clipboard used by the whole-line copy and cut actions; replaced in tests. */
interface TextClipboard {
    /** Returns `false` when the platform clipboard is unavailable. */
    fun writeText(text: String): Boolean

    /** The clipboard's current text; `null` when it holds no text or cannot be read. */
    fun readText(): String? = null
}

/** The AWT system clipboard; the editor lives outside a composition, so it cannot use Compose's. */
object SystemTextClipboard : TextClipboard {
    override fun writeText(text: String): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.isSuccess

    override fun readText(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return@runCatching null
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
