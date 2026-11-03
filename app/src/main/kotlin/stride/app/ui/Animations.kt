package stride.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether UI animations run. Tests and snapshots set this to false so nothing keeps the test
 * clock busy and every state renders in its final form.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }
