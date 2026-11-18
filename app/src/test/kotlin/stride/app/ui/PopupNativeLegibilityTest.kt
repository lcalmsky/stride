package stride.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.ExtendWith
import stride.app.editor.CompletionPopup
import stride.app.editor.CompletionRequest
import stride.app.editor.PasteHistoryPopup
import stride.app.editor.RenamePopup
import stride.app.editor.SurroundRequest
import stride.app.editor.SurroundWith
import stride.app.editor.SurroundWithPopup
import stride.app.editor.TextFileEditor
import stride.app.generate.MemberChooserPopup
import stride.app.glass.GlassHoles
import stride.app.glass.GlassRegistry
import stride.app.glass.LocalNativeGlass
import stride.app.navigation.Bookmark
import stride.app.navigation.BookmarksPopup
import stride.app.navigation.CallText
import stride.app.navigation.ChooserRow
import stride.app.navigation.FileStructureEntry
import stride.app.navigation.FileStructurePopup
import stride.app.navigation.NavigationBarChildrenPopup
import stride.app.navigation.NavigationLocation
import stride.app.navigation.ParameterInfoPanel
import stride.app.navigation.ParameterInfoRequest
import stride.app.navigation.PeekCandidate
import stride.app.navigation.QuickDefinitionPopup
import stride.app.navigation.RecentLocationsPopup
import stride.app.navigation.QuickDocPopup
import stride.app.navigation.TargetChooserPopup
import stride.app.problems.ErrorDescriptionPopup
import stride.app.problems.ErrorDescriptionRequest
import stride.app.problems.FileProblem
import stride.app.problems.ProblemSource
import stride.app.refactor.RefactoringNamePopup
import stride.app.run.ContextAction
import stride.app.run.ContextActionKind
import stride.app.run.ContextActionsPopup
import stride.app.search.FindInPathPopup
import stride.app.search.RecentFilesPopup
import stride.app.search.SearchEverywhere
import stride.app.search.SearchEverywherePopup
import stride.app.search.ShowUsagesPopup
import stride.app.search.ShowUsagesRequest
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.contrastRatio
import stride.core.services.Application
import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.MemberOption
import stride.lang.ProblemSeverity
import stride.lang.TextEdit
import stride.search.Declaration
import stride.search.DeclarationKind
import stride.search.FileNameIndex

/**
 * On the native path a popup's glyphs sit on the palette plate, while the system material under
 * the plate takes the desktop's brightness. Each popup renders offscreen on the native path over
 * a white and over a black desktop in both palettes, and every text role it can draw must clear
 * 4.5:1 on a glyph-free block inside the panel, and the selected row's label on its pill. The
 * desktop opposite to the palette's polarity is the case that fails without the plate; the tokens
 * themselves are checked without a render by [PopupPlateContrastTest].
 */
@ExtendWith(FixedUiFontExtension::class)
class PopupNativeLegibilityTest {
    private enum class Role(val label: String) {
        TEXT("text"),
        BUSY("textOnBusyGlass"),
        SECONDARY("textSecondaryOnGlass"),
        ACCENT2("accent2"),
        AMBER("neonAmber"),
        ACCENT("accentOnPopupPlate"),
        ERROR("errorOnPopupPlate"),
        ;

        fun color(): Color = when (this) {
            TEXT -> DesignTokens.text
            BUSY -> DesignTokens.textOnBusyGlass
            SECONDARY -> DesignTokens.textSecondaryOnGlass
            ACCENT2 -> DesignTokens.accent2
            AMBER -> DesignTokens.neonAmber
            ACCENT -> DesignTokens.accentOnPopupPlate
            ERROR -> DesignTokens.errorOnPopupPlate
        }
    }

    private class PopupCase(
        val name: String,
        val key: String,
        /** Every role the popup can draw; one rendered sample may show only some of them. */
        val roles: List<Role>,
        val renderMillis: Long,
        /** The popup draws no selection pill, so only the panel block is measured. */
        val selectionPill: Boolean = true,
        val content: @Composable () -> Unit,
    )

