package stride.app.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.ui.CloseIcon
import stride.app.ui.theme.CapsuleEdge
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.selectionRow

private val consoleStyle = TextStyle(
    fontFamily = DesignTokens.codeFont,
    fontSize = DesignTokens.codeTextSize,
    lineHeight = DesignTokens.codeLineHeight,
    color = DesignTokens.text,
)

/** The run tool window: a header, the test tree and the console, side by side. */
@Composable
fun RunToolWindow(manager: RunManager, modifier: Modifier = Modifier, focusRequests: Int = 0) {
    val session = manager.current
    if (session == null) {
        Text(
            "Run — no configuration yet",
            color = DesignTokens.muted,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = modifier.padding(DesignTokens.space12),
        )
        return
    }

    val focusRequester = remember { FocusRequester() }
    var selected by remember(session) { mutableStateOf<TestMethodNode?>(null) }
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        RunTabs(manager, session)
        RunHeader(session)
        if (session.configuration.kind == RunKind.TEST) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                TestTreePane(session, selected, onSelect = { selected = it }, Modifier.weight(0.36f).fillMaxHeight())
                ConsolePane(session, selected, Modifier.weight(0.64f).fillMaxHeight())
            }
        } else {
            ConsolePane(session, null, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * The tab strip is hidden while the only session is still running, so a single run looks the
 * same as before; a finished last session keeps its × so it can be closed.
 */
internal fun showRunTabs(sessions: List<RunSession>): Boolean =
    sessions.size > 1 || sessions.any { !it.isRunning }

/** One tab per concurrently existing configuration; hidden while the only session is running. */
@Composable
private fun RunTabs(manager: RunManager, current: RunSession) {
    if (!showRunTabs(manager.sessions)) return
    Row(Modifier.fillMaxWidth().height(DesignTokens.tabStripHeight)) {
        for (session in manager.sessions) {
            val active = session === current
            Row(
                Modifier
                    .fillMaxHeight()
                    .glassCapsule(
                        shape = RoundedCornerShape(DesignTokens.radiusSmall),
                        insetX = 2.dp,
                        insetY = 4.dp,
                        lineEdge = CapsuleEdge.BOTTOM,
                        lineAlpha = if (active) 1f else 0f,
                    )
                    .clickable { manager.select(session) }
                    .padding(start = DesignTokens.space8, end = DesignTokens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    session.configuration.name,
                    color = if (active) DesignTokens.text else DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(DesignTokens.space4))
                Box(
                    Modifier
                        .size(16.dp)
                        .clickable { manager.close(session) },
                    contentAlignment = Alignment.Center,
                ) {
                    CloseIcon(Modifier.size(9.dp))
                }
            }
        }
    }
}

@Composable
private fun RunHeader(session: RunSession) {
    val tree = session.tree
    val status = statusText(session)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space12, vertical = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.configuration.name,
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            status,
            color = when {
                session.isRunning -> DesignTokens.accent2
                tree.failed > 0 -> DesignTokens.neonPink
                tree.passed > 0 -> DesignTokens.neonLime
                else -> DesignTokens.muted
            },
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
        )
    }
}

@Composable
private fun TestTreePane(
    session: RunSession,
    selected: TestMethodNode?,
    onSelect: (TestMethodNode) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier) {
        for (classNode in session.tree.classes) {
            item(key = "class:${classNode.className}") {
                ClassRow(classNode)
            }
            items(classNode.methods, key = { method -> "method:${method.className}.${method.name}" }) { method ->
                MethodRow(method, method === selected, onClick = { onSelect(method) })
            }
        }
    }
}

