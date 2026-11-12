package stride.app.glass

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stride.app.ui.theme.DesignTokens

/**
 * The title-bar client properties and the merged title row are one switch. [titleBarProperties]
 * is true only while the native path is eligible and no attach has failed; [mergedTitleRow] is
 * true only after a successful attach, so a fallback never leaves a hidden title bar behind.
 */
internal data class ChromePlan(
    val titleBarProperties: Boolean,
    val mergedTitleRow: Boolean,
)

internal fun chromePlan(eligible: Boolean, attached: Boolean, failed: Boolean): ChromePlan =
    ChromePlan(
        titleBarProperties = eligible && !failed,
        mergedTitleRow = eligible && attached && !failed,
    )

/** The top chrome insets of the merged row and of the v1.3.0 layout. */
internal data class ChromeLayout(
    val topInset: Dp,
    val topRowHeight: Dp,
    val sidebarTop: Dp,
    val topBand: Dp,
)

internal fun chromeLayout(mergedTitleRow: Boolean): ChromeLayout {
    val topInset = if (mergedTitleRow) 0.dp else DesignTokens.chromeInset
    val topRowHeight = if (mergedTitleRow) DesignTokens.nativeTitleRowHeight else DesignTokens.capsuleHeight
    val sidebarTop = if (mergedTitleRow) {
        topInset + topRowHeight + DesignTokens.chromeGap
    } else {
        DesignTokens.chromeInset
    }
    return ChromeLayout(topInset, topRowHeight, sidebarTop, topInset + topRowHeight + DesignTokens.chromeGap)
}