    @AfterEach
    fun restoreDark() {
        AppearanceState.set(Appearance.DARK)
    }

    @TestFactory
    fun `popup text stays legible on the native glass over either desktop`(): List<DynamicTest> =
        cases().flatMap { case ->
            listOf(Appearance.DARK, Appearance.LIGHT).flatMap { appearance ->
                DESKTOPS.map { (desktopName, desktop) ->
                    DynamicTest.dynamicTest(
                        "${case.name} ${appearance.name.lowercase()} $desktopName",
                    ) {
                        assertLegible(case, appearance, desktopName, desktop)
                    }
                }
            }
        }

    private fun assertLegible(case: PopupCase, appearance: Appearance, desktopName: String, desktop: Color) {
        val rendered = render(
            case = case,
            appearance = appearance,
            desktop = desktop,
            output = SnapshotRenderer.snapshotDir().resolve("native-popups")
                .resolve("${case.name}-${appearance.name.lowercase()}-$desktopName.png"),
        )
        // The scene owns the hole; closing it must leave nothing behind for the next case.
        assertTrue(
            GlassHoles.snapshot().isEmpty(),
            "${case.name}: a ${GlassHoles.snapshot().size}-rect hole outlived its scene",
        )
        assertTrue(
            GlassRegistry.shared.reported.none { it.key == case.key },
            "${case.name}: the ${case.key} glass piece outlived its scene",
        )
        val hole = rendered.hole
        val right = hole.right.roundToInt()
        // A row's right end carries the vertical scrollbar once the list scrolls and the
        // selection pill on the selected row, so the block sits in the hint bar's plain plate
        // (the same plate the rows use), 4 dp in from the right edge.
        val blockY = hole.bottom.roundToInt() - 14
        val block = pixelsIn(rendered.pixels, right - 4 until right - 1, blockY - 1..blockY + 1)
        val label = "${case.name} ${appearance.name.lowercase()} $desktopName"
        for (role in case.roles) {
            val color = role.color()
            for (pixel in block) {
                val ratio = contrastRatio(color, Color(pixel))
                assertTrue(
                    ratio >= MIN_CONTRAST,
                    "$label: ${role.label} on #%06X = %.3f < %.1f".format(pixel and 0xFFFFFF, ratio, MIN_CONTRAST),
                )
            }
        }
        if (case.selectionPill) assertSelectionPill(label, rendered, hole)
    }

    /**
     * The selected row is the only row that draws a pill, and the pill is the first band at the
     * sampled column that differs from the plain panel. The block sits 10 dp in from the row's
     * right edge, inside the pill and clear of the 16 dp right padding that holds the text.
     */
    private fun assertSelectionPill(label: String, rendered: Rendered, hole: Rect) {
        val x = hole.right.roundToInt() - 10
        // The hint bar is plain panel at this column in every popup; the choice popup has no
        // header, so the pill scan starts at the panel's top edge rather than below a header.
        val baseline = rendered.pixels[index(x, hole.bottom.roundToInt() - 14)]
        var runStart = -1
        var bandStart = -1
        var bandEnd = -1
        for (y in hole.top.roundToInt() + 2 until hole.bottom.roundToInt() - 20) {
            val differs = channelDistance(rendered.pixels[index(x, y)], baseline) >= DIFFERENT_CHANNELS
            if (!differs) {
                if (bandStart >= 0) break
                runStart = -1
                continue
            }
            if (runStart < 0) runStart = y
            if (bandStart < 0 && y - runStart + 1 >= 16) {
                // The rounded corner's antialiased edge is one or two rows; a pill is 28 dp.
                bandStart = runStart
            }
            if (bandStart >= 0) bandEnd = y
        }
        assertTrue(bandStart >= 0, "$label: no selection pill at x=$x inside the popup")
        val height = bandEnd - bandStart + 1
        assertTrue(height in 16..32, "$label: the selection pill band is ${height}dp tall")
        val centerY = (bandStart + bandEnd) / 2
        val block = pixelsIn(rendered.pixels, x - 2..x, centerY - 1..centerY + 1)
        val color = DesignTokens.selectedText
        for (pixel in block) {
            val ratio = contrastRatio(color, Color(pixel))
            assertTrue(
                ratio >= MIN_CONTRAST,
                "$label: selectedText on #%06X = %.3f < %.1f".format(pixel and 0xFFFFFF, ratio, MIN_CONTRAST),
            )
        }
    }

