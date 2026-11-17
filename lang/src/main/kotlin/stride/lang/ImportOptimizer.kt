package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application

/**
 * Rewrites a document's import block. Edits use the offsets of the original text and must not
 * overlap; an empty list means there is nothing to change.
 */
interface ImportOptimizer {
    fun optimize(text: String): List<TextEdit>

    companion object {
        const val EP_NAME = "stride.lang.importOptimizer"

        val EP: BeanExtensionPointName<ImportOptimizerBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): ImportOptimizer? =
            EP.forKey(language.id, area).firstOrNull()?.optimizer()
    }
}

class ImportOptimizerBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun optimizer(): ImportOptimizer = instance(ImportOptimizer::class)
}
