package stride.app.ui.toolwindow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import stride.app.keys.KeyContext
import stride.app.ui.theme.CapsuleEdge
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.focusHalo
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.glassPanel
import stride.app.ui.theme.glassSlabRims
import stride.app.ui.theme.rememberNeonAlpha
import stride.app.ui.theme.slabShadow

/** Bottom panel: a 28 dp tab strip over the active window's content. */
@Composable
fun ToolWindowPanel(
    manager: ToolWindowManager,
    onFocusContext: (KeyContext) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = manager.active()
    val context = active?.context ?: KeyContext.GLOBAL
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(manager.focusRequests) {
        if (manager.focusRequests > 0) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(hasFocus, context) {
        if (hasFocus) onFocusContext(context)
    }

    val shape = RoundedCornerShape(DesignTokens.slabRadiusToolWindow)
    Column(
        modifier
            .slabShadow(shape)
            .glassSlabRims(shape)
            .glassPanel(shape)
            .focusHalo(hasFocus)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                hasFocus = focusState.hasFocus
                onFocusContext(if (focusState.hasFocus) context else KeyContext.GLOBAL)
            }
            .focusable(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            manager.windows.forEach { window ->
                ToolWindowTab(
                    title = window.title,
                    active = window.id == manager.activeId,
                    onClick = { manager.activate(window.id) },
                )
            }
        }
        active?.let { window ->
            window.content(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ToolWindowTab(title: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .glassCapsule(
                shape = RoundedCornerShape(DesignTokens.radiusSmall),
                insetX = 2.dp,
                insetY = 4.dp,
                lineEdge = CapsuleEdge.BOTTOM,
                lineAlpha = rememberNeonAlpha(active),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.space12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (active) DesignTokens.text else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}
