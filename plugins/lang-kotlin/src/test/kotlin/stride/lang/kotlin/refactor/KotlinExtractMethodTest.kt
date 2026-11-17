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
        return Applied(apply(text, valid.edit.edits), valid.edit.caret, valid.edit.message)
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
        val text = "fun f() {\n    println(foo(\n        1,\n        2,\n    ))\n}\n"
        val applied = extract(text, "foo(\n        1,\n        2,\n    )")
        assertEquals(
            "fun f() {\n    println(extracted())\n}\n\n" +
                "private fun extracted() = foo(\n        1,\n        2,\n    )\n",
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
        val applied = extract(literals, "println(s + c + ok + n + d + x + i)")
        val head = "private fun extracted(s: String, c: Char, ok: Boolean, n: Long, d: Double, x: Float, i: Int)"
        assertTrue(applied.text.contains(head))
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
        val text = "fun f() {\n    val total = Foo(1)\n    println(total.size)\n}\n"
        val applied = extract(text, "val total = Foo(1)")
        assertEquals(
            "fun f() {\n    val total = extracted()\n    println(total.size)\n}\n\n" +
                "private fun extracted(): Foo {\n    val total = Foo(1)\n    return total\n}\n",
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
}
