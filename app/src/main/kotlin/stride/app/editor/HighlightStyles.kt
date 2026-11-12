package stride.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.editorGlassFloor
import stride.app.ui.theme.paletteOf
import stride.app.ui.theme.readableOn
import stride.lang.ColorScheme
import stride.lang.TextAttributes
import stride.lang.TokenSpan

/** The syntax scheme of the effective appearance. */
fun activeColorScheme(): ColorScheme =
    if (AppearanceState.appearance == Appearance.LIGHT) ColorScheme.LIGHT else ColorScheme.DARK

/**
 * The syntax scheme over the native editor glass. Code keeps 4.5:1 on the worst plate the glass
 * can composite ([editorGlassFloor]): the tint over a white or a black desktop, under the
 * selection, current line or a find match. Only tokens that fall short move, toward black or
 * white by the smallest step that clears the ratio; the Skia path keeps [activeColorScheme].
 */
fun glassColorScheme(appearance: Appearance = AppearanceState.appearance): ColorScheme {
    val base = if (appearance == Appearance.LIGHT) ColorScheme.LIGHT else ColorScheme.DARK
    val floor = editorGlassFloor(paletteOf(appearance))
    val attributes = base.attributes().mapValues { (_, values) ->
        val background = values.background
        val plate = background?.let { Color(it.toInt()) } ?: floor
        val foreground = values.foreground?.let { readableOn(Color(it.toInt()), plate) }
        TextAttributes(
            foreground = foreground?.toArgb()?.toUInt()?.toLong(),
            background = background,
            bold = values.bold,
            italic = values.italic,
        )
    }
    return ColorScheme("${base.name} Glass", attributes)
}

/** Gutter line numbers over the native editor glass. */
fun glassGutterColor(appearance: Appearance = AppearanceState.appearance): Color =
    readableOn(paletteOf(appearance).muted, editorGlassFloor(paletteOf(appearance)))

fun textAttributesToSpanStyle(attributes: TextAttributes): SpanStyle = SpanStyle(
    color = attributes.foreground?.let { Color(it.toInt()) } ?: Color.Unspecified,
    background = attributes.background?.let { Color(it.toInt()) } ?: Color.Unspecified,
    fontWeight = if (attributes.bold) FontWeight.Bold else null,
    fontStyle = if (attributes.italic) FontStyle.Italic else null,
)

/** Maps highlighter spans to text styles, falling back along each key's chain through [scheme]. */
fun spanStyles(spans: List<TokenSpan>, scheme: ColorScheme = activeColorScheme()): List<AnnotatedString.Range<SpanStyle>> =
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
    private val scheme: ColorScheme = activeColorScheme(),
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
            other.backgroundSpans == backgroundSpans &&
            other.scheme === scheme

    override fun hashCode(): Int = 31 * (31 * spans.hashCode() + backgroundSpans.hashCode()) + scheme.hashCode()
}
