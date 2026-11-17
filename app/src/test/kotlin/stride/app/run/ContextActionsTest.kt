package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.IntentionAction
import stride.lang.IntentionCategory
import stride.lang.TextEdit

class ContextActionsTest {
    private val mainFile = """
        package demo

        fun main() {
            println("hi")
        }
    """.trimIndent()

    private fun caretOn(text: String, marker: String): Int =
        requireNotNull(text.indexOf(marker).takeIf { it >= 0 }) { "marker $marker not found" }

    @Test
    fun `puts the main first when the caret is inside it`() {
        val actions = ContextActions.collect(mainFile, "Main.kt", caretOn(mainFile, "println"))

        assertEquals(listOf(ContextActionKind.RUN_MAIN), actions.map { it.kind })
        assertEquals("Run 'MainKt.main()'", actions.single().label)
    }

    @Test
    fun `keeps the main available outside the function, after the test action`() {
        val text = """
            package demo

            fun main() {
            }

            class DemoTest {
                @Test
                fun `works`() {
                }
            }
        """.trimIndent()

        val actions = ContextActions.collect(text, "Main.kt", caretOn(text, "`works`"))

        assertEquals(listOf(ContextActionKind.RUN_TEST, ContextActionKind.RUN_MAIN), actions.map { it.kind })
        assertEquals("Run 'DemoTest.works'", actions[0].label)
        assertEquals("Run 'MainKt.main()'", actions[1].label)
    }

    @Test
    fun `lists a class test action when the caret is on the class`() {
        val text = """
            package demo

            class DemoTest {
            }
        """.trimIndent()

        val actions = ContextActions.collect(text, "DemoTest.kt", caretOn(text, "class DemoTest") + 6)

        assertEquals(listOf(ContextActionKind.RUN_TEST_CLASS), actions.map { it.kind })
        assertEquals("Run tests in 'DemoTest'", actions.single().label)
    }

    @Test
    fun `appends rerun and stop after the file actions`() {
        val actions = ContextActions.collect(
            text = mainFile,
            fileName = "Main.kt",
            caretOffset = caretOn(mainFile, "println"),
            rerunName = "demo.DemoTest",
            runningName = "MainKt",
        )

        assertEquals(
            listOf(ContextActionKind.RUN_MAIN, ContextActionKind.RERUN, ContextActionKind.STOP),
            actions.map { it.kind },
        )
        assertEquals("Rerun 'demo.DemoTest'", actions[1].label)
        assertEquals("Stop 'MainKt'", actions[2].label)
    }

    @Test
    fun `intentions come after quick fixes and before the run entries`() {
        val intentions = listOf(
            IntentionAction("Change 'val' to 'var'", IntentionCategory.INTENTION, emptyList()),
            IntentionAction("Import 'demo.time.Clock'", IntentionCategory.QUICK_FIX, listOf(TextEdit(0, 0, "x"))),
        )

        val actions = ContextActions.collect(
            text = mainFile,
            fileName = "Main.kt",
            caretOffset = caretOn(mainFile, "println"),
            intentions = intentions,
        )

        assertEquals(
            listOf(ContextActionKind.QUICK_FIX, ContextActionKind.INTENTION, ContextActionKind.RUN_MAIN),
            actions.map { it.kind },
        )
        assertEquals(
            listOf(ContextActionGroup.QUICK_FIX, ContextActionGroup.INTENTION, ContextActionGroup.RUN),
            actions.map { it.group },
        )
        assertEquals("Import 'demo.time.Clock'", actions[0].label)
        assertEquals(1, actions[0].edits.size)
    }

    @Test
    fun `returns nothing for a file without an entry point or a test`() {
        assertTrue(ContextActions.collect("plain text\n", "notes.txt", 2).isEmpty())
    }
}
