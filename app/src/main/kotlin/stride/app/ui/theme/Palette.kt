package stride.app.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle

/**
 * The colour half of the design tokens. [DesignTokens] reads the active instance, so every
 * surface, text and accent follows [AppearanceState] without any screen holding a colour of its
 * own. Derived colours (selection, glows, gutter) are computed here so both palettes stay in step.
 */
class Palette(
    val background: Color,
    val backgroundLift: Color,
    val fieldBottom: Color,
    val surface: Color,
    val raised: Color,
    val border: Color,
    val text: Color,
    val selectedText: Color,
    val muted: Color,
    val subtle: Color,
    val accent: Color,
    val accent2: Color,
    val accentHover: Color,
    val onAccent: Color,
    val currentLine: Color,
    val scrim: Color,
    val json: Color,
    val xml: Color,
    val neonPink: Color,
    val neonLime: Color,
    val neonAmber: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassBorderTop: Color,
    val glassBorderSide: Color,
    val glassBorderBottom: Color,
    val capsuleLift: Color,
    val capsuleRim: Color,
    val glassBase: Color,
    val sidebarFill: Color,
    val capsuleFill: Color,
    val slabRimTop: Color,
    val slabRimBottom: Color,
    val slabRimLeft: Color,
    val slabInnerRim: Color,
    val chromeLabelScrim: Color,
    val textOnBusyGlass: Color,
    val textSecondaryOnGlass: Color,
    val nativeGlassTint: Int,
    /** Tint of the editor and tool-window glass pieces; denser than the chrome tint. */
    val editorGlassTint: Int,
    val slabShadowColor: Int,
    val chromeTextShadowColor: Color,
    val scrollbarThumb: Color,
    val selectionAlpha: Float,
    val currentLineGlowAlpha: Float,
    val selectionPillLiftAlpha: Float,
    val selectionPillTintAlpha: Float,
) {
    val selection: Color get() = accent.copy(alpha = selectionAlpha)

    /**
     * The one selection signal: a white lift plus the accent at low alpha, so the pill reads
     * light on light glass and keeps the accent's neon hue on dark glass. The rim comes from
     * [capsuleRim].
     */
    val selectionPillLift: Color get() = Color.White.copy(alpha = selectionPillLiftAlpha)
    val selectionPillTint: Color get() = accent.copy(alpha = selectionPillTintAlpha)
    val gutter: Color get() = surface.copy(alpha = 0.6f)
    val accentGlow: Color get() = accent.copy(alpha = 0.55f)
    val accent2Glow: Color get() = accent2.copy(alpha = 0.55f)
    val neonPinkGlow: Color get() = neonPink.copy(alpha = 0.55f)
    val neonLimeGlow: Color get() = neonLime.copy(alpha = 0.55f)
    val neonAmberGlow: Color get() = neonAmber.copy(alpha = 0.55f)
    val currentLineGlow: Color get() = accent.copy(alpha = currentLineGlowAlpha)

    /** Stable per palette, so a recomposition never hands the tree a new text style. */
    val chromeTextShadow: Shadow = Shadow(
        color = chromeTextShadowColor,
        offset = Offset(0f, 1f),
        blurRadius = 2f,
    )
    val chromeTextStyle: TextStyle = TextStyle(shadow = chromeTextShadow)
}

