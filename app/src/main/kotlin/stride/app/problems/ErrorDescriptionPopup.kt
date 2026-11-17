package stride.app.problems

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import stride.app.editor.TextFileEditor
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.lang.ProblemSeverity

/** The open error description: the editor the caret was in and the problems it sits in. */
data class ErrorDescriptionRequest(val editor: TextFileEditor, val problems: List<FileProblem>)

const val ERROR_DESCRIPTION_POPUP_TAG: String = "error-description-popup"

private val errorDescriptionWidth = 520.dp
private val errorDescriptionMaxHeight = 420.dp

/**
 * Show Error Description: one block per problem at the caret with its severity, source, message
 * and position. It takes no scrim and closes on Esc, ⌘F1, ↵ or a click outside.
 */
@Composable
fun ErrorDescriptionPopup(request: ErrorDescriptionRequest, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(errorDescriptionWidth)
                .popupPanel("popup-error-description", shape)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(ERROR_DESCRIPTION_POPUP_TAG)
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when {
                            event.key == Key.Escape -> {
                                onClose()
                                true
                            }
                            event.key == Key.F1 && (event.isMetaPressed || event.isCtrlPressed) -> {
                                onClose()
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                onClose()
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Box(Modifier.fillMaxWidth().heightIn(max = errorDescriptionMaxHeight)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        request.problems.forEachIndexed { index, problem ->
                            if (index > 0) ErrorDivider()
                            ErrorEntry(problem)
                        }
                    }
                }
                PopupHintBar("Esc Close")
            }
        }
    }
}

@Composable
private fun ErrorEntry(problem: FileProblem) {
    val error = problem.severity == ProblemSeverity.ERROR
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space4),
    ) {
        Row {
            Text(
                if (error) "Error" else "Warning",
                color = if (error) DesignTokens.errorOnPopupPlate else DesignTokens.neonAmber,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
            if (problem.source == ProblemSource.BUILD) {
                Spacer(Modifier.width(DesignTokens.space8))
                Text(
                    "Build",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
            }
        }
        Text(
            problem.message,
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        Text(
            "Ln ${problem.line + 1}, Col ${problem.column + 1}",
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun ErrorDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16).height(1.dp).background(DesignTokens.glassBorderSide))
}
