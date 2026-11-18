package stride.lang.kotlin.refactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.NameCheck
import stride.lang.RefactoringStep
import stride.lang.TextEdit

class KotlinExtractMethodTest {
    private class Applied(val text: String, val caret: Int, val message: String?)

    private fun apply(text: String, edits: List<TextEdit>): String {
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    private fun startAt(text: String, start: Int, end: Int): RefactoringStep {
        val code = KotlinCode(text)
        return KotlinExtractMethod.prepare(code, start, end)
    }

    private fun select(text: String, snippet: String, occurrence: Int = 0): RefactoringStep {
        var index = -1
        repeat(occurrence + 1) { index = text.indexOf(snippet, index + 1) }
        check(index >= 0) { "missing snippet $snippet" }
        return startAt(text, index, index + snippet.length)
    }

    private fun refused(step: RefactoringStep): String = (step as RefactoringStep.Refused).reason

    private fun ask(step: RefactoringStep): RefactoringStep.AskName = step as RefactoringStep.AskName

    private fun finish(text: String, question: RefactoringStep.AskName, name: String = "extracted"): Applied {
        val valid = question.complete(name) as NameCheck.Valid
        val edited = apply(text, valid.edit.edits)
        val caret = valid.edit.caret
        assertEquals(name, edited.substring(caret, caret + name.length))
        return Applied(edited, caret, valid.edit.message)
    }

    private fun extract(text: String, snippet: String, name: String = "extracted"): Applied =
        finish(text, ask(select(text, snippet)), name)


    @Test
    fun `no selection is refused`() {
        val text = "fun f() {\n    foo()\n}\n"
        assertEquals("Select the code to extract", refused(startAt(text, 15, 15)))
    }

    @Test
    fun `a whitespace-only selection is refused`() {
        val text = "fun f() {\n    foo()\n}\n"
        val start = text.indexOf("    foo")
        assertEquals("Select the code to extract", refused(startAt(text, start, start + 4)))
    }

    @Test
    fun `unbalanced brackets are refused`() {
        val text = "fun f() {\n    val a = Foo(1\n}\n"
        assertEquals("Brackets do not match in this file", refused(select(text, "Foo(1")))
    }

    @Test
    fun `a selection that is neither statements nor an expression is refused`() {
        val text = "fun f() {\n    val a = 1 + 2\n}\n"
        assertEquals("Select whole statements or one expression", refused(select(text, "= 1")))
    }


    @Test
    fun `whole statements become a function at the top level`() {
        val text = "fun f() {\n    foo()\n    bar()\n}\n"
        val applied = extract(text, "foo()\n    bar()")
        assertEquals(
            "fun f() {\n    extracted()\n}\n\nprivate fun extracted() {\n    foo()\n    bar()\n}\n",
            applied.text,
        )
        assertEquals(applied.text.indexOf("extracted()"), applied.caret)
        assertEquals("Extracted method extracted", applied.message)
        assertEquals("extracted", ask(select(text, "foo()\n    bar()")).suggested)
    }

    @Test
    fun `a member function places the new function after it in the class body`() {
        val text = "class A {\n    fun f() {\n        println(1)\n    }\n}\n"
        val applied = extract(text, "println(1)")
        assertEquals(
            "class A {\n    fun f() {\n        extracted()\n    }\n\n" +
                "    private fun extracted() {\n        println(1)\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an expression becomes an expression-bodied function`() {
        val text = "fun f(a: Int, b: Int) {\n    val total = a + b\n}\n"
        val applied = extract(text, "a + b")
        assertEquals(
            "fun f(a: Int, b: Int) {\n    val total = extracted(a, b)\n}\n\n" +
                "private fun extracted(a: Int, b: Int) = a + b\n",
            applied.text,
        )
        assertEquals(applied.text.indexOf("extracted(a, b)"), applied.caret)
    }

    @Test
    fun `a selection in an expression body function is refused`() {
        val text = "fun f(a: Int) = a + 1\n"
        assertEquals("Expression body: convert it to a block body first", refused(select(text, "a + 1")))
    }

    @Test
    fun `a multi-line expression keeps its relative indentation`() {
        val text = "fun f(a: A) {\n    println(a.foo(\n        1,\n        2,\n    ))\n}\n"
        val applied = extract(text, "a.foo(\n        1,\n        2,\n    )")
        assertEquals(
            "fun f(a: A) {\n    println(extracted(a))\n}\n\n" +
                "private fun extracted(a: A) = a.foo(\n        1,\n        2,\n    )\n",
            applied.text,
        )
    }


    @Test
    fun `inputs are ordered by first use`() {
        val text = "fun f(b: Int, a: Int) {\n    println(a)\n    println(b)\n}\n"
        val applied = extract(text, "println(a)\n    println(b)")
        assertEquals(
            "fun f(b: Int, a: Int) {\n    extracted(a, b)\n}\n\n" +
                "private fun extracted(a: Int, b: Int) {\n    println(a)\n    println(b)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a local declared in the selection is not an input`() {
        val text = "fun f(a: Int) {\n    val b = a + 1\n    println(b)\n}\n"
        val applied = extract(text, "val b = a + 1\n    println(b)")
        assertEquals(
            "fun f(a: Int) {\n    extracted(a)\n}\n\n" +
                "private fun extracted(a: Int) {\n    val b = a + 1\n    println(b)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a constructor call and a literal write the input types`() {
        val constructor = "fun f() {\n    val item = Box<Int>(1)\n    println(item.size)\n}\n"
        assertEquals(
            "fun f() {\n    val item = Box<Int>(1)\n    extracted(item)\n}\n\n" +
                "private fun extracted(item: Box<Int>) {\n    println(item.size)\n}\n",
            extract(constructor, "println(item.size)").text,
        )
        val literals = "fun f() {\n    val s = \"hi\"\n    val c = 'x'\n    val ok = true\n" +
            "    val n = 1L\n    val d = 1.5\n    val x = 1.5f\n    val i = 42\n" +
            "    println(s + c + ok + n + d + x + i)\n}\n"
        assertEquals(
            "fun f() {\n    val s = \"hi\"\n    val c = 'x'\n    val ok = true\n" +
                "    val n = 1L\n    val d = 1.5\n    val x = 1.5f\n    val i = 42\n" +
                "    extracted(s, c, ok, n, d, x, i)\n}\n\n" +
                "private fun extracted(s: String, c: Char, ok: Boolean, n: Long, d: Double, x: Float, i: Int) {\n" +
                "    println(s + c + ok + n + d + x + i)\n}\n",
            extract(literals, "println(s + c + ok + n + d + x + i)").text,
        )
    }

    @Test
    fun `an input without a written type is refused`() {
        val text = "fun f() {\n    val item = create()\n    println(item.size)\n}\n"
        assertEquals(
            "Cannot infer the type of item without a compiler; declare it explicitly",
            refused(select(text, "println(item.size)")),
        )
    }

    @Test
    fun `a destructured input has no written type`() {
        val text = "fun f() {\n    val (a, b) = Pair(1, 2)\n    println(a)\n}\n"
        assertEquals(
            "Cannot infer the type of a without a compiler; declare it explicitly",
            refused(select(text, "println(a)")),
        )
    }

    @Test
    fun `a vararg parameter is refused`() {
        val text = "fun f(vararg items: Int) {\n    println(items.size)\n}\n"
        assertEquals("items is a vararg parameter", refused(select(text, "println(items.size)")))
    }

    @Test
    fun `a local function used by the selection is refused`() {
        val text = "fun f() {\n    fun helper() {}\n    helper()\n}\n"
        assertEquals("The selection uses local helper", refused(select(text, "helper()", occurrence = 1)))
    }

    @Test
    fun `a local class used by the selection is refused`() {
        val text = "fun f() {\n    class C\n    C()\n}\n"
        assertEquals("The selection uses local C", refused(select(text, "C()")))
    }

    @Test
    fun `a type parameter is refused`() {
        val text = "fun <T> f(x: T) {\n    println(x)\n}\n"
        assertEquals("The selection uses the type parameter T", refused(select(text, "println(x)")))
    }


    @Test
    fun `a val declared in the selection and read after becomes a returned output`() {
        val text = "class Item(val size: Int)\n\nfun f() {\n    val total = Item(1)\n    println(total.size)\n}\n"
        val applied = extract(text, "val total = Item(1)")
        assertEquals(
            "class Item(val size: Int)\n\nfun f() {\n    val total = extracted()\n    println(total.size)\n}\n\n" +
                "private fun extracted(): Item {\n    val total = Item(1)\n    return total\n}\n",
            applied.text,
        )
        assertEquals(applied.text.indexOf("extracted()"), applied.caret)
    }

    @Test
    fun `a var declared in the selection keeps its keyword`() {
        val text = "fun f() {\n    var count = 0\n    println(count)\n}\n"
        val applied = extract(text, "var count = 0")
        assertEquals(
            "fun f() {\n    var count = extracted()\n    println(count)\n}\n\n" +
                "private fun extracted(): Int {\n    var count = 0\n    return count\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a var declared before the selection is assigned back`() {
        val text = "fun f() {\n    var x = 0\n    x = compute(x)\n}\n"
        val applied = extract(text, "x = compute(x)")
        assertEquals(
            "fun f() {\n    var x = 0\n    x = extracted(x)\n}\n\n" +
                "private fun extracted(x: Int): Int {\n    var x = x\n    x = compute(x)\n    return x\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a compound assignment to a var is an output`() {
        val text = "fun f() {\n    var total = 0\n    total += 1\n}\n"
        val applied = extract(text, "total += 1")
        assertEquals(
            "fun f() {\n    var total = 0\n    total = extracted(total)\n}\n\n" +
                "private fun extracted(total: Int): Int {\n    var total = total\n    total += 1\n    return total\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an increment of a var is an output`() {
        val text = "fun f() {\n    var count = 0\n    count++\n}\n"
        val applied = extract(text, "count++")
        assertEquals(
            "fun f() {\n    var count = 0\n    count = extracted(count)\n}\n\n" +
                "private fun extracted(count: Int): Int {\n    var count = count\n    count++\n    return count\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a compound assignment to a val is only plusAssign`() {
        val text = "fun f() {\n    val result = ArrayList<Int>()\n    result += 1\n    println(result)\n}\n"
        val applied = extract(text, "result += 1")
        assertEquals(
            "fun f() {\n    val result = ArrayList<Int>()\n    extracted(result)\n    println(result)\n}\n\n" +
                "private fun extracted(result: ArrayList<Int>) {\n    result += 1\n}\n",
            applied.text,
        )
    }

    @Test
    fun `two outputs are refused`() {
        val text = "fun f() {\n    var a = 0\n    var b = 0\n    a = 1\n    b = 2\n    println(a + b)\n}\n"
        assertEquals(
            "The selection changes a and b, which are used later",
            refused(select(text, "a = 1\n    b = 2")),
        )
    }

    @Test
    fun `three outputs are named at most three`() {
        val text = "fun f() {\n    var a = 0\n    var b = 0\n    var c = 0\n    a = 1\n    b = 2\n    c = 3\n" +
            "    println(a + b + c)\n}\n"
        assertEquals(
            "The selection changes a, b and c, which are used later",
            refused(select(text, "a = 1\n    b = 2\n    c = 3")),
        )
    }

    @Test
    fun `a local function declared in the selection and used after it is refused`() {
        val text = "fun f() {\n    fun helper() {}\n    helper()\n}\n"
        assertEquals(
            "helper is declared in the selection and used after it",
            refused(select(text, "fun helper() {}")),
        )
    }


    @Test
    fun `a return is refused`() {
        val text = "fun f(): Int {\n    return 1\n}\n"
        assertEquals("The selection contains return", refused(select(text, "return 1")))
    }

    @Test
    fun `a labeled return inside a lambda written in the selection is allowed`() {
        val text = "fun f() {\n    val g = loop@{ x: Int -> return@loop x + 1 }\n}\n"
        val applied = extract(text, "val g = loop@{ x: Int -> return@loop x + 1 }")
        assertEquals(
            "fun f() {\n    extracted()\n}\n\n" +
                "private fun extracted() {\n    val g = loop@{ x: Int -> return@loop x + 1 }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a return inside a local function written in the selection is allowed`() {
        val text = "fun f() {\n    fun g(x: Int): Int {\n        return x + 1\n    }\n    println(g(1))\n}\n"
        val applied = extract(text, "fun g(x: Int): Int {\n        return x + 1\n    }\n    println(g(1))")
        assertEquals(
            "fun f() {\n    extracted()\n}\n\n" +
                "private fun extracted() {\n    fun g(x: Int): Int {\n        return x + 1\n    }\n" +
                "    println(g(1))\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a break of a loop outside the selection is refused`() {
        val text = "fun f() {\n    for (item in items) {\n        println(1)\n        break\n    }\n}\n"
        assertEquals(
            "The selection contains break or continue for a loop outside it",
            refused(select(text, "println(1)\n        break")),
        )
    }

    @Test
    fun `a continue of a loop outside the selection is refused`() {
        val text = "fun f() {\n    while (ready()) {\n        println(1)\n        continue\n    }\n}\n"
        assertEquals(
            "The selection contains break or continue for a loop outside it",
            refused(select(text, "println(1)\n        continue")),
        )
    }

    @Test
    fun `a plain return inside a lambda written in the selection is refused`() {
        val text = "fun f() {\n    val g = { x: Int -> return x + 1 }\n}\n"
        assertEquals("The selection contains return", refused(select(text, "val g = { x: Int -> return x + 1 }")))
    }

    @Test
    fun `a labeled break of a loop outside the selection is refused`() {
        val text = "fun f() {\n    outer@ for (i in 0..3) {\n        for (j in 0..3) {\n" +
            "            break@outer\n        }\n    }\n}\n"
        assertEquals(
            "The selection contains break or continue for a loop outside it",
            refused(select(text, "for (j in 0..3) {\n            break@outer\n        }")),
        )
    }

    @Test
    fun `a labeled break of a loop inside the selection is allowed`() {
        val text = "fun f() {\n    for (i in 0..3) {\n        inner@ for (j in 0..3) {\n" +
            "            if (j > 1) break@inner\n        }\n    }\n}\n"
        val applied = extract(text, "inner@ for (j in 0..3) {\n            if (j > 1) break@inner\n        }")
        assertEquals(
            "fun f() {\n    for (i in 0..3) {\n        extracted()\n    }\n}\n\n" +
                "private fun extracted() {\n    inner@ for (j in 0..3) {\n" +
                "        if (j > 1) break@inner\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a break of a loop inside the selection is allowed`() {
        val text = "fun f() {\n    for (i in 0..3) {\n        for (j in 0..3) {\n            if (j > 1) break\n" +
            "        }\n        println(i)\n    }\n}\n"
        val applied = extract(text, "for (j in 0..3) {\n            if (j > 1) break\n        }")
        assertEquals(
            "fun f() {\n    for (i in 0..3) {\n        extracted()\n        println(i)\n    }\n}\n\n" +
                "private fun extracted() {\n    for (j in 0..3) {\n        if (j > 1) break\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a continue of a loop inside the selection is allowed`() {
        val text = "fun f() {\n    for (i in 0..3) {\n        for (j in 0..3) {\n            if (j > 1) continue\n" +
            "            println(j)\n        }\n    }\n}\n"
        val applied = extract(text, "for (j in 0..3) {\n            if (j > 1) continue\n            println(j)\n        }")
        assertEquals(
            "fun f() {\n    for (i in 0..3) {\n        extracted()\n    }\n}\n\n" +
                "private fun extracted() {\n    for (j in 0..3) {\n        if (j > 1) continue\n" +
                "        println(j)\n    }\n}\n",
            applied.text,
        )
    }


    @Test
    fun `a selection inside a lambda is refused`() {
        val text = "fun f() {\n    list.forEach { item ->\n        println(item)\n    }\n}\n"
        assertEquals(
            "The selection is inside a lambda; this and it may mean something else",
            refused(select(text, "println(item)")),
        )
    }

    @Test
    fun `a selection inside a local function is refused`() {
        val text = "fun f() {\n    fun g() {\n        println(1)\n    }\n}\n"
        assertEquals("The selection is inside a local function", refused(select(text, "println(1)")))
    }

    @Test
    fun `a selection inside an initializer is refused`() {
        val text = "class A {\n    init {\n        println(1)\n    }\n}\n"
        assertEquals("Extract Method works inside a function body", refused(select(text, "println(1)")))
    }

    @Test
    fun `a selection inside an accessor is refused`() {
        val text = "class A {\n    val x: Int\n        get() {\n            return 1\n        }\n}\n"
        assertEquals("Extract Method works inside a function body", refused(select(text, "return 1")))
    }

    @Test
    fun `a selection inside a constructor is refused`() {
        val text = "class A {\n    constructor(x: Int) {\n        println(x)\n    }\n}\n"
        assertEquals("Extract Method works inside a function body", refused(select(text, "println(x)")))
    }

    @Test
    fun `a selection inside a class body or a property initializer is refused`() {
        assertEquals(
            "Extract Method works inside a function body",
            refused(select("class A {\n    val x = 1\n}\n", "1")),
        )
        assertEquals(
            "Extract Method works inside a function body",
            refused(select("val x = 1\n", "1")),
        )
    }

    @Test
    fun `the value of an expression-position branch becomes an expression function`() {
        val text = "fun f(flag: Boolean): Int =\n    if (flag) {\n        a.value\n    } else {\n        0\n    }\n"
        val applied = extract(text, "a.value")
        assertEquals(
            "fun f(flag: Boolean): Int =\n    if (flag) {\n        extracted()\n    } else {\n        0\n    }\n\n" +
                "private fun extracted() = a.value\n",
            applied.text,
        )
    }

    @Test
    fun `statements that include the block's value are refused`() {
        val text = "fun f(flag: Boolean): Int =\n    if (flag) {\n        println(1)\n        2\n    } else {\n        0\n    }\n"
        assertEquals(
            "The selection is the block's value; extract one expression",
            refused(select(text, "println(1)\n        2")),
        )
    }

    @Test
    fun `a try block that is a branch value keeps its value`() {
        val text = "fun f(flag: Boolean): Int = if (flag) {\n    try {\n        val a = 1\n        a + 1\n" +
            "    } finally {\n        reset()\n    }\n} else {\n    0\n}\n"
        val applied = extract(text, "a + 1")
        assertEquals(
            "fun f(flag: Boolean): Int = if (flag) {\n    try {\n        val a = 1\n        extracted(a)\n" +
                "    } finally {\n        reset()\n    }\n} else {\n    0\n}\n\n" +
                "private fun extracted(a: Int) = a + 1\n",
            applied.text,
        )
    }

    @Test
    fun `statements including a try block's value are refused`() {
        val text = "fun f(flag: Boolean): Int = if (flag) {\n    try {\n        val a = 1\n        a + 1\n" +
            "    } finally {\n        reset()\n    }\n} else {\n    0\n}\n"
        assertEquals(
            "The selection is the block's value; extract one expression",
            refused(select(text, "val a = 1\n        a + 1")),
        )
    }

    @Test
    fun `an else-if branch of a statement if is extracted`() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    if (a) {\n        println(1)\n    } else if (b) {\n" +
            "        println(2)\n    }\n}\n"
        val applied = extract(text, "println(2)")
        assertEquals(
            "fun f(a: Boolean, b: Boolean) {\n    if (a) {\n        println(1)\n    } else if (b) {\n" +
                "        extracted()\n    }\n}\n\nprivate fun extracted() {\n    println(2)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a bare empty-argument call is refused as an expression function`() {
        val text = "fun f(flag: Boolean) {\n    val items = if (flag) list() else emptyList()\n}\n"
        assertEquals(
            "Cannot infer the type of emptyList() without a compiler",
            refused(select(text, "emptyList()")),
        )
        assertEquals(
            "Cannot infer the type of list() without a compiler",
            refused(select(text, "list()")),
        )
    }

    @Test
    fun `a call on a receiver keeps its type`() {
        val text = "fun f(a: A) {\n    val x = a.total()\n}\n"
        val applied = extract(text, "a.total()")
        assertEquals(
            "fun f(a: A) {\n    val x = extracted(a)\n}\n\nprivate fun extracted(a: A) = a.total()\n",
            applied.text,
        )
    }

    @Test
    fun `a selection inside a braced when branch is extracted`() {
        val text = "fun f(x: Int) {\n    when (x) {\n        1 -> {\n            println(1)\n        }\n    }\n}\n"
        val applied = extract(text, "println(1)")
        assertEquals(
            "fun f(x: Int) {\n    when (x) {\n        1 -> {\n            extracted()\n        }\n    }\n}\n\n" +
                "private fun extracted() {\n    println(1)\n}\n",
            applied.text,
        )
    }


    @Test
    fun `suspend is copied`() {
        val text = "suspend fun f() {\n    delay(1)\n}\n"
        val applied = extract(text, "delay(1)")
        assertEquals(
            "suspend fun f() {\n    extracted()\n}\n\nprivate suspend fun extracted() {\n    delay(1)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a composable annotation is copied`() {
        val text = "@Composable\nfun Screen() {\n    draw()\n}\n"
        val applied = extract(text, "draw()")
        assertEquals(
            "@Composable\nfun Screen() {\n    extracted()\n}\n\n" +
                "@Composable\nprivate fun extracted() {\n    draw()\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an extension receiver is repeated`() {
        val text = "fun List<Int>.printTotal() {\n    println(sum())\n}\n"
        val applied = extract(text, "println(sum())")
        assertEquals(
            "fun List<Int>.printTotal() {\n    extracted()\n}\n\n" +
                "private fun List<Int>.extracted() {\n    println(sum())\n}\n",
            applied.text,
        )
    }


    @Test
    fun `the suggested name is made unique`() {
        val text = "fun f() {\n    println(1)\n}\n\nprivate fun extracted() {}\n"
        assertEquals("extracted2", ask(select(text, "println(1)")).suggested)
    }

    @Test
    fun `the name check refuses invalid and used names`() {
        val text = "fun f(a: Int) {\n    println(a)\n}\n"
        val question = ask(select(text, "println(a)"))
        assertEquals("extracted", question.suggested)
        assertEquals(NameCheck.Invalid("Not a valid name"), question.complete("1bad"))
        assertEquals(NameCheck.Invalid("Not a valid name"), question.complete("class"))
        assertEquals(NameCheck.Invalid("'a' is already used in this file"), question.complete("a"))
        assertTrue(question.complete("unused") is NameCheck.Valid)
    }


    @Test
    fun `a multi-line statement is reindented one unit`() {
        val text = "fun f(c: Boolean) {\n    if (c) {\n        val x = call(\n            1,\n            2,\n        )\n" +
            "        println(x)\n    }\n}\n"
        val applied = extract(text, "val x = call(\n            1,\n            2,\n        )\n        println(x)")
        assertEquals(
            "fun f(c: Boolean) {\n    if (c) {\n        extracted()\n    }\n}\n\n" +
                "private fun extracted() {\n    val x = call(\n        1,\n        2,\n    )\n    println(x)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a tab-indented file keeps tabs`() {
        val text = "fun f() {\n\tval a = 1\n\tprintln(a)\n}\n"
        val applied = extract(text, "println(a)")
        assertEquals(
            "fun f() {\n\tval a = 1\n\textracted(a)\n}\n\n" +
                "private fun extracted(a: Int) {\n\tprintln(a)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a carriage return file keeps its line ending`() {
        val text = "fun f() {\r\n    println(1)\r\n}\r\n"
        val applied = extract(text, "println(1)")
        assertEquals(
            "fun f() {\r\n    extracted()\r\n}\r\n\r\nprivate fun extracted() {\r\n    println(1)\r\n}\r\n",
            applied.text,
        )
    }

    @Test
    fun `an expression shape cannot carry an output`() {
        val text = "fun f() {\n    var x = 0\n    println(x++ + 1)\n}\n"
        assertEquals(
            "The selection changes x; extract whole statements instead",
            refused(select(text, "x++")),
        )
    }

    @Test
    fun `an integer literal past the Int range becomes a Long`() {
        val text = "fun f() {\n    val big = 4294967295\n    val hex = 0xFFFF_FFFF\n" +
            "    val bin = 0b1000_0000_0000_0000_0000_0000_0000_0000\n" +
            "    val small = 1_000\n    val max = 0x7FFF_FFFF\n" +
            "    println(big + hex + bin + small + max)\n}\n"
        assertEquals(
            "fun f() {\n    val big = 4294967295\n    val hex = 0xFFFF_FFFF\n" +
                "    val bin = 0b1000_0000_0000_0000_0000_0000_0000_0000\n" +
                "    val small = 1_000\n    val max = 0x7FFF_FFFF\n" +
                "    extracted(big, hex, bin, small, max)\n}\n\n" +
                "private fun extracted(big: Long, hex: Long, bin: Long, small: Int, max: Int) {\n" +
                "    println(big + hex + bin + small + max)\n}\n",
            extract(text, "println(big + hex + bin + small + max)").text,
        )
    }

    @Test
    fun `an unsigned integer literal has no inferred type`() {
        val text = "fun f() {\n    val u = 1u\n    println(u)\n}\n"
        assertEquals(
            "Cannot infer the type of u without a compiler; declare it explicitly",
            refused(select(text, "println(u)")),
        )
    }

    @Test
    fun `a constructor input needs written type arguments or a class in this file`() {
        val declared = "class Item(val size: Int)\n\nfun f() {\n    val item = Item(1)\n    println(item.size)\n}\n"
        assertEquals(
            "class Item(val size: Int)\n\nfun f() {\n    val item = Item(1)\n    extracted(item)\n}\n\n" +
                "private fun extracted(item: Item) {\n    println(item.size)\n}\n",
            extract(declared, "println(item.size)").text,
        )
        val fromAnotherFile = "fun f() {\n    val item = Item(1)\n    println(item.size)\n}\n"
        assertEquals(
            "Cannot infer the type of item without a compiler; declare it explicitly",
            refused(select(fromAnotherFile, "println(item.size)")),
        )
        val pair = "fun f() {\n    val pair = Pair(1, 2)\n    println(pair.first)\n}\n"
        assertEquals(
            "Cannot infer the type of pair without a compiler; declare it explicitly",
            refused(select(pair, "println(pair.first)")),
        )
        val capitalizedFunction = "fun f() {\n    val item = Build(1)\n    println(item.size)\n}\n"
        assertEquals(
            "Cannot infer the type of item without a compiler; declare it explicitly",
            refused(select(capitalizedFunction, "println(item.size)")),
        )
    }

    @Test
    fun `a generic class in this file called without type arguments cannot be inferred`() {
        val text = "class Box<T>(val value: T)\n\nfun f() {\n    val box = Box(1)\n    println(box.value)\n}\n"
        assertEquals(
            "Cannot infer the type of box without a compiler; declare it explicitly",
            refused(select(text, "println(box.value)")),
        )
    }

    @Test
    fun `a delegated local is refused as an input`() {
        val text = "fun f(holder: Holder) {\n    var x: Int by holder\n    println(x)\n}\n"
        assertEquals("x is a delegated local", refused(select(text, "println(x)")))
    }

    @Test
    fun `a delegated local is refused as an output`() {
        val text = "fun f(holder: Holder) {\n    var y: Int by holder\n    println(y)\n}\n"
        assertEquals("y is a delegated local", refused(select(text, "var y: Int by holder")))
    }

    @Test
    fun `a var captured by a lambda in the selection is refused`() {
        val read = "fun f() {\n    var x = 0\n    val cbs = ArrayList<() -> Unit>()\n" +
            "    cbs.add { println(x) }\n    x = 1\n    cbs.forEach { it() }\n}\n"
        assertEquals(
            "The selection captures var x in a lambda",
            refused(select(read, "cbs.add { println(x) }")),
        )
        val write = read.replace("println(x)", "x++")
        assertEquals(
            "The selection captures var x in a lambda",
            refused(select(write, "cbs.add { x++ }")),
        )
    }

    @Test
    fun `a val captured by a lambda in the selection is allowed`() {
        val text = "fun f() {\n    val x = 0\n    val cbs = ArrayList<() -> Unit>()\n    cbs.add { println(x) }\n}\n"
        assertEquals(
            "fun f() {\n    val x = 0\n    val cbs = ArrayList<() -> Unit>()\n    extracted(cbs, x)\n}\n\n" +
                "private fun extracted(cbs: ArrayList<() -> Unit>, x: Int) {\n    cbs.add { println(x) }\n}\n",
            extract(text, "cbs.add { println(x) }").text,
        )
    }

    @Test
    fun `a one-line container is refused`() {
        val text = "class A { val x = 1; fun f() { println(x) } }\n"
        assertEquals(
            "The function's last line holds other code; put it on its own line first",
            refused(select(text, "println(x)")),
        )
    }

    @Test
    fun `a label of a construct outside the selection is refused`() {
        val function = "fun f() {\n    run {\n        return@f\n    }\n}\n"
        assertEquals(
            "The selection uses the label @f from outside it",
            refused(select(function, "run {\n        return@f\n    }")),
        )
        val receiver = "fun String.f() {\n    run {\n        println(this@f)\n    }\n}\n"
        assertEquals(
            "The selection uses the label @f from outside it",
            refused(select(receiver, "run {\n        println(this@f)\n    }")),
        )
    }

    @Test
    fun `a return to a lambda written in the selection is allowed`() {
        val text = "fun f(items: List<Int>) {\n    items.forEach { return@forEach }\n}\n"
        val applied = extract(text, "items.forEach { return@forEach }")
        assertEquals(
            "fun f(items: List<Int>) {\n    extracted(items)\n}\n\n" +
                "private fun extracted(items: List<Int>) {\n    items.forEach { return@forEach }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an expression of only literals and operators is refused`() {
        assertEquals(
            "Cannot infer the type of 1 without a compiler",
            refused(select("fun f() {\n    val x: Long = 1\n}\n", "1")),
        )
        assertEquals(
            "Cannot infer the type of 1 + 2 without a compiler",
            refused(select("fun f() {\n    val x: Long = 1 + 2\n}\n", "1 + 2")),
        )
        assertEquals(
            "Cannot infer the type of -1 without a compiler",
            refused(select("fun f() {\n    val x = -1\n}\n", "-1")),
        )
    }

    @Test
    fun `a lambda itself is refused as an expression`() {
        assertEquals(
            "Cannot infer the type of { 1 } without a compiler",
            refused(select("fun f() {\n    val g = { 1 }\n}\n", "{ 1 }")),
        )
    }

    @Test
    fun `an anonymous function itself is refused as an expression`() {
        assertEquals(
            "Cannot infer the type of fun(x: Int): Int { return x + 1 } without a compiler",
            refused(select("fun f() {\n    val g = fun(x: Int): Int { return x + 1 }\n}\n", "fun(x: Int): Int { return x + 1 }")),
        )
    }

    @Test
    fun `a finally block is not a value block`() {
        val text = "fun f(flag: Boolean): Int = if (flag) {\n    try {\n        1\n" +
            "    } finally {\n        reset()\n        val a = 2\n        a + 1\n    }\n} else {\n    0\n}\n"
        val applied = extract(text, "val a = 2\n        a + 1")
        assertEquals(
            "fun f(flag: Boolean): Int = if (flag) {\n    try {\n        1\n" +
                "    } finally {\n        reset()\n        extracted()\n    }\n} else {\n    0\n}\n\n" +
                "private fun extracted() {\n    val a = 2\n    a + 1\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an expression in template code becomes an expression function`() {
        val text = "fun f(a: Int, b: Int) {\n    println(\"x\${a + b}\")\n}\n"
        val applied = extract(text, "a + b")
        assertEquals(
            "fun f(a: Int, b: Int) {\n    println(\"x\${extracted(a, b)}\")\n}\n\n" +
                "private fun extracted(a: Int, b: Int) = a + b\n",
            applied.text,
        )
    }

    @Test
    fun `modifiers do not count as used names`() {
        val text = "value class Id(val raw: Int)\n\ninline fun f() {\n    println(1)\n}\n"
        val question = ask(select(text, "println(1)"))
        assertTrue(question.complete("value") is NameCheck.Valid)
        assertTrue(question.complete("inline") is NameCheck.Valid)
        val objectText = "data class Row(val id: Int)\n\nclass Holder {\n    companion object {\n" +
            "        fun f() {\n            println(1)\n        }\n    }\n}\n"
        val objectQuestion = ask(select(objectText, "println(1)"))
        assertTrue(objectQuestion.complete("data") is NameCheck.Valid)
        assertTrue(objectQuestion.complete("companion") is NameCheck.Valid)
        val used = "fun f() {\n    val value = 1\n    println(value)\n}\n"
        assertEquals(
            NameCheck.Invalid("'value' is already used in this file"),
            ask(select(used, "println(value)")).complete("value"),
        )
    }

    @Test
    fun `the enclosing function check runs before the shape check for strings and comments`() {
        assertEquals(
            "Extract Method works inside a function body",
            refused(select("class A {\n    val x = \"hello\"\n}\n", "hello")),
        )
        assertEquals(
            "Extract Method works inside a function body",
            refused(select("class A {\n    // hello\n    val x = 1\n}\n", "hello")),
        )
    }

    @Test
    fun `a trailing semicolon moves into the function`() {
        val text = "fun f() {\n    foo();\n}\n"
        assertEquals(
            "fun f() {\n    extracted()\n}\n\nprivate fun extracted() {\n    foo();\n}\n",
            extract(text, "foo();").text,
        )
    }

    @Test
    fun `a trailing comment moves into the function`() {
        val text = "fun f() {\n    println(1) // note\n}\n"
        assertEquals(
            "fun f() {\n    extracted()\n}\n\nprivate fun extracted() {\n    println(1) // note\n}\n",
            extract(text, "println(1) // note").text,
        )
    }

    @Test
    fun `a comma-separated selection is refused`() {
        val text = "fun f(a: Int, b: Int) {\n    foo(a, b)\n}\n"
        assertEquals("Select whole statements or one expression", refused(select(text, "a, b")))
    }

    @Test
    fun `a nullable written type is kept`() {
        val text = "fun f(x: Int?) {\n    println(x)\n}\n"
        assertEquals(
            "fun f(x: Int?) {\n    extracted(x)\n}\n\n" +
                "private fun extracted(x: Int?) {\n    println(x)\n}\n",
            extract(text, "println(x)").text,
        )
    }

    @Test
    fun `inline operator and infix modifiers are not copied`() {
        val inline = "inline fun f(block: () -> Unit) {\n    block()\n}\n"
        assertEquals(
            "inline fun f(block: () -> Unit) {\n    extracted(block)\n}\n\n" +
                "private fun extracted(block: () -> Unit) {\n    block()\n}\n",
            extract(inline, "block()").text,
        )
        val operator = "operator fun Box.plus(other: Box) {\n    println(other)\n}\n"
        assertEquals(
            "operator fun Box.plus(other: Box) {\n    extracted(other)\n}\n\n" +
                "private fun Box.extracted(other: Box) {\n    println(other)\n}\n",
            extract(operator, "println(other)").text,
        )
        val infix = "infix fun Int.matches(other: Int) {\n    println(other)\n}\n"
        assertEquals(
            "infix fun Int.matches(other: Int) {\n    extracted(other)\n}\n\n" +
                "private fun Int.extracted(other: Int) {\n    println(other)\n}\n",
            extract(infix, "println(other)").text,
        )
    }

    @Test
    fun `a when used as an expression keeps its value`() {
        val text = "fun f(x: Int): Int = when (x) {\n    1 -> {\n        a.value\n    }\n    else -> 0\n}\n"
        val applied = extract(text, "a.value")
        assertEquals(
            "fun f(x: Int): Int = when (x) {\n    1 -> {\n        extracted()\n    }\n    else -> 0\n}\n\n" +
                "private fun extracted() = a.value\n",
            applied.text,
        )
    }

    @Test
    fun `an object an interface and a companion object hold the new function`() {
        val objectText = "object A {\n    fun f() {\n        println(1)\n    }\n}\n"
        assertEquals(
            "object A {\n    fun f() {\n        extracted()\n    }\n\n" +
                "    private fun extracted() {\n        println(1)\n    }\n}\n",
            extract(objectText, "println(1)").text,
        )
        val companion = "class A {\n    companion object {\n        fun f() {\n            println(1)\n" +
            "        }\n    }\n}\n"
        assertEquals(
            "class A {\n    companion object {\n        fun f() {\n            extracted()\n        }\n\n" +
                "        private fun extracted() {\n            println(1)\n        }\n    }\n}\n",
            extract(companion, "println(1)").text,
        )
        val interfaceText = "interface A {\n    fun f() {\n        println(1)\n    }\n}\n"
        assertEquals(
            "interface A {\n    fun f() {\n        extracted()\n    }\n\n" +
                "    private fun extracted() {\n        println(1)\n    }\n}\n",
            extract(interfaceText, "println(1)").text,
        )
    }

    @Test
    fun `a raw string keeps its lines as written`() {
        val text = "fun f(c: Boolean) {\n    if (c) {\n        println(\"\"\"\n            line1\n        \"\"\")\n    }\n}\n"
        val selection = "println(\"\"\"\n            line1\n        \"\"\")"
        assertEquals(
            "fun f(c: Boolean) {\n    if (c) {\n        extracted()\n    }\n}\n\n" +
                "private fun extracted() {\n    println(\"\"\"\n            line1\n        \"\"\")\n}\n",
            extract(text, selection).text,
        )
    }

    @Test
    fun `a raw string in a tab-indented file keeps its lines as written`() {
        val text = "fun f(c: Boolean) {\n\tif (c) {\n\t\tprintln(\"\"\"\n\t\t\tline1\n\t\t\"\"\")\n\t}\n}\n"
        val selection = "println(\"\"\"\n\t\t\tline1\n\t\t\"\"\")"
        assertEquals(
            "fun f(c: Boolean) {\n\tif (c) {\n\t\textracted()\n\t}\n}\n\n" +
                "private fun extracted() {\n\tprintln(\"\"\"\n\t\t\tline1\n\t\t\"\"\")\n}\n",
            extract(text, selection).text,
        )
    }

    @Test
    fun `a raw string inside a multi-line expression keeps its lines`() {
        val text = "fun f(a: A) {\n    val x = a.render(\n        \"\"\"\n        line\n        \"\"\",\n    )\n}\n"
        val selection = "a.render(\n        \"\"\"\n        line\n        \"\"\",\n    )"
        assertEquals(
            "fun f(a: A) {\n    val x = extracted(a)\n}\n\n" +
                "private fun extracted(a: A) = a.render(\n        \"\"\"\n        line\n        \"\"\",\n    )\n",
            extract(text, selection).text,
        )
    }

    @Test
    fun `an expression selection that starts or ends at a bad token is refused`() {
        assertEquals(
            "Select whole statements or one expression",
            refused(select("fun f(a: Int) {\n    val n = a - 1\n}\n", "- 1")),
        )
        assertEquals(
            "Select whole statements or one expression",
            refused(select("fun f(x: Int, key: Int) {\n    val n = x + key\n}\n", "+ key")),
        )
        assertEquals(
            "Select whole statements or one expression",
            refused(select("fun f() {\n    val x = ArrayList<KtToken>()\n}\n", "ArrayList<KtToken")),
        )
        assertEquals(
            "Select whole statements or one expression",
            refused(select("fun f(): Int {\n    val value = try {\n        1\n    } catch (e: E) {\n        2\n    }\n    return value\n}\n", "try")),
        )
        assertEquals(
            "Select whole statements or one expression",
            refused(select("fun f() {\n    var t by remember { 0 }\n    println(t)\n}\n", "by")),
        )
    }

    @Test
    fun `an expression selection may start with a prefix operator and end at an operand end`() {
        val minus = "fun f() {\n    consume(-1)\n}\n"
        assertEquals(
            "Cannot infer the type of -1 without a compiler",
            refused(select(minus, "-1")),
        )
        val not = "fun f(flag: Boolean) {\n    println(!flag)\n}\n"
        assertEquals("extracted", ask(select(not, "!flag")).suggested)
        val asserted = "fun f(x: Int?) {\n    println(x!!)\n}\n"
        assertEquals("extracted", ask(select(asserted, "x!!")).suggested)
        val indexed = "fun f(list: List<Int>) {\n    println(list[0])\n}\n"
        assertEquals("extracted", ask(select(indexed, "list[0]")).suggested)
        val incremented = "fun f() {\n    var i = 0\n    consume(i++)\n}\n"
        assertEquals(
            "The selection changes i; extract whole statements instead",
            refused(select(incremented, "i++")),
        )
    }

    @Test
    fun `the second line of a line-end infix call is one expression`() {
        val text = "fun pair(a: Int, b: Int): Pair<Int, Int> {\n" +
            "    return colorOf(a, b, a) to\n        colorOf(b, a, b)\n}\n"
        assertEquals(
            "Cannot infer the type of colorOf(b, a, b) without a compiler",
            refused(select(text, "colorOf(b, a, b)")),
        )
    }

    @Test
    fun `a name the model cannot place refuses the selection`() {
        val text = "fun f() {\n    @Suppress(\"unused\")\n    val a = 1\n    println(a)\n}\n"
        assertEquals(
            "Suppress appears where this refactoring cannot read it",
            refused(select(text, "@Suppress(\"unused\")\n    val a = 1")),
        )
    }

    @Test
    fun `a letter attached to a number is not an input`() {
        val text = "fun f(u: Int) {\n    consume(1u)\n}\n"
        assertEquals(
            "fun f(u: Int) {\n    consume(extracted())\n}\n\nprivate fun extracted() = 1u\n",
            extract(text, "1u").text,
        )
    }

    @Test
    fun `a member name still counts in the file name check`() {
        val text = "fun f() {\n    foo.extracted()\n    println(1)\n}\n"
        assertEquals("extracted2", ask(select(text, "println(1)")).suggested)
    }

    @Test
    fun `the file name check counts every spelling`() {
        val text = "fun f(user: User) {\n    println(user.name)\n    println(1)\n}\n"
        val question = ask(select(text, "println(1)"))
        assertEquals(NameCheck.Invalid("'name' is already used in this file"), question.complete("name"))
        assertEquals(NameCheck.Invalid("'user' is already used in this file"), question.complete("user"))
    }
}
