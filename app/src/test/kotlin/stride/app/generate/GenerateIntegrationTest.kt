package stride.app.generate

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.GenerateKind
import stride.search.DeclarationIndex

/** Generate end to end: the Kotlin plugin's `KotlinGenerate` over the app's `ProjectTypeAnswers`. */
class GenerateIntegrationTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var root: Path
    private lateinit var state: AppState

    @BeforeEach
    fun setUp() {
        Application.resetForTests()
        Application.init(GenerateIntegrationTest::class.java.classLoader)
        root = temp.resolve("api-server")
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
        Application.resetForTests()
    }

    @Test
    fun `implement substitutes a generic supertype's type arguments and adds its import`() {
        val cart = """
            package demo

            import model.Order
            import model.Registry

            class Cart : Registry<Order> {
            }
        """.trimIndent() + "\n"
        openProject(
            mapOf(
                "src/main/kotlin/model/Registry.kt" to
                    "package model\n\nimport util.Tag\n\ninterface Registry<T> {\n" +
                    "    fun save(item: T): Tag\n    fun find(id: Long): T?\n}\n",
                "src/main/kotlin/model/Order.kt" to "package model\n\nclass Order(val id: Long)\n",
                "src/main/kotlin/util/Tag.kt" to "package util\n\nclass Tag\n",
                "src/main/kotlin/demo/Cart.kt" to cart,
            ),
        )
        val editor = openAt("src/main/kotlin/demo/Cart.kt")

        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))

        val request = requireNotNull(state.memberChoose)
        assertEquals(
            listOf("fun save(item: Order): Tag", "fun find(id: Long): Order?"),
            request.step.members.map { it.text },
        )
        assertEquals(listOf("Registry", "Registry"), request.step.members.map { it.group })
        assertTrue(request.step.members.all { it.checked && it.disabledReason == null })

        assertTrue(submitMemberChoose(state, request.step.members))
        assertEquals(
            """
            package demo

            import model.Order
            import model.Registry
            import util.Tag

            class Cart : Registry<Order> {
                override fun save(item: Order): Tag {
                    TODO("Not yet implemented")
                }

                override fun find(id: Long): Order? {
                    TODO("Not yet implemented")
                }
            }
            """.trimIndent() + "\n",
            editor.value.text,
        )

        assertTrue(editor.undo())
        assertEquals(cart, editor.value.text)
    }

    @Test
    fun `implement lists the members of a supertype with a quoted name`() {
        openProject(
            mapOf(
                "src/main/kotlin/demo/Spaced.kt" to
                    "package demo\n\ninterface `I B` {\n    fun ping(): Int\n}\n",
                "src/main/kotlin/demo/Impl.kt" to "package demo\n\nclass Impl : `I B` {\n}\n",
            ),
        )
        val editor = openAt("src/main/kotlin/demo/Impl.kt")

        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))

        val request = requireNotNull(state.memberChoose)
        assertEquals(listOf("fun ping(): Int"), request.step.members.map { it.text })
        assertEquals(listOf("`I B`"), request.step.members.map { it.group })
        assertTrue(request.step.members.all { it.checked && it.disabledReason == null })

        assertTrue(submitMemberChoose(state, request.step.members))
        assertEquals(
            """
            package demo

            class Impl : `I B` {
                override fun ping(): Int {
                    TODO("Not yet implemented")
                }
            }
            """.trimIndent() + "\n",
            editor.value.text,
        )
    }

    @Test
    fun `an open editor's unsaved supertype members reach the implement list`() {
        openProject(
            mapOf(
                "src/main/kotlin/demo/Shape.kt" to "package demo\n\ninterface Shape {\n}\n",
                "src/main/kotlin/demo/Circle.kt" to "package demo\n\nclass Circle : Shape {\n}\n",
            ),
        )
        val shape = open("src/main/kotlin/demo/Shape.kt")
        shape.updateValue(
            TextFieldValue(
                "package demo\n\ninterface Shape {\n    fun area(): Double\n    val name: String\n}\n",
                TextRange(0),
            ),
        )
        val circle = openAt("src/main/kotlin/demo/Circle.kt")

        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))

        val request = requireNotNull(state.memberChoose)
        assertEquals(
            listOf("fun area(): Double", "val name: String"),
            request.step.members.map { it.text },
        )
        assertEquals(listOf("Shape", "Shape"), request.step.members.map { it.group })
        assertTrue(request.step.members.all { it.checked && it.disabledReason == null })

        assertTrue(submitMemberChoose(state, request.step.members))
        assertEquals(
            """
            package demo

            class Circle : Shape {
                override fun area(): Double {
                    TODO("Not yet implemented")
                }

                override val name: String
                    get() = TODO("Not yet implemented")
            }
            """.trimIndent() + "\n",
            circle.value.text,
        )
    }

    private fun openProject(files: Map<String, String>) {
        for ((relative, text) in files) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        state.openFolder(root)
        val index = requireNotNull(state.session).project.service<DeclarationIndex>()
        assertTrue(index.awaitReady(5_000), "declaration index did not finish")
    }

    private fun open(relative: String): TextFileEditor =
        requireNotNull(state.session).editors.openFile(VFile(root.resolve(relative))) as TextFileEditor

    /** Opens [relative] with the caret just inside its class body. */
    private fun openAt(relative: String): TextFileEditor {
        val editor = open(relative)
        val text = editor.value.text
        editor.updateValue(TextFieldValue(text, TextRange(text.indexOf('{') + 1)))
        return editor
    }
}
