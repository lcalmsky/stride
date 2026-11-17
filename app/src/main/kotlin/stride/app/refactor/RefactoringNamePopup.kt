package stride.app.refactor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel

private val refactoringNamePopupWidth = 320.dp

/** Test tags the UI tests find the refactoring name popup by. */
const val REFACTORING_NAME_POPUP_TAG: String = "refactoring-name-popup"
const val REFACTORING_NAME_FIELD_TAG: String = "refactoring-name-field"
const val REFACTORING_NAME_ERROR_TAG: String = "refactoring-name-error"

/**
 * The refactoring name popup: the step's [title], a field with [suggested] selected and, when the
 * last name was rejected, its [error] in the error color. Enter submits, Escape cancels.
 */
@Composable
fun RefactoringNamePopup(
    title: String,
    suggested: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by remember(suggested) {
        mutableStateOf(TextFieldValue(suggested, TextRange(0, suggested.length)))
    }
    var emptyHint by remember(suggested) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(suggested) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(REFACTORING_NAME_POPUP_TAG)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(refactoringNamePopupWidth)
                .popupPanel("popup-refactoring-name", shape)
                .then(contentFade)
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.Escape -> {
                                onClose()
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                if (input.text.isEmpty()) {
                                    emptyHint = true
                                } else {
                                    onSubmit(input.text)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Text(
                    title,
                    color = DesignTokens.text,
                    fontSize = DesignTokens.labelTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(
                        horizontal = DesignTokens.space16,
                        vertical = DesignTokens.space8,
                    ),
                )
                Box(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = 6.dp)) {
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            emptyHint = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag(REFACTORING_NAME_FIELD_TAG),
                        singleLine = true,
                        textStyle = DesignTokens.chromeTextStyle.merge(
                            TextStyle(
                                color = DesignTokens.text,
                                fontSize = DesignTokens.uiTextSize,
                                fontFamily = DesignTokens.uiFont,
                            ),
                        ),
                        cursorBrush = SolidColor(DesignTokens.accent),
                    )
                }
                if (error != null) {
                    Text(
                        error,
                        color = DesignTokens.errorOnPopupPlate,
                        fontSize = DesignTokens.labelTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier
                            .padding(horizontal = DesignTokens.space16)
                            .testTag(REFACTORING_NAME_ERROR_TAG),
                    )
                }
                if (emptyHint) {
                    Text(
                        "Type a name",
                        color = DesignTokens.neonAmber,
                        fontSize = DesignTokens.labelTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16),
                    )
                }
                PopupHintBar("↵ Apply", "Esc Cancel")
            }
        }
    }
}
