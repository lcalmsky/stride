package stride.lang.kotlin.refactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinCodeModelTest {
    private fun model(text: String) = KotlinCode(text)

    private fun blocks(text: String, kind: BlockKind) = model(text).blocks.filter { it.kind == kind }

    private fun bodyStatements(text: String): List<String> {
        val code = model(text)
        return code.statements
            .filter { it.block.kind == BlockKind.FUNCTION_BODY && it.block.parent == null }
            .map { code.text.substring(it.start, it.end) }
    }

    private fun local(text: String, name: String, kind: LocalKind? = null): KtLocal {
        val code = model(text)
        return code.locals.first { it.name == name && (kind == null || it.kind == kind) }
    }

    private fun rangeOf(text: String, snippet: String): KtRange {
        val start = text.indexOf(snippet)
        check(start >= 0) { "missing snippet: $snippet" }
        return KtRange(start, start + snippet.length)
    }


    @Test
    fun `a multi-line if header opens a control body`() {
        val text = "fun a(x: Int) {\n    if (x > 0 &&\n        x < 10) {\n        foo()\n    }\n}\n"
        val control = blocks(text, BlockKind.CONTROL_BODY)
        assertEquals(1, control.size)
        assertEquals("if", control.single().headerKeyword)
        assertFalse(control.single().loopBody)
    }

    @Test
    fun `an expression body lambda is a lambda`() {
        val text = "fun f() = run {\n    val a = 1\n}\n"
        assertEquals(listOf(BlockKind.LAMBDA), model(text).blocks.map { it.kind })
        assertTrue(model(text).blocks.single().parent == null)
    }

    @Test
    fun `an anonymous object opens a class body`() {
        val text = "class C {\n    val x = object : Runnable {\n        override fun run() {\n        }\n    }\n}\n"
        val classBodies = blocks(text, BlockKind.CLASS_BODY)
        assertEquals(2, classBodies.size)
        assertEquals(listOf("class", "object"), classBodies.map { it.headerKeyword })
    }

    @Test
    fun `when bodies and branch blocks are told apart`() {
        val text = "fun f(x: Int) {\n    when (x) {\n        1 -> {\n            foo()\n        }\n        else -> bar()\n    }\n}\n"
        val whenBodies = blocks(text, BlockKind.WHEN_BODY)
        assertEquals(1, whenBodies.size)
        val branch = blocks(text, BlockKind.CONTROL_BODY).single()
        assertEquals(BlockKind.WHEN_BODY, branch.parent?.kind)
    }

    @Test
    fun `accessor and initializer blocks`() {
        val text = "class C {\n    val x: Int\n        get() {\n            return 1\n        }\n    init {\n        foo()\n    }\n}\n"
        val code = model(text)
        assertEquals(listOf("get"), code.blocks.filter { it.kind == BlockKind.FUNCTION_BODY }.map { it.headerKeyword })
        assertEquals(1, code.blocks.count { it.kind == BlockKind.INITIALIZER })
    }

    @Test
    fun `a trailing lambda is a lambda and a loop body is a loop body`() {
        val text = "fun f() {\n    list.map {\n        it + 1\n    }\n    while (a) {\n        foo()\n    }\n}\n"
        val code = model(text)
        assertEquals(1, code.blocks.count { it.kind == BlockKind.LAMBDA })
        val loop = code.blocks.first { it.kind == BlockKind.CONTROL_BODY }
        assertEquals("while", loop.headerKeyword)
        assertTrue(loop.loopBody)
    }

    @Test
    fun `a brace inside a string template is code`() {
        val text = "fun f() {\n    val s = \"\${ if (a) {\n        b\n    } }\"\n}\n"
        val code = model(text)
        assertEquals(1, code.blocks.count { it.kind == BlockKind.CONTROL_BODY && it.headerKeyword == "if" })
    }


    @Test
    fun `a line ending with an operator continues the statement`() {
        val text = "fun f() {\n    val x = a +\n        b\n}\n"
        assertEquals(listOf("val x = a +\n        b"), bodyStatements(text))
    }

    @Test
    fun `lines starting with a member, elvis or and continue the statement`() {
        val text = "fun f() {\n    val x = list\n        .size\n    val y = a\n        ?: b\n    val z = a\n        && b\n}\n"
        assertEquals(
            listOf("val x = list\n        .size", "val y = a\n        ?: b", "val z = a\n        && b"),
            bodyStatements(text),
        )
    }

    @Test
    fun `else, catch and finally stay with their statement`() {
        val text = "fun f() {\n    if (a) {\n        foo()\n    } else {\n        bar()\n    }\n    try {\n        foo()\n    } catch (e: E) {\n        bar()\n    } finally {\n        baz()\n    }\n}\n"
        val statements = bodyStatements(text)
        assertEquals(2, statements.size)
        assertTrue(statements[0].contains("else"))
        assertTrue(statements[1].contains("catch"))
        assertTrue(statements[1].contains("finally"))
    }

    @Test
    fun `do while stays one statement`() {
        val text = "fun f() {\n    do {\n        foo()\n    } while (a > 0)\n    bar()\n}\n"
        val statements = bodyStatements(text)
        assertEquals(2, statements.size)
        assertTrue(statements[0].contains("while (a > 0)"))
    }

    @Test
    fun `an annotation line joins the declaration below it`() {
        val text = "fun f() {\n    @Ann\n    val x = 1\n}\n"
        assertEquals(listOf("@Ann\n    val x = 1"), bodyStatements(text))
    }

    @Test
    fun `semicolons split statements`() {
        val text = "fun f() {\n    val a = 1; val b = 2\n}\n"
        assertEquals(listOf("val a = 1", "val b = 2"), bodyStatements(text))
    }

    @Test
    fun `a lambda parameter list is not a statement`() {
        val text = "fun f() {\n    list.map { x ->\n        x + 1\n    }\n}\n"
        val code = model(text)
        val lambdaStatements = code.statements.filter { it.block.kind == BlockKind.LAMBDA }
        assertEquals(listOf("x + 1"), lambdaStatements.map { code.text.substring(it.start, it.end) })
    }

    @Test
    fun `a statement that starts with a string keeps the literal`() {
        val text = "fun f() {\n    println(\"a\".repeat(2))\n}\n"
        assertEquals(listOf("println(\"a\".repeat(2))"), bodyStatements(text))
    }


    @Test
    fun `parameters and lambda parameters are locals`() {
        val text = "fun f(v: Int) {\n    list.map { it + v }\n    list.map { x -> x }\n}\n"
        val code = model(text)
        assertEquals(setOf("v", "it", "x"), code.locals.map { it.name }.toSet())
        val it = code.locals.first { it.name == "it" }
        assertEquals(LocalKind.LAMBDA_PARAMETER, it.kind)
        val reference = code.references.first { it.name == "it" }
        assertEquals(it, reference.resolved)
    }

    @Test
    fun `an inner val shadows a parameter`() {
        val text = "fun f(x: Int) {\n    val x = 1\n    println(x)\n}\n"
        val code = model(text)
        val printed = code.references.last { it.name == "x" }
        assertEquals(LocalKind.VAL, printed.resolved?.kind)
        assertEquals(2, code.locals.count { it.name == "x" })
    }

    @Test
    fun `a reference before a declaration does not resolve to it`() {
        val text = "fun f() {\n    println(x)\n    val x = 1\n}\n"
        val code = model(text)
        assertNull(code.references.first { it.name == "x" }.resolved)
    }

    @Test
    fun `for, catch, when subject and destructuring are locals`() {
        val text = "fun f(map: Map<Int, Int>) {\n    for ((k, v) in map) {\n        println(k)\n    }\n    try {\n        foo()\n    } catch (e: Exception) {\n        println(e)\n    }\n    when (val w = 1) {\n        1 -> println(w)\n        else -> println(0)\n    }\n    val (p, q) = map.entries.first()\n}\n"
        val code = model(text)
        val expected = mapOf(
            "k" to LocalKind.FOR,
            "v" to LocalKind.FOR,
            "e" to LocalKind.CATCH,
            "w" to LocalKind.WHEN_SUBJECT,
            "p" to LocalKind.VAL,
            "q" to LocalKind.VAL,
        )
        for ((name, kind) in expected) {
            val found = code.locals.first { it.name == name }
            assertEquals(kind, found.kind, name)
            assertEquals(name in setOf("p", "q", "k", "v"), found.destructured, name)
        }
        assertTrue(code.locals.first { it.name == "e" }.writtenType == "Exception")
    }

    @Test
    fun `delegated and lateinit locals are marked`() {
        val text = "fun f() {\n    lateinit var a: String\n    val b by lazy { 1 }\n}\n"
        val code = model(text)
        assertTrue(code.locals.first { it.name == "a" }.lateinit)
        assertTrue(code.locals.first { it.name == "b" }.delegated)
    }

    @Test
    fun `a local function is a local`() {
        val text = "fun f() {\n    fun inner() = 1\n    println(inner())\n}\n"
        val code = model(text)
        val inner = code.locals.first { it.name == "inner" }
        assertEquals(LocalKind.LOCAL_FUN, inner.kind)
        assertEquals(inner, code.references.first { it.name == "inner" }.resolved)
    }

    @Test
    fun `a declaration name is not a reference`() {
        val text = "fun f() {\n    val value = 1\n}\n"
        assertTrue(model(text).references.isEmpty())
    }


    @Test
    fun `template references are simple and expression templates`() {
        val text = "fun f(v: Int) {\n    println(\"\$v \${v + 1}\")\n}\n"
        val code = model(text)
        val vReferences = code.references.filter { it.name == "v" }
        assertEquals(listOf(TemplateUsage.SIMPLE, TemplateUsage.EXPRESSION), vReferences.map { it.usage })
        assertTrue(vReferences.all { it.resolved?.kind == LocalKind.PARAMETER })
    }

    @Test
    fun `named arguments, members, labels and annotations are not references`() {
        val text = "fun f() {\n    foo(name = bar)\n    val x = a.b\n    loop@ while (true) {\n        break@loop\n    }\n}\n"
        val names = model(text).references.map { it.name }
        assertFalse("name" in names)
        assertFalse("b" in names)
        assertFalse("loop" in names)
        assertTrue("bar" in names)
        assertTrue("a" in names)
    }


    @Test
    fun `calls are parentheses, indexing and trailing lambdas`() {
        val text = "fun f() {\n    foo(1)\n    list[0]\n    list.map { it }\n    Foo()\n}\n"
        val calls = model(text).calls
        assertEquals(4, calls.size)
        assertEquals(
            listOf(CallKind.PARENTHESES, CallKind.INDEX, CallKind.LAMBDA, CallKind.PARENTHESES),
            calls.map { it.kind },
        )
    }

    @Test
    fun `a function declaration parameter list is not a call`() {
        val text = "fun f(x: Int) {\n    println(x)\n}\n"
        assertEquals(1, model(text).calls.size)
    }

    @Test
    fun `effects are calls, assignments and increments`() {
        val text = "fun f() {\n    val a = 1\n    var b = 2\n    b = f()\n    b += 1\n    b++\n}\n"
        val code = model(text)
        val kinds = code.effects.map { it.kind }
        assertEquals(1, kinds.count { it == EffectKind.CALL })
        assertEquals(2, kinds.count { it == EffectKind.ASSIGNMENT })
        assertEquals(1, kinds.count { it == EffectKind.INCREMENT })
        val assignment = code.effects.first { it.kind == EffectKind.ASSIGNMENT }
        assertEquals(code.statementAt(assignment.offset - 1)?.end, assignment.offset)
    }

    @Test
    fun `a declaration initializer and a named argument are not assignments`() {
        val text = "fun f() {\n    val a = 1\n    foo(name = 2)\n}\n"
        val code = model(text)
        assertTrue(code.effects.none { it.kind == EffectKind.ASSIGNMENT })
    }


    @Test
    fun `a plain expression passes the rules`() {
        val text = "fun f() {\n    val x = a + b\n}\n"
        assertNull(model(text).checkExpression(rangeOf(text, "a + b")))
    }

    @Test
    fun `an expression starting inside a string fails the rules`() {
        val text = "fun f() {\n    println(\"hello\")\n}\n"
        val code = model(text)
        val start = text.indexOf("ell")
        assertEquals(ExpressionProblem.BOUNDARY, code.checkExpression(KtRange(start, start + 3)))
    }

    @Test
    fun `an expression across statements fails the rules`() {
        val text = "fun f() {\n    val a = 1\n    val b = 2\n}\n"
        assertEquals(ExpressionProblem.STATEMENT, model(text).checkExpression(rangeOf(text, "1\n    val b = 2")))
    }

    @Test
    fun `an expression ending before a call fails the rules`() {
        val text = "fun f() {\n    foo(bar)\n}\n"
        assertEquals(ExpressionProblem.CALL_CONTINUATION, model(text).checkExpression(rangeOf(text, "foo")))
    }

    @Test
    fun `a type position fails the rules`() {
        val text = "fun f(x: List<Int>) {\n    val y: Map<String, Int> = emptyMap()\n}\n"
        val code = model(text)
        assertEquals(ExpressionProblem.TYPE_POSITION, code.checkExpression(rangeOf(text, "String, Int")))
    }

    @Test
    fun `a return inside the expression fails the rules`() {
        val text = "fun f(): Int {\n    return 1\n}\n"
        assertEquals(ExpressionProblem.CONTROL, model(text).checkExpression(rangeOf(text, "return 1")))
    }


    @Test
    fun `stable expressions hold literals, parameters and the listed operators`() {
        val text = "fun f(v: Int, w: Int) {\n    val a = v + 1\n    val b = v ?: w\n    val c = \"text\"\n    val d = v / w\n    val e = v.toString()\n}\n"
        val code = model(text)
        assertTrue(code.isStable(rangeOf(text, "v + 1")))
        assertTrue(code.isStable(rangeOf(text, "v ?: w")))
        assertTrue(code.isStable(rangeOf(text, "\"text\"")))
        assertFalse(code.isStable(rangeOf(text, "v / w")))
        assertFalse(code.isStable(rangeOf(text, "v.toString()")))
    }

    @Test
    fun `a var is read-only but not stable`() {
        val text = "fun f() {\n    var v = 1\n    val a = v + 1\n}\n"
        val code = model(text)
        assertFalse(code.isStable(rangeOf(text, "v + 1")))
        assertTrue(code.isReadOnly(rangeOf(text, "v + 1")))
    }

    @Test
    fun `member reads are read-only and calls are neither`() {
        val text = "fun f(a: A) {\n    val x = a.size\n    val y = a.count()\n}\n"
        val code = model(text)
        assertTrue(code.isReadOnly(rangeOf(text, "a.size")))
        assertFalse(code.isReadOnly(rangeOf(text, "a.count()")))
        assertFalse(code.isStable(rangeOf(text, "a.size")))
    }

    @Test
    fun `atomic expressions`() {
        val text = "fun f(a: A) {\n    val x = a.b\n    val y = a.b(1)\n    val z = a + b\n    val w = (a + b)\n}\n"
        val code = model(text)
        assertTrue(code.isAtomic(rangeOf(text, "a.b")))
        assertTrue(code.isAtomic(rangeOf(text, "a.b(1)")))
        assertFalse(code.isAtomic(rangeOf(text, "a + b")))
        assertTrue(code.isAtomic(rangeOf(text, "(a + b)")))
    }


    @Test
    fun `the first if condition is not conditional`() {
        val text = "fun f(a: Int, b: Int) {\n    if (a > 0 && b > 0) {\n        println(a)\n    }\n}\n"
        assertFalse(model(text).isConditional(rangeOf(text, "b > 0")))
    }

    @Test
    fun `a while condition stays conditional`() {
        val text = "fun f(a: Int) {\n    while (a > 0) {\n        println(a)\n    }\n}\n"
        assertTrue(model(text).isConditional(rangeOf(text, "a > 0")))
    }

    @Test
    fun `a when subject and a for iterable are not conditional`() {
        val text = "fun f(a: Int, b: Int) {\n    when (a && b) {\n        else -> println(a)\n    }\n    for (i in a..b) {\n        println(i)\n    }\n}\n"
        val code = model(text)
        assertFalse(code.isConditional(rangeOf(text, "a && b")))
        assertFalse(code.isConditional(rangeOf(text, "a..b")))
    }

    @Test
    fun `elvis, safe calls, else-if and branch arrows are conditional`() {
        val text = "fun f(a: A, b: Int) {\n    val x = a ?: b\n    val y = a?.f(b)\n    if (b > 0) foo() else if (b > 1) bar()\n    list.map { v -> f(b) }\n}\n"
        val code = model(text)
        assertTrue(code.isConditional(rangeOf(text, "b\n    val y")))
        assertTrue(code.isConditional(rangeOf(text, "b)\n    if")))
        assertTrue(code.isConditional(rangeOf(text, "b > 1")))
        val unbraced = "fun f() {\n    when (x) {\n        1 -> f(a)\n    }\n}\n"
        val branchCode = model(unbraced)
        assertTrue(branchCode.isConditional(KtRange(unbraced.indexOf("a)"), unbraced.indexOf("a)") + 1)))
    }

    @Test
    fun `an and inside a group that closed before the expression is ignored`() {
        val text = "fun f(a: Int, b: Int, c: Int) {\n    val x = check(a && b) + c\n}\n"
        assertFalse(model(text).isConditional(rangeOf(text, "c\n")))
    }


    @Test
    fun `a call before the expression in the same statement runs before it`() {
        val text = "fun f() {\n    val x = foo() + bar()\n}\n"
        val code = model(text)
        val bar = rangeOf(text, "bar()")
        assertEquals(1, code.effectsBefore(bar).size)
    }

    @Test
    fun `an enclosing call runs after the expression`() {
        val text = "fun f() {\n    val x = foo(bar())\n}\n"
        val code = model(text)
        assertTrue(code.effectsBefore(rangeOf(text, "bar()")).isEmpty())
    }

    @Test
    fun `an increment before the expression runs before it`() {
        val text = "fun f() {\n    var i = 0\n    val x = i++ + i\n}\n"
        val code = model(text)
        val second = text.indexOf("i++ + i") + 6
        assertEquals(1, code.effectsBefore(KtRange(second, second + 1)).size)
    }


    @Test
    fun `identifier and keyword checks`() {
        assertTrue(isValidKotlinIdentifier("total"))
        assertTrue(isValidKotlinIdentifier("_x1"))
        assertFalse(isValidKotlinIdentifier("1x"))
        assertFalse(isValidKotlinIdentifier("a b"))
        assertFalse(isValidKotlinIdentifier(""))
        assertTrue(isHardKeyword("class"))
        assertFalse(isHardKeyword("value"))
    }

    @Test
    fun `a name used in the function is found`() {
        val text = "fun f() {\n    val a = 1\n    println(b)\n}\n"
        val code = model(text)
        val scope = code.nameScope(text.indexOf("println"))
        assertTrue(scope != null)
        assertTrue(isNameUsedIn(code, scope!!, "a"))
        assertTrue(isNameUsedIn(code, scope, "b"))
        assertFalse(isNameUsedIn(code, scope, "c"))
    }

    @Test
    fun `a nested lambda narrows the name scope`() {
        val text = "fun f() {\n    list.map {\n        val inner = 1\n        println(inner)\n    }\n    val outer = 2\n}\n"
        val code = model(text)
        val innerScope = code.nameScope(text.indexOf("println(inner)"))
        assertTrue(isNameUsedIn(code, innerScope!!, "inner"))
        assertFalse(isNameUsedIn(code, innerScope, "outer"))
    }

    @Test
    fun `unbalanced brackets are reported`() {
        val text = "fun f() {\n    val a = (1\n}\n"
        assertFalse(model(text).bracketsMatch)
    }

    @Test
    fun `locals named like modifiers stay references before in, is, as and else`() {
        val text = """
            fun f(range: IntRange, flag: Boolean) {
                val value = 3
                val data: Any = value
                val open = 1
                if (value in range) println(value)
                if (data is String) println(data)
                val n = open as Int
                val m = if (flag) value else open
            }
        """.trimIndent()
        val code = model(text)

        val value = code.locals.first { it.name == "value" }
        val data = code.locals.first { it.name == "data" }
        val open = code.locals.first { it.name == "open" }
        assertEquals(4, code.references.count { it.name == "value" && it.resolved == value })
        assertEquals(2, code.references.count { it.name == "data" && it.resolved == data })
        assertEquals(2, code.references.count { it.name == "open" && it.resolved == open })
    }
}
