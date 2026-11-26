package stride.app.actions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.UIManager
import javax.swing.KeyStroke as SwingKeyStroke
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextClipboard
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.Keymap
import stride.app.keys.KeyStroke as Stroke
import stride.app.keys.dispatchAction
import stride.app.terminal.TerminalSession
import stride.app.terminal.TerminalView
import stride.app.ui.FolderChooserGuard
import stride.core.services.Application
import stride.core.services.Project
import stride.core.vfs.VFile

class MainMenuBarTest {
    @TempDir
    lateinit var temp: Path

    private val keymap: Keymap = Keymap.forPlatform(true)

    private val probeSource = JPanel()

    private val nonModalContexts = KeyContext.entries.filter { it !in Keymap.MODAL_CONTEXTS }

    /** Actions that start a process or a git call: judged by [canDispatch] alone, never dispatched. */
    private val quietActions = setOf(
        ActionId.COMMIT,
        ActionId.PUSH,
        ActionId.FETCH,
        ActionId.UPDATE_PROJECT,
        ActionId.RUN,
        ActionId.RUN_CONTEXT,
        ActionId.STOP,
        ActionId.RERUN,
        ActionId.BUILD_PROJECT,
        ActionId.CHOOSE_RUN_CONFIGURATION,
        ActionId.REVEAL_IN_FINDER,
        ActionId.OPEN_IN_TERMINAL,
    )

    private enum class Scenario { S1, S2, S3, S4, S5 }

    // T1: keys never reach the bar.
    @Test
    fun `no bar component takes a shortcut, mnemonic or focus and no key reaches a row`() {
        var dispatched = 0
        for (scenario in Scenario.entries) {
            for (context in KeyContext.entries) {
                val state = newState(scenario)
                try {
                    state.focus = context
                    val bar = probeBar(state) { action, appState, choose ->
                        dispatched++
                        dispatchAction(action, appState, choose)
                    }
                    openEveryMenu(bar)
                    assertNoKeyMetadata(bar, "$scenario $context")
                    for (stroke in keymap.bindings.keys) {
                        assertFalse(press(bar, stroke), "$scenario $context: $stroke reached the bar")
                    }
                } finally {
                    state.dispose()
                }
            }
        }
        assertEquals(0, dispatched)
    }

    @Test
    fun `the probe reaches an accelerator on a control bar`() {
        val bar = ProbeBar()
        bar.add(
            JMenuItem("Generate…").apply {
                accelerator = SwingKeyStroke.getKeyStroke(Key.N.nativeKeyCode, InputEvent.META_DOWN_MASK)
            },
        )
        assertTrue(press(bar, Stroke.parse("meta N")))
    }

