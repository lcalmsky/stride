package stride.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import stride.app.glass.GlassRuntime

/**
 * Every color, size and spacing the app shell uses. UI files read values from here only.
 * Colours come from the palette of the effective [AppearanceState], so the whole window
 * re-themes when the system appearance changes.
 */
object DesignTokens {
    val palette: Palette
        get() = paletteOf(AppearanceState.appearance)

    val background: Color get() = palette.background
    val backgroundLift: Color get() = palette.backgroundLift
    val fieldBottom: Color get() = palette.fieldBottom
    val surface: Color get() = palette.surface
    val raised: Color get() = palette.raised
    val border: Color get() = palette.border
    val text: Color get() = palette.text
    val selectedText: Color get() = palette.selectedText
    val muted: Color get() = palette.muted
    val subtle: Color get() = palette.subtle
    val accent: Color get() = palette.accent
    val accent2: Color get() = palette.accent2
    val accentHover: Color get() = palette.accentHover
    val onAccent: Color get() = palette.onAccent
    val selection: Color get() = palette.selection
    val currentLine: Color get() = palette.currentLine
    val scrim: Color get() = palette.scrim
    val gutter: Color get() = palette.gutter
    val json: Color get() = palette.json
    val xml: Color get() = palette.xml
    val transparent = Color.Transparent

    val neonPink: Color get() = palette.neonPink
    val neonLime: Color get() = palette.neonLime
    val neonAmber: Color get() = palette.neonAmber
    val accentGlow: Color get() = palette.accentGlow
    val accent2Glow: Color get() = palette.accent2Glow
    val neonPinkGlow: Color get() = palette.neonPinkGlow
    val neonLimeGlow: Color get() = palette.neonLimeGlow
    val neonAmberGlow: Color get() = palette.neonAmberGlow
    val selectionBar: Color get() = palette.selectionBar
    val currentLineGlow: Color get() = palette.currentLineGlow
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

