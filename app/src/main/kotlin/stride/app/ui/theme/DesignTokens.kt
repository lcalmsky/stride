package stride.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every color, size and spacing the app shell uses. UI files read values from here only. */
object DesignTokens {
    val background = Color(0xFF13141A)
    val backgroundLift = Color(0xFF1A1B23)
    val fieldBottom = Color(0xFF0B0C10)
    val surface = Color(0xFF0F1015)
    val raised = Color(0xFF21232B)
    val border = Color(0xFF2F313A)
    val text = Color(0xFFE6E8F0)
    val selectedText = Color.White
    val muted = Color(0xFF8A93A6)
    val subtle = Color(0xFF5A6274)
    val accent = Color(0xFF7C5CFF)
    val accent2 = Color(0xFF00D1FF)
    val accentHover = Color(0xFF9078FF)
    val onAccent = Color.White
    val selection = accent.copy(alpha = 0.22f)
    val currentLine = Color(0x0AFFFFFF)
    val scrim = Color(0x66000000)
    val gutter = surface.copy(alpha = 0.6f)
    val json = Color(0xFFE5C07B)
    val xml = Color(0xFFE06C75)
    val transparent = Color.Transparent

    val neonPink = Color(0xFFFF2E97)
    val neonLime = Color(0xFFB6FF3B)
    val neonAmber = Color(0xFFFFC83D)
    val accentGlow = accent.copy(alpha = 0.55f)
    val accent2Glow = accent2.copy(alpha = 0.55f)
    val neonPinkGlow = neonPink.copy(alpha = 0.55f)
    val neonLimeGlow = neonLime.copy(alpha = 0.55f)
    val neonAmberGlow = neonAmber.copy(alpha = 0.55f)
    val selectionBar = accent2
    val currentLineGlow = accent.copy(alpha = 0.10f)
    val glowRadius = 10.dp
    val glowRadiusLarge = 18.dp
    val focusGlowRadius = 6.dp
    val selectionBarWidth = 2.dp
    const val neonFadeMillis = 150

    val uiFont: FontFamily get() = UiFont.family
    val codeFont = FontFamily.Monospace
    val uiTextSize = 13.sp
    val codeTextSize = 13.sp
    val codeLineHeight = 20.sp
    val tabTextSize = 13.sp
    val statusTextSize = 13.sp
    val smallTextSize = 13.sp
    val labelTextSize = 13.sp

    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp

    val radiusTiny = 4.dp
    val radiusSmall = 6.dp
    val radiusCapsule = 8.dp
    val radiusPopup = 18.dp

    val topBarHeight = 36.dp
    val tabStripHeight = 36.dp
    val statusBarHeight = 24.dp
    val treeHeaderHeight = 32.dp
    val treeRowHeight = 24.dp
    val treeIndent = 16.dp
    val treeDefaultWidth = 280.dp
    val treeMinWidth = 200.dp
    val treeMaxWidth = 480.dp
    val splitterWidth = 4.dp
    val tabMaxWidth = 220.dp

    val popupWidth = 680.dp
    val popupTop = 96.dp
    val popupTabHeight = 30.dp
    val popupRowHeight = 32.dp
    val popupGroupHeight = 22.dp
    val popupFooterHeight = 28.dp
    val popupMaxRows = 12

    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = motionShortMillis,
        unhoverColor = Color.White.copy(alpha = 0.14f),
        hoverColor = accent2,
    )

    val glassFill = Color.White.copy(alpha = 0.07f)
    val glassFillStrong = Color.White.copy(alpha = 0.10f)
    val glassBorderTop = Color.White.copy(alpha = 0.10f)
    val glassBorderSide = Color.White.copy(alpha = 0.08f)
    val glassBorderBottom = Color.White.copy(alpha = 0.06f)
    val hoverFill = Color.White.copy(alpha = 0.04f)

    /** Interior lift and top rim of a selected capsule; the capsule keeps the backdrop out. */
    val capsuleLift = Color.White.copy(alpha = 0.07f)
    val capsuleRim = Color.White.copy(alpha = 0.16f)

    val glassBlurRadius = 16.dp
    val glassBlurRadiusMax = 20.dp

    /**
     * Floating glass is thin: [glassBase] is the neutral floor and [glassBackdropMix] of the
     * blurred backdrop's luminance (plus [glassChromaGain] of its hue) shows through it, so code
     * behind a popup or a bar stays visible as a soft impression.
     */
    val glassBase = Color(0xFF17181E)
    val glassBackdropMix = 0.40f
    val glassChromaGain = 0.6f
    val liquidGlassBlurRadius = 18.dp
    val glassBezel = 16.dp
    val glassLensStrength = 14.dp
    val glassAberration = 0.5.dp
    val glassSpecular = 0.42f

    /** Chrome bars: the same thin glass without edge lens, with the hairline frame. */
    val chromeGlassBezel = 10.dp
    val chromeGlassBlurRadius = 4.dp
    val chromeBackdropMix = 0.8f

    /** The small lens on the selected tree row and the active tab. */
    val capsuleBezel = 6.dp
    val capsuleLensStrength = 3.5.dp
    val capsuleEndTaper = 10.dp

    /** Hairline grid baked into the project tree glass, so its lens has neutral detail to bend. */
    val glassGridSpacing = 48.dp
    val glassGrid = Color.White.copy(alpha = 0.03f)

    /** Soft shadow under chrome and popup text, which carries its own contrast on thin glass. */
    val chromeTextShadow = Shadow(
        color = Color.Black.copy(alpha = 0.7f),
        offset = Offset(0f, 1f),
        blurRadius = 3f,
    )
    val chromeTextStyle = TextStyle(shadow = chromeTextShadow)

    val contentTintAlpha = 0.92f

    val glowCoreSigma = 3.dp
    val glowCoreAlpha = 0.9f
    val glowHaloSigma = 10.dp
    val glowHaloAlpha = 0.5f
    val glowBloomSigma = 24.dp
    val glowBloomAlpha = 0.25f

    /** Secondary text on glass; first [muted]→[text] step that clears [contrastText] on `glassFillStrong`. */
    val textSecondaryOnGlass = Color(0xFF919AAC)

    const val motionShortMillis = 150
    const val motionMediumMillis = 200
    const val motionPopupMillis = 250
    val standardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val contrastText = 4.5
    const val contrastLarge = 3.0
    const val contrastUi = 3.0
}