/** The graphite palette of v1.3.0; kept pixel-identical so the dark snapshots do not move. */
val DarkPalette = Palette(
    background = Color(0xFF13141A),
    backgroundLift = Color(0xFF1A1B23),
    fieldBottom = Color(0xFF0B0C10),
    surface = Color(0xFF0F1015),
    raised = Color(0xFF21232B),
    border = Color(0xFF2F313A),
    text = Color(0xFFE6E8F0),
    selectedText = Color.White,
    muted = Color(0xFF8A93A6),
    subtle = Color(0xFF5A6274),
    accent = Color(0xFF7C5CFF),
    accent2 = Color(0xFF00D1FF),
    accentHover = Color(0xFF9078FF),
    onAccent = Color.White,
    currentLine = Color(0x0AFFFFFF),
    scrim = Color(0x4D000000),
    json = Color(0xFFE5C07B),
    xml = Color(0xFFE06C75),
    neonPink = Color(0xFFFF2E97),
    neonLime = Color(0xFFB6FF3B),
    neonAmber = Color(0xFFFFC83D),
    glassFill = Color.White.copy(alpha = 0.07f),
    glassFillStrong = Color.White.copy(alpha = 0.10f),
    glassBorderTop = Color.White.copy(alpha = 0.10f),
    glassBorderSide = Color.White.copy(alpha = 0.08f),
    glassBorderBottom = Color.White.copy(alpha = 0.06f),
    capsuleLift = Color.White.copy(alpha = 0.07f),
    capsuleRim = Color.White.copy(alpha = 0.16f),
    glassBase = Color(0xFF14151B),
    sidebarFill = Color(0xFF14151B).copy(alpha = 0.30f),
    capsuleFill = Color(0xFF14151B).copy(alpha = 0.58f),
    slabRimTop = Color.White.copy(alpha = 0.22f),
    slabRimBottom = Color.White.copy(alpha = 0.06f),
    slabRimLeft = Color.White.copy(alpha = 0.12f),
    slabInnerRim = Color.White.copy(alpha = 0.06f),
    chromeLabelScrim = Color.Black.copy(alpha = 0.45f),
    textOnBusyGlass = Color(0xFFC4CBDA),
    textSecondaryOnGlass = Color(0xFF919AAC),
    nativeGlassTint = Color(0xFF14151B).copy(alpha = 0.78f).toArgb(),
    editorGlassTint = Color(0xFF14151B).copy(alpha = 0.92f).toArgb(),
    slabShadowColor = 0x8C000000.toInt(),
    chromeTextShadowColor = Color.Black.copy(alpha = 1f),
    scrollbarThumb = Color.White.copy(alpha = 0.14f),
    selectionAlpha = 0.22f,
    currentLineGlowAlpha = 0.10f,
    selectionPillLiftAlpha = 0.10f,
    selectionPillTintAlpha = 0.22f,
)

/**
 * Neutral light surfaces: a white opaque editor panel, translucent grey-white chrome and dark
 * ink. The violet and cyan accents are darkened so text and indicators keep 4.5:1 on white and
 * on the near-white floor the native tint leaves over a dark desktop, and the selection keeps
 * the cyan bar with a restrained glow.
 */
val LightPalette = Palette(
    background = Color(0xFFF3F4F8),
    backgroundLift = Color(0xFFFBFCFE),
    fieldBottom = Color(0xFFE9ECF2),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF0F2F6),
    border = Color(0xFFD2D7E0),
    text = Color(0xFF1D2330),
    selectedText = Color(0xFF10151F),
    muted = Color(0xFF576070),
    subtle = Color(0xFF8A92A2),
    accent = Color(0xFF5B3FD6),
    accent2 = Color(0xFF085683),
    accentHover = Color(0xFF4A32B8),
    onAccent = Color.White,
    currentLine = Color(0x0F000000),
    scrim = Color(0x33000000),
    json = Color(0xFF9A5B00),
    xml = Color(0xFFB3261E),
    neonPink = Color(0xFFBD2269),
    neonLime = Color(0xFF305C00),
    neonAmber = Color(0xFF704900),
    glassFill = Color.Black.copy(alpha = 0.05f),
    glassFillStrong = Color.Black.copy(alpha = 0.08f),
    glassBorderTop = Color.Black.copy(alpha = 0.10f),
    glassBorderSide = Color.Black.copy(alpha = 0.08f),
    glassBorderBottom = Color.Black.copy(alpha = 0.06f),
    capsuleLift = Color.White.copy(alpha = 0.55f),
    capsuleRim = Color.Black.copy(alpha = 0.14f),
    glassBase = Color(0xFFF6F7FA),
    sidebarFill = Color(0xFFF2F4F8).copy(alpha = 0.62f),
    capsuleFill = Color.White.copy(alpha = 0.62f),
    slabRimTop = Color.Black.copy(alpha = 0.10f),
    slabRimBottom = Color.Black.copy(alpha = 0.04f),
    slabRimLeft = Color.Black.copy(alpha = 0.06f),
    slabInnerRim = Color.Black.copy(alpha = 0.03f),
    chromeLabelScrim = Color.White.copy(alpha = 0.72f),
    textOnBusyGlass = Color(0xFF4C5564),
    textSecondaryOnGlass = Color(0xFF4A5261),
    nativeGlassTint = Color(0xFFF5F6F9).copy(alpha = 0.80f).toArgb(),
    editorGlassTint = Color(0xFFF5F6F9).copy(alpha = 0.92f).toArgb(),
    slabShadowColor = 0x33000000,
    chromeTextShadowColor = Color.White.copy(alpha = 1f),
    scrollbarThumb = Color.Black.copy(alpha = 0.16f),
    selectionAlpha = 0.30f,
    currentLineGlowAlpha = 0.10f,
    selectionPillLiftAlpha = 0.72f,
    selectionPillTintAlpha = 0.15f,
)