@Composable
private fun ClassRow(node: TestClassNode) {
    val color = statusColor(node.status)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space8, vertical = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(node.status, color)
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            node.className,
            color = DesignTokens.text,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MethodRow(method: TestMethodNode, selected: Boolean, onClick: () -> Unit) {
    val color = statusColor(method.status)
    Row(
        Modifier
            .fillMaxWidth()
            .selectionRow(
                selected = selected,
                hovered = false,
            )
            .clickable(onClick = onClick)
            .padding(start = DesignTokens.space16, end = DesignTokens.space8, top = DesignTokens.space4, bottom = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(method.status, color)
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            method.name,
            color = if (selected) DesignTokens.text else DesignTokens.text.copy(alpha = 0.9f),
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusGlyph(status: TestStatus, color: Color) {
    Text(
        statusGlyph(status),
        color = color,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.codeFont,
    )
}

@Composable
private fun ConsolePane(session: RunSession, selected: TestMethodNode?, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(session.consoleLines.size) {
        if (session.consoleLines.isNotEmpty()) listState.scrollToItem(session.consoleLines.lastIndex)
    }
    Column(modifier.padding(horizontal = DesignTokens.space8, vertical = DesignTokens.space4)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(session.consoleLines) { line ->
                Text(line, style = consoleStyle, modifier = Modifier.fillMaxWidth())
            }
        }
        if (selected != null) {
            MethodDetail(selected)
        }
        if (session.configuration.kind == RunKind.APPLICATION) {
            ConsoleInput(session)
        }
    }
}

const val RUN_INPUT_TAG: String = "run-stdin"

/** Writes Enter-terminated lines to the child's stdin; disabled once the process ends. */
@Composable
private fun ConsoleInput(session: RunSession) {
    var line by remember(session) { mutableStateOf("") }
    val enabled = session.isRunning
    LaunchedEffect(session.undeliveredInput) {
        val undelivered = session.undeliveredInput ?: return@LaunchedEffect
        line = undelivered
        session.clearUndeliveredInput()
    }
    Row(
        Modifier.fillMaxWidth().padding(top = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ">",
            style = consoleStyle.copy(color = if (enabled) DesignTokens.accent2 else DesignTokens.muted),
        )
        Spacer(Modifier.width(DesignTokens.space8))
        BasicTextField(
            value = line,
            onValueChange = { if (enabled) line = it },
            modifier = Modifier
                .weight(1f)
                .testTag(RUN_INPUT_TAG)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (enabled && session.writeInput(line)) line = ""
                                true
                            }
                            else -> false
                        }
                    }
                },
            enabled = enabled,
            singleLine = true,
            textStyle = consoleStyle.copy(color = if (enabled) DesignTokens.text else DesignTokens.muted),
            cursorBrush = SolidColor(if (enabled) DesignTokens.accent2 else DesignTokens.muted),
        )
    }
}

@Composable
private fun MethodDetail(method: TestMethodNode) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 140.dp)
            .verticalScroll(rememberScrollState())
            .padding(DesignTokens.space8),
    ) {
        Text(
            method.name,
            color = statusColor(method.status),
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            fontWeight = FontWeight.SemiBold,
        )
        method.message?.let { message ->
            Text(message, style = consoleStyle.copy(color = DesignTokens.neonPink), modifier = Modifier.padding(top = DesignTokens.space4))
        }
        method.stackTrace?.let { stackTrace ->
            Text(stackTrace, style = consoleStyle.copy(color = DesignTokens.muted), modifier = Modifier.padding(top = DesignTokens.space4))
        }
        if (method.output.isNotEmpty()) {
            Text(
                method.output.joinToString("\n"),
                style = consoleStyle.copy(color = DesignTokens.muted),
                modifier = Modifier.padding(top = DesignTokens.space4),
            )
        }
    }
}


private fun statusText(session: RunSession): String {
    if (session.isRunning) return "Running…"
    val tree = session.tree
    val total = tree.passed + tree.failed + tree.skipped
    return when {
        tree.failed > 0 -> "Failed ${tree.failed} of $total"
        tree.passed > 0 -> "Passed ${tree.passed}"
        else -> "Exit code ${session.exitCode}"
    }
}

private fun statusColor(status: TestStatus): Color = when (status) {
    TestStatus.PASSED -> DesignTokens.neonLime
    TestStatus.FAILED -> DesignTokens.neonPink
    TestStatus.RUNNING -> DesignTokens.accent2
    TestStatus.SKIPPED -> DesignTokens.muted
}

private fun statusGlyph(status: TestStatus): String = when (status) {
    TestStatus.PASSED -> "✓"
    TestStatus.FAILED -> "✕"
    TestStatus.RUNNING -> "●"
    TestStatus.SKIPPED -> "●"
}
