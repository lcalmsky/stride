package stride.lang

interface FileType {
    val name: String
    val description: String
    val defaultExtension: String
}

open class LanguageFileType(
    open val language: Language,
    override val name: String = language.id,
    override val description: String = name,
    override val defaultExtension: String = language.id,
) : FileType

object PlainTextFileType : FileType {
    override val name: String = "Plain text"
    override val description: String = "Plain text file"
    override val defaultExtension: String = "txt"
}
