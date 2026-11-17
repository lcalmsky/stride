package stride.app.problems

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import stride.app.editor.TextFileEditor
import stride.app.glass.nativeGlass
import stride.app.keys.ActionId
import stride.app.keys.Keymap
import stride.app.keys.shortcutText
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.rememberSelectionAlpha
import stride.app.ui.toolwindow.ListToolWindow
import stride.app.ui.toolwindow.ListWindowRow
import stride.app.ui.toolwindow.ToolWindowEmptyState
import stride.lang.ProblemSeverity

/** Which list the Problems window shows; the last choice is kept in [ProblemsToolWindowState]. */
enum class ProblemScope { FILE, PROJECT_ERRORS }

/** The Problems window's range tab, which survives a hidden window. */
class ProblemsToolWindowState {
    var scope: ProblemScope by mutableStateOf(ProblemScope.FILE)

    fun selectNext() {
        scope = when (scope) {
            ProblemScope.FILE -> ProblemScope.PROJECT_ERRORS
            ProblemScope.PROJECT_ERRORS -> ProblemScope.FILE
        }
    }

    fun selectPrevious() = selectNext()

    fun reset() {
        scope = ProblemScope.FILE
    }
}

/**
 * The Problems tool window: the selected file's problems and the project's errors. A row opens
 * its problem; the window stays visible.
 */
@Composable
fun ProblemsToolWindow(
    editor: TextFileEditor?,
    model: ProblemsModel?,
    toolState: ProblemsToolWindowState,
    focusRequests: Int,
    keymap: Keymap,
    root: Path?,
    isOpen: (Path) -> Boolean,
    onOpenEditorProblem: (FileProblem) -> Unit,
    onOpenFileProblem: (Path, FileProblem) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        ToolWindowEmptyState("No file selected", modifier, focusRequests = focusRequests, onEscape = onEscape)
        return
    }
    val fileProblems = editor?.problems.orEmpty()
    val projectErrors = model.projectErrors(isOpen)
    val projectErrorCount = projectErrors.sumOf { it.errors.size }
    Column(modifier) {
        ScopeTabs(
            scope = toolState.scope,
            fileCount = fileProblems.size,
            projectErrorCount = projectErrorCount,
            onSelect = { toolState.scope = it },
        )
        when (toolState.scope) {
            ProblemScope.FILE -> {
                if (editor == null) {
                    ToolWindowEmptyState("No file selected", Modifier.fillMaxWidth().weight(1f), focusRequests, onEscape)
                } else if (fileProblems.isEmpty()) {
                    ToolWindowEmptyState(
                        "No problems in ${editor.file.name}",
                        Modifier.fillMaxWidth().weight(1f),
                        focusRequests,
                        onEscape,
                    )
                } else {
                    ListToolWindow(
                        rows = fileProblems.map(::problemRow),
                        focusRequests = focusRequests,
                        onOpen = { index -> fileProblems.getOrNull(index)?.let(onOpenEditorProblem) },
                        onEscape = onEscape,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
            ProblemScope.PROJECT_ERRORS -> {
                if (projectErrors.isEmpty()) {
                    ToolWindowEmptyState(
                        "No errors · ${shortcutText(keymap, ActionId.BUILD_PROJECT)} Build Project",
                        Modifier.fillMaxWidth().weight(1f),
                        focusRequests,
                        onEscape,
                    )
                } else {
                    val rows = ArrayList<ListWindowRow>()
                    val opens = ArrayList<() -> Unit>()
                    for (file in projectErrors) {
                        rows += fileRow(file.path, file.errors.size, root)
                        opens += { onOpenFileProblem(file.path, file.errors.first()) }
                        for (error in file.errors) {
                            rows += problemRow(error, indent = 1)
                            opens += { onOpenFileProblem(file.path, error) }
                        }
                    }
                    ListToolWindow(
                        rows = rows,
                        focusRequests = focusRequests,
                        onOpen = { index -> opens.getOrNull(index)?.invoke() },
                        onEscape = onEscape,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeTabs(
    scope: ProblemScope,
    fileCount: Int,
    projectErrorCount: Int,
    onSelect: (ProblemScope) -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(DesignTokens.capsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass("problems-tabs", shape, tint = DesignTokens.nativeGlassTint),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            ScopeTab("File $fileCount", scope == ProblemScope.FILE) { onSelect(ProblemScope.FILE) }
            ScopeTab("Project Errors $projectErrorCount", scope == ProblemScope.PROJECT_ERRORS) {
                onSelect(ProblemScope.PROJECT_ERRORS)
            }
        }
    }
}

@Composable
private fun ScopeTab(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val pill = rememberSelectionAlpha(active)
    Row(
        Modifier
            .fillMaxHeight()
            .glassCapsule(shape = shape, insetX = 2.dp, insetY = 3.dp, selectionAlpha = pill)
            .clickable(onClick = onClick)
            .padding(start = DesignTokens.space8, end = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun problemRow(problem: FileProblem, indent: Int = 0): ListWindowRow {
    val symbol = if (problem.severity == ProblemSeverity.ERROR) "● " else "▲ "
    val color = if (problem.severity == ProblemSeverity.ERROR) {
        DesignTokens.neonPinkOnGlass
    } else {
        DesignTokens.neonAmberOnGlass
    }
    val primary = buildAnnotatedString {
        withStyle(SpanStyle(color = color)) { append(symbol) }
        append(problem.message)
    }
    val secondary = "${problem.line + 1}:${problem.column + 1}" +
        if (problem.source == ProblemSource.BUILD) " · build" else ""
    return ListWindowRow(
        primary = primary,
        secondary = AnnotatedString(secondary),
        indent = indent,
    )
}

private fun fileRow(path: Path, errors: Int, root: Path?): ListWindowRow {
    val relative = root?.let { runCatching { it.relativize(path).toString().replace('\\', '/') }.getOrNull() }
        ?: path.toString()
    return ListWindowRow(
        primary = AnnotatedString(relative),
        secondary = AnnotatedString(if (errors == 1) "1 error" else "$errors errors"),
        iconName = path.fileName?.toString(),
    )
}

