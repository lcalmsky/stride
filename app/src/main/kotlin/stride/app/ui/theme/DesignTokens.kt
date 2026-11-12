package stride.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
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
    val scrim = Color(0x4D000000)
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

    /**
     * Floating chrome: the sidebar and every control capsule sit [chromeInset] in from the window
     * edge over edge-to-edge content, separated by [chromeGap] and rounded to half their height.
     */
    val chromeInset = 10.dp
    val chromeGap = 8.dp
    val capsuleHeight = 34.dp
    val statusCapsuleHeight = 32.dp
    val contentGap = 10.dp

    /**
     * The merged title row of the native glass path. macOS centres the traffic lights 14 pt
     * below the window top, so the row starts at 0 with this height and the lights share its
     * centre; [nativeTitleLightsInset] is the row start right of the lights.
     */
    val nativeTitleRowHeight = 28.dp
    val nativeTitleLightsInset = 72.dp

    /** Opaque content panels (editor, tool window) floating inside the glass window. */
    val contentSurfaceRadius = 12.dp
    val sidebarRadius = 20.dp
    val treeHeaderHeight = 32.dp
    val treeRowHeight = 24.dp
    val treeIndent = 16.dp
    val treeDefaultWidth = 280.dp
    val treeMinWidth = 200.dp
    val treeMaxWidth = 480.dp
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

    /**
     * Tint over the system material on the native path: the dark floor that keeps text at
     * 4.5:1 even when a white desktop shows through the glass.
     */
    val nativeGlassTint: Int = Color(0xFF14151B).copy(alpha = 0.78f).toArgb()

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
    val glassBase = Color(0xFF14151B)
    val glassBackdropMix = 0.52f
    val glassChromaGain = 0.8f
    val liquidGlassBlurRadius = 8.dp
    val glassBezel = 18.dp
    val glassLensStrength = 17.dp
    val glassAberration = 0.5.dp
    val glassSpecular = 0.42f

    /** The small lens on the selected tree row and the active tab. */
    val capsuleBezel = 6.dp
    val capsuleLensStrength = 3.5.dp
    val capsuleEndTaper = 10.dp

    /** Floating chrome casts the same soft neutral shadow; a frame only blits a baked image. */
    val slabShadow = 16.dp

    /** The sidebar tint over the background extension: the mirrored content stays visible. */
    val sidebarFill = Color(0xFF14151B).copy(alpha = 0.30f)

    /** The capsule tint over the already blurred content band, plus its neutral lift. */
    val capsuleFill = Color(0xFF14151B).copy(alpha = 0.58f)

    /** A capsule's own backdrop blur: strong enough that code under it is only a soft impression. */
    val capsuleFrostBlur = 20.dp

    /** The scroll edge effect: the content band under the capsule rows softens toward the edge. */
    val scrollEdgeBlur = 14.dp

    /** How far past each band the edge blur eases out, so sharpness never ends on a hard line. */
    val scrollEdgeTail = 24.dp

    /** The strip mirrored behind the sidebar is blurred hard so only its colour and rhythm remain. */
    val backgroundExtensionBlur = 56.dp

    /** The specular slab rim: bright at the top and left, a faint line at the bottom. */
    val slabRimTop = Color.White.copy(alpha = 0.22f)
    val slabRimBottom = Color.White.copy(alpha = 0.06f)
    val slabRimLeft = Color.White.copy(alpha = 0.12f)
    val slabInnerRimInset = 2.dp
    val slabInnerRim = Color.White.copy(alpha = 0.06f)

    /** Label band for a bar with code under it, so the label run keeps its contrast. */
    val chromeLabelScrim = Color.Black.copy(alpha = 0.45f)
    val chromeLabelScrimInset = 2.dp

    /** Secondary labels on busy glass (a bar over code, a popup over the editor). */
    val textOnBusyGlass = Color(0xFFC4CBDA)

    /**
     * Shadow under chrome and popup text. On thin glass the text is read against this plate, so
     * it is opaque black with a tight blur; the measured worst case is in `PopupGlassTest`.
     */
    val chromeTextShadow = Shadow(
        color = Color.Black.copy(alpha = 1f),
        offset = Offset(0f, 1f),
        blurRadius = 2f,
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
