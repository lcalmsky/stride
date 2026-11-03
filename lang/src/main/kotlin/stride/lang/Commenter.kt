package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application

/** How a language marks comments; `null` means the operation is not supported. */
interface Commenter {
    val lineCommentPrefix: String?
    val blockCommentPrefix: String?
    val blockCommentSuffix: String?

    companion object {
        const val EP_NAME = "stride.lang.commenter"

        val EP: BeanExtensionPointName<CommenterBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): Commenter? =
            EP.forKey(language.id, area).firstOrNull()?.commenter()
    }
}

class CommenterBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun commenter(): Commenter = instance(Commenter::class)
}
