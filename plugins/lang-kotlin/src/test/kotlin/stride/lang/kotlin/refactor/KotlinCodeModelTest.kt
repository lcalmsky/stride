package stride.lang.kotlin.refactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `a next line starting with a brace continues the statement`() {
        val text = "fun f() {\n    val x = list.map\n    { it + 1 }\n}\n"
        assertEquals(listOf("val x = list.map\n    { it + 1 }"), bodyStatements(text))
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
    fun `a name directly before an at sign is a label, so the code after it is code`() {
        val text = "fun f(value: Int) {\n    loop@ println(value)\n}\n"
        val code = model(text)
        val value = code.references.filter { it.name == "value" }
        assertEquals(1, value.size)
        assertTrue(value.all { it.resolved?.kind == LocalKind.PARAMETER })
        assertFalse(code.references.any { it.name == "loop" })
    }

    @Test
    fun `an annotation is still an annotation`() {
        val text = "fun f() {\n    @Suppress(\"x\") foo()\n}\n"
        val names = model(text).references.map { it.name }
        assertFalse("Suppress" in names)
        assertFalse("x" in names)
        assertTrue("foo" in names)
    }

    @Test
    fun `letters attached to a number belong to the literal`() {
        val text = "fun f(u: Int) {\n    println(1u + 2UL + 3uL + 4U + 5L)\n}\n"
        assertFalse("u" in model(text).references.map { it.name })
        assertFalse("UL" in model(text).references.map { it.name })
        assertFalse("uL" in model(text).references.map { it.name })
        assertFalse("U" in model(text).references.map { it.name })
        assertTrue("println" in model(text).references.map { it.name })
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
    fun `keyword operators at either end fail the rules`() {
        val text = "fun f(a: Any) {\n    val x = a as String\n    val y = a is String\n    val z = a in list\n    val w = a as? String\n    val v = a !is String\n}\n"
        val code = model(text)
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "a as")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "as String")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "a is")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "a in")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "in list")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "a as?")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "!is String")))
    }

    @Test
    fun `statement keywords and an if without else fail the rules`() {
        val text = "fun f(c: Boolean) {\n    while (c) use(1)\n    if (c) use(1)\n    if (c) use(1) else use(2)\n    do use(1) while (c)\n}\n"
        val code = model(text)
        assertEquals(ExpressionProblem.STATEMENT, code.checkExpression(rangeOf(text, "while (c) use(1)")))
        assertEquals(ExpressionProblem.STATEMENT, code.checkExpression(rangeOf(text, "if (c) use(1)")))
        assertNull(code.checkExpression(rangeOf(text, "if (c) use(1) else use(2)")))
        assertEquals(ExpressionProblem.STATEMENT, code.checkExpression(rangeOf(text, "do use(1) while (c)")))
    }

    @Test
    fun `a group right after a name is call arguments`() {
        val call = "fun f() {\n    draw(1)\n}\n"
        assertEquals(ExpressionProblem.CALL_CONTINUATION, model(call).checkExpression(rangeOf(call, "(1)")))
        val index = "fun f() {\n    val x = list[0]\n}\n"
        assertEquals(ExpressionProblem.CALL_CONTINUATION, model(index).checkExpression(rangeOf(index, "[0]")))
        val generic = "fun f() {\n    val y = f<Int>(1)\n}\n"
        assertEquals(ExpressionProblem.CALL_CONTINUATION, model(generic).checkExpression(rangeOf(generic, "(1)")))
        val group = "fun f() {\n    val z = (1)\n}\n"
        assertNull(model(group).checkExpression(rangeOf(group, "(1)")))
    }

    @Test
    fun `a declared name fails the rules`() {
        val text = "fun f() {\n    val hovered = 1\n    for (i in list) use(i)\n}\n"
        val code = model(text)
        assertEquals(ExpressionProblem.DECLARATION, code.checkExpression(rangeOf(text, "hovered")))
        assertEquals(ExpressionProblem.DECLARATION, code.checkExpression(rangeOf(text, "i")))
        val call = "fun f(obj: Map<Int, Int>) {\n    val x = obj.get(1)\n}\n"
        assertNull(model(call).checkExpression(rangeOf(call, "obj.get(1)")))
    }

    @Test
    fun `a selection may not cross a string or template boundary`() {
        val text = "fun f(a: A) {\n    val s = \"\${a.b}c\"\n    val t = \"\${x} and \${y}\"\n}\n"
        val code = model(text)
        assertNull(code.checkExpression(rangeOf(text, "a.b")))
        assertEquals(ExpressionProblem.BOUNDARY, code.checkExpression(rangeOf(text, "a.b}c")))
        assertEquals(ExpressionProblem.BOUNDARY, code.checkExpression(rangeOf(text, "a.b}c\"")))
        assertEquals(ExpressionProblem.BOUNDARY, code.checkExpression(rangeOf(text, "x} and \${y")))
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
    fun `boolean and null literals are atomic`() {
        val text = "fun f() {\n    val a = true\n    val b = false\n    val c = null\n}\n"
        val code = model(text)
        assertTrue(code.isAtomic(rangeOf(text, "true")))
        assertTrue(code.isAtomic(rangeOf(text, "false")))
        assertTrue(code.isAtomic(rangeOf(text, "null")))
    }

    @Test
    fun `markers inside a when subject and a for iterable are conditional`() {
        val whenText = "fun f(a: Boolean) {\n    when (a && foo()) {\n        else -> use(1)\n    }\n}\n"
        assertTrue(model(whenText).isConditional(rangeOf(whenText, "foo()")))
        val forText = "fun f(c: Boolean) {\n    for (i in if (c) foo() else bar()) {\n        use(i)\n    }\n}\n"
        assertTrue(model(forText).isConditional(rangeOf(forText, "foo()")))
        val groupText = "fun f(a: Boolean, b: Boolean) {\n    if ((a || b) && foo()) {\n        use(1)\n    }\n}\n"
        assertTrue(model(groupText).isConditional(rangeOf(groupText, "foo()")))
    }

    @Test
    fun `the first if condition is not conditional but a marker inside it is`() {
        val text = "fun f(a: Int, b: Int) {\n    if (a > 0 && b > 0) {\n        println(a)\n    }\n}\n"
        val code = model(text)
        assertFalse(code.isConditional(rangeOf(text, "a > 0 && b > 0")))
        assertTrue(code.isConditional(rangeOf(text, "b > 0")))
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
        assertFalse(isValidKotlinIdentifier("_"))
        assertFalse(isValidKotlinIdentifier("__"))
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

    @Test
    fun `a modifier word before a value is a name`() {
        val text = "fun f(other: Int) {\n    val value = 3\n    println(value)\n    val p = value to other\n}\n"
        val code = model(text)
        val value = code.locals.first { it.name == "value" }
        assertEquals(2, code.references.count { it.name == "value" && it.resolved == value })
        assertFalse(code.modifierTokens.any { code.code[it].text == "value" })

        val data = "fun f(other: Int) {\n    val data = 1\n    println(data)\n    val p = data provides other\n}\n"
        assertEquals(2, model(data).references.count { it.name == "data" && it.resolved != null })

        val open = "fun f(other: Any) {\n    val open = 1\n    val n = open as Int\n    println(n)\n}\n"
        assertTrue(model(open).references.any { it.name == "open" && it.resolved != null })
    }

    @Test
    fun `a modifier word in a declaration position is a modifier`() {
        val text = "value class Id(val raw: Int)\n\n" +
            "inline fun <reified T> pick(v: T, vararg rest: Int): T = v\n\n" +
            "class Box<out T>(private val item: T)\n"
        val code = model(text)
        val modifiers = code.modifierTokens.map { code.code[it].text }
        assertEquals(setOf("value", "inline", "reified", "vararg", "private", "out"), modifiers.toSet())
        val names = code.references.map { it.name }
        assertFalse("value" in names)
        assertFalse("reified" in names)
        assertFalse("out" in names)
        assertFalse("vararg" in names)
    }

    @Test
    fun `an unread spelling is a token the model cannot place`() {
        val text = "fun f(value: Int) {\n    println(value)\n    println(other.value)\n    g(value = 1)\n" +
            "    loop@ while (value > 0) break@loop\n    @value\n    println(1)\n}\n"
        val code = model(text)
        val scope = code.nameScope(text.indexOf("println(value)"))
        val unread = code.unreadSpellings("value", scope!!)
        assertEquals(listOf(text.indexOf("@value") + 1), unread.map { it.start })
    }

    @Test
    fun `a backticked spelling is the same name`() {
        val text = "fun f() {\n    val value = 3\n    println(value)\n    println(`value`)\n}\n"
        val code = model(text)
        val value = code.locals.first { it.name == "value" }
        assertEquals(2, code.references.count { it.resolved == value })
        assertTrue(code.unreadSpellings("value", value.scope).isEmpty())
        assertTrue(isNameUsedIn(code, value.scope, "value"))
    }

    @Test
    fun `a name check counts any spelling, member names and named arguments included`() {
        val text = "fun f() {\n    val user = user()\n    println(user.name)\n    g(name = 1)\n}\n"
        val code = model(text)
        val scope = code.nameScope(text.indexOf("println"))!!
        assertTrue(isNameUsedIn(code, scope, "name"))
        assertTrue(isNameUsedIn(code, scope, "user"))
        assertFalse(isNameUsedIn(code, scope, "missing"))
    }

    @Test
    fun `an expression starts and ends on an operand`() {
        val text = "fun f(a: Int, key: Int, flag: Boolean, list: List<Int>) {\n" +
            "    val n = a - 1\n    val m = a + key\n    f(-1)\n    val b = !flag\n    val c = list.size!!\n" +
            "    val d = list[0]\n    i++\n" +
            "}\n"
        val code = model(text)
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "- 1")))
        assertEquals(ExpressionProblem.OPERATOR_EDGE, code.checkExpression(rangeOf(text, "+ key")))
        assertNull(code.checkExpression(rangeOf(text, "-1")))
        assertNull(code.checkExpression(rangeOf(text, "!flag")))
        assertNull(code.checkExpression(rangeOf(text, "list.size!!")))
        assertNull(code.checkExpression(rangeOf(text, "list[0]")))
        assertNull(code.checkExpression(rangeOf(text, "i++")))
    }

    @Test
    fun `an expression does not start at a soft keyword in a bad position`() {
        val tryText = "fun f(): Int {\n    val value = try {\n        1\n    } catch (e: E) {\n        2\n    }\n    return value\n}\n"
        assertEquals(ExpressionProblem.OPERATOR_EDGE, model(tryText).checkExpression(rangeOf(tryText, "try")))
        val byText = "fun f() {\n    var t by remember { 0 }\n    println(t)\n}\n"
        assertEquals(ExpressionProblem.OPERATOR_EDGE, model(byText).checkExpression(rangeOf(byText, "by")))
    }

    @Test
    fun `a type argument list opens and closes inside the expression`() {
        val text = "fun f() {\n    val x = ArrayList<KtToken>()\n}\n"
        val code = model(text)
        assertEquals(ExpressionProblem.BRACKETS, code.checkExpression(rangeOf(text, "ArrayList<KtToken")))
        assertNull(code.checkExpression(rangeOf(text, "ArrayList<KtToken>()")))
        val merged = "fun f(x: Array<out Any?>?) {\n    println(x.size)\n}\n"
        assertNull(model(merged).checkExpression(rangeOf(merged, "x.size")))
    }

    @Test
    fun `a line ending with an infix name continues on the next line`() {
        assertEquals(
            listOf("val p = a to\n    foo(x)"),
            bodyStatements("fun f() {\n    val p = a to\n    foo(x)\n}\n"),
        )
        assertEquals(
            listOf("return 0 until\n    n"),
            bodyStatements("fun f(): IntRange {\n    return 0 until\n    n\n}\n"),
        )
        assertEquals(
            listOf("val p = \"k\" to\n    v"),
            bodyStatements("fun f(v: Int) {\n    val p = \"k\" to\n    v\n}\n"),
        )
        assertEquals(
            listOf("val p = x!! shl\n    2"),
            bodyStatements("fun f(x: Int?) {\n    val p = x!! shl\n    2\n}\n"),
        )
    }

    @Test
    fun `a line ending with a complete operand ends the statement`() {
        assertEquals(
            listOf("if (c) a", "foo()"),
            bodyStatements("fun f(c: Boolean) {\n    if (c) a\n    foo()\n}\n"),
        )
        assertEquals(
            listOf("val x by lazyValue", "foo()"),
            bodyStatements("fun f() {\n    val x by lazyValue\n    foo()\n}\n"),
        )
        assertEquals(
            listOf("val p = a", "foo()"),
            bodyStatements("fun f(a: Int) {\n    val p = a\n    foo()\n}\n"),
        )
        assertEquals(
            listOf("break@outer", "foo()"),
            bodyStatements("fun f() {\n    break@outer\n    foo()\n}\n"),
        )
        assertEquals(
            listOf("val visible = a until rows", "if (visible) return 1", "return 0"),
            bodyStatements("fun f(): Int {\n    val visible = a until rows\n    if (visible) return 1\n    return 0\n}\n"),
        )
    }

    @Test
    fun `a next line block joins a call or a header`() {
        assertEquals(
            listOf("foo()\n    { }", "println(1)"),
            bodyStatements("fun f() {\n    foo()\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("x.y\n    { }", "println(1)"),
            bodyStatements("fun f() {\n    x.y\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("if (c)\n    { }", "println(1)"),
            bodyStatements("fun f(c: Boolean) {\n    if (c)\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("fun g()\n    { }", "println(1)"),
            bodyStatements("fun f() {\n    fun g()\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("class B\n    { }", "println(1)"),
            bodyStatements("fun f() {\n    class B\n    { }\n    println(1)\n}\n"),
        )
    }

    @Test
    fun `a next line block after a value is a statement of its own`() {
        assertEquals(
            listOf("run", "{ }", "println(1)"),
            bodyStatements("fun f() {\n    run\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("1", "{ }", "println(1)"),
            bodyStatements("fun f() {\n    1\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("\"s\"", "{ }", "println(1)"),
            bodyStatements("fun f() {\n    \"s\"\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("(a)", "{ }", "println(1)"),
            bodyStatements("fun f(a: Int) {\n    (a)\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("a[0]", "{ }", "println(1)"),
            bodyStatements("fun f(a: List<Int>) {\n    a[0]\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("a!!", "{ }", "println(1)"),
            bodyStatements("fun f(a: Int?) {\n    a!!\n    { }\n    println(1)\n}\n"),
        )
        assertEquals(
            listOf("{ print(1) }", "{ }", "println(1)"),
            bodyStatements("fun f() {\n    { print(1) }\n    { }\n    println(1)\n}\n"),
        )
    }

    @Test
    fun `a class body property and its accessor are separate lines`() {
        val text = "class C {\n    var x = 0\n    private set\n}\n"
        val code = model(text)
        val zero = code.code.first { it.text == "0" }
        val private = code.code.first { it.text == "private" }
        assertTrue(code.splitsAfter(zero, private))
    }

    @Test
    fun `the model is reused for the same text instance and rebuilt for another`() {
        val text = "fun f() {\n    println(1)\n}\n"

        assertSame(kotlinCodeFor(text), kotlinCodeFor(text))
        assertNotSame(kotlinCodeFor(text), kotlinCodeFor(String(text.toCharArray())))
    }
}
