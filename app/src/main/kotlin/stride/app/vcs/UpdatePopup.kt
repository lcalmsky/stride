package stride.app.vcs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.AppState
import stride.app.search.ReplaceTextButton
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel

/** The update confirmation's contents: the plan the service computed for the current branch. */
data class UpdateRequest(val plan: UpdatePlan.Ready)

private val updateWidth = 560.dp
private val updateListMaxHeight = 280.dp

/**
 * The update confirmation: where the branch goes, the commits that come in, the strategy `Tab`
 * switches and the editors an update will save first. `↩` updates, Esc cancels; a click outside
 * cancels too.
 */
@Composable
fun UpdatePopup(request: UpdateRequest, state: AppState, onClose: () -> Unit) {
    val plan = request.plan
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    var strategy by remember(request) { mutableStateOf(plan.defaultStrategy) }
    var fired by remember { mutableStateOf(false) }
    val confirm = {
        if (!fired) {
            fired = true
            onClose()
            runUpdate(state, plan, strategy)
        }
    }
    val switchStrategy = {
        val index = plan.strategies.indexOf(strategy)
        if (plan.strategies.size > 1 && index >= 0) {
            strategy = plan.strategies[(index + 1) % plan.strategies.size]
        }
    }
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
                .width(updateWidth)
                .popupPanel("popup-update", shape)
                .focusRequester(focusRequester)
                .focusable()
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
                            Key.Tab -> {
                                switchStrategy()
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                confirm()
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = DesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Update Project",
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${plan.branch} ← ${plan.remote}/${shortRef(plan.remoteRef)}",
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.codeFont,
                    )
                }
                IncomingCommits(plan)
                if (plan.ahead > 0) {
                    Text(
                        "${plan.ahead} local commits",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.smallTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
                    )
                }
                StrategyRow(plan, strategy)
                if (plan.unsavedEditors > 0) {
                    Text(
                        "Saves ${plan.unsavedEditors} unsaved editors first",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.smallTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space8, vertical = DesignTokens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    ReplaceTextButton("Update", onClick = confirm)
                    ReplaceTextButton("Cancel", onClick = onClose)
                }
                val hints = buildList {
                    add("↵ Update")
                    if (plan.strategies.size > 1) add("Tab Merge/Rebase")
                    add("Esc Close")
                }
                PopupHintBar(*hints.toTypedArray())
            }
        }
    }
}

@Composable
private fun IncomingCommits(plan: UpdatePlan.Ready) {
    if (plan.incoming.isEmpty()) return
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = updateListMaxHeight)
            .padding(top = DesignTokens.space8),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (entry in plan.incoming) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.popupRowHeight)
                        .padding(horizontal = DesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.shortId,
                        color = DesignTokens.text,
                        fontSize = DesignTokens.codeTextSize,
                        fontFamily = DesignTokens.codeFont,
                    )
                    Spacer(Modifier.width(DesignTokens.space12))
                    Text(
                        entry.subject,
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(DesignTokens.space12))
                    Text(
                        entry.author,
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.smallTextSize,
                        fontFamily = DesignTokens.uiFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (plan.moreIncoming > 0) {
                Text(
                    "… ${plan.moreIncoming} more",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
                )
            }
        }
    }
}

@Composable
private fun StrategyRow(plan: UpdatePlan.Ready, chosen: UpdateStrategy) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val single = plan.strategies.singleOrNull()
        if (single != null) {
            Text(
                strategyLabel(single),
                color = DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
            )
        } else {
            for (option in plan.strategies) {
                StrategyCell(strategyLabel(option), selected = option == chosen)
                Spacer(Modifier.width(DesignTokens.space8))
            }
        }
    }
}

@Composable
private fun StrategyCell(label: String, selected: Boolean) {
    Row(
        Modifier.height(DesignTokens.popupRowHeight).padding(horizontal = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) DesignTokens.accentOnPopupPlate else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
        )
    }
}

private fun strategyLabel(strategy: UpdateStrategy): String = when (strategy) {
    UpdateStrategy.FAST_FORWARD -> "Fast-forward"
    UpdateStrategy.MERGE -> "Merge"
    UpdateStrategy.REBASE -> "Rebase"
}
