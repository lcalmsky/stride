package stride.lang

import stride.core.extensions.BeanExtensionPointName
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionBean
import stride.core.services.Application
import java.util.logging.Level
import java.util.logging.Logger

/** [ERROR] is code that cannot compile; [WARNING] compiles but is likely a mistake. */
enum class ProblemSeverity { ERROR, WARNING }

/**
 * One problem in `[start, end)` of the text it was computed on, with the message the Problems
 * window and the error description show. An empty range marks a position.
 */
data class Problem(val start: Int, val end: Int, val severity: ProblemSeverity, val message: String)

/** What a [ProblemProvider] reads: the document text and its file name, nothing outside the file. */
class ProblemContext(val text: String, val fileName: String)

/**
 * Reports the problems that follow from a document's text alone. No compiler, classpath or project
 * index stands behind a provider, so a check that needs name or type resolution does not belong
 * here; the build output reports those. A provider that cannot tell a real problem from code it
 * does not understand reports nothing rather than guess.
 */
interface ProblemProvider {
    fun collect(context: ProblemContext): List<Problem>

    companion object {
        private val LOG = Logger.getLogger("stride.lang.problem")

        const val EP_NAME = "stride.lang.problemProvider"

        val EP: BeanExtensionPointName<ProblemProviderBean> = BeanExtensionPointName(EP_NAME)

        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<ProblemProvider> =
            EP.forKey(language.id, area).map { it.provider() }

        /**
         * Every provider of [language]; a provider that throws is logged and skipped, and a problem
         * outside the text is dropped. Sorted by start, errors before warnings at the same start.
         */
        fun collect(
            language: Language,
            context: ProblemContext,
            area: ExtensionArea = Application.extensionArea,
        ): List<Problem> {
            val length = context.text.length
            val problems = ArrayList<Problem>()
            for (provider in forLanguage(language, area)) {
                try {
                    provider.collect(context).filterTo(problems) { it.start in 0..it.end && it.end <= length }
                } catch (t: Throwable) {
                    LOG.log(Level.WARNING, "Problem provider ${provider::class.java.name} failed", t)
                }
            }
            return problems.sortedWith(compareBy({ it.start }, { it.severity.ordinal }))
        }
    }
}

class ProblemProviderBean : ExtensionBean() {
    override val key: String? get() = attributes["language"]

    val language: String? get() = attributes["language"]

    fun provider(): ProblemProvider = instance(ProblemProvider::class)
}
