package stride.lang.kotlin.refactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.RefactoringContext
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TextEdit

class KotlinInlineTest {
    private class Applied(val text: String, val caret: Int, val message: String?)

    private fun apply(text: String, edits: List<TextEdit>): String {
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    private fun startAt(text: String, start: Int, end: Int = start): RefactoringStep =
        KotlinInline.prepare(RefactoringContext(text, start, end, "Test.kt"))

    private fun caretAt(text: String, snippet: String, occurrence: Int = 0, delta: Int = 0): RefactoringStep {
        var index = -1
        repeat(occurrence + 1) { index = text.indexOf(snippet, index + 1) }
        check(index >= 0) { "missing snippet $snippet" }
        return startAt(text, index + delta)
    }

    private fun refused(step: RefactoringStep): String = (step as RefactoringStep.Refused).reason

    private fun apply(step: RefactoringStep, text: String): Applied {
        val apply = step as RefactoringStep.Apply
        return applied(text, apply.edit)
    }

    private fun applied(text: String, edit: RefactoringEdit): Applied =
        Applied(apply(text, edit.edits), edit.caret, edit.message)

    private fun choose(step: RefactoringStep, index: Int): RefactoringStep {
        val chooser = step as RefactoringStep.Choose
        return chooser.options[index].next()
    }


    @Test
    fun `a val is inlined at every use`() {
        val text = "fun f(a: Int, b: Int) {\n    val sum = a + b\n    println(sum)\n    println(sum)\n}\n"
        val applied = apply(caretAt(text, "sum)"), text)

        assertEquals("fun f(a: Int, b: Int) {\n    println(a + b)\n    println(a + b)\n}\n", applied.text)
        assertEquals(applied.text.indexOf("a + b"), applied.caret)
        assertEquals("Inlined sum (2 uses)", applied.message)
    }

    @Test
    fun `a selection of the declaration name inlines`() {
        val text = "fun f(a: Int, b: Int) {\n    val sum = a + b\n    println(sum)\n}\n"
        val start = text.indexOf("sum")
        val applied = apply(startAt(text, start, start + "sum".length), text)

        assertEquals("fun f(a: Int, b: Int) {\n    println(a + b)\n}\n", applied.text)
        assertEquals("Inlined sum (1 use)", applied.message)
    }

    @Test
    fun `a parameter and a member are not targets`() {
        assertEquals(
            "Inline works on local variables and functions declared in this file",
            refused(caretAt("fun f(a: Int) {\n    println(a)\n}\n", "a)")),
        )
        assertEquals(
            "Inline works on local variables and functions declared in this file",
            refused(caretAt("fun f() {\n    println(member)\n}\n", "member")),
        )
    }


    @Test
    fun `a var is refused`() {
        val text = "fun f() {\n    var x = 1\n    println(x)\n}\n"
        assertEquals("x is a var; only vals are inlined", refused(caretAt(text, "x)")))
    }

    @Test
    fun `a destructured or delegated val is refused`() {
        val destructured = "fun f(pair: Pair<Int, Int>) {\n    val (p, q) = pair\n    println(p)\n}\n"
        assertEquals("p has no initializer to inline", refused(caretAt(destructured, "p)")))
        val delegated = "fun f() {\n    val b by lazy { 1 }\n    println(b)\n}\n"
        assertEquals("b has no initializer to inline", refused(caretAt(delegated, "b)")))
    }

    @Test
    fun `a written type needs an initializer of that type`() {
        val mismatch = "fun f() {\n    val n: Number = 1\n    println(n)\n}\n"
        assertEquals(
            "n declares type Number; the inlined expression could get a different type",
            refused(caretAt(mismatch, "n)")),
        )
        val matching = "fun f() {\n    val user: User = User()\n    println(user.name)\n}\n"
        val applied = apply(caretAt(matching, "user.name"), matching)
        assertEquals("fun f() {\n    println(User().name)\n}\n", applied.text)
    }

    @Test
    fun `an unused val is refused`() {
        val text = "fun f() {\n    val x = 1\n}\n"
        assertEquals("x is never used", refused(caretAt(text, "x = 1")))
    }

    @Test
    fun `a nested declaration of the same name is refused`() {
        val text = "fun f() {\n    val x = 1\n    println(x)\n    run { val x = 2 }\n}\n"
        assertEquals("x is declared again in a nested scope", refused(caretAt(text, "x = 1")))
    }

    @Test
    fun `a declaration sharing its line is refused`() {
        val text = "fun f() {\n    val x = 1; println(x)\n}\n"
        assertEquals("The declaration shares its line with other code", refused(caretAt(text, "x = 1")))
    }


    @Test
    fun `a stable initializer replaces lambda, loop and condition uses`() {
        val text = "fun f(a: Int, b: Int) {\n    val sum = a + b\n" +
            "    run {\n        println(sum)\n    }\n" +
            "    for (i in 0..3) {\n        println(sum)\n    }\n" +
            "    if (sum > 0) {\n        println(sum)\n    }\n}\n"
        val applied = apply(caretAt(text, "sum)"), text)

        assertEquals(
            "fun f(a: Int, b: Int) {\n" +
                "    run {\n        println(a + b)\n    }\n" +
                "    for (i in 0..3) {\n        println(a + b)\n    }\n" +
                "    if ((a + b) > 0) {\n        println(a + b)\n    }\n}\n",
            applied.text,
        )
        assertEquals("Inlined sum (4 uses)", applied.message)
    }

    @Test
    fun `an initializer name that another declaration would capture is refused`() {
        val text = "fun f(v: Int) {\n    val inner = v + 1\n    run {\n        val v = 2\n        println(inner)\n    }\n}\n"
        assertEquals(
            "Inlining would make v refer to another declaration",
            refused(caretAt(text, "inner)")),
        )
    }


    @Test
    fun `an initializer with a call is inlined into the next statement`() {
        val text = "fun f() {\n    val x = load()\n    println(x)\n}\n"
        val applied = apply(caretAt(text, "x)"), text)
        assertEquals("fun f() {\n    println(load())\n}\n", applied.text)
        assertEquals("Inlined x (1 use)", applied.message)
    }

    @Test
    fun `an initializer with a call is inlined into a first if condition`() {
        val text = "fun f() {\n    val x = load()\n    if (x > 0) println(1)\n}\n"
        val applied = apply(caretAt(text, "x >"), text)
        assertEquals("fun f() {\n    if (load() > 0) println(1)\n}\n", applied.text)
    }

    @Test
    fun `an initializer with a call and two uses is refused`() {
        val text = "fun f() {\n    val x = load()\n    println(x)\n    println(x)\n}\n"
        assertEquals("x is used 2 times and its initializer is not stable", refused(caretAt(text, "x)")))
    }

    @Test
    fun `an initializer with a call inside a lambda is refused`() {
        val text = "fun f() {\n    val x = load()\n    run { println(x) }\n}\n"
        assertEquals("x's initializer would run later or repeatedly", refused(caretAt(text, "x)")))
    }

    @Test
    fun `an initializer with a call inside a loop is refused`() {
        val text = "fun f() {\n    val x = load()\n    while (ready) {\n        println(x)\n    }\n}\n"
        assertEquals("x's initializer would run later or repeatedly", refused(caretAt(text, "x)")))
    }

    @Test
    fun `an initializer with a call inside an if or try body is refused`() {
        val inIf = "fun f() {\n    val x = load()\n    if (ready) {\n        println(x)\n    }\n}\n"
        assertEquals("x's initializer would run only in some cases", refused(caretAt(inIf, "x)")))
        val inTry = "fun f() {\n    val x = load()\n    try {\n        println(x)\n    } catch (e: E) {\n        println(0)\n    }\n}\n"
        assertEquals("x's initializer would run only in some cases", refused(caretAt(inTry, "x)")))
    }

    @Test
    fun `an effect at the end of the declaration is not between it and the use`() {
        val text = "fun f() {\n    var out = 0\n    val x = (out += 1)\n    println(x)\n}\n"
        val applied = apply(caretAt(text, "x)"), text)
        assertEquals("fun f() {\n    var out = 0\n    println((out += 1))\n}\n", applied.text)
        assertEquals("Inlined x (1 use)", applied.message)
    }

    @Test
    fun `an initializer with a call after another effect is refused`() {
        val text = "fun f() {\n    val x = load()\n    reset()\n    println(x)\n}\n"
        assertEquals(
            "Code between the declaration and the use of x would run before its initializer",
            refused(caretAt(text, "x)")),
        )
    }


    @Test
    fun `template uses become braced templates`() {
        val text = "fun f(v: Int) {\n    val x = v + 1\n    println(\"\$x and \${x}\")\n}\n"
        val applied = apply(caretAt(text, "x ="), text)
        assertEquals("fun f(v: Int) {\n    println(\"\${v + 1} and \${v + 1}\")\n}\n", applied.text)
    }

    @Test
    fun `parentheses follow the substitution rule`() {
        val text = "fun f(a: Int, b: Int) {\n    val sum = a + b\n    println(sum)\n" +
            "    val doubled = sum * 2\n    val copy = sum\n}\n"
        val applied = apply(caretAt(text, "sum)"), text)
        assertEquals(
            "fun f(a: Int, b: Int) {\n    println(a + b)\n    val doubled = (a + b) * 2\n    val copy = a + b\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an atomic initializer is not parenthesized`() {
        val text = "fun f(v: Int) {\n    val x = v\n    println(x * 2)\n}\n"
        val applied = apply(caretAt(text, "x *"), text)
        assertEquals("fun f(v: Int) {\n    println(v * 2)\n}\n", applied.text)
    }


    @Test
    fun `an expression body function inlines one call`() {
        val text = "private fun twice(v: Int) = v * 2\n\nfun f() {\n    println(twice(2))\n}\n"
        val step = caretAt(text, "twice(2)")
        val applied = apply(choose(step, 0), text)
        assertEquals("private fun twice(v: Int) = v * 2\n\nfun f() {\n    println(2 * 2)\n}\n", applied.text)
        assertEquals("Inlined a call of twice", applied.message)
        assertEquals(applied.text.indexOf("2 * 2"), applied.caret)
    }

    @Test
    fun `a return body function inlines one call`() {
        val text = "private fun inc(v: Int) {\n    return v + 1\n}\n\nfun f() {\n    println(inc(2))\n}\n"
        val applied = apply(choose(caretAt(text, "inc(2)"), 0), text)
        assertEquals("private fun inc(v: Int) {\n    return v + 1\n}\n\nfun f() {\n    println(2 + 1)\n}\n", applied.text)
    }

    @Test
    fun `a unit statement body function replaces the call statement`() {
        val text = "private fun show(v: Int) {\n    println(v)\n}\n\nfun f() {\n    show(2)\n}\n"
        val applied = apply(choose(caretAt(text, "show(2)"), 0), text)
        assertEquals("private fun show(v: Int) {\n    println(v)\n}\n\nfun f() {\n    println(2)\n}\n", applied.text)
    }

    @Test
    fun `a body of several statements is refused`() {
        val text = "private fun two(v: Int) {\n    println(v)\n    println(v)\n}\n\nfun f() {\n    two(1)\n}\n"
        assertEquals("two has a body of several statements", refused(caretAt(text, "two(1)")))
    }


    @Test
    fun `open, override and interface members are refused`() {
        assertEquals(
            "Calls of f may reach another implementation",
            refused(caretAt("class C {\n    open fun f(v: Int) = v\n}\n", "fun f", delta = 4)),
        )
        assertEquals(
            "Calls of f may reach another implementation",
            refused(caretAt("class C : B() {\n    override fun f(v: Int) = v\n}\n", "fun f", delta = 4)),
        )
        assertEquals(
            "Calls of f may reach another implementation",
            refused(caretAt("interface I {\n    fun f(v: Int) = v\n}\n", "fun f", delta = 4)),
        )
    }

    @Test
    fun `generic, extension and vararg functions are refused`() {
        assertEquals(
            "id is generic",
            refused(caretAt("private fun <T> id(v: T) = v\n", "id(")),
        )
        assertEquals(
            "plus2 is an extension function",
            refused(caretAt("private fun Int.plus2() = this + 2\n", "plus2")),
        )
        assertEquals(
            "sum has a vararg parameter",
            refused(caretAt("private fun sum(vararg v: Int) = v.size\n", "sum(")),
        )
    }

    @Test
    fun `a recursive function is refused`() {
        val text = "private fun f(v: Int) = f(v)\n"
        assertEquals("f is recursive", refused(caretAt(text, "fun f", delta = 4)))
    }

    @Test
    fun `a return inside the body is refused`() {
        val text = "private fun f(v: Int) = list.map { return v }\n"
        assertEquals("f returns from inside its body", refused(caretAt(text, "fun f", delta = 4)))
    }

    @Test
    fun `a return type other than the body type is refused`() {
        val text = "private fun count(): Int = list.size\n"
        assertEquals(
            "count declares type Int; the inlined expression could get a different type",
            refused(caretAt(text, "count")),
        )
    }

    @Test
    fun `a Boolean comparison body is accepted`() {
        val text = "private fun positive(v: Int): Boolean = v > 0\n\nfun f() {\n    if (positive(2)) {\n        println(1)\n    }\n}\n"
        val applied = apply(choose(caretAt(text, "positive(2)"), 0), text)
        assertEquals(
            "private fun positive(v: Int): Boolean = v > 0\n\nfun f() {\n    if (2 > 0) {\n        println(1)\n    }\n}\n",
            applied.text,
        )
    }


    @Test
    fun `a call that omits a default argument is refused`() {
        val text = "private fun add(v: Int, w: Int = 2) = v + w\n\nfun f() {\n    add(1)\n}\n"
        assertEquals("The call at line 4 uses default arguments", refused(caretAt(text, "add(1)")))
    }

    @Test
    fun `a member call from another class is refused`() {
        val text = "class A {\n    fun f(v: Int) = v + 1\n    inner class B {\n        fun g() {\n            f(2)\n        }\n    }\n}\n"
        assertEquals("The call at line 5 is in another class", refused(caretAt(text, "f(2)")))
    }

    @Test
    fun `a statement body call that uses the result is refused`() {
        val text = "private fun show(v: Int) {\n    println(v)\n}\n\nfun f() {\n    val z = show(1)\n}\n"
        assertEquals("The call at line 6 uses the result of show", refused(caretAt(text, "show(1)")))
    }


    @Test
    fun `named arguments map by name`() {
        val text = "private fun move(from: Int, to: Int) = from + to\n\nfun f() {\n    println(move(to = 2, from = 1))\n}\n"
        val applied = apply(choose(caretAt(text, "move(to"), 0), text)
        assertEquals(
            "private fun move(from: Int, to: Int) = from + to\n\nfun f() {\n    println(1 + 2)\n}\n",
            applied.text,
        )
    }

    @Test
    fun `all mode shows the first failing call`() {
        val text = "private fun add(v: Int, w: Int = 2) = v + w\n\nfun f() {\n    add(1, 3)\n    add(1)\n}\n"
        assertEquals(
            "The call at line 5 uses default arguments",
            refused(caretAt(text, "fun add", delta = 4)),
        )
    }

    @Test
    fun `a stable argument replaces every use`() {
        val text = "private fun twice(v: Int) = v + v\n\nfun f() {\n    println(twice(2))\n}\n"
        val applied = apply(choose(caretAt(text, "twice(2)"), 0), text)
        assertEquals("private fun twice(v: Int) = v + v\n\nfun f() {\n    println(2 + 2)\n}\n", applied.text)
    }

    @Test
    fun `an argument with a call and one use is accepted`() {
        val text = "private fun id(v: Int) = v\n\nfun f() {\n    println(id(load()))\n}\n"
        val applied = apply(choose(caretAt(text, "id(load())"), 0), text)
        assertEquals("private fun id(v: Int) = v\n\nfun f() {\n    println(load())\n}\n", applied.text)
    }

    @Test
    fun `an argument with a call and two uses is refused`() {
        val text = "private fun twice(v: Int) = v + v\n\nfun f() {\n    println(twice(load()))\n}\n"
        assertEquals("The argument load() would run 2 times or only in some cases", refused(caretAt(text, "twice(load())")))
    }

    @Test
    fun `an unused argument with a call would no longer run`() {
        val text = "private fun ignore(v: Int) = 1\n\nfun f() {\n    ignore(load())\n}\n"
        assertEquals("load() would no longer run", refused(caretAt(text, "ignore(load())")))
    }

    @Test
    fun `arguments whose uses are reordered are refused`() {
        val text = "private fun pick(a: Int, b: Int) = b - a\n\nfun f() {\n    println(pick(load(), next()))\n}\n"
        assertEquals(
            "Arguments of the call at line 4 would run in a different order",
            refused(caretAt(text, "pick(load(), next())")),
        )
    }

    @Test
    fun `an argument whose use comes after a body call is refused`() {
        val text = "private fun pick(v: Int) = reset() + v\n\nfun f() {\n    println(pick(load()))\n}\n"
        assertEquals(
            "Arguments of the call at line 4 would run in a different order",
            refused(caretAt(text, "pick(load())")),
        )
    }


    @Test
    fun `a body name captured at the call is refused`() {
        val text = "private fun show(v: Int) = v + count\n\nfun f() {\n    val count = 2\n    println(show(1))\n}\n"
        assertEquals("count means something else at the call at line 5", refused(caretAt(text, "show(1)")))
    }

    @Test
    fun `an argument name captured by a body declaration is refused`() {
        val text = "private fun apply(v: Int) = list.map { size -> v + size }\n\nfun f() {\n    val size = 2\n    println(apply(size))\n}\n"
        assertEquals("size means something else at the call at line 5", refused(caretAt(text, "apply(size)")))
    }


    @Test
    fun `a call offers both modes`() {
        val text = "private fun twice(v: Int) = v * 2\n\nfun f() {\n    println(twice(2))\n}\n"
        val chooser = caretAt(text, "twice(2)") as RefactoringStep.Choose
        assertEquals("Inline twice", chooser.title)
        assertEquals(
            listOf("Inline this call only", "Inline all and remove twice"),
            chooser.options.map { it.text },
        )
        val callHighlight = chooser.options[0].highlights.single()
        assertEquals("twice(2)", text.substring(callHighlight.first, callHighlight.last + 1))
        assertEquals(2, chooser.options[1].highlights.size)
        val declarationHighlight = chooser.options[1].highlights.last()
        assertTrue(text.substring(declarationHighlight.first, declarationHighlight.last + 1).startsWith("private fun twice"))
    }

    @Test
    fun `a non-private call only inlines that call`() {
        val text = "fun twice(v: Int) = v * 2\n\nfun f() {\n    println(twice(2))\n}\n"
        val applied = apply(caretAt(text, "twice(2)"), text)
        assertEquals("fun twice(v: Int) = v * 2\n\nfun f() {\n    println(2 * 2)\n}\n", applied.text)
    }

    @Test
    fun `a non-private declaration is refused`() {
        val text = "fun twice(v: Int) = v * 2\n"
        assertEquals(
            "twice is not private; other files may call it. Put the caret on a call to inline only that call",
            refused(caretAt(text, "fun twice", delta = 4)),
        )
    }

    @Test
    fun `a callable reference blocks all mode`() {
        val text = "private fun twice(v: Int) = v * 2\n\nfun f() {\n    val ref = ::twice\n    println(twice(2))\n}\n"
        assertEquals("twice is used as a reference", refused(caretAt(text, "fun twice", delta = 4)))
    }

    @Test
    fun `a call on another object blocks all mode`() {
        val text = "class A {\n    private fun f(v: Int) = v + 1\n    fun g() {\n        f(1)\n    }\n" +
            "    inner class B {\n        fun h(other: A) {\n            other.f(2)\n        }\n    }\n}\n"
        assertEquals("f is also called on other objects", refused(caretAt(text, "fun f", delta = 4)))
    }

    @Test
    fun `a this call is inlined`() {
        val text = "class C {\n    fun f(v: Int) = v + 1\n    fun g() {\n        println(this.f(2))\n    }\n}\n"
        val applied = apply(caretAt(text, "f(2)"), text)
        assertEquals("class C {\n    fun f(v: Int) = v + 1\n    fun g() {\n        println(2 + 1)\n    }\n}\n", applied.text)
    }

    @Test
    fun `all mode inlines every call and removes the declaration`() {
        val text = "private fun twice(v: Int) = v * 2\n\nfun f() {\n    println(twice(2))\n    println(twice(3))\n}\n"
        val chooser = caretAt(text, "fun twice", delta = 4) as RefactoringStep.Apply
        val edit = chooser.edit

        assertEquals("fun f() {\n    println(2 * 2)\n    println(3 * 2)\n}\n", apply(text, edit.edits))
        assertEquals("Inlined 2 calls of twice", edit.message)
        assertEquals("fun f() {\n    println(2 * 2)\n    println(3 * 2)\n}\n".indexOf("2 * 2"), edit.caret)
    }

    @Test
    fun `removal drops the KDoc and one blank line`() {
        val text = "/** Doubles v. */\nprivate fun twice(v: Int) = v * 2\n\nfun f() {\n    println(twice(2))\n}\n"
        val edit = (caretAt(text, "fun twice", delta = 4) as RefactoringStep.Apply).edit
        assertEquals("fun f() {\n    println(2 * 2)\n}\n", apply(text, edit.edits))
        assertEquals("Inlined a call of twice", edit.message)
    }

    @Test
    fun `a trailing lambda maps to the last parameter`() {
        val text = "private fun each(v: Int, block: (Int) -> Unit) = list.forEach(block)\n\n" +
            "fun f() {\n    each(1) { println(it) }\n}\n"
        val applied = apply(choose(caretAt(text, "each(1)"), 0), text)
        assertEquals(
            "private fun each(v: Int, block: (Int) -> Unit) = list.forEach(block)\n\n" +
                "fun f() {\n    list.forEach({ println(it) })\n}\n",
            applied.text,
        )
    }

    @Test
    fun `an argument used after a marker inside the first if condition is refused`() {
        val text = "private fun f(a: Boolean, p: Boolean) = if (a && p) 1 else 2\n\n" +
            "fun g(c: Boolean) {\n    println(f(c, check()))\n}\n"
        assertEquals(
            "The argument check() would run 1 times or only in some cases",
            refused(caretAt(text, "f(c, check())")),
        )
    }

    @Test
    fun `a whole first if condition still takes an argument`() {
        val text = "private fun f(a: Boolean, p: Boolean) = if (p) 1 else 2\n\n" +
            "fun g(c: Boolean) {\n    println(f(c, check()))\n}\n"
        val applied = apply(choose(caretAt(text, "f(c, check())"), 0), text)
        assertEquals(
            "private fun f(a: Boolean, p: Boolean) = if (p) 1 else 2\n\n" +
                "fun g(c: Boolean) {\n    println(if (check()) 1 else 2)\n}\n",
            applied.text,
        )
    }


    @Test
    fun `an unused function is never used`() {
        val text = "private fun twice(v: Int) = v * 2\n"
        assertEquals("twice is never used", refused(caretAt(text, "fun twice", delta = 4)))
    }

    @Test
    fun `a return to the function name is refused`() {
        val text = "private fun f(v: Int) = list.map { return@f v }\n"
        assertEquals("f returns from inside its body", refused(caretAt(text, "fun f", delta = 4)))
    }

    @Test
    fun `a return to a label outside the body is refused`() {
        val text = "private fun f(v: Int) = list.map { return@outer v }\n"
        assertEquals("f returns from inside its body", refused(caretAt(text, "fun f", delta = 4)))
    }

    @Test
    fun `a return to a label written in the body is allowed`() {
        val text = "private fun f(v: Int) = list.map lit@ { return@lit v }\n\nfun g() {\n    println(f(2))\n}\n"
        val applied = apply(choose(caretAt(text, "f(2)"), 0), text)
        assertEquals(
            "private fun f(v: Int) = list.map lit@ { return@lit v }\n\n" +
                "fun g() {\n    println(list.map lit@ { return@lit 2 })\n}\n",
            applied.text,
        )
    }


    @Test
    fun `unbalanced brackets are refused`() {
        val text = "fun f() {\n    val a = Foo(1\n    println(a)\n}\n"
        assertEquals("Brackets do not match in this file", refused(caretAt(text, "a", 1)))
    }
}
