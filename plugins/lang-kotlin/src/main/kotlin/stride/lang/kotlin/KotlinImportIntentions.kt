package stride.lang.kotlin

import stride.lang.IntentionAction
import stride.lang.IntentionCategory
import stride.lang.IntentionContext
import stride.lang.IntentionProvider
import stride.lang.TextEdit

/** Import quick fixes: add the import of an unresolved project symbol, or drop unused imports. */
class KotlinImportIntentions : IntentionProvider {
    override fun collect(context: IntentionContext): List<IntentionAction> {
        val parsed = parseImports(context.text)
        return importActions(context, parsed) + removeActions(context, parsed)
    }

    private fun importActions(context: IntentionContext, parsed: KotlinImportList): List<IntentionAction> {
        val text = context.text
        val caret = context.caretOffset
        val tokens = codeTokens(text)
        val index = tokens.indexOfFirst {
            it.type == KotlinTokenTypes.IDENTIFIER && caret > it.start && caret <= it.end
        }
        if (index < 0) return emptyList()
        val token = tokens[index]
        if (onPackageOrImportLine(token.start, parsed)) return emptyList()
        if (tokens.getOrNull(index - 1)?.type == KotlinTokenTypes.DOT) return emptyList()
        val name = unquoteIdentifier(token.text)
        if (name.isEmpty() || name == "*") return emptyList()
        if (parsed.imports.any { !it.star && it.boundName == name }) return emptyList()
        if (declaredInFile(text, name)) return emptyList()
        val filePackage = parsed.packageName
        val starPackages = parsed.imports.filter { it.star }.map { starPackage(it.path) }.toSet()
        val candidates = context.symbols.qualifiedNames(name)
            .filter { packageOf(it) != filePackage }
            .filter { fqn -> packageOf(fqn)?.let { it in starPackages } != true }
            .distinct()
            .sorted()
            .take(MAX_CANDIDATES)
        return candidates.map { fqn ->
            IntentionAction("Import '$fqn'", IntentionCategory.QUICK_FIX, listOf(importEdit(fqn, parsed)))
        }
    }

    private fun removeActions(context: IntentionContext, parsed: KotlinImportList): List<IntentionAction> {
        val imports = parsed.imports
        if (imports.isEmpty()) return emptyList()
        val caret = context.caretOffset
        if (caret < imports.first().start || caret >= imports.last().end) return emptyList()
        val used = usedIdentifiers(context.text, parsed)
        val unused = imports.filter { !it.star && it.boundName !in used && it.boundName !in ALWAYS_USED }
        if (unused.isEmpty()) return emptyList()
        val caretImport = unused.firstOrNull { caret >= it.start && caret < it.end }
        val actions = ArrayList<IntentionAction>()
        if (caretImport != null) {
            actions.add(
                IntentionAction(
                    "Remove unused import '${caretImport.path}'",
                    IntentionCategory.QUICK_FIX,
                    deletionEdits(context.text, parsed, listOf(caretImport)),
                ),
            )
        }
        if (unused.any { it !== caretImport }) {
            actions.add(
                IntentionAction(
                    "Remove unused imports",
                    IntentionCategory.QUICK_FIX,
                    deletionEdits(context.text, parsed, unused),
                ),
            )
        }
        return actions
    }

    private fun usedIdentifiers(text: String, parsed: KotlinImportList): Set<String> {
        val used = HashSet<String>()
        for (token in codeTokens(text)) {
            if (token.type != KotlinTokenTypes.IDENTIFIER) continue
            if (onPackageOrImportLine(token.start, parsed)) continue
            used.add(unquoteIdentifier(token.text))
        }
        return used
    }

    private fun onPackageOrImportLine(offset: Int, parsed: KotlinImportList): Boolean {
        val packageStart = parsed.packageLineStart
        val packageEnd = parsed.packageLineEnd
        if (packageStart != null && packageEnd != null && offset in packageStart..packageEnd) return true
        return parsed.imports.any { offset >= it.start && offset < it.end }
    }

    private fun importEdit(fqn: String, parsed: KotlinImportList): TextEdit {
        val imports = parsed.imports
        if (imports.isNotEmpty()) {
            val insert = "import $fqn\n"
            val after = imports.firstOrNull { it.path > fqn }
            return if (after != null) {
                TextEdit(after.start, after.start, insert)
            } else {
                TextEdit(imports.last().end, imports.last().end, insert)
            }
        }
        val packageEnd = parsed.packageLineEnd
        if (parsed.packageName != null && packageEnd != null) {
            return TextEdit(packageEnd, packageEnd, "\n\nimport $fqn")
        }
        return TextEdit(0, 0, "import $fqn\n\n")
    }

    private fun deletionEdits(text: String, parsed: KotlinImportList, removed: List<KotlinImport>): List<TextEdit> {
        val removeAll = removed.size == parsed.imports.size
        val sorted = removed.sortedBy { it.start }
        return sorted.mapIndexed { index, import ->
            val last = index == sorted.lastIndex
            val end = if (removeAll && last && import === parsed.imports.last()) collapseBlankLine(text, import.end) else import.end
            TextEdit(import.start, end, "")
        }
    }

    private fun collapseBlankLine(text: String, offset: Int): Int = when {
        text.startsWith("\r\n", offset) -> offset + 2
        text.startsWith("\n", offset) -> offset + 1
        else -> offset
    }

    private fun declaredInFile(text: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        val declaration = Regex("""\b(?:class|interface|object|typealias|val|var)\s+`?$escaped`?(?![\w`])""")
        if (declaration.containsMatchIn(text)) return true
        val function = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w`]+(?:\.[\w`]+)*\.)?`?$escaped`?\s*[(<]""")
        return function.containsMatchIn(text)
    }

    private fun packageOf(fqn: String): String? = fqn.substringBeforeLast('.', "").ifEmpty { null }

    private fun starPackage(path: String): String =
        path.removeSuffix("*").removeSuffix(".").split('.').joinToString(".") { unquoteIdentifier(it) }

    private companion object {
        const val MAX_CANDIDATES = 6

        val ALWAYS_USED: Set<String> = setOf(
            "getValue", "setValue", "provideDelegate", "get", "set", "invoke", "contains", "iterator",
            "next", "hasNext", "compareTo", "equals", "plus", "minus", "times", "div", "rem", "rangeTo",
            "rangeUntil", "unaryPlus", "unaryMinus", "not", "inc", "dec", "plusAssign", "minusAssign",
            "timesAssign", "divAssign", "remAssign",
            "component1", "component2", "component3", "component4", "component5", "component6",
            "component7", "component8", "component9",
        )
    }
}
