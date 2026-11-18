package stride.lang.kotlin.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.GenerateKind
import stride.lang.NameCheck
import stride.lang.RefactoringStep

class KotlinGenerateConstructorTest {
    private val constructor = GenerateKind.SECONDARY_CONSTRUCTOR

    @Test
    fun `checked properties become parameters and assignments`() {
        val source = """
            class OrderService {
                var count: Int = 0
                val label: String
                lateinit var name: String
            }
        """.trimIndent()
        val text = run(constructor, source, currentTypes(source)).text ?: error("refused")
        assertTrue(text.contains("constructor(count: Int, label: String, name: String) {"), text)
        assertTrue(text.contains("this.count = count"), text)
        assertTrue(text.contains("this.label = label"), text)
        assertTrue(text.contains("this.name = name"), text)
    }

    @Test
    fun `a val with an initializer delegated property and custom getter are left out`() {
        val source = """
            class OrderService {
                val fixed: Int = 1
                val lazyOne: Int by lazy { 1 }
                val computed: Int
                    get() = 2
                var open: Int = 0
            }
        """.trimIndent()
        val step = prepare(constructor, source, currentTypes(source))
        assertTrue(step is RefactoringStep.ChooseMembers, step.toString())
        step as RefactoringStep.ChooseMembers
        assertEquals(listOf("open: Int"), step.members.map { it.text })
    }

    @Test
    fun `a property assigned in an init block is left out`() {
        val source = """
            class OrderService {
                var count: Int
                init {
                    count = 0
                }
            }
        """.trimIndent()
        val text = run(constructor, source, currentTypes(source)).text ?: error("refused")
        assertTrue(text.contains("constructor()"), text)
    }

    @Test
    fun `a property whose type is not written cannot be checked`() {
        val source = "class OrderService {\n    var count = 0\n}"
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        assertEquals("type not written", step.members.single().disabledReason)
        assertFalse(step.members.single().checked)
    }

    @Test
    fun `no candidate generates an empty constructor`() {
        val text = run(constructor, "class OrderService {\n}", currentTypes("class OrderService {\n}")).text ?: error("refused")
        assertTrue(text.contains("constructor()"), text)
    }

    @Test
    fun `an existing constructor with the same types refuses`() {
        val source = "class OrderService {\n    var count: Int = 0\n    constructor(count: Int) {\n        this.count = count\n    }\n}"
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        val check = step.complete(step.members)
        assertEquals("constructor(Int) already exists", (check as NameCheck.Invalid).message)
    }

    @Test
    fun `an existing constructor reachable with the same argument count refuses`() {
        val source = "class OrderService {\n    var count: Long = 0\n    constructor(count: Long, x: Int = 0) {\n        this.count = count\n    }\n}"
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        val check = step.complete(step.members)
        assertEquals(
            "OrderService(…) with 1 argument would call the new constructor",
            (check as NameCheck.Invalid).message,
        )
    }

    @Test
    fun `a vararg constructor reachable with the same argument count refuses`() {
        val source = "class OrderService {\n    var count: Int = 0\n    constructor(vararg xs: Long) {\n    }\n}"
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        val check = step.complete(step.members)
        assertEquals("OrderService(…) with 1 argument would call the new constructor", (check as NameCheck.Invalid).message)
    }

    @Test
    fun `a companion invoke refuses`() {
        val source = """
            class OrderService {
                var count: Int = 0
                companion object {
                    operator fun invoke(v: Int): OrderService = OrderService()
                }
            }
        """.trimIndent()
        assertEquals("OrderService's companion invoke() would lose its calls", run(constructor, source, currentTypes(source)).status)
    }

    @Test
    fun `a top-level function of the class name in this file refuses`() {
        val source = "class OrderService {\n    var count: Int = 0\n}\nfun OrderService(): OrderService = OrderService()"
        assertEquals("A top-level function OrderService(…) exists", run(constructor, source, currentTypes(source)).status)
    }

