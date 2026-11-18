package stride.lang.kotlin.generate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.GenerateKind
import stride.lang.RefactoringStep

class KotlinGenerateMembersTest {
    private val overrideKind = GenerateKind.OVERRIDE_METHODS
    private val implementKind = GenerateKind.IMPLEMENT_METHODS

    private val repo = """
        package demo

        abstract class Repo<T> {
            abstract fun save(x: T)
            open fun find(id: Int): T? = null
            fun helper() {}
            private fun secret() {}
            open val name: String = ""
            open var level: Int = 0
            abstract var count: Int
            abstract fun load(vararg ids: Int): T
            open fun join(vararg ids: Int): String = ""
            open fun ping(): Int = 1
        }
    """.trimIndent()

    private fun orderService(supertype: String = "Repo<User>()"): String =
        "package demo\n\nclass OrderService : $supertype {\n}"

    private fun types(source: String, vararg others: Pair<String, String>): FakeProjectTypes =
        currentTypes(source, mapOf(REPO_PATH to repo, USER_PATH to "package demo\n\nclass User") + others)

    @Test
    fun `implement lists the abstract members checked with substituted types`() {
        val step = prepare(implementKind, orderService(), types(orderService())) as RefactoringStep.ChooseMembers
        val texts = step.members.map { it.text }
        assertTrue("fun save(x: User)" in texts, texts.toString())
        assertTrue("var count: Int" in texts, texts.toString())
        assertTrue("fun load(vararg ids: Int): User" in texts, texts.toString())
        assertFalse(texts.any { it.contains("find") }, texts.toString())
        assertTrue(step.members.all { it.checked })
        assertTrue(step.members.all { it.group == "Repo" }, step.members.map { it.group }.toString())
    }

    @Test
    fun `override lists every overridable member unchecked`() {
        val step = prepare(overrideKind, orderService(), types(orderService())) as RefactoringStep.ChooseMembers
        val texts = step.members.map { it.text }
        assertTrue("fun save(x: User)" in texts, texts.toString())
        assertTrue("fun find(id: Int): User?" in texts, texts.toString())
        assertTrue("fun load(vararg ids: Int): User" in texts, texts.toString())
        assertTrue("val name: String" in texts, texts.toString())
        assertTrue("var count: Int" in texts, texts.toString())
        assertFalse(texts.any { it.contains("helper") }, texts.toString())
        assertFalse(texts.any { it.contains("secret") }, texts.toString())
        assertTrue(step.members.none { it.checked })
    }

    @Test
    fun `implemented code calls super and abstracts write TODO`() {
        val source = orderService()
        val text = run(overrideKind, source, types(source), setOf("fun find(id: Int): User?", "fun save(x: User)")).text
            ?: error("refused")
        assertTrue(
            text.contains("override fun find(id: Int): User? {\n        return super.find(id)\n    }"),
            text,
        )
        assertTrue(text.contains("override fun save(x: User) {\n        TODO(\"Not yet implemented\")\n    }"), text)
    }

    @Test
    fun `a vararg passes with a star`() {
        val source = orderService()
        val text = run(overrideKind, source, types(source), setOf("fun join(vararg ids: Int): String")).text ?: error("refused")
        assertTrue(text.contains("return super.join(*ids)"), text)
    }

    @Test
    fun `two direct supertypes need super with the owner`() {
        val other = "package demo\n\nabstract class Store {\n    open fun ping(): Int = 2\n}"
        val source = "package demo\n\nclass OrderService : Repo<User>(), Store() {\n}"
        val text = run(overrideKind, source, types(source, STORE_PATH to other), setOf("fun ping(): Int")).text
            ?: error("refused")
        assertTrue(text.contains("return super<Repo>.ping()"), text)
    }

