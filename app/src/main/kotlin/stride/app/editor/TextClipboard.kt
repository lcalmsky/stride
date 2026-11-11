package stride.app.editor

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Clipboard used by the whole-line copy and cut actions; replaced in tests. */
interface TextClipboard {
    /** Returns `false` when the platform clipboard is unavailable. */
    fun writeText(text: String): Boolean
}

/** The AWT system clipboard; the editor lives outside a composition, so it cannot use Compose's. */
object SystemTextClipboard : TextClipboard {
    override fun writeText(text: String): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.isSuccess
}