    @Test
    fun `a top-level function of the class name in project sources refuses`() {
        val source = "class OrderService {\n    var count: Int = 0\n}"
        val other = "/project/src/main/kotlin/demo/Factories.kt" to "fun OrderService(): OrderService = OrderService()"
        assertEquals(
            "A top-level function OrderService(…) exists",
            run(constructor, source, currentTypes(source, mapOf(other))).status,
        )
    }

    @Test
    fun `a class without any explicit constructor keeps the implicit one`() {
        val source = "class OrderService {\n    var count: Int = 0\n}"
        val text = run(constructor, source, currentTypes(source)).text ?: error("refused")
        assertTrue(text.contains("constructor()\n"), text)
        assertTrue(text.indexOf("constructor()") < text.indexOf("constructor(count: Int)"), text)
    }

    @Test
    fun `a supertype initialized in the header refuses`() {
        val source = "class OrderService : Base(1) {\n    var count: Int = 0\n}"
        assertEquals("OrderService initializes Base() in its header", run(constructor, source, currentTypes(source)).status)
    }

    @Test
    fun `a class supertype without parentheses delegates`() {
        val source = "class OrderService : Base {\n    constructor() : super()\n    var count: Int = 0\n}"
        val base = "/project/src/main/kotlin/demo/Base.kt" to "open class Base(val label: String = \"\")"
        val result = run(constructor, source, currentTypes(source, mapOf(base)))
        val text = result.text ?: error(result.status ?: "refused")
        assertTrue(text.contains("constructor(label: String, count: Int) : super(label) {"), text)
        assertTrue(text.contains("this.count = count"), text)
    }

    @Test
    fun `a super primary vararg is kept and passed with a star`() {
        val source = "class OrderService : Base {\n    constructor() : super()\n    var count: Int = 0\n}"
        val base = "/project/src/main/kotlin/demo/Base.kt" to "open class Base(vararg xs: Int)"
        val result = run(constructor, source, currentTypes(source, mapOf(base)))
        val text = result.text ?: error(result.status ?: "refused")
        assertTrue(text.contains("constructor(vararg xs: Int, count: Int) : super(*xs) {"), text)
    }

    @Test
    fun `super constructor parameters keep their backticks`() {
        val source = "class OrderService : Base {\n    constructor() : super()\n    var count: Int = 0\n}"
        val base = "/project/src/main/kotlin/demo/Base.kt" to "open class Base(val `is`: Int = 0)"
        val result = run(constructor, source, currentTypes(source, mapOf(base)))
        val text = result.text ?: error(result.status ?: "refused")
        assertTrue(text.contains("constructor(`is`: Int, count: Int) : super(`is`) {"), text)
    }

    @Test
    fun `a class supertype not in project sources refuses`() {
        val source = "class OrderService : Base {\n    constructor() : super()\n    var count: Int = 0\n}"
        assertEquals("Base is not in project sources", run(constructor, source, currentTypes(source)).status)
    }

    @Test
    fun `an unparenthesized supertype without constructors needs no super call`() {
        val source = "class OrderService : Lib {\n    var count: Int = 0\n}"
        val result = run(constructor, source, currentTypes(source))
        val text = result.text ?: error(result.status ?: "refused")
        assertFalse(text.contains("super"), text)
    }

    @Test
    fun `an interface outside project sources does not block a constructor`() {
        val source = """
            class OrderService : Comparable<OrderService> {
                var count: Int = 0
                override fun compareTo(other: OrderService): Int = 0
            }
        """.trimIndent()
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        assertEquals(listOf("count: Int"), step.members.map { it.text })
    }

    @Test
    fun `an unparenthesized library class with constructors refuses`() {
        val source = "class OrderService : Lib, P {\n    constructor() : super()\n    var count: Int = 0\n}"
        val p = "/project/src/main/kotlin/demo/P.kt" to "interface P {\n    fun f(): Int\n}"
        val result = run(constructor, source, currentTypes(source, mapOf(p)))
        assertEquals("Lib is not in project sources", result.status)
    }

