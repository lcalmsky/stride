package stride.lang.kotlin.refactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.NameCheck
import stride.lang.RefactoringContext
import stride.lang.RefactoringStep
import stride.lang.TextEdit

class KotlinIntroduceVariableTest {
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
        return KotlinIntroduceVariable.prepare(code, start, end)
    }

    private fun select(text: String, snippet: String, occurrence: Int = 0): RefactoringStep {
        var index = -1
        repeat(occurrence + 1) { index = text.indexOf(snippet, index + 1) }
        check(index >= 0) { "missing snippet $snippet" }
        return startAt(text, index, index + snippet.length)
    }

    private fun caret(text: String, snippet: String, delta: Int = 0): RefactoringStep =
        startAt(text, text.indexOf(snippet) + delta, text.indexOf(snippet) + delta)

    private fun refused(step: RefactoringStep): String = (step as RefactoringStep.Refused).reason

    private fun ask(step: RefactoringStep): RefactoringStep.AskName = step as RefactoringStep.AskName

    private fun choose(step: RefactoringStep, index: Int): RefactoringStep {
        val chooser = step as RefactoringStep.Choose
        return chooser.options[index].next()
    }

    private fun finish(text: String, question: RefactoringStep.AskName, name: String): Applied {
        val check = question.complete(name)
        val valid = check as NameCheck.Valid
        return Applied(apply(text, valid.edit.edits), valid.edit.caret, valid.edit.message)
    }


    @Test
    fun `a selection becomes the expression`() {
        val text = "fun f() {\n    val total = a + b\n}\n"
        val question = ask(select(text, "a + b"))

        assertEquals("value", question.suggested)
        val applied = finish(text, question, "sum")
        assertEquals("fun f() {\n    val sum = a + b\n    val total = sum\n}\n", applied.text)
        assertEquals(applied.text.indexOf("val total = sum") + "val total = ".length + 3, applied.caret)
        assertEquals("Introduced variable sum", applied.message)
    }

    @Test
    fun `a selection is trimmed`() {
        val text = "fun f() {\n    val total = a + b\n}\n"
        val selectionStart = text.indexOf(" a + b")
        val question = ask(startAt(text, selectionStart, selectionStart + " a + b\n".length))
        val applied = finish(text, question, "sum")
        assertEquals("fun f() {\n    val sum = a + b\n    val total = sum\n}\n", applied.text)
    }

    @Test
    fun `a selection inside a string is refused`() {
        val text = "fun f() {\n    println(\"hello\")\n}\n"
        assertEquals("Select an expression", refused(select(text, "ell")))
    }

    @Test
    fun `a selection inside template code is allowed`() {
        val text = "fun f(v: Int) {\n    println(\"v=\${v + 1}\")\n}\n"
        val applied = finish(text, ask(select(text, "v + 1")), "value")
        assertEquals("fun f(v: Int) {\n    val value = v + 1\n    println(\"v=\${value}\")\n}\n", applied.text)
    }

    @Test
    fun `a selection ending with a name inside template code is allowed`() {
        val text = "fun f(a: Int, b: Int) {\n    println(\"x\${a + b}\")\n}\n"
        val applied = finish(text, ask(select(text, "a + b")), "sum")
        assertEquals("fun f(a: Int, b: Int) {\n    val sum = a + b\n    println(\"x\${sum}\")\n}\n", applied.text)
    }

    @Test
    fun `a comma-separated argument list is not an expression`() {
        val text = "fun f(a: Int, b: Int) {\n    foo(a, b)\n}\n"
        assertEquals("Select an expression", refused(select(text, "a, b")))
    }


    @Test
    fun `an expression body is refused`() {
        val text = "fun f() = 1 + 2\n"
        assertEquals("Expression body: convert it to a block body first", refused(select(text, "1 + 2")))
    }

    @Test
    fun `a place outside a function body is refused`() {
        val text = "val x = 1 + 2\n"
        assertEquals("Introduce Variable works inside a function body", refused(select(text, "1 + 2")))
    }

    @Test
    fun `a statement sharing its line is refused`() {
        val text = "fun f() {\n    println(1); val a = 2\n}\n"
        assertEquals("The statement shares its line; put it on its own line first", refused(select(text, "1")))
    }

    @Test
    fun `unbalanced brackets are refused`() {
        val text = "fun f() {\n    val a = Foo(1\n}\n"
        assertEquals("Brackets do not match in this file", refused(select(text, "Foo(1")))
    }


    @Test
    fun `a conditional expression is refused`() {
        val text = "fun f(a: Int) {\n    val x = a ?: 1\n}\n"
        assertEquals(
            "1 runs only in some cases here; a variable would run it every time",
            refused(select(text, "1")),
        )
    }

    @Test
    fun `a while condition is refused`() {
        val text = "fun f(b: Int) {\n    while (b > 0) {\n        foo()\n    }\n}\n"
        assertEquals(
            "b > 0 runs only in some cases here; a variable would run it every time",
            refused(select(text, "b > 0")),
        )
    }

    @Test
    fun `an else-if condition is refused`() {
        val text = "fun f(a: Int, b: Int) {\n    if (a > 0) foo() else if (b > 0) bar()\n}\n"
        assertEquals(
            "b > 0 runs only in some cases here; a variable would run it every time",
            refused(select(text, "b > 0")),
        )
    }

    @Test
    fun `an expression after a safe call is refused`() {
        val text = "fun f(a: A, b: Int) {\n    val y = a?.f(b)\n}\n"
        assertEquals(
            "b runs only in some cases here; a variable would run it every time",
            refused(select(text, "b", 1)),
        )
    }

    @Test
    fun `a when branch is refused`() {
        val text = "fun f(x: Int, a: Int) {\n    when (x) {\n        1 -> f(a)\n    }\n}\n"
        assertEquals(
            "a runs only in some cases here; a variable would run it every time",
            refused(select(text, "a", 1)),
        )
    }

    @Test
    fun `a marker inside the first if condition is refused`() {
        val text = "fun f(a: Int, b: Int) {\n    if (a > 0 && b > 0) {\n        foo()\n    }\n}\n"
        assertEquals(
            "b > 0 runs only in some cases here; a variable would run it every time",
            refused(select(text, "b > 0")),
        )
    }

    @Test
    fun `a whole first if condition is allowed`() {
        val text = "fun f(b: Int) {\n    if (b > 0) {\n        foo()\n    }\n}\n"
        val applied = finish(text, ask(select(text, "b > 0")), "positive")
        assertEquals(
            "fun f(b: Int) {\n    val positive = b > 0\n    if (positive) {\n        foo()\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a when subject is allowed`() {
        val text = "fun f(a: Int, b: Int) {\n    when (a > 0 && b > 0) {\n        else -> foo()\n    }\n}\n"
        val applied = finish(text, ask(select(text, "a > 0 && b > 0")), "condition")
        assertEquals(
            "fun f(a: Int, b: Int) {\n    val condition = a > 0 && b > 0\n    when (condition) {\n        else -> foo()\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a for iterable is allowed`() {
        val text = "fun f(a: Int, b: Int) {\n    for (i in a..b) {\n        foo(i)\n    }\n}\n"
        val applied = finish(text, ask(select(text, "a..b")), "range")
        assertEquals(
            "fun f(a: Int, b: Int) {\n    val range = a..b\n    for (i in range) {\n        foo(i)\n    }\n}\n",
            applied.text,
        )
    }


    @Test
    fun `code before the expression that runs after it is refused`() {
        val text = "fun f() {\n    val x = foo() + bar()\n}\n"
        assertEquals(
            "Code before bar() in this statement would run after it",
            refused(select(text, "bar()")),
        )
    }


    @Test
    fun `a stable expression replaces every occurrence`() {
        val text = "fun f(v: Int) {\n    println(v + 1)\n    println(v + 1)\n}\n"
        val step = select(text, "v + 1")
        val chooser = step as RefactoringStep.Choose
        assertEquals("Occurrences", chooser.title)
        assertEquals(
            listOf("Replace this occurrence only", "Replace all 2 occurrences"),
            chooser.options.map { it.text },
        )
        val applied = finish(text, ask(choose(step, 1)), "next")
        assertEquals("fun f(v: Int) {\n    val next = v + 1\n    println(next)\n    println(next)\n}\n", applied.text)
    }

    @Test
    fun `replacing one occurrence leaves the others`() {
        val text = "fun f(v: Int) {\n    println(v + 1)\n    println(v + 1)\n}\n"
        val applied = finish(text, ask(choose(select(text, "v + 1"), 0)), "next")
        assertEquals("fun f(v: Int) {\n    val next = v + 1\n    println(next)\n    println(v + 1)\n}\n", applied.text)
    }

    @Test
    fun `a read-only expression replaces only the occurrences before the first effect`() {
        val text = "fun f() {\n    var v = 1\n    consume(v * 2, v * 2)\n    reset()\n    consume(v * 2)\n}\n"
        val step = select(text, "v * 2")
        assertEquals("Replace all 2 occurrences", (step as RefactoringStep.Choose).options[1].text)
        val applied = finish(text, ask(choose(step, 1)), "twice")
        assertEquals(
            "fun f() {\n    var v = 1\n    val twice = v * 2\n    consume(twice, twice)\n    reset()\n    consume(v * 2)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an expression with a call has no other occurrences`() {
        val text = "fun f() {\n    println(a.b())\n    println(a.b())\n}\n"
        val question = ask(select(text, "a.b()"))
        assertEquals("b2", question.suggested)
        val applied = finish(text, question, "result")
        assertEquals("fun f() {\n    val result = a.b()\n    println(result)\n    println(a.b())\n}\n", applied.text)
    }


    @Test
    fun `a read-only occurrence inside a lambda is not replaced`() {
        val text = "fun f() {\n    var v = 1\n    val a = v + 1\n    val task = { use(v + 1) }\n    v = 2\n    task()\n}\n"
        val applied = finish(text, ask(select(text, "v + 1")), "value")
        assertEquals(
            "fun f() {\n    var v = 1\n    val value = v + 1\n    val a = value\n" +
                "    val task = { use(v + 1) }\n    v = 2\n    task()\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a read-only occurrence inside a loop body is not replaced`() {
        val text = "fun f() {\n    var v = 1\n    val a = v + 1\n    for (i in 1..3) {\n        use(v + 1)\n        v += 1\n    }\n}\n"
        val applied = finish(text, ask(select(text, "v + 1")), "value")
        assertEquals(
            "fun f() {\n    var v = 1\n    val value = v + 1\n    val a = value\n" +
                "    for (i in 1..3) {\n        use(v + 1)\n        v += 1\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a read-only occurrence inside a local function is not replaced`() {
        val text = "fun f() {\n    var v = 1\n    val a = v + 1\n    fun g() = use(v + 1)\n    v = 2\n    g()\n}\n"
        val applied = finish(text, ask(select(text, "v + 1")), "value")
        assertEquals(
            "fun f() {\n    var v = 1\n    val value = v + 1\n    val a = value\n" +
                "    fun g() = use(v + 1)\n    v = 2\n    g()\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a read-only occurrence in a while header is not replaced`() {
        val text = "fun f() {\n    var v = 1\n    val a = v + 1\n    while (v + 1 < 10) {\n        v += 1\n    }\n}\n"
        val applied = finish(text, ask(select(text, "v + 1")), "value")
        assertEquals(
            "fun f() {\n    var v = 1\n    val value = v + 1\n    val a = value\n" +
                "    while (v + 1 < 10) {\n        v += 1\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an occurrence resolving to another local is not replaced`() {
        val text = "fun f(c: Boolean) {\n    var v = 1\n    val a = v + 1\n    if (c) {\n        var v = 5\n        val b = v + 1\n    }\n}\n"
        val applied = finish(text, ask(select(text, "v + 1")), "value")
        assertEquals(
            "fun f(c: Boolean) {\n    var v = 1\n    val value = v + 1\n    val a = value\n" +
                "    if (c) {\n        var v = 5\n        val b = v + 1\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a stable occurrence resolving to another local is not replaced`() {
        val text = "fun f(c: Boolean) {\n    val w = 1\n    val x = w\n    if (c) {\n        val w = 5\n        use(w)\n    }\n}\n"
        val start = text.indexOf("= w") + 2
        val applied = finish(text, ask(startAt(text, start, start + 1)), "value")
        assertEquals(
            "fun f(c: Boolean) {\n    val w = 1\n    val value = w\n    val x = value\n" +
                "    if (c) {\n        val w = 5\n        use(w)\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a parameter declaration name in a local function is not replaced`() {
        val text = "fun f(c: Boolean) {\n    val w = 1\n    val x = w\n    if (c) {\n        fun g(w: Int) = use(w)\n        g(1)\n    }\n}\n"
        val start = text.indexOf("= w") + 2
        val applied = finish(text, ask(startAt(text, start, start + 1)), "value")
        assertEquals(
            "fun f(c: Boolean) {\n    val w = 1\n    val value = w\n    val x = value\n    if (c) {\n" +
                "        fun g(w: Int) = use(w)\n        g(1)\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a named argument and a local function parameter are not read-only occurrences`() {
        val text = "fun f(c: Boolean) {\n    var w = 1\n    val x = w\n    if (c) {\n        fun g(w: Int) = use(w)\n        g(w = 1)\n    }\n}\n"
        val start = text.indexOf("= w") + 2
        val applied = finish(text, ask(startAt(text, start, start + 1)), "value")
        assertEquals(
            "fun f(c: Boolean) {\n    var w = 1\n    val value = w\n    val x = value\n    if (c) {\n" +
                "        fun g(w: Int) = use(w)\n        g(w = 1)\n    }\n}\n",
            applied.text,
        )
    }


    @Test
    fun `a marker inside the when subject is refused`() {
        val text = "fun f(a: Boolean) {\n    when (a && foo()) {\n        else -> use(1)\n    }\n}\n"
        assertEquals(
            "foo() runs only in some cases here; a variable would run it every time",
            refused(select(text, "foo()")),
        )
    }

    @Test
    fun `a marker inside the for iterable is refused`() {
        val text = "fun f(c: Boolean) {\n    for (i in if (c) foo() else bar()) {\n        use(i)\n    }\n}\n"
        assertEquals(
            "foo() runs only in some cases here; a variable would run it every time",
            refused(select(text, "foo()")),
        )
    }

    @Test
    fun `a marker inside a grouped first if condition is refused`() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    if ((a || b) && foo()) {\n        use(1)\n    }\n}\n"
        assertEquals(
            "foo() runs only in some cases here; a variable would run it every time",
            refused(select(text, "foo()")),
        )
    }


    @Test
    fun `a selection starting or ending with a keyword operator is refused`() {
        assertEquals("Select an expression", refused(select("fun f(a: Any) {\n    val x = a as String\n}\n", "a as")))
        assertEquals("Select an expression", refused(select("fun f(a: Any) {\n    val x = a is String\n}\n", "a is")))
        assertEquals("Select an expression", refused(select("fun f() {\n    val x = a in list\n}\n", "a in")))
        assertEquals("Select an expression", refused(select("fun f(a: Any) {\n    val x = a as String\n}\n", "as String")))
    }


    @Test
    fun `a whole loop statement is refused and left out of the candidates`() {
        val whileText = "fun f(c: Boolean) {\n    while (c) use(1)\n}\n"
        assertEquals("Select an expression", refused(select(whileText, "while (c) use(1)")))
        val whileCaret = whileText.indexOf("(c)") + 1
        val whileChooser = startAt(whileText, whileCaret, whileCaret) as RefactoringStep.Choose
        assertEquals(listOf("c", "(c)"), whileChooser.options.map { it.text })

        val forText = "fun f() {\n    for (i in list) use(i)\n}\n"
        assertEquals("Select an expression", refused(select(forText, "for (i in list) use(i)")))
        val forCaret = forText.indexOf("(i in list)") + 6
        val forQuestion = startAt(forText, forCaret, forCaret) as RefactoringStep.AskName
        assertEquals("value", forQuestion.suggested)
    }

    @Test
    fun `a caret on an if statement keyword has no candidate`() {
        val text = "fun f(c: Boolean) {\n    if (c) use(1)\n}\n"
        assertEquals("Select an expression", refused(caret(text, "if")))
    }


    @Test
    fun `an underscore name is refused`() {
        val text = "fun f() {\n    val total = a + b\n}\n"
        val question = ask(select(text, "a + b"))
        assertEquals(NameCheck.Invalid("Not a valid name"), question.complete("_"))
        assertEquals(NameCheck.Invalid("Not a valid name"), question.complete("__"))
    }

    @Test
    fun `a character literal suggests value`() {
        assertEquals("value", ask(select("fun f() {\n    println('a')\n}\n", "'a'")).suggested)
    }

    @Test
    fun `a call argument list is not an expression`() {
        assertEquals("Select an expression", refused(select("fun f() {\n    draw(1)\n}\n", "(1)")))
        assertEquals("Select an expression", refused(select("fun f() {\n    val x = list[0]\n}\n", "[0]")))
        assertEquals("Select an expression", refused(select("fun f() {\n    val y = f<Int>(1)\n}\n", "(1)")))
    }

    @Test
    fun `a declared name is not an expression`() {
        val delegated = "fun f() {\n    val hovered by lazy { 1 }\n    println(hovered)\n}\n"
        assertEquals("Select an expression", refused(select(delegated, "hovered")))
        val parameter = "fun f() {\n    fun g(w: Int) = use(w)\n    g(1)\n}\n"
        val parameterStart = parameter.indexOf("w: Int")
        assertEquals("Select an expression", refused(startAt(parameter, parameterStart, parameterStart + 1)))
        val loop = "fun f() {\n    for (i in list) use(i)\n}\n"
        assertEquals("Select an expression", refused(select(loop, "i")))
    }

    @Test
    fun `a selection crossing a string or template boundary is refused`() {
        val text = "fun f(a: A, b: A) {\n    val s = \"\${a.b}c\"\n    val t = \"\${a} and \${b}\"\n}\n"
        assertEquals("Select an expression", refused(select(text, "a.b}c")))
        assertEquals("Select an expression", refused(select(text, "\"\${a.b")))
        assertEquals("Select an expression", refused(select(text, "a} and \${b")))
    }


    @Test
    fun `candidates are offered innermost first`() {
        val text = "fun f() {\n    println(a.b.c())\n}\n"
        val step = caret(text, "a.b.c()")
        val chooser = step as RefactoringStep.Choose
        assertEquals("Expressions", chooser.title)
        assertEquals(listOf("a", "a.b", "a.b.c()", "println(a.b.c())"), chooser.options.map { it.text })
        val highlight = chooser.options.first().highlights.single()
        assertEquals("a", text.substring(highlight.first, highlight.last + 1))
        val applied = finish(text, ask(choose(step, 2)), "chain")
        assertEquals("fun f() {\n    val chain = a.b.c()\n    println(chain)\n}\n", applied.text)
    }

    @Test
    fun `a single candidate is used directly`() {
        val text = "fun f() {\n    a\n}\n"
        val question = ask(caret(text, "a"))
        assertEquals("value", question.suggested)
        val applied = finish(text, question, "x")
        assertEquals("fun f() {\n    val x = a\n    x\n}\n", applied.text)
    }


    @Test
    fun `suggested names follow the expression`() {
        assertEquals("name2", ask(select("fun f() {\n    println(user.name)\n}\n", "user.name")).suggested)
        assertEquals("name", ask(select("fun f() {\n    println(getName())\n}\n", "getName()")).suggested)
        assertEquals("foo", ask(select("fun f() {\n    println(Foo())\n}\n", "Foo()")).suggested)
        assertEquals("text", ask(select("fun f() {\n    println(\"hello\")\n}\n", "\"hello\"")).suggested)
        assertEquals("number", ask(select("fun f() {\n    println(42)\n}\n", "42")).suggested)
    }

    @Test
    fun `a suggested name is made unique`() {
        val text = "fun f() {\n    val value = 1\n    println(a + b)\n}\n"
        assertEquals("value2", ask(select(text, "a + b")).suggested)
    }

    @Test
    fun `the name check refuses invalid and used names`() {
        val text = "fun f() {\n    val total = a + b\n}\n"
        val question = ask(select(text, "a + b"))
        assertEquals(NameCheck.Invalid("Not a valid name"), question.complete("1bad"))
        assertEquals(NameCheck.Invalid("Not a valid name"), question.complete("class"))
        assertEquals(NameCheck.Invalid("'b' is already used in this function"), question.complete("b"))
        assertTrue(question.complete("unused") is NameCheck.Valid)
    }


    @Test
    fun `a multi-line expression keeps its lines`() {
        val text = "fun f() {\n    val x = foo(\n        1,\n        2,\n    )\n}\n"
        val applied = finish(text, ask(select(text, "foo(\n        1,\n        2,\n    )")), "call")
        assertEquals("fun f() {\n    val call = foo(\n        1,\n        2,\n    )\n    val x = call\n}\n", applied.text)
    }

    @Test
    fun `a statement inside a lambda keeps its indentation`() {
        val text = "fun f() {\n    list.map { x ->\n        val y = x + 1\n        y * 2\n    }\n}\n"
        val applied = finish(text, ask(select(text, "x + 1")), "incremented")
        assertEquals(
            "fun f() {\n    list.map { x ->\n        val incremented = x + 1\n        val y = incremented\n        y * 2\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a carriage return file keeps its line ending`() {
        val text = "fun f() {\r\n    val total = a + b\r\n}\r\n"
        val applied = finish(text, ask(select(text, "a + b")), "sum")
        assertEquals("fun f() {\r\n    val sum = a + b\r\n    val total = sum\r\n}\r\n", applied.text)
    }


    @Test
    fun `an occurrence inside a receiver scope is left out`() {
        val text = "class C {\n    fun f(other: C) {\n        val a = this\n        with(other) { use(this) }\n    }\n}\n"
        val applied = finish(text, ask(select(text, "this")), "b")

        assertEquals(
            "class C {\n    fun f(other: C) {\n        val b = this\n        val a = b\n        with(other) { use(this) }\n    }\n}\n",
            applied.text,
        )
    }

    @Test
    fun `a local only expression keeps its occurrences in a receiver scope`() {
        val text = "fun f(a: Int) {\n    val b = a + 1\n    with(a) { use(a + 1) }\n}\n"
        val step = select(text, "a + 1")
        assertEquals("Replace all 2 occurrences", (step as RefactoringStep.Choose).options[1].text)
    }


    @Test
    fun `an if expression whose every branch has an else is an expression`() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    val v = if (a) 1 else if (b) 2 else 3\n}\n"
        val applied = finish(text, ask(select(text, "if (a) 1 else if (b) 2 else 3")), "value")

        assertEquals(
            "fun f(a: Boolean, b: Boolean) {\n    val value = if (a) 1 else if (b) 2 else 3\n    val v = value\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an if expression whose inner if lacks an else is refused`() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    val v = if (a) if (b) 1 else 2\n}\n"
        assertEquals("Select an expression", refused(select(text, "if (a) if (b) 1 else 2")))
    }

    @Test
    fun `an expression starting or ending at a bad token is refused`() {
        val minus = "fun f(a: Int) {\n    val n = a - 1\n}\n"
        assertEquals("Select an expression", refused(select(minus, "- 1")))
        val plus = "fun f(x: Int, key: Int) {\n    val n = x + key\n}\n"
        assertEquals("Select an expression", refused(select(plus, "+ key")))
        val generic = "fun f() {\n    val x = ArrayList<KtToken>()\n}\n"
        assertEquals("Select an expression", refused(select(generic, "ArrayList<KtToken")))
        val tryText = "fun f(): Int {\n    val value = try {\n        1\n    } catch (e: E) {\n        2\n    }\n    return value\n}\n"
        assertEquals("Select an expression", refused(select(tryText, "try")))
        val byText = "fun f() {\n    var t by remember { 0 }\n    println(t)\n}\n"
        assertEquals("Select an expression", refused(select(byText, "by")))
    }

    @Test
    fun `an expression may start with a prefix operator and end at an operand end`() {
        val minus = "fun f() {\n    consume(-1)\n}\n"
        assertEquals("value", ask(select(minus, "-1")).suggested)
        val not = "fun f(flag: Boolean) {\n    println(!flag)\n}\n"
        assertEquals("value", ask(select(not, "!flag")).suggested)
        val asserted = "fun f(x: Int?) {\n    println(x!!)\n}\n"
        assertEquals("value", ask(select(asserted, "x!!")).suggested)
        val incremented = "fun f() {\n    var i = 0\n    consume(i++)\n}\n"
        assertEquals("value", ask(select(incremented, "i++")).suggested)
        val indexed = "fun f(list: List<Int>) {\n    println(list[0])\n}\n"
        assertEquals("value", ask(select(indexed, "list[0]")).suggested)
    }

    @Test
    fun `a name spelled by an infix use is already used`() {
        val text = "fun f() {\n    val value = 3\n    println(value)\n    val p = value to 1\n    println(a + b)\n}\n"
        val question = ask(select(text, "a + b"))
        assertEquals(
            NameCheck.Invalid("'value' is already used in this function"),
            question.complete("value"),
        )
    }

    @Test
    fun `a selection the model misread before refuses or round-trips`() {
        val tryText = "fun f(): Int {\n    val value = try {\n        1\n    } catch (e: E) {\n        2\n    }\n    return value\n}\n"
        assertEquals("Select an expression", refused(select(tryText, "try")))
        val byText = "fun f() {\n    var t by remember { 0 }\n    println(t)\n}\n"
        assertEquals("Select an expression", refused(select(byText, "by")))

        val infix = "fun f(y: Int) {\n    val p = \"X\" to y\n    println(p)\n}\n"
        assertEquals(infix, introduceThenInline(infix, "to"))
        assertEquals(infix, introduceThenInline(infix, "\"X\" to y"))

        val provides = "fun f(tokens: DesignTokens) {\n    val style = LocalTextStyle provides tokens\n    println(style)\n}\n"
        assertEquals(provides, introduceThenInline(provides, "provides"))
    }

    /** Introduce Variable with the suggested name, then Inline it at its only use. */
    private fun introduceThenInline(text: String, snippet: String): String {
        val introduced = finish(text, ask(select(text, snippet)), "value").text
        val code = KotlinCode(introduced)
        val local = code.locals.first { it.name == "value" && it.kind == LocalKind.VAL }
        val use = code.references.first { it.resolved === local }
        val step = KotlinInline.prepare(RefactoringContext(introduced, use.token.start, use.token.end, "Test.kt"))
        val edit = (step as RefactoringStep.Apply).edit
        return apply(introduced, edit.edits)
    }
}