    private class Rendered(val pixels: IntArray, val hole: Rect)

    private fun render(case: PopupCase, appearance: Appearance, desktop: Color, output: Path): Rendered {
        AppearanceState.set(appearance)
        Files.createDirectories(output.parent)
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(
                LocalAnimationsEnabled provides false,
                LocalNativeGlass provides true,
            ) {
                Box(Modifier.fillMaxSize().background(desktop)) { case.content() }
            }
        }
        try {
            var nanos = 0L
            var last: org.jetbrains.skia.Image? = null
            val start = System.nanoTime()
            while ((System.nanoTime() - start) / 1_000_000 < case.renderMillis) {
                last?.close()
                last = scene.render(nanos)
                nanos += 16_000_000L
                Thread.sleep(10)
            }
            val image = requireNotNull(last) { "${case.name}: no frame was rendered" }
            try {
                val pixels = IntArray(WIDTH * HEIGHT)
                image.toComposeImageBitmap().readPixels(pixels)
                val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
                    "${case.name}: PNG encoding failed"
                }
                Files.write(output, data.bytes)
                val holes = GlassHoles.snapshot()
                assertTrue(
                    holes.size == 1,
                    "${case.name}: expected one popup hole, found ${holes.size}",
                )
                assertTrue(
                    GlassRegistry.shared.reported.any { it.key == case.key },
                    "${case.name}: the ${case.key} glass piece was not reported",
                )
                return Rendered(pixels, holes.single())
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }

