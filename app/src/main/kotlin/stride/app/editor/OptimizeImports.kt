package stride.app.editor

import stride.lang.FileTypeRegistry
import stride.lang.ImportOptimizer
import stride.lang.LanguageFileType
import stride.lang.TextEdit

/** Optimizes the editor's imports; returns the status line to show. */
fun optimizeImports(editor: TextFileEditor): String {
    if (editor.isReadOnly) return "File is read-only"
    val language = (FileTypeRegistry.getFileType(editor.file.name) as? LanguageFileType)?.language
    val optimizer = language?.let { ImportOptimizer.forLanguage(it) }
        ?: return "Optimize Imports is not available for this file"
    val text = editor.value.text
    val edits = optimizer.optimize(text)
    if (edits.isEmpty()) return "Imports are already optimized"
    if (!editor.applyEdits(edits)) return "Optimize Imports could not apply its edits"
    val removed = removedLines(text, edits)
    if (removed == 0) return "Imports optimized"
    return if (removed == 1) "Removed 1 import" else "Removed $removed imports"
}

/** Lines the [edits] take out of [text], so a rewrite that only reorders them counts none. */
private fun removedLines(text: String, edits: List<TextEdit>): Int = edits.sumOf { edit ->
    nonBlankLines(text.substring(edit.start, edit.end)) - nonBlankLines(edit.replacement)
}

private fun nonBlankLines(text: String): Int = text.lines().count { it.isNotBlank() }