    @Test
    fun `an abstract property cannot be assigned`() {
        val source = "abstract class OrderService {\n    abstract val x: Int\n    var y: Int = 0\n}"
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        assertEquals(listOf("y: Int"), step.members.map { it.text })
    }

    @Test
    fun `a var with a custom setter can be assigned`() {
        val source = """
            class OrderService {
                var count: Int = 0
                    set(value) {
                        field = value
                    }
            }
        """.trimIndent()
        val step = prepare(constructor, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        assertEquals(listOf("count: Int"), step.members.map { it.text })
    }

    @Test
    fun `a companion extension invoke refuses`() {
        val source = """
            class OrderService {
                var count: Int = 0
                companion object
            }
            operator fun OrderService.Companion.invoke(v: Int): OrderService = OrderService().apply { count = v }
        """.trimIndent()
        assertEquals(
            "OrderService's companion invoke() would lose its calls",
            run(constructor, source, currentTypes(source)).status,
        )
    }

    @Test
    fun `a top-level invoke in project sources refuses when the class has a companion`() {
        val source = "class OrderService {\n    var count: Int = 0\n    companion object\n}"
        val other = "/project/src/main/kotlin/demo/Factories.kt" to "fun invoke(): Int = 1"
        assertEquals(
            "OrderService's companion invoke() would lose its calls",
            run(constructor, source, currentTypes(source, mapOf(other))).status,
        )
    }

    @Test
    fun `a null index refuses`() {
        val source = "class OrderService {\n    var count: Int = 0\n}"
        assertEquals("Indexing… try again in a moment", run(constructor, source, null).status)
    }

    @Test
    fun `an enum without a primary constructor gets one`() {
        val source = "enum class E {\n    A;\n\n    var value: Int = 0\n}"
        val text = run(constructor, source, currentTypes(source)).text ?: error("refused")
        assertTrue(text.contains("constructor(value: Int) {"), text)
        assertTrue(text.indexOf("constructor(value: Int)") > text.indexOf(";"), text)
    }

    @Test
    fun `an enum without a semicolon refuses`() {
        val source = "enum class E {\n    A\n}"
        assertEquals("Add ; after the enum entries first", run(constructor, source, currentTypes(source)).status)
    }

    @Test
    fun `kinds the form does not allow are refused`() {
        assertEquals(
            "Secondary Constructor is not available in a class with a primary constructor",
            run(constructor, "data class A(val x: Int)", currentTypes("data class A(val x: Int)")).status,
        )
        assertEquals(
            "Secondary Constructor is not available in an object",
            run(constructor, "object A {\n}", currentTypes("object A {\n}")).status,
        )
        assertEquals(
            "Secondary Constructor is not available in an interface",
            run(constructor, "interface A {\n}", currentTypes("interface A {\n}")).status,
        )
        assertEquals(
            "Secondary Constructor is not available in an annotation class",
            run(constructor, "annotation class A", currentTypes("annotation class A")).status,
        )
    }

    @Test
    fun `the caret goes to the first assignment or after the last parameter`() {
        val withBody = "class A {\n    var x: Int = 0\n    constructor() {}\n}"
        val text = run(constructor, withBody, currentTypes(withBody)).text ?: error("refused")
        val caret = run(constructor, withBody, currentTypes(withBody)).caret ?: error("no caret")
        assertTrue(text.substring(caret).startsWith("this.x = x"), text.substring(caret))

        val empty = "class A {\n}"
        val emptyText = run(constructor, empty, currentTypes(empty)).text ?: error("refused")
        val emptyCaret = run(constructor, empty, currentTypes(empty)).caret ?: error("no caret")
        assertTrue(emptyText.substring(emptyCaret).startsWith(")"), emptyText.substring(emptyCaret))
        assertTrue(emptyText.substring(0, emptyCaret).endsWith("constructor("), emptyText.substring(0, emptyCaret))
    }
}