    private fun cases(): List<PopupCase> {
        val editor = TextFileEditor(VFile(root.resolve("src/AlphaOne.kt")))
        val recent = listOf("AlphaOne.kt", "AlphaTwo.kt", "BetaOne.kt").map { VFile(root.resolve("src/$it")) }
        val completion = CompletionRequest(
            editor = editor,
            prefixStart = 0,
            prefixLength = 0,
            prefix = "",
            nameSource = { listOf("alphaOne", "alphaTwo", "betaOne", "betaTwo") },
        )
        val search = SearchEverywhere(project)
        val index = project.service<FileNameIndex>()
        val peekCandidates = listOf(
            PeekCandidate(
                declaration = Declaration(
                    "alphaOne",
                    DeclarationKind.CLASS,
                    VFile(root.resolve("src/AlphaOne.kt")),
                    "src/AlphaOne.kt",
                    2,
                ),
                signature = "class AlphaOne(val alpha: Int)",
                doc = "A sample declaration used by the popup checks.",
                definition = "class AlphaOne {\n    val alpha = 1\n}",
                definitionTruncatedLines = 0,
            ),
            PeekCandidate(
                declaration = Declaration(
                    "alphaTwo",
                    DeclarationKind.CLASS,
                    VFile(root.resolve("src/AlphaTwo.kt")),
                    "src/AlphaTwo.kt",
                    2,
                ),
                signature = "class AlphaTwo(val alpha: Int)",
                doc = null,
                definition = "class AlphaTwo {\n    val alpha = 2\n}",
                definitionTruncatedLines = 12,
            ),
        )
        val callText = "alpha(1, 2)"
        val callEditor = TextFileEditor(VFile(root.resolve("src/Call.kt")))
        callEditor.updateValue(TextFieldValue(callText, TextRange(callText.length - 1)))
        val parameterInfo = ParameterInfoRequest(
            editor = callEditor,
            openOffset = requireNotNull(CallText.openParen(callText, callText.length - 1)),
            signatures = listOf(
                "alpha(first: Int, second: Int)",
                "alpha(value: Int)",
            ),
        )
        return listOf(
            PopupCase(
                name = "file-structure",
                key = "popup-file-structure",
                roles = listOf(Role.TEXT, Role.BUSY, Role.ACCENT2),
                renderMillis = 600,
            ) {
                FileStructurePopup(
                    entries = listOf(
                        FileStructureEntry("AlphaOne", DeclarationKind.CLASS, 2, 0),
                        FileStructureEntry("alpha", DeclarationKind.SYMBOL, 3, 1),
                    ),
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "bookmarks",
                key = "popup-bookmarks",
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 600,
            ) {
                BookmarksPopup(
                    bookmarks = listOf(
                        Bookmark(root.resolve("src/AlphaOne.kt"), "src/AlphaOne.kt", 2, "val alpha = 1"),
                        Bookmark(root.resolve("src/AlphaTwo.kt"), "src/AlphaTwo.kt", 2, "val alpha = 2"),
                    ),
                    onChoose = {},
                    onRemove = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "target-chooser",
                key = CHOOSER_TITLE_KEY,
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 600,
            ) {
                TargetChooserPopup(
                    title = CHOOSER_TITLE,
                    rows = listOf(
                        ChooserRow("AlphaOne — src/AlphaOne.kt:3", 0),
                        ChooserRow("AlphaTwo — src/AlphaTwo.kt:3", 1),
                    ),
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "refactor-this",
                key = "popup-chooser-Refactor This",
                roles = listOf(Role.TEXT, Role.BUSY, Role.SECONDARY),
                renderMillis = 600,
            ) {
                TargetChooserPopup(
                    title = "Refactor This",
                    rows = listOf(
                        ChooserRow("Rename…", 0, "⇧F6"),
                        ChooserRow("Introduce Variable…", 1, "⌥⌘V"),
                        ChooserRow("Inline…", 2, "⌥⌘N"),
                    ),
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "search",
                key = "popup-search",
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 1_800,
            ) {
                SearchEverywherePopup(
                    search = search,
                    onChoose = {},
                    onClose = {},
                    initialQuery = "Alpha",
                )
            },
            PopupCase(
                name = "recent-files",
                key = "popup-recent",
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 600,
            ) {
                RecentFilesPopup(files = recent, projectRoot = root, onChoose = {}, onClose = {})
            },
            PopupCase(
                name = "find-in-path",
                key = "popup-find-in-path",
                roles = listOf(Role.TEXT, Role.BUSY, Role.ACCENT2, Role.AMBER),
                renderMillis = 1_800,
            ) {
                FindInPathPopup(
                    index = index,
                    initialQuery = "alpha",
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "find-in-path-replace",
                key = "popup-find-in-path",
                roles = listOf(Role.TEXT, Role.BUSY, Role.AMBER, Role.ACCENT),
                renderMillis = 1_800,
            ) {
                FindInPathPopup(
                    index = index,
                    initialQuery = "alpha",
                    initialReplacement = "beta",
                    replace = true,
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "context-actions",
                key = "popup-context-actions",
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 600,
            ) {
                ContextActionsPopup(
                    actions = listOf(
                        ContextAction(ContextActionKind.QUICK_FIX, "Import 'demo.time.Clock'", listOf(TextEdit(0, 0, ""))),
                        ContextAction(ContextActionKind.INTENTION, "Change 'val' to 'var'", listOf(TextEdit(0, 3, "var"))),
                        ContextAction(ContextActionKind.RUN_MAIN, "Run 'Main.main()'"),
                    ),
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "completion",
                key = "popup-completion",
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 1_200,
            ) {
                CompletionPopup(request = completion, onChoose = {}, onClose = {})
            },
            PopupCase(
                name = "rename",
                key = "popup-rename",
                roles = listOf(Role.TEXT, Role.BUSY, Role.AMBER),
                renderMillis = 600,
                selectionPill = false,
            ) {
                RenamePopup(initialName = "alphaOne", onRename = {}, onClose = {})
            },
            PopupCase(
                name = "refactoring-name",
                key = "popup-refactoring-name",
                roles = listOf(Role.TEXT, Role.BUSY, Role.AMBER, Role.ERROR),
                renderMillis = 600,
                selectionPill = false,
            ) {
                RefactoringNamePopup(
                    title = "Introduce Variable",
                    suggested = "alphaOne",
                    error = "Not a valid name",
                    onSubmit = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "member-chooser",
                key = "popup-member-chooser",
                roles = listOf(Role.TEXT, Role.BUSY, Role.SECONDARY, Role.ACCENT2, Role.ERROR),
                renderMillis = 600,
            ) {
                MemberChooserPopup(
                    title = "Override Methods",
                    members = listOf(
                        MemberOption("alphaOne(): Unit", group = "AlphaOne", checked = true),
                        MemberOption(
                            "betaOne(): Unit",
                            group = "AlphaOne",
                            disabledReason = "refers to another name here",
                        ),
                        MemberOption("gammaOne(): Unit", group = "Any"),
                    ),
                    error = "Choose a member",
                    onSubmit = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "paste-history",
                key = "popup-paste-history",
                roles = listOf(Role.TEXT, Role.BUSY, Role.SECONDARY),
                renderMillis = 600,
            ) {
                PasteHistoryPopup(
                    items = listOf(
                        "val alpha = 1",
                        "val beta = 2\nval gamma = 3\nval delta = 4",
                    ),
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "goto-line",
                key = "popup-goto-line",
                roles = listOf(Role.SECONDARY, Role.TEXT),
                renderMillis = 600,
                selectionPill = false,
            ) {
                // The 1 dp neon stroke and its faint glow sit at the rounded edge, and the row's
                // 12 dp right padding is empty, so 4 dp in is a clear block.
                editor.GotoLinePopup(Modifier.padding(top = DesignTokens.popupTop))
            },
            PopupCase(
                name = "quick-doc",
                key = "popup-quick-doc",
                roles = listOf(Role.TEXT, Role.SECONDARY, Role.BUSY),
                renderMillis = 600,
                selectionPill = false,
            ) {
                QuickDocPopup(candidates = peekCandidates, onGoTo = {}, onClose = {})
            },
            PopupCase(
                name = "quick-definition",
                key = "popup-quick-definition",
                roles = listOf(Role.TEXT, Role.SECONDARY, Role.BUSY),
                renderMillis = 600,
                selectionPill = false,
            ) {
                QuickDefinitionPopup(candidates = peekCandidates, onGoTo = {}, onClose = {})
            },
            PopupCase(
                name = "parameter-info",
                key = "popup-parameter-info",
                roles = listOf(Role.TEXT, Role.SECONDARY, Role.ACCENT),
                renderMillis = 600,
                selectionPill = false,
            ) {
                Box(Modifier.fillMaxSize()) {
                    ParameterInfoPanel(
                        request = parameterInfo,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = DesignTokens.popupTop),
                    )
                }
            },
            PopupCase(
                name = "error-description",
                key = "popup-error-description",
                roles = listOf(Role.TEXT, Role.SECONDARY, Role.BUSY, Role.ERROR, Role.AMBER),
                renderMillis = 600,
                selectionPill = false,
            ) {
                ErrorDescriptionPopup(
                    request = ErrorDescriptionRequest(
                        editor = editor,
                        problems = listOf(
                            FileProblem(
                                ProblemSeverity.ERROR,
                                "Unresolved reference: alpha",
                                ProblemSource.BUILD,
                                2,
                                4,
                                -1,
                                -1,
                            ),
                            FileProblem(
                                ProblemSeverity.WARNING,
                                "Variable 'alpha' is never used",
                                ProblemSource.EDITOR,
                                3,
                                8,
                                20,
                                25,
                            ),
                        ),
                    ),
                    onClose = {},
                )
            },
            PopupCase(
                name = "surround",
                key = "popup-surround",
                roles = listOf(Role.TEXT, Role.SECONDARY, Role.BUSY),
                renderMillis = 600,
            ) {
                SurroundWithPopup(
                    request = SurroundRequest(editor, SurroundWith.templatesFor("kt")),
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "show-usages",
                key = "popup-usages",
                roles = listOf(Role.TEXT, Role.BUSY, Role.ACCENT),
                renderMillis = 1_200,
            ) {
                ShowUsagesPopup(
                    request = ShowUsagesRequest(id = 1, name = "alpha", files = index.snapshot()),
                    onOpen = {},
                    onOpenInFindWindow = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "recent-locations",
                key = "popup-recent-locations",
                roles = listOf(Role.TEXT, Role.SECONDARY, Role.BUSY),
                renderMillis = 600,
                // A row is three code lines tall, taller than the selection pill scan allows.
                selectionPill = false,
            ) {
                RecentLocationsPopup(
                    request = 0,
                    locations = listOf(
                        NavigationLocation(root.resolve("src/AlphaOne.kt").toAbsolutePath(), 8),
                        NavigationLocation(root.resolve("src/AlphaTwo.kt").toAbsolutePath(), 12),
                        NavigationLocation(root.resolve("src/BetaOne.kt").toAbsolutePath(), 4),
                    ),
                    caret = null,
                    projectRoot = root,
                    textOf = { path ->
                        if (path.fileName?.toString() == "BetaOne.kt") null
                        else "package demo\n\nclass Alpha(val value: Int) {\n    val alpha = 1\n}\n"
                    },
                    onChoose = {},
                    onClose = {},
                )
            },
            PopupCase(
                name = "nav-bar-children",
                key = "popup-nav-bar",
                roles = listOf(Role.TEXT, Role.BUSY),
                renderMillis = 600,
            ) {
                Box(Modifier.fillMaxSize()) {
                    NavigationBarChildrenPopup(
                        children = listOf(
                            VFile(root.resolve("src/AlphaOne.kt")),
                            VFile(root.resolve("src/AlphaTwo.kt")),
                            VFile(root.resolve("src/BetaOne.kt")),
                        ),
                        selected = 1,
                        onChoose = {},
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = DesignTokens.popupTop),
                    )
                }
            },
        )
    }

    private fun pixelsIn(pixels: IntArray, xs: IntRange, ys: IntRange): IntArray =
        IntArray(xs.count() * ys.count()) { position ->
            val x = xs.first + position % xs.count()
            val y = ys.first + position / xs.count()
            pixels[index(x, y)]
        }

    private fun channelDistance(first: Int, second: Int): Int {
        fun channel(pixel: Int, shift: Int): Int = (pixel shr shift) and 0xFF
        return maxOf(
            kotlin.math.abs(channel(first, 16) - channel(second, 16)),
            kotlin.math.abs(channel(first, 8) - channel(second, 8)),
            kotlin.math.abs(channel(first, 0) - channel(second, 0)),
        )
    }

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    companion object {
        private const val WIDTH = 800
        private const val HEIGHT = 640
        private const val MIN_CONTRAST = 4.5
        private const val DIFFERENT_CHANNELS = 8
        private const val CHOOSER_TITLE = "Go to Declaration"
        private const val CHOOSER_TITLE_KEY = "popup-chooser-Go to Declaration"

        private val DESKTOPS = listOf("white" to Color.White, "black" to Color.Black)

        private lateinit var root: Path
        private lateinit var project: Project

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(PopupNativeLegibilityTest::class.java.classLoader)
            root = Files.createTempDirectory("stride-popup-legibility")
            Files.createDirectories(root.resolve("src"))
            Files.writeString(
                root.resolve("src/AlphaOne.kt"),
                "package demo\n\nclass AlphaOne {\n    val alpha = 1\n}\n",
            )
            Files.writeString(
                root.resolve("src/AlphaTwo.kt"),
                "package demo\n\nclass AlphaTwo {\n    val alpha = 2\n}\n",
            )
            Files.writeString(root.resolve("src/BetaOne.kt"), "package demo\n\nclass BetaOne\n")
            project = Project(root)
            assumeTrue(
                project.service<FileNameIndex>().awaitReady(5_000),
                "the file index did not finish",
            )
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            if (::project.isInitialized) project.dispose()
            if (::root.isInitialized) {
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
            }
            Application.resetForTests()
        }
    }
}
