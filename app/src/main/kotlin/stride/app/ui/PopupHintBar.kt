package stride.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import stride.app.ui.theme.DesignTokens

/**
 * The footer every popup shares: keycap glyphs followed by sentence-case words, separated by
 * spacing only.
 */
@Composable
fun PopupHintBar(vararg hints: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupFooterHeight)
            .drawBehind {
                drawLine(DesignTokens.glassBorderSide, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
            }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEachIndexed { index, hint ->
            if (index > 0) Spacer(Modifier.width(20.dp))
            Text(
                hint,
                color = DesignTokens.textOnBusyGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}
