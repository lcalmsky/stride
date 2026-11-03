package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestEventParserTest {
    @Test
    fun `parses the five event kinds`() {
        assertEquals(
            ParsedLine.Event(TestEvent.Start("demo.DemoTest", "adds")),
            TestEventParser.parse("##stride[test start class='demo.DemoTest' name='adds']"),
        )
        assertEquals(
            ParsedLine.Event(TestEvent.Pass("demo.DemoTest", "adds", 12)),
            TestEventParser.parse("##stride[test pass class='demo.DemoTest' name='adds' duration='12']"),
        )
        assertEquals(
            ParsedLine.Event(
                TestEvent.Fail("demo.DemoTest", "adds", 3, "boom", "at demo.DemoTest.adds(DemoTest.kt:4)"),
            ),
            TestEventParser.parse(
                "##stride[test fail class='demo.DemoTest' name='adds' duration='3' " +
                    "message='boom' stack='at demo.DemoTest.adds(DemoTest.kt:4)']",
            ),
        )
        assertEquals(
            ParsedLine.Event(TestEvent.Skip("demo.DemoTest", "adds")),
            TestEventParser.parse("##stride[test skip class='demo.DemoTest' name='adds']"),
        )
        assertEquals(
            ParsedLine.Event(TestEvent.Out("demo.DemoTest", "adds", "stderr", "hello")),
            TestEventParser.parse(
                "##stride[test out class='demo.DemoTest' name='adds' stream='stderr' text='hello']",
            ),
        )
    }

    @Test
    fun `unescapes the teamcity escapes`() {
        val parsed = TestEventParser.parse(
            "##stride[test fail class='a.B' name='c' duration='1' " +
                "message='line1|nline2|'quoted|' and || pipe|[' stack='at |]x|[']",
        )

        val event = (parsed as ParsedLine.Event).event as TestEvent.Fail
        assertEquals("line1\nline2'quoted' and | pipe[", event.message)
        assertEquals("at ]x[", event.stackTrace)
    }

    @Test
    fun `keeps lines without the prefix as output`() {
        assertEquals(ParsedLine.Output("BUILD SUCCESSFUL in 1s"), TestEventParser.parse("BUILD SUCCESSFUL in 1s"))
        assertEquals(ParsedLine.Output(""), TestEventParser.parse(""))
    }

    @Test
    fun `keeps a broken prefixed line verbatim as output`() {
        val broken = "##stride[test pass class='demo.DemoTest' name='adds'"
        assertEquals(ParsedLine.Output(broken), TestEventParser.parse(broken))

        val unknown = "##stride[test nope class='a.B' name='c']"
        assertEquals(ParsedLine.Output(unknown), TestEventParser.parse(unknown))

        val badDuration = "##stride[test pass class='a.B' name='c' duration='soon']"
        assertEquals(ParsedLine.Output(badDuration), TestEventParser.parse(badDuration))
    }

    @Test
    fun `preserves the order of events and output`() {
        val lines = listOf(
            "##stride[test start class='a.B' name='c']",
            "downloading",
            "##stride[test pass class='a.B' name='c' duration='1']",
            "done",
        )

        val kinds = lines.map {
            when (val parsed = TestEventParser.parse(it)) {
                is ParsedLine.Event -> parsed.event::class.simpleName
                is ParsedLine.Output -> parsed.text
            }
        }

        assertEquals(listOf("Start", "downloading", "Pass", "done"), kinds)
    }
}
