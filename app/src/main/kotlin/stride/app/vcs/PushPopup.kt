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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
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

/** The push confirmation's contents: the plan the service computed for the current branch. */
data class PushRequest(val plan: PushPlan.Ready)

private val pushWidth = 560.dp
private val pushListMaxHeight = 280.dp

/**
 * The push confirmation: where the branch goes, the commits that go with it and the refusal a
 * behind branch will meet. `↩` pushes, Esc cancels; a click outside cancels too.
 */
@Composable
fun PushPopup(request: PushRequest, state: AppState, onClose: () -> Unit) {
    val plan = request.plan
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
                .width(pushWidth)
                .popupPanel("popup-push", shape)
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
                            Key.Enter, Key.NumPadEnter -> {
                                push(state, plan, onClose)
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
                        "Push",
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
                        "${plan.branch} → ${plan.remote}/${shortRef(plan.remoteRef)}",
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.codeFont,
                    )
                    if (plan.newBranch) {
                        Spacer(Modifier.width(DesignTokens.space8))
                        Text(
                            "new branch",
                            color = DesignTokens.accent2,
                            fontSize = DesignTokens.smallTextSize,
                            fontFamily = DesignTokens.uiFont,
                        )
                    }
                }
                OutgoingCommits(plan)
                if (plan.behind > 0) {
                    Text(
                        "The remote has ${plan.behind} commits you don't have; the push will be rejected",
                        color = DesignTokens.neonAmber,
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
                    ReplaceTextButton("Push", onClick = { push(state, plan, onClose) })
                    ReplaceTextButton("Cancel", onClick = onClose)
                }
                PopupHintBar("↵ Push", "Esc Close")
            }
        }
    }
}

@Composable
private fun OutgoingCommits(plan: PushPlan.Ready) {
    if (plan.outgoing.isEmpty()) return
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = pushListMaxHeight)
            .padding(top = DesignTokens.space8),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (entry in plan.outgoing) {
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
            if (plan.moreOutgoing > 0) {
                Text(
                    "… ${plan.moreOutgoing} more",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
                )
            }
        }
    }
}

private fun push(state: AppState, plan: PushPlan.Ready, onClose: () -> Unit) {
    onClose()
    val git = state.session?.git ?: return
    git.launch {
        val result = git.push(plan)
        Snapshot.withMutableSnapshot { state.statusMessage = result.message }
    }
}

private fun shortRef(ref: String): String =
    ref.removePrefix("refs/heads/").removePrefix("refs/tags/").removePrefix("refs/remotes/")
