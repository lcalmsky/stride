package stride.app.actions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * A4/A5: reports a right press — the secondary button, or the primary button with ⌃ held — at
 * [onRequest] in root coordinates. The press is consumed in the initial pass, so the surface's
 * own primary-press behaviour (selection toggle, open, caret move) never sees it.
 */
fun Modifier.contextMenuRequest(onRequest: (Offset) -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onRequest)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coordinates = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val secondary = event.buttons.isSecondaryPressed
                    val controlPress = event.buttons.isPrimaryPressed &&
                        event.keyboardModifiers.isCtrlPressed
                    if (!secondary && !controlPress) continue
                    val local = event.changes.firstOrNull()?.position ?: continue
                    val root = coordinates?.localToRoot(local) ?: local
                    event.changes.forEach { it.consume() }
                    current(root)
                }
            }
        }
}
