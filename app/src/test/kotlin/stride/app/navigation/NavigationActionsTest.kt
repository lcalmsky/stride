package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.search.DeclarationKind
import stride.search.ParsedDeclaration

class NavigationActionsTest {
    @Test
    fun `a kotlin string template drops its dollar prefix`() {
        val text = "println(\"name = \$userName\")"

        assertEquals("userName", identifierAt(text, "Main.kt", text.indexOf("userName")))
    }

    @Test
    fun `a java identifier keeps its dollar signs`() {
        val text = "int \$total = 1"

        assertEquals("\$total", identifierAt(text, "Main.java", text.indexOf("total")))
    }

    @Test
    fun `a template marker without a name has no identifier`() {
        val text = "val x = \"\${value}\""

        assertNull(identifierAt(text, "Main.kt", text.indexOf('$')))
    }

    @Test
    fun `usage matches are whole word and case sensitive`() {
        val text = "alpha Alpha alphas alpha\n"

        assertEquals(listOf(0..4, 19..23), UsageMatches.ranges(text, "alpha"))
    }

    @Test
    fun `the usage walk wraps at both ends`() {
        val highlights = listOf(0..4, 10..14, 20..24)

        assertEquals(10..14, UsageMatches.next(highlights, 5))
        assertEquals(0..4, UsageMatches.next(highlights, 24))
        assertEquals(0..4, UsageMatches.previous(highlights, 5))
        assertEquals(20..24, UsageMatches.previous(highlights, 0))
        assertNull(UsageMatches.next(emptyList(), 0))
        assertNull(UsageMatches.previous(emptyList(), 0))
    }

    @Test
    fun `local targets can be looked up in declarations that were parsed already`() {
        val declarations = listOf(
            ParsedDeclaration("helper", DeclarationKind.SYMBOL, 2),
            ParsedDeclaration("helper", DeclarationKind.SYMBOL, 8),
        )

        assertEquals(2, DeclarationNavigator.localTarget(declarations, "helper", 5)?.line)
        assertEquals(8, DeclarationNavigator.localTarget(declarations, "helper", 9)?.line)
        assertNull(DeclarationNavigator.localTarget(declarations, "missing", 9))
    }
}
