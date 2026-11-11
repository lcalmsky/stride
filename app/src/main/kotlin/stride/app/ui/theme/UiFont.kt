package stride.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.util.prefs.Preferences

/** The UI font the user chose; [preferenceValue] is what the preferences node stores. */
enum class UiFontChoice(val preferenceValue: String, val label: String) {
    PRETENDARD("pretendard", "Pretendard"),
    SYSTEM("system", "System"),
    ;

    fun next(): UiFontChoice = if (this == PRETENDARD) SYSTEM else PRETENDARD

    companion object {
        val default: UiFontChoice = PRETENDARD

        /** The stored value, or [default] when it is missing or unknown. */
        fun fromPreference(value: String?): UiFontChoice =
            entries.firstOrNull { it.preferenceValue == value } ?: default
    }
}

/** Pretendard, bundled under `fonts/pretendard`, in the weights the UI uses. */
val PretendardFontFamily: FontFamily = FontFamily(
    Font("fonts/pretendard/Pretendard-Regular.otf", FontWeight.Normal),
    Font("fonts/pretendard/Pretendard-Medium.otf", FontWeight.Medium),
    Font("fonts/pretendard/Pretendard-SemiBold.otf", FontWeight.SemiBold),
    Font("fonts/pretendard/Pretendard-Bold.otf", FontWeight.Bold),
)

/** Reads and writes the UI font choice in a preferences node. */
class UiFontPreference(private val node: Preferences?) {
    fun read(): UiFontChoice =
        runCatching { node?.get(KEY, null) }.getOrNull().let(UiFontChoice::fromPreference)

    fun write(choice: UiFontChoice) {
        runCatching {
            node?.put(KEY, choice.preferenceValue)
            node?.flush()
        }
    }

    companion object {
        const val NODE: String = "stride"
        const val KEY: String = "uiFont"
    }
}

/** The runtime UI font; [choice] is snapshot state so every reader recomposes when it flips. */
object UiFont {
    /** Test seam: replace this with a temporary preferences node. */
    var preference: UiFontPreference = UiFontPreference(
        runCatching { Preferences.userRoot().node(UiFontPreference.NODE) }.getOrNull(),
    )
        set(value) {
            field = value
            choice = value.read()
        }

    var choice: UiFontChoice by mutableStateOf(preference.read())
        private set

    val family: FontFamily
        get() = when (choice) {
            UiFontChoice.PRETENDARD -> PretendardFontFamily
            UiFontChoice.SYSTEM -> FontFamily.Default
        }

    /** Flips the choice and stores it; returns the new choice. */
    fun toggle(): UiFontChoice {
        val next = choice.next()
        choice = next
        preference.write(next)
        return next
    }
}
