package stride.app.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import stride.app.ui.theme.DesignTokens

/** The hit's line with the matched characters highlighted, shared by the popup and the Find window. */
fun hitLine(hit: FindInPathHit): AnnotatedString = buildAnnotatedString {
    append(hit.lineText)
    val from = (hit.column - 1).coerceIn(0, hit.lineText.length)
    val to = (from + (hit.end - hit.start)).coerceIn(from, hit.lineText.length)
    if (to > from) {
        addStyle(SpanStyle(color = DesignTokens.neonAmber, background = DesignTokens.neonAmberGlow), from, to)
    }
}