    val scrollbarStyle: ScrollbarStyle get() = ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 6.dp,
        shape = RoundedCornerShape(3.dp),
        hoverDurationMillis = motionShortMillis,
        unhoverColor = palette.scrollbarThumb,
        hoverColor = accent2,
    )

    /**
     * Tint over the system material on the native path: the neutral floor that keeps text at
     * 4.5:1 even when a white desktop shows through the glass.
     */
    val nativeGlassTint: Int get() = palette.nativeGlassTint

    /** The editor and tool-window glass tint: denser than the chrome tint, same style. */
    val editorGlassTint: Int get() = palette.editorGlassTint

    /**
     * Text drawn over the native editor and tool-window glass. On the Skia path these are the
     * palette values; on the native path they move just far enough to clear 4.5:1 on
     * [editorGlassFloor].
     */
    val mutedOnGlass: Color get() = glassText()?.muted ?: palette.muted
    val accent2OnGlass: Color get() = glassText()?.accent2 ?: palette.accent2
    val neonPinkOnGlass: Color get() = glassText()?.neonPink ?: palette.neonPink
    val neonLimeOnGlass: Color get() = glassText()?.neonLime ?: palette.neonLime
    val neonAmberOnGlass: Color get() = glassText()?.neonAmber ?: palette.neonAmber

    /** The five glass text roles, derived once per appearance and native state. */
    private class GlassTextColors(
        val muted: Color,
        val accent2: Color,
        val neonPink: Color,
        val neonLime: Color,
        val neonAmber: Color,
    )

    private var glassTextAppearance: Appearance? = null
    private var glassTextCache: GlassTextColors? = null

    private fun glassText(): GlassTextColors? {
        if (!GlassRuntime.active.value) return null
        val appearance = AppearanceState.appearance
        if (glassTextAppearance == appearance) glassTextCache?.let { return it }
        val current = palette
        val floor = editorGlassFloor(current)
        return GlassTextColors(
            muted = readableOn(current.muted, floor),
            accent2 = readableOn(current.accent2, floor),
            neonPink = readableOn(current.neonPink, floor),
            neonLime = readableOn(current.neonLime, floor),
            neonAmber = readableOn(current.neonAmber, floor),
        ).also {
            glassTextAppearance = appearance
            glassTextCache = it
        }
    }

    val glassFill: Color get() = palette.glassFill
    val glassFillStrong: Color get() = palette.glassFillStrong
    val glassBorderTop: Color get() = palette.glassBorderTop
    val glassBorderSide: Color get() = palette.glassBorderSide
    val glassBorderBottom: Color get() = palette.glassBorderBottom
    val hoverFill: Color get() = palette.hoverFill

    /** Interior lift and top rim of a selected capsule; the capsule keeps the backdrop out. */
    val capsuleLift: Color get() = palette.capsuleLift
    val capsuleRim: Color get() = palette.capsuleRim

    val glassBlurRadius = 16.dp
    val glassBlurRadiusMax = 20.dp

    /**
     * Floating glass is thin: [glassBase] is the neutral floor and [glassBackdropMix] of the
     * blurred backdrop's luminance (plus [glassChromaGain] of its hue) shows through it, so code
     * behind a popup or a bar stays visible as a soft impression.
     */
    val glassBase: Color get() = palette.glassBase
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
    val slabShadowColor: Int get() = palette.slabShadowColor

    /** The sidebar tint over the background extension: the mirrored content stays visible. */
    val sidebarFill: Color get() = palette.sidebarFill

    /** The capsule tint over the already blurred content band, plus its neutral lift. */
    val capsuleFill: Color get() = palette.capsuleFill

    /** A capsule's own backdrop blur: strong enough that code under it is only a soft impression. */
    val capsuleFrostBlur = 20.dp

    /** The scroll edge effect: the content band under the capsule rows softens toward the edge. */
    val scrollEdgeBlur = 14.dp

    /** How far past each band the edge blur eases out, so sharpness never ends on a hard line. */
    val scrollEdgeTail = 24.dp

    /** The strip mirrored behind the sidebar is blurred hard so only its colour and rhythm remain. */
    val backgroundExtensionBlur = 56.dp

    /** The specular slab rim: brighter at the top and left, a faint line at the bottom. */
    val slabRimTop: Color get() = palette.slabRimTop
    val slabRimBottom: Color get() = palette.slabRimBottom
    val slabRimLeft: Color get() = palette.slabRimLeft
    val slabInnerRimInset = 2.dp
    val slabInnerRim: Color get() = palette.slabInnerRim

    /** Label band for a bar with code under it, so the label run keeps its contrast. */
    val chromeLabelScrim: Color get() = palette.chromeLabelScrim
    val chromeLabelScrimInset = 2.dp

    /** Secondary labels on busy glass (a bar over code, a popup over the editor). */
    val textOnBusyGlass: Color get() = palette.textOnBusyGlass

    /**
     * Shadow under chrome and popup text. On thin glass the text is read against this plate, so
     * it is opaque and tight; the measured worst case is in `PopupGlassTest`. It is light on the
     * light palette, where the labels are dark.
     */
    val chromeTextShadow: Shadow get() = palette.chromeTextShadow
    val chromeTextStyle: TextStyle get() = palette.chromeTextStyle

    val contentTintAlpha = 0.92f

    val glowCoreSigma = 3.dp
    val glowCoreAlpha = 0.9f
    val glowHaloSigma = 10.dp
    val glowHaloAlpha = 0.5f
    val glowBloomSigma = 24.dp
    val glowBloomAlpha = 0.25f

    /** Secondary text on glass; on the dark palette the first [muted]→[text] step clearing 4.5. */
    val textSecondaryOnGlass: Color get() = palette.textSecondaryOnGlass

    const val motionShortMillis = 150
    const val motionMediumMillis = 200
    const val motionPopupMillis = 250
    val standardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val contrastText = 4.5
    const val contrastLarge = 3.0
    const val contrastUi = 3.0

    /**
     * The modal scrim's share on the native path. The window is real system glass there, so the
     * full Skia scrim would flatten it; the popup's own material and drop shadow carry the modal
     * separation instead.
     */
    const val nativeScrimFactor = 0.25f
}
