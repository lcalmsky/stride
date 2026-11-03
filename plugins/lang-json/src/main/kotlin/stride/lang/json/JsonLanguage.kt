package stride.lang.json

import stride.lang.Language
import stride.lang.LanguageFileType

val JsonLanguage: Language = Language("json")

object JsonFileType : LanguageFileType(
    JsonLanguage,
    name = "JSON",
    description = "JSON file",
    defaultExtension = "json",
)
