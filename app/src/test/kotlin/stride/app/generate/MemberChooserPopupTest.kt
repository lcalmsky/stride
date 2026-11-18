package stride.app.generate

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.lang.MemberOption

@ExtendWith(FixedUiFontExtension::class)
class MemberChooserPopupTest {
    private var submitted: List<MemberOption>? = null
    private var closed = false

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `space toggles the row under the cursor and enter submits it`() = runComposeUiTest {
        setContent { Chooser(members("alpha", "beta", "gamma")) }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("alpha"), submitted?.map { it.text })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `enter without a check submits the row under the cursor`() = runComposeUiTest {
        setContent { Chooser(members("alpha", "beta", "gamma")) }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("beta"), submitted?.map { it.text })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command A checks every enabled row and again unchecks them`() = runComposeUiTest {
        setContent {
            Chooser(
                listOf(
                    MemberOption("alpha"),
                    MemberOption("beta", disabledReason = "refers to another name here"),
                    MemberOption("gamma"),
                ),
            )
        }
        waitForIdle()

        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.A) } }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("alpha", "gamma"), submitted?.map { it.text })

        submitted = null
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.A) } }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("alpha"), submitted?.map { it.text })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a disabled row cannot be checked`() = runComposeUiTest {
        setContent { Chooser(listOf(MemberOption("alpha"), MemberOption("beta", disabledReason = "not here"))) }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertNull(submitted)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `initially checked rows are submitted in list order`() = runComposeUiTest {
        setContent {
            Chooser(
                listOf(
                    MemberOption("alpha", checked = true),
                    MemberOption("beta"),
                    MemberOption("gamma", checked = true),
                ),
            )
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("alpha", "gamma"), submitted?.map { it.text })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `moving the cursor past the last visible row scrolls the list`() = runComposeUiTest {
        setContent { Chooser(List(30) { MemberOption("member $it") }) }
        waitForIdle()

        repeat(29) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        waitForIdle()

        onNodeWithText("member 29").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape closes the popup`() = runComposeUiTest {
        setContent { Chooser(members("alpha", "beta")) }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(true, closed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `group headings, disabled reasons and the error are drawn`() = runComposeUiTest {
        setContent {
            MemberChooserHost(
                title = "Override Methods",
                members = listOf(
                    MemberOption("alpha(): Unit", group = "AlphaOne"),
                    MemberOption("beta(): Unit", group = "AlphaOne", disabledReason = "refers to another name here"),
                ),
                error = "Choose a member",
            )
        }
        waitForIdle()

        onNodeWithText("Override Methods").assertIsDisplayed()
        onNodeWithText("AlphaOne").assertIsDisplayed()
        onNodeWithText("alpha(): Unit").assertIsDisplayed()
        onNodeWithText("refers to another name here").assertIsDisplayed()
        onNodeWithTag(MEMBER_CHOOSER_ERROR_TAG).assertIsDisplayed()
        onNodeWithText("Choose a member").assertIsDisplayed()
    }

    private fun members(vararg names: String): List<MemberOption> = names.map { MemberOption(it) }

    @androidx.compose.runtime.Composable
    private fun Chooser(members: List<MemberOption>) {
        MemberChooserHost(title = "Implement Methods", members = members, error = null)
    }

    @androidx.compose.runtime.Composable
    private fun MemberChooserHost(title: String, members: List<MemberOption>, error: String?) {
        CompositionLocalProvider(LocalAnimationsEnabled provides false) {
            MemberChooserPopup(
                title = title,
                members = members,
                error = error,
                onSubmit = { submitted = it },
                onClose = { closed = true },
            )
        }
    }
}
