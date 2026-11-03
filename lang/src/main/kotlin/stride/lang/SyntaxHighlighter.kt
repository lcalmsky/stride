package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application

interface SyntaxHighlighter {
    fun getHighlightingLexer(): Lexer

    fun getTokenHighlights(tokenType: TokenType): Array<TextAttributesKey>
}

interface SyntaxHighlighterFactory {
    fun getSyntaxHighlighter(): SyntaxHighlighter

    companion object {
        const val EP_NAME = "stride.lang.syntaxHighlighterFactory"

        val EP: BeanExtensionPointName<SyntaxHighlighterFactoryBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): SyntaxHighlighter? =
            EP.forKey(language.id, area).firstOrNull()
                ?.instance(SyntaxHighlighterFactory::class)
                ?.getSyntaxHighlighter()
    }
}

class SyntaxHighlighterFactoryBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun factory(): SyntaxHighlighterFactory = instance(SyntaxHighlighterFactory::class)
}
