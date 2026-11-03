package stride.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import stride.lang.ColorScheme
import stride.lang.TextAttributes
import stride.lang.TokenSpan

fun textAttributesToSpanStyle(attributes: TextAttributes): SpanStyle = SpanStyle(
    color = attributes.foreground?.let { Color(it.toInt()) } ?: Color.Unspecified,
    background = attributes.background?.let { Color(it.toInt()) } ?: Color.Unspecified,
    fontWeight = if (attributes.bold) FontWeight.Bold else null,
    fontStyle = if (attributes.italic) FontStyle.Italic else null,
)

/** Maps highlighter spans to text styles, falling back along each key's chain through [scheme]. */
fun spanStyles(spans: List<TokenSpan>, scheme: ColorScheme = ColorScheme.DARK): List<AnnotatedString.Range<SpanStyle>> =
    spans.mapNotNull { span ->
        val attributes = scheme.getAttributes(span.key) ?: return@mapNotNull null
        AnnotatedString.Range(textAttributesToSpanStyle(attributes), span.start, span.end)
    }

/** A background-only range, painted over the token styles (search hits, gutter-adjacent marks). */
data class BackgroundSpan(val start: Int, val end: Int, val color: Color)

/**
 * Applies highlight spans without changing offsets, so a [androidx.compose.foundation.text.BasicTextField]
 * keeps its selection and cursor positions.
 */
class HighlightingVisualTransformation(
    private val spans: List<TokenSpan>,
    private val backgroundSpans: List<BackgroundSpan> = emptyList(),
    private val scheme: ColorScheme = ColorScheme.DARK,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        val defaultColor = scheme.getAttributes(stride.lang.TextAttributesKey.DEFAULT_TEXT)?.foreground
        if (defaultColor != null) {
            builder.addStyle(SpanStyle(color = Color(defaultColor.toInt())), 0, text.length)
        }
        for (range in spanStyles(spans, scheme)) {
            builder.addStyle(range.item, range.start, range.end)
        }
        for (span in backgroundSpans) {
            builder.addStyle(SpanStyle(background = span.color), span.start, span.end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is HighlightingVisualTransformation &&
            other.spans == spans &&
            other.backgroundSpans == backgroundSpans

    override fun hashCode(): Int = 31 * spans.hashCode() + backgroundSpans.hashCode()
}