    @Test
    fun `properties become getters and var setters`() {
        val source = orderService()
        val text = run(overrideKind, source, types(source), setOf("val name: String", "var level: Int")).text
            ?: error("refused")
        assertTrue(text.contains("override val name: String\n        get() = super.name"), text)
        assertTrue(text.contains("override var level: Int"), text)
        assertTrue(text.contains("set(value) {\n            super.level = value\n        }"), text)
    }

    @Test
    fun `implement properties write TODO and an empty setter`() {
        val source = orderService()
        val text = run(implementKind, source, types(source), setOf("var count: Int")).text ?: error("refused")
        assertTrue(text.contains("override var count: Int"), text)
        assertTrue(text.contains("get() = TODO(\"Not yet implemented\")"), text)
        assertTrue(text.contains("set(value) {}"), text)
    }

    @Test
    fun `type arguments compose along the walk`() {
        val middle = "package demo\n\nabstract class Middle<T> : Repo<T>() {\n}"
        val source = "package demo\n\nclass OrderService : Middle<User>() {\n}"
        val text = run(implementKind, source, types(source, MIDDLE_PATH to middle), setOf("fun save(x: User)")).text
            ?: error("refused")
        assertTrue(text.contains("override fun save(x: User) {"), text)
    }

    @Test
    fun `a star projection disables the member`() {
        val source = "package demo\n\nclass OrderService : Repo<*>() {\n}"
        val step = prepare(implementKind, source, types(source)) as RefactoringStep.ChooseMembers
        val save = step.members.first { it.text.contains("save") }
        assertTrue(save.disabledReason != null, save.text)
    }

    @Test
    fun `an out projection disables the member`() {
        val source = "package demo\n\nclass OrderService : Repo<out User>() {\n}"
        val step = prepare(implementKind, source, types(source)) as RefactoringStep.ChooseMembers
        val save = step.members.first { it.text.contains("save") }
        assertTrue(save.disabledReason != null, save.text)
    }

    @Test
    fun `a member type parameter with a substituted name disables the member`() {
        val collide = "package demo\n\nabstract class Colliding<T> {\n    abstract fun <User> map(x: User): User\n}"
        val source = "package demo\n\nclass OrderService : Colliding<User>() {\n}"
        val step = prepare(implementKind, source, types(source, COLLIDING_PATH to collide)) as RefactoringStep.ChooseMembers
        assertEquals("member type parameter User is already used", step.members.single().disabledReason)
    }

    @Test
    fun `a star import in the supertype file disables the member`() {
        val base = "package demo\n\nimport model.*\n\nabstract class Store {\n    abstract fun save(x: Unknown)\n}"
        val source = "package demo\n\nclass OrderService : Store() {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertEquals("refers to another Unknown here", step.members.single().disabledReason)
    }

    @Test
    fun `an alias in the supertype file disables the member`() {
        val base = "package demo\n\nimport model.Thing as Unknown\n\nabstract class Store {\n    abstract fun save(x: Unknown)\n}"
        val source = "package demo\n\nclass OrderService : Store() {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertEquals("refers to another Unknown here", step.members.single().disabledReason)
    }

    @Test
    fun `a member the class already declares is left out`() {
        val source = "package demo\n\nclass OrderService : Repo<User>() {\n    override fun save(x: User) {\n    }\n}"
        val step = prepare(overrideKind, source, types(source)) as RefactoringStep.ChooseMembers
        assertFalse(step.members.any { it.text == "fun save(x: User)" }, step.members.map { it.text }.toString())
    }

