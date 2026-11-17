package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application
import java.util.logging.Level
import java.util.logging.Logger

/** Replaces `[start, end)` of the text the intention was computed on. */
data class TextEdit(val start: Int, val end: Int, val replacement: String)

/** Quick fixes repair a problem at the caret; intentions rewrite code that already works. */
enum class IntentionCategory { QUICK_FIX, INTENTION }

/** One ⌥↩ entry. [edits] use offsets of the original text and must not overlap. */
data class IntentionAction(val text: String, val category: IntentionCategory, val edits: List<TextEdit>)

/** Fully qualified names of project declarations with this simple name (`Clock` → `demo.time.Clock`). */
fun interface SymbolLookup {
    fun qualifiedNames(simpleName: String): List<String>

    companion object {
        val EMPTY: SymbolLookup = SymbolLookup { emptyList() }
    }
}

class IntentionContext(
    val text: String,
    val caretOffset: Int,
    val fileName: String,
    val symbols: SymbolLookup = SymbolLookup.EMPTY,
)

interface IntentionProvider {
    fun collect(context: IntentionContext): List<IntentionAction>

    companion object {
        private val LOG = Logger.getLogger("stride.lang.intention")

        const val EP_NAME = "stride.lang.intentionProvider"

        val EP: BeanExtensionPointName<IntentionProviderBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<IntentionProvider> =
            EP.forKey(language.id, area).map { it.provider() }

        /** Every provider of [language] in EP order; a provider that throws is logged and skipped. Quick fixes come before intentions, stable otherwise. */
        fun collect(
            language: Language,
            context: IntentionContext,
            area: ExtensionArea = Application.extensionArea,
        ): List<IntentionAction> {
            val actions = ArrayList<IntentionAction>()
            for (provider in forLanguage(language, area)) {
                try {
                    actions.addAll(provider.collect(context))
                } catch (t: Throwable) {
                    LOG.log(Level.WARNING, "Intention provider ${provider::class.java.name} failed", t)
                }
            }
            return actions.sortedBy { it.category.ordinal }
        }
    }
}

class IntentionProviderBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun provider(): IntentionProvider = instance(IntentionProvider::class)
}
