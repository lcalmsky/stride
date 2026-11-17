package stride.app.search

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.editor.find.FindOptions
import stride.core.services.Application
import stride.core.vfs.VFile
import stride.lang.TextEdit
import stride.search.IndexedFile

class ReplaceInPathTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `literal replacement edits every match`() {
        val edits = ReplaceInPath.replacementsFor("alpha beta alpha", "alpha", FindOptions(), "gamma")

        assertEquals(listOf(TextEdit(0, 5, "gamma"), TextEdit(11, 16, "gamma")), edits)
    }

    @Test
    fun `a case-insensitive query edits matches of either case`() {
        val insensitive = ReplaceInPath.replacementsFor("Alpha alpha", "alpha", FindOptions(), "x")
        val sensitive = ReplaceInPath.replacementsFor("Alpha alpha", "alpha", FindOptions(caseSensitive = true), "x")

        assertEquals(2, insensitive.size)
        assertEquals(listOf(TextEdit(6, 11, "x")), sensitive)
    }

    @Test
    fun `whole word replacement skips the query inside a longer word`() {
        val edits = ReplaceInPath.replacementsFor(
            "alpha alphabet alpha",
            "alpha",
            FindOptions(wholeWord = true),
            "x",
        )

        assertEquals(listOf(TextEdit(0, 5, "x"), TextEdit(15, 20, "x")), edits)
    }

    @Test
    fun `overlapping literal matches keep the first one`() {
        val edits = ReplaceInPath.replacementsFor("aaa", "aa", FindOptions(), "b")

        assertEquals(listOf(TextEdit(0, 2, "b")), edits)
    }

    @Test
    fun `a regex replacement expands group references`() {
        val edits = ReplaceInPath.replacementsFor(
            "name: alpha; other: beta",
            """(\w+): (\w+)""",
            FindOptions(regex = true),
            "\$2 -> \$1",
        )

        assertEquals(
            listOf(TextEdit(0, 11, "alpha -> name"), TextEdit(13, 24, "beta -> other")),
            edits,
        )
    }

    @Test
    fun `a regex replacement sees the text a lookbehind needs`() {
        val edits = ReplaceInPath.replacementsFor(
            "price: 10 and total: 10",
            """(?<=price: )\d+""",
            FindOptions(regex = true),
            "N",
        )

        assertEquals(listOf(TextEdit(7, 9, "N")), edits)
    }

    @Test
    fun `the plan reads the overlay text before the disk and totals the files`() {
        val a = indexed("A.kt", "alpha\n")
        val b = indexed("B.kt", "alpha alpha\n")

        val plan = ReplaceInPath.plan(
            files = listOf(a, b),
            query = "alpha",
            options = FindOptions(),
            replacement = "beta",
            textOf = { path -> if (path.fileName.toString() == "A.kt") "alpha alpha\n" else null },
        )

        assertEquals(2, plan.files.size)
        val editorPlan = plan.files.first { it.relativePath == "A.kt" }
        assertEquals(ReplaceSource.EDITOR, editorPlan.source)
        assertEquals("alpha alpha\n", editorPlan.originalText)
        assertEquals("beta beta\n", editorPlan.newText)
        assertEquals(2, editorPlan.edits.size)
        val diskPlan = plan.files.first { it.relativePath == "B.kt" }
        assertEquals(ReplaceSource.DISK, diskPlan.source)
        assertEquals("alpha alpha\n", diskPlan.originalText)
        assertEquals(4, plan.occurrences)
        assertEquals(1, plan.diskFiles)
    }

    @Test
    fun `a hit whose match is gone plans nothing`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha\n")
        val hit = hit(path, "alpha", 0, 5)

        assertNotNull(ReplaceInPath.planHit(hit, "alpha", FindOptions(), "beta"))

        Files.writeString(path, "beta\n")
        assertNull(ReplaceInPath.planHit(hit, "alpha", FindOptions(), "beta"))
    }

    @Test
    fun `an open editor takes the edits as one undo step and stays unsaved`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha alpha\n")
        val editor = TextFileEditor(VFile(path))
        val planned = requireNotNull(ReplaceInPath.planHit(hit(path, "alpha", 0, 5), "alpha", FindOptions(), "beta"))

        val outcome = ReplaceInPath.apply(ReplacePlan(listOf(planned)), listOf(editor))

        assertEquals("beta alpha\n", editor.value.text)
        assertTrue(editor.isModified)
        assertEquals("alpha alpha\n", Files.readString(path))
        assertEquals(1, outcome.replaced)
        assertEquals(1, outcome.files)
        assertEquals(1, outcome.unsavedFiles)
        assertTrue(editor.undo())
        assertEquals("alpha alpha\n", editor.value.text)
    }

    @Test
    fun `a read-only editor is skipped`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha\n")
        val editor = TextFileEditor(VFile(path), readOnly = true)
        val planned = requireNotNull(ReplaceInPath.planHit(hit(path, "alpha", 0, 5), "alpha", FindOptions(), "beta"))

        val outcome = ReplaceInPath.apply(ReplacePlan(listOf(planned)), listOf(editor))

        assertEquals(0, outcome.replaced)
        assertEquals(1, outcome.skippedReadOnly)
        assertEquals("alpha\n", editor.value.text)
        assertEquals("alpha\n", Files.readString(path))
    }

    @Test
    fun `a file with no open editor is written to disk`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha alpha\n")
        val plan = ReplaceInPath.plan(listOf(IndexedFile(VFile(path), "A.kt")), "alpha", FindOptions(), "beta")

        val outcome = ReplaceInPath.apply(plan, emptyList())

        assertEquals("beta beta\n", Files.readString(path))
        assertEquals(2, outcome.replaced)
        assertEquals(1, outcome.files)
        assertEquals(0, outcome.unsavedFiles)
    }

    @Test
    fun `a disk file that changed after the plan is skipped`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha\n")
        val plan = ReplaceInPath.plan(listOf(IndexedFile(VFile(path), "A.kt")), "alpha", FindOptions(), "beta")
        Files.writeString(path, "edited elsewhere\n")

        val outcome = ReplaceInPath.apply(plan, emptyList())

        assertEquals(1, outcome.skippedChanged)
        assertEquals(0, outcome.replaced)
        assertEquals("edited elsewhere\n", Files.readString(path))
    }

    @Test
    fun `a non-writable disk file is skipped`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha\n")
        val plan = ReplaceInPath.plan(listOf(IndexedFile(VFile(path), "A.kt")), "alpha", FindOptions(), "beta")
        val file = path.toFile()
        try {
            assertTrue(file.setWritable(false), "the test file could not be made read-only")

            val outcome = ReplaceInPath.apply(plan, emptyList())

            assertEquals(1, outcome.skippedReadOnly)
            assertEquals("alpha\n", Files.readString(path))
        } finally {
            file.setWritable(true)
        }
    }

    @Test
    fun `the outcome message counts occurrences, files and skips`() {
        assertEquals("Replaced 1 occurrence in 1 file", ReplaceOutcome(replaced = 1, files = 1).message)
        assertEquals(
            "Replaced 0 occurrences in 0 files, 1 skipped (changed)",
            ReplaceOutcome(skippedChanged = 1).message,
        )
        assertEquals(
            "Replaced 2 occurrences in 2 files, 1 skipped (read-only), 1 skipped (changed); 1 open file not saved",
            ReplaceOutcome(replaced = 2, files = 2, skippedReadOnly = 1, skippedChanged = 1, unsavedFiles = 1).message,
        )
    }

    @Test
    fun `the confirmation states the totals and the disk warning in singular and plural`() {
        val path = temp.resolve("A.kt")
        Files.writeString(path, "alpha\n")
        val one = ReplaceInPath.plan(listOf(IndexedFile(VFile(path), "A.kt")), "alpha", FindOptions(), "beta")
        assertEquals(
            "Replace 1 occurrence of 'alpha' across 1 file with 'beta'? " +
                "1 file is written to disk and cannot be undone.",
            ReplaceInPath.confirmation(one, "alpha", "beta"),
        )

        Files.writeString(path, "alpha alpha\n")
        val two = ReplaceInPath.plan(listOf(IndexedFile(VFile(path), "A.kt")), "alpha", FindOptions(), "beta")
        assertEquals(
            "Replace 2 occurrences of 'alpha' across 1 file with 'beta'? " +
                "1 file is written to disk and cannot be undone.",
            ReplaceInPath.confirmation(two, "alpha", "beta"),
        )
    }

    private fun indexed(name: String, content: String): IndexedFile {
        val path = temp.resolve(name)
        Files.writeString(path, content)
        return IndexedFile(VFile(path), name)
    }

    private fun hit(path: Path, matchText: String, start: Int, end: Int): FindInPathHit = FindInPathHit(
        file = VFile(path),
        relativePath = path.fileName.toString(),
        line = 1,
        column = start + 1,
        lineText = "alpha alpha",
        start = start,
        end = end,
        matchText = matchText,
    )

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(ReplaceInPathTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