    @Test
    fun `a primary constructor property is left out of implement and override`() {
        val p = "package demo\n\ninterface P {\n    val x: Int\n    var count: Int\n}"
        val source = "package demo\n\nclass OrderService(override val x: Int, override var count: Int) : P {\n}"
        val files = mapOf(P_PATH to p)
        assertEquals(
            "No members to implement in project sources",
            run(implementKind, source, currentTypes(source, files)).status,
        )
        val override = prepare(overrideKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
        assertFalse(override.members.any { it.text == "val x: Int" }, override.members.map { it.text }.toString())
        assertFalse(override.members.any { it.text == "var count: Int" }, override.members.map { it.text }.toString())
    }

    @Test
    fun `a supertype function with an expression body and no return type is disabled`() {
        val base = "package demo\n\nabstract class Base {\n    open fun name() = \"x\"\n}"
        val source = "package demo\n\nclass OrderService : Base() {\n}"
        val step = prepare(overrideKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertEquals("type not written", step.members.first { it.text.contains("name") }.disabledReason)
    }

    @Test
    fun `an unrelated interface expression body without a return type is disabled for implement`() {
        val b = "package demo\n\nopen class B {\n    open fun name() = \"x\"\n}"
        val q = "package demo\n\ninterface Q {\n    fun name() = \"y\"\n}"
        val source = "package demo\n\nclass OrderService : B(), Q {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(BASE_PATH to b, Q_PATH to q))) as RefactoringStep.ChooseMembers
        assertEquals("type not written", step.members.first { it.text.contains("name") }.disabledReason)
    }

    @Test
    fun `an interface lists no Any group`() {
        val base = "package demo\n\ninterface Base {\n    fun ping(): Int\n}"
        val source = "package demo\n\ninterface OrderService : Base {\n}"
        val step = prepare(overrideKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertEquals(listOf("fun ping(): Int"), step.members.map { it.text })
    }

    @Test
    fun `an interface without supertypes has no members to implement`() {
        val source = "package demo\n\ninterface OrderService {\n}"
        assertEquals("No members to implement in project sources", run(overrideKind, source, currentTypes(source)).status)
        assertEquals("No members to implement in project sources", run(overrideKind, source, null).status)
    }

    @Test
    fun `the nearest declaration along the walk wins`() {
        val middle = "package demo\n\nabstract class Middle : Repo<User>() {\n    override fun save(x: User) {\n    }\n}"
        val source = "package demo\n\nclass OrderService : Middle() {\n}"
        val step = prepare(overrideKind, source, types(source, MIDDLE_PATH to middle)) as RefactoringStep.ChooseMembers
        assertEquals(1, step.members.count { it.text == "fun save(x: User)" }, step.members.map { it.text }.toString())
        assertEquals("Middle", step.members.first { it.text == "fun save(x: User)" }.group)
    }

    @Test
    fun `a member extension is listed disabled`() {
        val extension = "package demo\n\ninterface Ext {\n    fun String.trimAll(): String\n}"
        val source = "package demo\n\nclass OrderService : Ext {\n}"
        val step = prepare(implementKind, source, types(source, EXT_PATH to extension)) as RefactoringStep.ChooseMembers
        assertEquals("member extensions are not generated", step.members.single().disabledReason)
    }

    @Test
    fun `an import is added for a project declaration`() {
        val base = "package demo\n\nimport model.User\n\nabstract class Store {\n    abstract fun save(x: User)\n}"
        val source = "package demo\n\nclass OrderService : Store() {\n}"
        val files = mapOf(
            BASE_PATH to base,
            USER_PATH to "package model\n\nclass User",
        )
        val text = run(implementKind, source, currentTypes(source, files), setOf("fun save(x: User)")).text
            ?: error("refused")
        assertTrue(text.contains("import model.User"), text)
    }

    @Test
    fun `an import is added for a library type the supertype imports`() {
        val base = "package demo\n\nimport java.time.Instant\n\nabstract class Store {\n    abstract fun at(x: Instant)\n}"
        val source = "package demo\n\nclass OrderService : Store() {\n}"
        val text = run(implementKind, source, currentTypes(source, mapOf(BASE_PATH to base)), setOf("fun at(x: Instant)")).text
            ?: error("refused")
        assertTrue(text.contains("import java.time.Instant"), text)
    }

    @Test
    fun `no import is added when the target already resolves the type`() {
        val base = "package demo\n\nimport model.User\n\nabstract class Store {\n    abstract fun save(x: User)\n}"
        val source = "package demo\n\nimport model.User\n\nclass OrderService : Store() {\n}"
        val files = mapOf(BASE_PATH to base, USER_PATH to "package model\n\nclass User")
        val text = run(implementKind, source, currentTypes(source, files), setOf("fun save(x: User)")).text
            ?: error("refused")
        assertEquals(1, Regex("import model.User").findAll(text).count(), text)
    }

    @Test
    fun `a type that means another declaration here disables the member`() {
        val base = "package demo\n\nabstract class Store {\n    abstract fun save(x: Repo)\n}"
        val source = "package demo\n\nimport other.Repo\n\nclass OrderService : Store() {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertEquals("refers to another Repo here", step.members.single().disabledReason)
    }

    @Test
    fun `an interface outside project sources only drops members and names itself in the title`() {
        val base = "package demo\n\nabstract class Store {\n    abstract fun save()\n}"
        val source = "package demo\n\nclass OrderService : Store(), Runnable {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertTrue(step.title.contains("Runnable is not in project sources"), step.title)
        assertEquals(listOf("fun save()"), step.members.map { it.text })
    }

    @Test
    fun `implement refuses when a library class sits in the hierarchy`() {
        val source = "package demo\n\nclass OrderService : Base(), Runnable {\n}"
        val result = run(implementKind, source, currentTypes(source))
        assertEquals("Base is not in project sources; it may implement these members", result.status)
    }

    @Test
    fun `nothing to implement refuses and override falls back to Any`() {
        val source = "package demo\n\nclass OrderService {\n}"
        assertEquals("No members to implement in project sources", run(implementKind, source, currentTypes(source)).status)
        val step = prepare(overrideKind, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        assertEquals(listOf("Any", "Any", "Any"), step.members.map { it.group })
    }

    @Test
    fun `an existing equals member keeps Any from offering it`() {
        val source = "package demo\n\nclass OrderService {\n    override fun equals(other: Any?): Boolean = false\n}"
        val step = prepare(overrideKind, source, currentTypes(source)) as RefactoringStep.ChooseMembers
        assertFalse(step.members.any { it.text.contains("equals") }, step.members.map { it.text }.toString())
    }

    @Test
    fun `a class declaring every Any member has nothing to override`() {
        val source = "package demo\n\nclass OrderService {\n    override fun equals(other: Any?): Boolean = false\n    override fun hashCode(): Int = 0\n    override fun toString(): String = \"\"\n}"
        assertEquals("No members to implement in project sources", run(overrideKind, source, currentTypes(source)).status)
    }

    @Test
    fun `a null index lists Any for override and refuses implement`() {
        assertEquals("No members to implement in project sources", run(implementKind, "package demo\n\nclass A {\n}", null).status)
        val step = prepare(overrideKind, "package demo\n\nclass A {\n}", null) as RefactoringStep.ChooseMembers
        assertEquals(3, step.members.size)
        val withSuper = run(implementKind, "package demo\n\nclass A : Base() {\n}", null)
        assertEquals("Indexing… try again in a moment", withSuper.status)
    }

    @Test
    fun `override lists Any members with the other candidates`() {
        val base = "package demo\n\nabstract class Base {\n    open fun ping(): Int = 1\n}"
        val source = "package demo\n\nclass OrderService : Base() {\n}"
        val step = prepare(overrideKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertTrue("fun ping(): Int" in step.members.map { it.text }, step.members.map { it.text }.toString())
        assertEquals(
            listOf("fun equals(other: Any?): Boolean", "fun hashCode(): Int", "fun toString(): String"),
            step.members.filter { it.group == "Any" }.map { it.text },
        )
    }

    @Test
    fun `overloads of Any members keep the Any rows`() {
        val base = "package demo\n\nabstract class Base {\n    fun equals(o: Base): Boolean = false\n}"
        val source = "package demo\n\nclass OrderService : Base() {\n    fun toString(prefix: String): String = prefix\n}"
        val step = prepare(overrideKind, source, currentTypes(source, mapOf(BASE_PATH to base))) as RefactoringStep.ChooseMembers
        assertEquals(
            listOf("fun equals(other: Any?): Boolean", "fun hashCode(): Int", "fun toString(): String"),
            step.members.filter { it.group == "Any" }.map { it.text },
        )
    }

    @Test
    fun `an unparenthesized library class with constructors refuses implement`() {
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val source = "package demo\n\nclass OrderService : Lib, P {\n    constructor() : super()\n}"
        val result = run(implementKind, source, currentTypes(source, mapOf(P_PATH to p)))
        assertEquals("Lib is not in project sources; it may implement these members", result.status)
    }

    @Test
    fun `a class with a primary constructor treats an unparenthesized library type as an interface`() {
        val p = "package demo\n\ninterface ProjectIface {\n    fun f(): Int\n}"
        val source = "package demo\n\nclass Impl(val r: Repo) : ProjectIface, Serializable {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(P_PATH to p))) as RefactoringStep.ChooseMembers
        assertTrue(step.title.contains("Serializable is not in project sources"), step.title)
        assertEquals(listOf("fun f(): Int"), step.members.map { it.text })
    }

    @Test
    fun `an unparenthesized supertype without constructors is an interface`() {
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val source = "package demo\n\nclass OrderService : Lib, P {\n}"
        val step = prepare(implementKind, source, currentTypes(source, mapOf(P_PATH to p))) as RefactoringStep.ChooseMembers
        assertTrue(step.title.contains("Lib is not in project sources"), step.title)
        assertEquals(listOf("fun f(): Int"), step.members.map { it.text })
    }

    @Test
    fun `an interface default body counts as implemented in either order`() {
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val r = "package demo\n\ninterface R : P {\n    override fun f(): Int = 7\n}"
        val files = mapOf(P_PATH to p, R_PATH to r)
        for (supertypes in listOf("P, R", "R, P")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val result = run(implementKind, source, currentTypes(source, files))
            assertEquals("No members to implement in project sources", result.status, supertypes)
        }
    }

    @Test
    fun `an independent interface default body does not implement`() {
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n}"
        val files = mapOf(P_PATH to p, Q_PATH to q)
        for (supertypes in listOf("P, Q", "Q, P")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("fun f(): Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
        }
    }

    @Test
    fun `an independent interface property default body does not implement`() {
        val p = "package demo\n\ninterface P {\n    val x: Int\n}"
        val q = "package demo\n\ninterface Q {\n    val x: Int get() = 1\n}"
        val files = mapOf(P_PATH to p, Q_PATH to q)
        for (supertypes in listOf("P, Q", "Q, P")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("val x: Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
        }
    }

    @Test
    fun `a class body implements an interface member without an override chain`() {
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val b = "package demo\n\nabstract class B {\n    fun f(): Int = 1\n}"
        val source = "package demo\n\nclass OrderService : B(), P {\n}"
        val result = run(implementKind, source, currentTypes(source, mapOf(P_PATH to p, BASE_PATH to b)))
        assertEquals("No members to implement in project sources", result.status)
    }

    @Test
    fun `a private class body does not implement`() {
        val b = "package demo\n\nopen class B {\n    private fun f(): Int = 1\n}"
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val files = mapOf(P_PATH to p, BASE_PATH to b)
        for (supertypes in listOf("B(), P", "P, B()")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("fun f(): Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
            val text = run(implementKind, source, currentTypes(source, files), setOf("fun f(): Int")).text
                ?: error("refused")
            assertTrue(text.contains("override fun f(): Int {\n        TODO(\"Not yet implemented\")\n    }"), text)
        }
    }

    @Test
    fun `a private class property body does not implement`() {
        val b = "package demo\n\nopen class B {\n    private val x: Int = 1\n}"
        val p = "package demo\n\ninterface P {\n    val x: Int\n}"
        val files = mapOf(P_PATH to p, BASE_PATH to b)
        for (supertypes in listOf("B(), P", "P, B()")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("val x: Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
            val text = run(implementKind, source, currentTypes(source, files), setOf("val x: Int")).text
                ?: error("refused")
            assertTrue(text.contains("override val x: Int\n        get() = TODO(\"Not yet implemented\")"), text)
        }
    }

    @Test
    fun `two interface bodies that do not override each other are listed`() {
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n}"
        val r = "package demo\n\ninterface R : P {\n    override fun f(): Int = 7\n}"
        val files = mapOf(P_PATH to p, Q_PATH to q, R_PATH to r)
        for (supertypes in listOf("Q, R", "R, Q")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("fun f(): Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
            val text = run(implementKind, source, currentTypes(source, files), setOf("fun f(): Int")).text
                ?: error("refused")
            assertTrue(text.contains("override fun f(): Int {\n        TODO(\"Not yet implemented\")\n    }"), text)
        }
    }

    @Test
    fun `two interface bodies without an abstract declaration are listed`() {
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n}"
        val s = "package demo\n\ninterface S {\n    fun f(): Int = 8\n}"
        val files = mapOf(Q_PATH to q, S_PATH to s)
        for (supertypes in listOf("Q, S", "S, Q")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("fun f(): Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
            val text = run(implementKind, source, currentTypes(source, files), setOf("fun f(): Int")).text
                ?: error("refused")
            assertTrue(text.contains("override fun f(): Int {\n        TODO(\"Not yet implemented\")\n    }"), text)
        }
    }

    @Test
    fun `an interface body overriding another body is not listed`() {
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n}"
        val r = "package demo\n\ninterface R : Q {\n    override fun f(): Int = 8\n}"
        val files = mapOf(Q_PATH to q, R_PATH to r)
        for (supertypes in listOf("Q, R", "R, Q")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            assertEquals(
                "No members to implement in project sources",
                run(implementKind, source, currentTypes(source, files)).status,
                supertypes,
            )
        }
    }

    @Test
    fun `an open class body and an unrelated interface body are listed`() {
        val b = "package demo\n\nopen class B {\n    open fun f(): Int = 1\n}"
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n}"
        val files = mapOf(BASE_PATH to b, Q_PATH to q)
        for (supertypes in listOf("B(), Q", "Q, B()")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("fun f(): Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
            val text = run(implementKind, source, currentTypes(source, files), setOf("fun f(): Int")).text
                ?: error("refused")
            assertTrue(text.contains("override fun f(): Int {\n        TODO(\"Not yet implemented\")\n    }"), text)
        }
    }

    @Test
    fun `a final class body and an unrelated interface body are listed but disabled`() {
        // Kotlin requires an override here and rejects one, since B.f is final: no generated text compiles.
        val b = "package demo\n\nopen class B {\n    fun f(): Int = 1\n    val x: Int = 1\n}"
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n    val x: Int get() = 7\n}"
        val files = mapOf(BASE_PATH to b, Q_PATH to q)
        for (supertypes in listOf("B(), Q", "Q, B()")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("f is final in B", "x is final in B"), step.members.map { it.disabledReason }, supertypes)
            assertTrue(step.members.none { it.checked }, supertypes)
        }
    }

    @Test
    fun `a class body and an abstract interface declaration are not listed in either order`() {
        val b = "package demo\n\nopen class B {\n    fun f(): Int = 1\n}"
        val p = "package demo\n\ninterface P {\n    fun f(): Int\n}"
        val files = mapOf(BASE_PATH to b, P_PATH to p)
        for (supertypes in listOf("B(), P", "P, B()")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            assertEquals(
                "No members to implement in project sources",
                run(implementKind, source, currentTypes(source, files)).status,
                supertypes,
            )
        }
    }

    @Test
    fun `an abstract class declaration and an unrelated interface body are listed`() {
        val b = "package demo\n\nabstract class B {\n    abstract fun f(): Int\n}"
        val q = "package demo\n\ninterface Q {\n    fun f(): Int = 7\n}"
        val files = mapOf(BASE_PATH to b, Q_PATH to q)
        for (supertypes in listOf("B(), Q", "Q, B()")) {
            val source = "package demo\n\nclass OrderService : $supertypes {\n}"
            val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
            assertEquals(listOf("fun f(): Int"), step.members.map { it.text }, supertypes)
            assertTrue(step.members.single().checked, supertypes)
        }
    }

    @Test
    fun `a default import type needs no import`() {
        val base = "package demo\n\nabstract class Store {\n    abstract fun at(t: Thread)\n}"
        val source = "package demo\n\nclass OrderService : Store() {\n}"
        val files = mapOf(BASE_PATH to base)
        val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
        assertNull(step.members.single().disabledReason)
        val text = run(implementKind, source, currentTypes(source, files), setOf("fun at(t: Thread)")).text
            ?: error("refused")
        assertFalse(text.contains("import"), text)
    }

    @Test
    fun `a target star import that already uses the name disables the member`() {
        val base = "package demo\n\nimport model.User\n\nabstract class Store {\n    abstract fun save(u: User)\n}"
        val user = "package model\n\nclass User"
        val source = "package demo\n\nimport other.*\n\nclass OrderService : Store() {\n    fun show(u: User) = u\n}"
        val files = mapOf(BASE_PATH to base, USER_PATH to user)
        val step = prepare(implementKind, source, currentTypes(source, files)) as RefactoringStep.ChooseMembers
        assertEquals("User may come from import other.*", step.members.single().disabledReason)
    }

    @Test
    fun `a target star import whose name is unused still gets the import`() {
        val base = "package demo\n\nimport model.User\n\nabstract class Store {\n    abstract fun save(u: User)\n}"
        val user = "package model\n\nclass User"
        val source = "package demo\n\nimport other.*\n\nclass OrderService : Store() {\n}"
        val files = mapOf(BASE_PATH to base, USER_PATH to user)
        val text = run(implementKind, source, currentTypes(source, files), setOf("fun save(u: User)")).text
            ?: error("refused")
        assertTrue(text.contains("import model.User"), text)
    }

    @Test
    fun `imports and members use the target line separator`() {
        val base = "package demo\n\nimport model.User\n\nabstract class Store {\n    abstract fun save(u: User)\n}"
        val user = "package model\n\nclass User"
        val source = "package demo\r\n\r\nclass OrderService : Store() {\r\n}\r\n"
        val files = mapOf(BASE_PATH to base, USER_PATH to user)
        val text = run(implementKind, source, currentTypes(source, files), setOf("fun save(u: User)")).text
            ?: error("refused")
        val shown = text.replace("\r\n", "<CRLF>")
        assertTrue(text.contains("import model.User\r\n"), shown)
        assertFalse(text.replace("\r\n", "").contains("\n"), shown)
    }

    private companion object {
        const val REPO_PATH = "/project/src/main/kotlin/demo/Repo.kt"
        const val USER_PATH = "/project/src/main/kotlin/demo/User.kt"
        const val STORE_PATH = "/project/src/main/kotlin/demo/Store.kt"
        const val MIDDLE_PATH = "/project/src/main/kotlin/demo/Middle.kt"
        const val COLLIDING_PATH = "/project/src/main/kotlin/demo/Colliding.kt"
        const val EXT_PATH = "/project/src/main/kotlin/demo/Ext.kt"
        const val BASE_PATH = "/project/src/main/kotlin/demo/Base.kt"
        const val P_PATH = "/project/src/main/kotlin/demo/P.kt"
        const val Q_PATH = "/project/src/main/kotlin/demo/Q.kt"
        const val R_PATH = "/project/src/main/kotlin/demo/R.kt"
        const val S_PATH = "/project/src/main/kotlin/demo/S.kt"
    }
}
