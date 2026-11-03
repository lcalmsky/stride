package stride.lang.kotlin

import stride.lang.Language
import stride.lang.LanguageFileType

val KotlinLanguage: Language = Language("kotlin")

object KotlinFileType : LanguageFileType(
    KotlinLanguage,
    name = "Kotlin",
    description = "Kotlin source file",
    defaultExtension = "kt",
)
