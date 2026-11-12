package stride.app.ui.toolwindow

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.glass.nativeGlass
import stride.app.keys.KeyContext
import stride.app.ui.theme.CapsuleEdge
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.contentClip
import stride.app.ui.theme.contentTint
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.rememberNeonAlpha

/**
 * Bottom panel: edge-to-edge content with one glass capsule group floating over it. The group
 * holds the Run and Terminal switcher; the active window keeps its own controls inside.
 */
@Composable
fun ToolWindowPanel(
    manager: ToolWindowManager,
    onFocusContext: (KeyContext) -> Unit,
    modifier: Modifier = Modifier,
    contentInset: Dp = 0.dp,
    contentBottomInset: Dp = 0.dp,
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

    val switcherShape = RoundedCornerShape(percent = 50)
    val nativeGlass = LocalNativeGlass.current
    val holes = if (nativeGlass) GlassHoles.snapshot() else emptyList()
    val surfaceShape = RoundedCornerShape(DesignTokens.contentSurfaceRadius)
    val contentTop = DesignTokens.chromeGap + DesignTokens.capsuleHeight + DesignTokens.chromeGap
    Box(
        modifier
            .then(if (nativeGlass) Modifier else Modifier.contentTint())
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                hasFocus = focusState.hasFocus
                onFocusContext(if (focusState.hasFocus) context else KeyContext.GLOBAL)
            }
            .focusable(),
    ) {
        active?.let { window ->
            if (nativeGlass) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = contentInset,
                            end = DesignTokens.chromeInset,
                            top = contentTop,
                            bottom = contentBottomInset,
                        )
                        .nativeGlass("tool-window-surface", surfaceShape, tint = DesignTokens.editorGlassTint)
                        .contentClip(surfaceShape, holes),
                ) {
                    window.content(Modifier.fillMaxSize())
                }
            } else {
                window.content(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = contentInset,
                            top = contentTop,
                            bottom = contentBottomInset,
                        ),
                )
            }
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = contentInset, y = DesignTokens.chromeGap)
                .height(DesignTokens.capsuleHeight)
                .capsuleGlass(switcherShape)
                .nativeGlass("tool-switcher", switcherShape, tint = DesignTokens.nativeGlassTint)
                .panelPressBlocker()
                .padding(horizontal = DesignTokens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                manager.windows.forEach { window ->
                    ToolWindowTab(
                        title = window.title,
                        active = window.id == manager.activeId,
                        onClick = { manager.activate(window.id) },
                    )
                }
            }
        }
    }
}

/** Keeps a press on the floating switcher from reaching the panel content under it. */
private fun Modifier.panelPressBlocker(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

@Composable
private fun ToolWindowTab(title: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .glassCapsule(
                shape = RoundedCornerShape(percent = 50),
                insetX = 2.dp,
                insetY = 3.dp,
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
