package stride.lang.kotlin.refactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinScopesTest {
    private fun sensitive(text: String, snippet: String): String? {
        val code = KotlinCode(text)
        val start = text.indexOf(snippet)
        check(start >= 0) { "missing snippet $snippet" }
        return receiverSensitiveName(code, KtRange(start, start + snippet.length))
    }

    private fun between(text: String, outerSnippet: String, innerSnippet: String): Boolean {
        val code = KotlinCode(text)
        val outer = text.indexOf(outerSnippet)
        val inner = text.indexOf(innerSnippet)
        check(outer >= 0 && inner >= 0) { "missing snippet" }
        return receiverScopeBetween(code, outer, inner)
    }

    @Test
    fun `this and super are receiver sensitive`() {
        assertEquals("this", sensitive("fun f() {\n    println(this)\n}\n", "this"))
        assertEquals("super", sensitive("class C : B() {\n    fun f() {\n        super.f()\n    }\n}\n", "super"))
    }

    @Test
    fun `a name that does not resolve to a local is receiver sensitive`() {
        assertEquals("base", sensitive("fun f() {\n    println(base + 1)\n}\n", "base + 1"))
        assertEquals("load", sensitive("fun f() {\n    println(load())\n}\n", "load()"))
        assertEquals("count", sensitive("fun f() {\n    println(count)\n}\n", "count"))
    }

    @Test
    fun `local names, member names, named arguments and labels are not`() {
        assertNull(sensitive("fun f(a: Int) {\n    println(a + 1)\n}\n", "a + 1"))
        assertNull(sensitive("fun f(user: User) {\n    println(user.name)\n}\n", "name"))
        assertNull(sensitive("fun f() {\n    println(call(size = 1))\n}\n", "size = 1"))
        assertNull(sensitive("fun f() {\n    run lit@ { println(1) }\n}\n", "lit"))
    }

    @Test
    fun `a lambda that does not hold the outer position is a receiver scope`() {
        val text = "fun f() {\n    val x = 1\n    run { println(x) }\n}\n"
        assertTrue(between(text, "1\n", "x)"))
    }

    @Test
    fun `a lambda that holds both positions is not`() {
        val text = "fun f() {\n    run {\n        val x = 1\n        println(x)\n    }\n}\n"
        assertFalse(between(text, "1\n", "x)"))
    }

    @Test
    fun `class bodies and object expressions are receiver scopes`() {
        val local = "fun f() {\n    val x = 1\n    class D {\n        fun g() = x\n    }\n}\n"
        assertTrue(between(local, "1\n", "x\n"))
        val objectExpression = "fun f() {\n    val x = 1\n    val o = object {\n        val p = x\n    }\n}\n"
        assertTrue(between(objectExpression, "1\n", "x\n"))
    }

    @Test
    fun `block and expression body local functions are receiver scopes`() {
        val block = "fun f() {\n    val x = 1\n    fun g() {\n        println(x)\n    }\n}\n"
        assertTrue(between(block, "1\n", "x)"))
        val expression = "fun f() {\n    val x = 1\n    fun g() = x\n}\n"
        assertTrue(between(expression, "1\n", "x\n"))
    }

    @Test
    fun `a call in a class body is below a top level declaration`() {
        val text = "private fun f(v: Int) = v\n\nclass A {\n    fun g() {\n        println(f(1))\n    }\n}\n"
        assertTrue(between(text, "fun f", "f(1)"))
        assertFalse(between(text, "fun g", "println"))
    }
}
