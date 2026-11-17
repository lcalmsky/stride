package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application
import java.util.logging.Level
import java.util.logging.Logger

/** The refactorings a language plugin can provide. Rename stays an app feature. */
enum class RefactoringKind(val title: String) {
    INTRODUCE_VARIABLE("Introduce Variable"),
    EXTRACT_METHOD("Extract Method"),
    INLINE("Inline"),
}

/** The editor's whole text and its selection; [selectionStart] equals [selectionEnd] without a selection. */
class RefactoringContext(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val fileName: String,
)

/**
 * What a finished refactoring changes. [edits] use offsets of the text the refactoring was
 * prepared on and must not overlap; [caret] is an offset in the text after the edits; [message]
 * is the status line the app shows after applying, `null` for none.
 */
data class RefactoringEdit(
    val edits: List<TextEdit>,
    val caret: Int,
    val message: String? = null,
)

/** One step of a refactoring. The app shows it and follows it until [Apply] or [Refused]. */
sealed interface RefactoringStep {
    /** Not possible here; [reason] is one line for the status bar. */
    data class Refused(val reason: String) : RefactoringStep

    /** The user picks one of [options]; the app highlights the selected option's ranges in the editor. */
    class Choose(val title: String, val options: List<RefactoringOption>) : RefactoringStep

    /** The user types a name, prefilled with [suggested]; [complete] checks it. */
    class AskName(val title: String, val suggested: String, val complete: (String) -> NameCheck) : RefactoringStep

    /** Ready to apply without more input. */
    class Apply(val edit: RefactoringEdit) : RefactoringStep
}

/** One row of [RefactoringStep.Choose]. [highlights] are `start until end` ranges of the prepared text. */
class RefactoringOption(
    val text: String,
    val highlights: List<IntRange> = emptyList(),
    val next: () -> RefactoringStep,
)

/** The answer of [RefactoringStep.AskName.complete]. */
sealed interface NameCheck {
    /** The popup stays open and shows [message]. */
    data class Invalid(val message: String) : NameCheck

    class Valid(val edit: RefactoringEdit) : NameCheck
}

interface RefactoringProvider {
    /**
     * Prepares [kind] at the context's selection. Returns `null` when this provider does not
     * implement [kind] and [RefactoringStep.Refused] when the text does not prove the result keeps
     * the code's meaning.
     */
    fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep?

    companion object {
        private val LOG = Logger.getLogger("stride.lang.refactoring")

        const val EP_NAME = "stride.lang.refactoringProvider"

        val EP: BeanExtensionPointName<RefactoringProviderBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<RefactoringProvider> =
            EP.forKey(language.id, area).map { it.provider() }

        /**
         * The first non-null step of [language]'s providers in EP order. A provider that throws is
         * logged and refuses; no provider at all refuses with [notAvailable].
         */
        fun prepare(
            language: Language?,
            kind: RefactoringKind,
            context: RefactoringContext,
            area: ExtensionArea = Application.extensionArea,
        ): RefactoringStep {
            val providers = if (language == null) emptyList() else forLanguage(language, area)
            for (provider in providers) {
                val step = try {
                    provider.prepare(kind, context)
                } catch (t: Throwable) {
                    LOG.log(Level.WARNING, "Refactoring provider ${provider::class.java.name} failed", t)
                    return RefactoringStep.Refused("${kind.title} failed: ${t::class.java.simpleName}")
                }
                if (step != null) return step
            }
            return RefactoringStep.Refused(notAvailable(kind, context.fileName))
        }

        /** `Extract Method is not available for .java files`; a name without an extension reads `for this file`. */
        fun notAvailable(kind: RefactoringKind, fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            val target = if (dot <= 0 || dot == fileName.length - 1) "this file" else "${fileName.substring(dot)} files"
            return "${kind.title} is not available for $target"
        }
    }
}

class RefactoringProviderBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun provider(): RefactoringProvider = instance(RefactoringProvider::class)
}
