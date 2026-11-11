package stride.app.ui

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import stride.app.ui.theme.UiFont
import stride.app.ui.theme.UiFontPreference

/**
 * Pins the UI font to the bundled default for a test class, so rendered pixel assertions never
 * depend on the developer's stored choice, and restores the previous preference afterwards.
 */
class FixedUiFontExtension : BeforeAllCallback, AfterAllCallback {
    private var previous: UiFontPreference? = null

    override fun beforeAll(context: ExtensionContext) {
        previous = UiFont.preference
        UiFont.preference = UiFontPreference(null)
    }

    override fun afterAll(context: ExtensionContext) {
        previous?.let { UiFont.preference = it }
    }
}