fun paletteOf(appearance: Appearance): Palette =
    if (appearance == Appearance.LIGHT) LightPalette else DarkPalette

/** Find-match plate alpha on the native glass; the Skia path keeps its stronger glow. */
const val GLASS_FIND_MATCH_ALPHA = 0.22f

private fun glassBases(palette: Palette): List<Color> {
    val tint = Color(palette.editorGlassTint)
    return listOf(blendOver(tint, Color.White), blendOver(tint, Color.Black))
}

/** The editor tint over the desktop extreme that is worst for the palette's text polarity. */
fun editorGlassBase(palette: Palette): Color =
    if (relativeLuminance(palette.background) < 0.5) {
        glassBases(palette).maxBy { relativeLuminance(it) }
    } else {
        glassBases(palette).minBy { relativeLuminance(it) }
    }

/**
 * The overlays the editor can lay over the native glass, including the combinations that really
 * stack: the current find match is also the editor selection and can sit on the caret line, so
 * selection + match + glow is one plate, not three separate ones.
 */
private fun glassOverlayStacks(palette: Palette): List<List<Color>> {
    val matchPink = palette.neonPink.copy(alpha = GLASS_FIND_MATCH_ALPHA)
    val matchAmber = palette.neonAmber.copy(alpha = GLASS_FIND_MATCH_ALPHA)
    return listOf(
        emptyList(),
        listOf(palette.selection),
        listOf(palette.currentLine),
        listOf(palette.currentLineGlow),
        listOf(matchPink),
        listOf(matchAmber),
        listOf(palette.selection, matchPink),
        listOf(palette.selection, matchAmber),
        listOf(palette.selection, matchPink, palette.currentLineGlow),
        listOf(palette.selection, matchAmber, palette.currentLineGlow),
        listOf(palette.currentLine, matchPink),
        listOf(palette.currentLine, matchAmber),
        listOf(palette.currentLineGlow, matchPink),
        listOf(palette.currentLineGlow, matchAmber),
        listOf(palette.selection, palette.currentLineGlow),
    )
}

/**
 * Every plate editor and console text can sit on over the native glass: the editor tint over a
 * white and a black desktop, under each single overlay and each stacked combination above.
 */
fun editorGlassPlates(palette: Palette): List<Color> {
    val stacks = glassOverlayStacks(palette)
    val plates = ArrayList<Color>(stacks.size * 2)
    for (base in glassBases(palette)) {
        for (stack in stacks) {
            var plate = base
            for (overlay in stack) plate = blendOver(overlay, plate)
            plates += plate
        }
    }
    return plates
}

/**
 * The worst plate text can sit on: the lightest plate for the dark palette, the darkest for the
 * light one. Text must clear 4.5 on it, which also covers every other plate.
 */
fun editorGlassFloor(palette: Palette): Color {
    val plates = editorGlassPlates(palette)
    return if (relativeLuminance(palette.background) < 0.5) {
        plates.maxBy { relativeLuminance(it) }
    } else {
        plates.minBy { relativeLuminance(it) }
    }
}
