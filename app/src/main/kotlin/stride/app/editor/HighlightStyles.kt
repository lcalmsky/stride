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
 * Applies highlight spans and, when [folds] is not empty, replaces each collapsed range with
 * the fold placeholder and maps the field's offsets through it. Without folds the text and the
 * identity mapping are the ones the field had before folding existed.
 */
class HighlightingVisualTransformation internal constructor(
    private val spans: List<TokenSpan>,
    private val backgroundSpans: List<BackgroundSpan>,
    private val scheme: ColorScheme,
    private val folds: FoldView,
    private val placeholderStyle: SpanStyle,
) : VisualTransformation {
    constructor(
        spans: List<TokenSpan>,
        backgroundSpans: List<BackgroundSpan> = emptyList(),
        scheme: ColorScheme = activeColorScheme(),
    ) : this(spans, backgroundSpans, scheme, FoldView.NONE, SpanStyle())

    override fun filter(text: AnnotatedString): TransformedText {
        if (folds.isEmpty || text.length != folds.docLength) return identity(text)
        val folded = foldedText(text)
        val builder = AnnotatedString.Builder(folded)
        val defaultColor = scheme.getAttributes(stride.lang.TextAttributesKey.DEFAULT_TEXT)?.foreground
        if (defaultColor != null) {
            builder.addStyle(SpanStyle(color = Color(defaultColor.toInt())), 0, folded.length)
        }
        for (range in spanStyles(spans, scheme)) {
            val start = mappedStart(range.start)
            val end = mappedEnd(range.end)
            if (start < end) builder.addMappedStyle(range.item, start, end)
        }
        for (span in backgroundSpans) {
            val start = mappedStart(span.start)
            val end = mappedEnd(span.end)
            if (start < end) builder.addMappedStyle(SpanStyle(background = span.color), start, end)
        }
        for (index in folds.folds.indices) {
            val start = folds.placeholderStart(index)
            builder.addStyle(placeholderStyle, start, start + FOLD_PLACEHOLDER.length)
        }
        return TransformedText(builder.toAnnotatedString(), folds)
    }

    private fun identity(text: AnnotatedString): TransformedText {
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

    private fun foldedText(text: AnnotatedString): AnnotatedString {
        val builder = StringBuilder(folds.viewLength)
        var cursor = 0
        for (fold in folds.folds) {
            builder.append(text.text, cursor, fold.hiddenStart)
            builder.append(FOLD_PLACEHOLDER)
            cursor = fold.hiddenEnd
        }
        builder.append(text.text, cursor, text.length)
        return AnnotatedString(builder.toString())
    }

    private fun mappedStart(offset: Int): Int {
        val clamped = offset.coerceIn(0, folds.docLength)
        val index = folds.foldIndexContaining(clamped)
        if (index >= 0) return folds.placeholderStart(index) + FOLD_PLACEHOLDER.length
        return folds.toView(clamped)
    }

    private fun mappedEnd(offset: Int): Int {
        val clamped = offset.coerceIn(0, folds.docLength)
        val index = folds.foldIndexEndingAt(clamped)
        if (index >= 0) return folds.placeholderStart(index)
        return folds.toView(clamped)
    }

    /** Adds [style] in the pieces of `[start, end)` that fall outside every placeholder. */
    private fun AnnotatedString.Builder.addMappedStyle(style: SpanStyle, start: Int, end: Int) {
        var cursor = start
        var index = folds.firstPlaceholderAtOrAfter(start)
        while (index < folds.folds.size) {
            val placeholderStart = folds.placeholderStart(index)
            val placeholderEnd = placeholderStart + FOLD_PLACEHOLDER.length
            if (placeholderEnd > end) break
            if (cursor < placeholderStart) addStyle(style, cursor, placeholderStart)
            cursor = placeholderEnd
            index++
        }
        if (cursor < end) addStyle(style, cursor, end)
    }

    override fun equals(other: Any?): Boolean =
        other is HighlightingVisualTransformation &&
            other.spans == spans &&
            other.backgroundSpans == backgroundSpans &&
            other.scheme === scheme &&
            other.folds === folds &&
            other.placeholderStyle == placeholderStyle

    override fun hashCode(): Int {
        var result = 31 * spans.hashCode() + backgroundSpans.hashCode()
        result = 31 * result + scheme.hashCode()
        result = 31 * result + folds.hashCode()
        return 31 * result + placeholderStyle.hashCode()
    }
}
