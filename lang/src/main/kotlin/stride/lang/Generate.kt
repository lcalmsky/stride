package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The members Generate can add to the class at the caret. More kinds may follow in a minor
 * release, so a provider's `when` over this enum ends in `else -> null`.
 */
enum class GenerateKind(val title: String) {
    SECONDARY_CONSTRUCTOR("Secondary Constructor"),
    EQUALS_HASH_CODE("equals() and hashCode()"),
    TO_STRING("toString()"),
    OVERRIDE_METHODS("Override Methods"),
    IMPLEMENT_METHODS("Implement Methods"),
}

/**
 * A class, interface or object declared in a project source file. [text] is the file's whole
 * text, the open editor's unsaved text when the file is open; [nameOffset] is the offset of the
 * declaration's name token in it, its opening backtick for a backticked name.
 */
class TypeSource(
    val qualifiedName: String,
    val path: String,
    val text: String,
    val nameOffset: Int,
)

/** Project-wide declarations for generators, answered by the app from the declaration index. */
interface ProjectTypes {
    /**
     * The project declarations a type name [written] (with or without backticks) at [offset] of the
     * file at [path] refers to, through that file's package, imports and enclosing classes. Empty
     * when the name is not declared in project sources; more than one when it is ambiguous there.
     */
    fun resolve(written: String, path: String, offset: Int): List<TypeSource>

    /**
     * Whether project sources declare a top-level function or property named [name] in any file,
     * private ones included; the index does not tell them apart, and a property of a function type is called
     * like a function.
     */
    fun hasTopLevelFunction(name: String): Boolean
}

/**
 * The editor's whole text and caret. [path] is the file's absolute path, the one [ProjectTypes]
 * takes; [types] is `null` while the project's declaration index is not ready.
 */
class GenerateContext(
    val text: String,
    val caretOffset: Int,
    val fileName: String,
    val path: String,
    val types: ProjectTypes?,
)

interface GenerateProvider {
    /**
     * Prepares [kind] for the class at the caret. Returns `null` when this provider does not
     * implement [kind] and [RefactoringStep.Refused] when the class or the text does not allow it.
     */
    fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep?

    companion object {
        private val LOG = Logger.getLogger("stride.lang.generate")

        const val EP_NAME = "stride.lang.generateProvider"

        val EP: BeanExtensionPointName<GenerateProviderBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<GenerateProvider> =
            EP.forKey(language.id, area).map { it.provider() }

        /**
         * The first non-null step of [language]'s providers in EP order. A provider that throws is
         * logged and refuses; no provider at all refuses with [notAvailable].
         */
        fun prepare(
            language: Language?,
            kind: GenerateKind,
            context: GenerateContext,
            area: ExtensionArea = Application.extensionArea,
        ): RefactoringStep {
            val providers = if (language == null) emptyList() else forLanguage(language, area)
            for (provider in providers) {
                val step = try {
                    provider.prepare(kind, context)
                } catch (t: Throwable) {
                    LOG.log(Level.WARNING, "Generate provider ${provider::class.java.name} failed", t)
                    return RefactoringStep.Refused("${kind.title} failed: ${t::class.java.simpleName}")
                }
                if (step != null) return step
            }
            return RefactoringStep.Refused(notAvailable(kind, context.fileName))
        }

        /** `toString() is not available for .java files`; a name without an extension reads `for this file`. */
        fun notAvailable(kind: GenerateKind, fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            val target = if (dot <= 0 || dot == fileName.length - 1) "this file" else "${fileName.substring(dot)} files"
            return "${kind.title} is not available for $target"
        }
    }
}

class GenerateProviderBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun provider(): GenerateProvider = instance(GenerateProvider::class)
}