    // T1b: F10 under the Aqua screen menu bar.
    @Test
    fun `F10 reaches no row under the Aqua screen menu bar`() {
        val originalLaf = UIManager.getLookAndFeel()
        val originalProperty = System.getProperty(SCREEN_MENU_BAR_PROPERTY)
        val state = newState(Scenario.S3)
        try {
            UIManager.setLookAndFeel("com.apple.laf.AquaLookAndFeel")
            System.setProperty(SCREEN_MENU_BAR_PROPERTY, "true")
            val bar = probeBar(state)
            openEveryMenu(bar)
            val event = KeyEvent(
                probeSource,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_F10,
                KeyEvent.CHAR_UNDEFINED,
            )
            assertFalse(bar.press(SwingKeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), event))
        } finally {
            state.dispose()
            if (originalLaf != null) {
                UIManager.setLookAndFeel(originalLaf)
            } else {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            }
            if (originalProperty == null) {
                System.clearProperty(SCREEN_MENU_BAR_PROPERTY)
            } else {
                System.setProperty(SCREEN_MENU_BAR_PROPERTY, originalProperty)
            }
        }
    }

    // T2: enablement.
    @Test
    fun `canDispatch matches the dispatcher for every menu action in every non-modal context`() {
        val actions = mainMenuActions()
        var checked = 0
        for (scenario in Scenario.entries) {
            for (context in nonModalContexts) {
                for (action in actions) {
                    val state = newState(scenario)
                    try {
                        state.focus = context
                        val expected = stride.app.keys.canDispatch(action, state, context)
                        if (action in quietActions) {
                            assertEquals(
                                state.session != null,
                                expected,
                                "$scenario $context $action: quiet action condition",
                            )
                        } else {
                            val visible = actionUpdate(action, state).visible
                            state.focus = context
                            val actual = dispatchAction(action, state) { null }
                            assertEquals(expected, actual, "$scenario $context $action")
                            if (visible && expected) {
                                assertTrue(actual, "$scenario $context $action: enabled row was handed back")
                            }
                        }
                        checked++
                    } finally {
                        state.dispose()
                    }
                }
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `every row is disabled while a modal popup owns the keyboard`() {
        for (scenario in Scenario.entries) {
            for (context in Keymap.MODAL_CONTEXTS) {
                val state = newState(scenario)
                try {
                    state.recentProjects.record(temp.resolve("recent-$scenario"))
                    state.focus = context
                    val bar = buildMainMenuBar(state, keymap, FolderChooserGuard { null })
                    openEveryMenu(bar)
                    var rows = 0
                    forEachLeafItem(bar) { item ->
                        rows++
                        assertFalse(item.isEnabled, "$scenario $context: ${item.text} is enabled")
                    }
                    assertTrue(rows > 0)
                } finally {
                    state.dispose()
                }
            }
        }
    }

    // T3: the shortcut column.
    @Test
    fun `the shortcut column shows only keys that run the row from the surface with the keyboard`() {
        val state = newState(Scenario.S3)
        try {
            state.focus = KeyContext.EDITOR
            var bar = buildBar(state)
            assertEquals("Generate…  ⌘N", findItem(menu(bar, "Code"), "Generate…")!!.text)
            assertEquals("Rollback…", findItem(menu(bar, "Git"), "Rollback…")!!.text)

            state.focus = KeyContext.PROJECT_TREE
            bar = buildBar(state)
            assertEquals("Generate…", findItem(menu(bar, "Code"), "Generate…")!!.text)

            state.focus = KeyContext.COMMIT_WINDOW
            bar = buildBar(state)
            assertEquals("Rollback…  ⌥⌘Z", findItem(menu(bar, "Git"), "Rollback…")!!.text)
        } finally {
            state.dispose()
        }
    }

    // T4: rows resolve on every open.
    @Test
    fun `a menu rebuilds on every open even when it is already selected`() {
        val state = newState(Scenario.S3)
        try {
            state.focus = KeyContext.EDITOR
            val bar = buildBar(state)
            val window = menu(bar, "Window")
            window.isSelected = true
            assertTrue(findItem(window, "Close All Tabs")!!.isEnabled)

            state.session!!.editors.closeAll()
            window.isSelected = true
            assertFalse(findItem(window, "Close All Tabs")!!.isEnabled)
        } finally {
            state.dispose()
        }
    }

    // T9: the menu outline.
    @Test
    fun `the eleven menus have the dump's submenus and rows`() {
        val state = newState(Scenario.S3)
        try {
            val bar = buildBar(state)
            openEveryMenu(bar)
            assertEquals(MAIN_MENU_OUTLINE, outline(bar))
        } finally {
            state.dispose()
        }
    }

    // T5: choosing a row resolves it again.
    @Test
    fun `a row chosen after its state changed runs nothing`() {
        val state = newState(Scenario.S3)
        try {
            state.focus = KeyContext.EDITOR
            val dispatcher = Dispatcher()
            val bar = buildMainMenuBar(state, keymap, FolderChooserGuard { null }, dispatch = dispatcher::dispatch)
            openEveryMenu(bar)
            val item = findItem(menu(bar, "File"), "Kotlin Class/File")!!
            assertTrue(item.isEnabled)

            state.closeSession()
            item.doClick()

            assertEquals(0, dispatcher.calls)
            assertNull(state.fileOperation)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a row chosen while a modal popup owns the keyboard runs nothing`() {
        val state = newState(Scenario.S3)
        try {
            state.focus = KeyContext.EDITOR
            val dispatcher = Dispatcher()
            val bar = buildMainMenuBar(state, keymap, FolderChooserGuard { null }, dispatch = dispatcher::dispatch)
            openEveryMenu(bar)
            val item = findItem(menu(bar, "Window"), "Close All Tabs")!!

            state.focus = KeyContext.CONTEXT_POPUP
            item.doClick()

            assertEquals(0, dispatcher.calls)
            assertEquals(1, state.session!!.editors.openFiles.size)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a row chosen while the folder chooser is open runs nothing`() {
        val state = newState(Scenario.S3)
        try {
            state.focus = KeyContext.EDITOR
            val dispatcher = Dispatcher()
            lateinit var bar: JMenuBar
            val chooser = FolderChooserGuard {
                findItem(menu(bar, "File"), "Kotlin Class/File")!!.doClick()
                null
            }
            bar = buildMainMenuBar(state, keymap, chooser, dispatch = dispatcher::dispatch)
            openEveryMenu(bar)

            findItem(menu(bar, "File"), "Open…")!!.doClick()

            assertEquals(1, dispatcher.calls)
            assertNull(state.fileOperation)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a generated row chosen while the context is modal runs nothing`() {
        val folder = Files.createDirectories(temp.resolve("recent-modal"))
        val state = newState(Scenario.S1)
        try {
            state.recentProjects.record(folder)
            val bar = buildBar(state)
            openEveryMenu(bar)
            val item = findItem(menu(bar, "File"), "recent-modal")!!
            assertTrue(item.isEnabled)

            state.focus = KeyContext.CONTEXT_POPUP
            item.doClick()

            assertNull(state.session)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `a generated row chosen while the folder chooser is open runs nothing`() {
        val folder = Files.createDirectories(temp.resolve("recent-chooser"))
        val state = newState(Scenario.S1)
        try {
            state.recentProjects.record(folder)
            lateinit var bar: JMenuBar
            val chooser = FolderChooserGuard {
                findItem(menu(bar, "File"), "recent-chooser")!!.doClick()
                null
            }
            bar = buildMainMenuBar(state, keymap, chooser)
            openEveryMenu(bar)

            findItem(menu(bar, "File"), "Open…")!!.doClick()

            assertNull(state.session)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `choosing a row runs its action`() {
        val state = newState(Scenario.S3)
        try {
            state.focus = KeyContext.EDITOR
            val bar = buildBar(state)
            openEveryMenu(bar)

            findItem(menu(bar, "View"), "Terminal")!!.doClick()

            assertTrue(state.toolWindows.visible)
            assertEquals("terminal", state.toolWindows.activeId)
        } finally {
            state.dispose()
        }
    }

    // T6: the stroke rows.
    @Test
    fun `the Edit menu's stroke rows act on the surface with the keyboard`() {
        val state = newState(Scenario.S3)
        try {
            val editor = state.session!!.editors.selected as TextFileEditor

            // ⌘C with a caret copies the line, the key path the dispatcher runs for COPY_LINE.
            state.clipboard = MenuBarClipboard()
            state.focus = KeyContext.EDITOR
            assertTrue(dispatchAction(ActionId.COPY_LINE, state) { null })
            val keyClipboard = (state.clipboard as MenuBarClipboard).text
            val keyText = editor.value.text

            state.clipboard = MenuBarClipboard()
            editor.updateValue(TextFieldValue(KOTLIN_TEXT, TextRange(KOTLIN_CARET)))
            var bar = buildBar(state)
            val copy = findItem(menu(bar, "Edit"), "Copy")!!
            assertEquals("Copy  ⌘C", copy.text)
            assertTrue(copy.isEnabled)
            copy.doClick()
            assertEquals(keyClipboard, (state.clipboard as MenuBarClipboard).text)
            assertEquals(keyText, editor.value.text)

            state.focus = KeyContext.PROJECT_TREE
            bar = buildBar(state)
            val treeCopy = findItem(menu(bar, "Edit"), "Copy")!!
            assertEquals("Copy  ⌘C", treeCopy.text)
            treeCopy.doClick()
            assertNotNull(state.session!!.files.clipboard)
            val delete = findItem(menu(bar, "Edit"), "Delete")!!
            assertEquals("Delete  ⌫", delete.text)
            assertTrue(delete.isEnabled)

            state.focus = KeyContext.EDITOR
            bar = buildBar(state)
            val editorDelete = findItem(menu(bar, "Edit"), "Delete")!!
            assertEquals("Delete", editorDelete.text)
            assertFalse(editorDelete.isEnabled)

            var copied = false
            state.terminalMenuActions = {
                mapOf(ActionId.COPY to ComponentAction(run = { copied = true }))
            }
            state.focus = KeyContext.TERMINAL
            bar = buildBar(state)
            val terminalCopy = findItem(menu(bar, "Edit"), "Copy")!!
            assertEquals("Copy  ⌘C", terminalCopy.text)
            assertTrue(terminalCopy.isEnabled)
            terminalCopy.doClick()
            assertTrue(copied)
            state.terminalMenuActions = null

            state.focus = KeyContext.GLOBAL
            bar = buildBar(state)
            val globalCopy = findItem(menu(bar, "Edit"), "Copy")!!
            assertEquals("Copy", globalCopy.text)
            assertFalse(globalCopy.isEnabled)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the terminal registers its menu actions while composed and clears them on dispose`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("terminal-provider"))
        val project = Project(root)
        val session = TerminalSession(project)
        val state = AppState()
        val show = mutableStateOf(true)
        try {
            setContent {
                if (show.value) TerminalView(session, state = state)
            }
            waitForIdle()
            assertNotNull(state.terminalMenuActions)
            runOnIdle { show.value = false }
            waitForIdle()
            assertNull(state.terminalMenuActions)
        } finally {
            session.dispose()
            project.dispose()
            state.dispose()
        }
    }

    // T8: installation.
    @Test
    fun `the screen menu bar applies only on macOS under Aqua with the property on`() {
        assertTrue(screenMenuBarApplies(isMac = true, lookAndFeelId = "Aqua", screenMenuProperty = "true"))
        assertFalse(screenMenuBarApplies(isMac = false, lookAndFeelId = "Aqua", screenMenuProperty = "true"))
        assertFalse(screenMenuBarApplies(isMac = true, lookAndFeelId = "Metal", screenMenuProperty = "true"))
        assertFalse(screenMenuBarApplies(isMac = true, lookAndFeelId = "Aqua", screenMenuProperty = null))
        assertFalse(screenMenuBarApplies(isMac = true, lookAndFeelId = "Aqua", screenMenuProperty = "false"))
    }

    private fun newState(scenario: Scenario): AppState {
        val state = AppState()
        state.clipboard = MenuBarClipboard()
        when (scenario) {
            Scenario.S1 -> Unit
            Scenario.S2 -> state.openFolder(root("s2"))
            Scenario.S3 -> {
                state.openFolder(root("s3"))
                openKotlin(state)
                state.focus = KeyContext.EDITOR
            }
            Scenario.S4 -> {
                state.openFolder(root("s4"))
                openKotlin(state)
                openLocked(state)
                state.focus = KeyContext.EDITOR
            }
            Scenario.S5 -> {
                state.openFolder(root("s5"))
                state.toolWindows.activate("commit")
                state.focus = KeyContext.COMMIT_WINDOW
            }
        }
        return state
    }

    private fun root(name: String): Path {
        val directory = temp.resolve(name)
        val file = directory.resolve(KOTLIN_FILE)
        if (!Files.exists(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(file, KOTLIN_TEXT)
        }
        return directory
    }

    private fun openKotlin(state: AppState): TextFileEditor {
        val session = state.session!!
        val editor = session.editors.openFile(VFile(session.project.root.resolve(KOTLIN_FILE))) as TextFileEditor
        editor.updateValue(TextFieldValue(KOTLIN_TEXT, TextRange(KOTLIN_CARET)))
        return editor
    }

    private fun openLocked(state: AppState) {
        val file = root("s4").resolve("locked.txt")
        if (!Files.exists(file)) Files.writeString(file, "class Locked\n")
        val permissions = Files.getPosixFilePermissions(file)
        Files.setPosixFilePermissions(file, emptySet())
        try {
            state.session!!.editors.openFile(VFile(file))
        } finally {
            Files.setPosixFilePermissions(file, permissions)
        }
    }

    private fun buildBar(state: AppState, bar: JMenuBar = JMenuBar()): JMenuBar =
        buildMainMenuBar(state, keymap, FolderChooserGuard { null }, bar)

    private fun probeBar(
        state: AppState,
        dispatch: (ActionId, AppState, () -> Path?) -> Boolean = ::dispatchAction,
    ): ProbeBar {
        val bar = ProbeBar()
        buildMainMenuBar(state, keymap, FolderChooserGuard { null }, bar, dispatch)
        return bar
    }

    private fun mainMenuActions(): Set<ActionId> {
        val actions = LinkedHashSet<ActionId>()
        fun visit(items: List<GroupItem>) {
            for (item in items) {
                when (item) {
                    is GroupAction -> actions.add(item.action)
                    is GroupStroke -> for (context in KeyContext.entries) {
                        keymap.actionFor(Stroke.parse(item.stroke), context)?.let(actions::add)
                    }
                    is GroupSubmenu -> visit(item.items)
                    is GroupReference -> ActionGroups.byId[item.groupId]?.let { visit(it.items) }
                    is GroupGenerated, GroupSeparator -> Unit
                }
            }
        }
        visit(ActionGroups.mainMenu.items)
        return actions
    }

    private fun menu(bar: JMenuBar, text: String): JMenu =
        (0 until bar.menuCount).map(bar::getMenu).first { it.text == text }

    private fun openEveryMenu(bar: JMenuBar) {
        for (index in 0 until bar.menuCount) openMenu(bar.getMenu(index))
    }

    private fun openMenu(menu: JMenu) {
        menu.isSelected = true
        for (index in 0 until menu.itemCount) {
            (menu.getItem(index) as? JMenu)?.let(::openMenu)
        }
    }

    private fun outline(bar: JMenuBar): String {
        val lines = mutableListOf<String>()
        fun visit(menu: JMenu, depth: Int) {
            lines += "${"  ".repeat(depth)}${menu.text} >"
            for (index in 0 until menu.menuComponentCount) {
                when (val child = menu.getMenuComponent(index)) {
                    is JMenu -> visit(child, depth + 1)
                    is JSeparator -> lines += "${"  ".repeat(depth + 1)}---"
                    is JMenuItem -> lines += "${"  ".repeat(depth + 1)}${child.text.substringBefore("  ")}"
                    else -> Unit
                }
            }
        }
        for (index in 0 until bar.menuCount) visit(bar.getMenu(index), 0)
        return lines.joinToString("\n")
    }

    private fun findItem(menu: JMenu, text: String): JMenuItem? {
        for (index in 0 until menu.itemCount) {
            val item = menu.getItem(index) ?: continue
            if (item.text == text || item.text.startsWith("$text  ")) return item
            if (item is JMenu) findItem(item, text)?.let { return it }
        }
        return null
    }

    private fun forEachLeafItem(component: Component, action: (JMenuItem) -> Unit) {
        when (component) {
            is JMenu -> for (index in 0 until component.itemCount) {
                component.getItem(index)?.let { forEachLeafItem(it, action) }
            }
            is JMenuItem -> action(component)
            is Container -> for (child in component.components) forEachLeafItem(child, action)
        }
    }

    private fun assertNoKeyMetadata(component: Component, where: String) {
        assertFalse(component.isFocusable, "$where: ${component.javaClass.simpleName} is focusable")
        if (component is JMenuItem) {
            assertNull(component.accelerator, "$where: ${component.text} has an accelerator")
            assertEquals(0, component.mnemonic, "$where: ${component.text} has a mnemonic")
            assertTrue(component.displayedMnemonicIndex < 0, "$where: ${component.text} shows a mnemonic")
        }
        when (component) {
            is JMenu -> for (index in 0 until component.itemCount) {
                component.getItem(index)?.let { assertNoKeyMetadata(it, where) }
            }
            is Container -> for (child in component.components) assertNoKeyMetadata(child, where)
        }
    }

    private fun press(bar: ProbeBar, stroke: Stroke): Boolean {
        val modifiers = awtModifiers(stroke)
        val awtStroke = SwingKeyStroke.getKeyStroke(stroke.key.nativeKeyCode, modifiers)
        val event = KeyEvent(
            probeSource,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            stroke.key.nativeKeyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
        return bar.press(awtStroke, event)
    }

    private fun awtModifiers(stroke: Stroke): Int {
        var modifiers = 0
        if (stroke.meta) modifiers = modifiers or InputEvent.META_DOWN_MASK
        if (stroke.ctrl) modifiers = modifiers or InputEvent.CTRL_DOWN_MASK
        if (stroke.alt) modifiers = modifiers or InputEvent.ALT_DOWN_MASK
        if (stroke.shift) modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK
        return modifiers
    }

    private class ProbeBar : JMenuBar() {
        fun press(stroke: SwingKeyStroke, event: KeyEvent): Boolean =
            processKeyBinding(stroke, event, JComponent.WHEN_IN_FOCUSED_WINDOW, true)
    }

    private class Dispatcher {
        var calls: Int = 0
            private set

        fun dispatch(action: ActionId, state: AppState, choose: () -> Path?): Boolean {
            calls++
            return dispatchAction(action, state, choose)
        }
    }

    companion object {
        private const val KOTLIN_FILE = "src/main/kotlin/api/OrderService.kt"
        private const val KOTLIN_TEXT = "class OrderService {}\n"
        private val KOTLIN_CARET = KOTLIN_TEXT.indexOf("OrderService") + 3

        private val MAIN_MENU_OUTLINE = """
            File >
              New >
                Kotlin Class/File
                File
                Directory
              Open…
              ---
              Save All
            Edit >
              Undo
              Redo
              ---
              Cut
              Copy
              Copy Paths
              Copy Path/Reference… >
                Copy Reference
              Paste >
                Paste
                Paste from History…
              Delete
              ---
              Find >
                Find…
                Replace…
                Find Next / Move to Next Occurrence
                Find Previous / Move to Previous Occurrence
                Select All Occurrences
                Add Selection for Next Occurrence
                Unselect Occurrence
                ---
                Find in Files…
                Replace in Files…
              Find Usages >
                Find Usages
                Show Usages
                ---
                Highlight Usages in File
              ---
              Column Selection Mode
              Extend Selection
              Shrink Selection
              ---
              Toggle Case
              Join Lines
              Duplicate Line or Selection
              ---
              Indent Selection
              Unindent Line or Selection
              ---
              Bookmarks >
                Toggle Bookmark
                ---
                Show Bookmarks…
            View >
              Tool Windows >
                Project
                Git
                Commit
                Terminal
                Run
                Problems
                Structure
                Bookmarks
                Find
              ---
              Quick Definition
              Quick Documentation
              Parameter Info
              Error Description
              ---
              Jump to Source
              ---
              Recent Files
              Recent Locations
            Navigate >
              Back
              Forward
              ---
              Search Everywhere
              ---
              Go to Class…
              Go to File…
              Go to Symbol…
              Go to Line:Column…
              ---
              Next Highlighted Error
              Previous Highlighted Error
              ---
              Last Edit Location
              ---
              Navigate in File >
                Next Method
                Previous Method
                Move Caret to Matching Brace
                ---
                Next Change
                Previous Change
              ---
              Select in Project View
              Jump to Navigation Bar
              ---
              Go to Declaration or Usages
              Go to Implementation(s)
              Go to Type Declaration
              Go to Super Method
              Go to Test
              ---
              File Structure
              Type Hierarchy
              Call Hierarchy
            Code >
              Override Methods…
              Implement Methods…
              Generate…
              ---
              Code Completion >
                Basic
                Type-Matching
                ---
                Complete Current Statement
                ---
                Cyclic Expand Word
              ---
              Surround With…
              Unwrap/Remove…
              ---
              Folding >
                Expand
                Expand All
                ---
                Collapse
                Collapse All
              ---
              Comment with Line Comment
              Comment with Block Comment
              Reformat Code
              Optimize Imports
              ---
              Move Statement Down
              Move Statement Up
              Move Line Down
              Move Line Up
            Refactor >
              Refactor This…
              Rename…
              Rename File…
              ---
              Extract/Introduce >
                Introduce Variable…
                ---
                Extract Method…
              Inline…
              ---
              Move…
              Copy…
            Build >
              Build Project
            Run >
              Run…
              ---
              Stop
            Git >
              Commit…
              Push…
              Update Project…
              Fetch
              ---
              Branches…
              New Branch…
              ---
              Show VCS Log
              Uncommitted Changes >
                Rollback…
              ---
              VCS Operations Popup…
            Window >
              Active Tool Window >
                Hide Active Tool Window
                Hide All Tool Windows
              Editor Tabs >
                Select Next Tab
                Select Previous Tab
                ---
                Close Tab
                Close Other Tabs
                Close All Tabs
                Close Tabs to the Left
                Close Tabs to the Right
                ---
                Reopen Closed Tab
            Help >
              Find Action…
        """.trimIndent()

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(MainMenuBarTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}

private class MenuBarClipboard : TextClipboard {
    var text: String? = null

    override fun writeText(text: String): Boolean {
        this.text = text
        return true
    }

    override fun readText(): String? = text
}
