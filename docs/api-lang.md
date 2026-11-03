# stride lang public API

This document is the contract for `:lang`, `:plugins:lang-kotlin` and `:plugins:lang-json`.
Signatures below are the public Kotlin API of their `src/main/kotlin`, exactly as implemented.
It also contains the extension point declarations that step 3 must add to the application's
descriptor; `:lang` itself declares no extension points.

## Module layout

```
:lang                  stride.lang.*                         (this document)
:plugins:lang-kotlin   stride.lang.kotlin.*                  (Kotlin lexer, highlighter, file type)
:plugins:lang-json     stride.lang.json.*                    (JSON lexer, highlighter, file type)
```

Both plugin modules depend on `:core` and `:lang`. `:lang` depends only on `:core`; it has no
Compose dependency and no knowledge of the other language modules.

## `stride.lang`

```kotlin
class Language(val id: String)

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

object PlainTextFileType : FileType   // name "Plain text", defaultExtension "txt"

class FileTypeBean : ExtensionBean() {
    override val key: String?          // attributes["extension"], lowercased
    val extension: String?
    val language: Language?            // Language(attributes["language"])
    fun fileType(): FileType           // instance(FileType::class)
}

object FileTypeRegistry {
    const val FILE_TYPE_EP = "stride.fileType"
    fun getFileType(fileName: String, area: ExtensionArea = Application.extensionArea): FileType
    fun getFileTypeByExtension(extension: String?, area: ExtensionArea = Application.extensionArea): FileType
}
```

`getFileType` takes the text after the last dot of the file name. A dotfile
(`.gitignore`) and a name without a dot have no extension. Matching is case-insensitive and
uses the bean key, so the extension of a file type is the `extension` attribute of its
`fileType` extension, not `FileType.defaultExtension`. An unknown or missing extension
returns `PlainTextFileType`.

`LanguageFileType` defaults `name`, `description` and `defaultExtension` to the language id;
plugins override them. `Language` compares by id.

```kotlin
interface Lexer {
    fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int)
    fun getState(): Int
    fun getTokenType(): TokenType?     // null when lexing is finished
    fun getTokenStart(): Int
    fun getTokenEnd(): Int
    fun advance()
}

class TokenType(val debugName: String) {
    companion object { val BAD_CHARACTER: TokenType }
}
```

The lexer contract: `initialState` must equal the value `getState()`
returned at `getTokenStart()`. `getState()` is the state *at the current token start* (when
finished, at `endOffset`), so `start(buffer, tokenStart, end, getState())` resumes exactly
there. States must be an `Int` and must fully describe how to resume at a line start.

Whitespace is not returned as a token. Lexers in this module emit tokens that stay inside a
single line: a construct that spans lines (raw string, block comment, multi-line template)
produces one token per line. A lexer passed to `LineStateHighlighter` must have both
properties.

```kotlin
class TextAttributesKey(val externalName: String, val fallback: TextAttributesKey? = null)

class TextAttributes(
    val foreground: Long? = null,   // ARGB
    val background: Long? = null,   // ARGB
    val bold: Boolean = false,
    val italic: Boolean = false,
)

class ColorScheme(val name: String, attributes: Map<TextAttributesKey, TextAttributes>) {
    fun getAttributes(key: TextAttributesKey): TextAttributes?
    fun attributes(): Map<TextAttributesKey, TextAttributes>
    companion object {
        const val DARK_BACKGROUND = 0xFF0F1117
        const val DARK_ACCENT = 0xFF7C5CFF
        const val DARK_ACCENT_2 = 0xFF00D1FF
        val DARK: ColorScheme
        val LIGHT: ColorScheme
    }
}
```

Default keys: `DEFAULT_TEXT` (no fallback), and `KEYWORD`, `STRING`, `NUMBER`,
`LINE_COMMENT`, `BLOCK_COMMENT`, `IDENTIFIER`, `OPERATION_SIGN`, `BRACES`, `BRACKETS`,
`PARENTHESES`, `COMMA`, `DOT`, `SEMICOLON` (all falling back to `DEFAULT_TEXT`), plus
`CHARACTER` (falling back to `STRING`) and `BAD_CHARACTER`. Keys compare by `externalName`.
`getAttributes` walks `fallback` until it finds an entry and returns `null` when the chain
ends without one.

`DARK` is named `Stride Dark` and uses the logo palette: background `#0F1117`, keywords and
operators `#7C5CFF`, numbers `#00D1FF`. `LIGHT` is named `Stride Light` and uses a white
background. Both schemes define every default key.

```kotlin
interface SyntaxHighlighter {
    fun getHighlightingLexer(): Lexer
    fun getTokenHighlights(tokenType: TokenType): Array<TextAttributesKey>
}

interface SyntaxHighlighterFactory {
    fun getSyntaxHighlighter(): SyntaxHighlighter
    companion object {
        const val EP_NAME = "stride.lang.syntaxHighlighterFactory"
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): SyntaxHighlighter?
    }
}

class SyntaxHighlighterFactoryBean : ExtensionBean() {
    override val key: String?          // attributes["language"]
    val language: String?
    fun factory(): SyntaxHighlighterFactory
}
```

`forLanguage` returns a freshly created highlighter for the first bean whose `language`
attribute equals `language.id`, or `null` when no bean matches. A highlighter returns an
empty array from `getTokenHighlights` for tokens it does not color.

```kotlin
data class TokenSpan(val start: Int, val end: Int, val key: TextAttributesKey)

class LineStateHighlighter(
    lexer: Lexer,
    text: CharSequence,
    tokenHighlights: (TokenType) -> Array<TextAttributesKey> = { emptyArray() },
) {
    val lastRelexedLineCount: Int
    fun spans(): List<TokenSpan>
    fun lineState(line: Int): Int
    fun lineCount(): Int
    fun edit(offset: Int, oldLength: Int, newText: String)
}
```

`LineStateHighlighter` stores the lexer state at every line start and produces the token
spans of the text. The key of a span is the first entry of `tokenHighlights(type)`, or
`DEFAULT_TEXT` when the array is empty. `spans()` returns a snapshot.

`edit` replaces `[offset, offset + oldLength)` with `newText`, relexes from the start state
of the line containing `offset`, and stops as soon as a line boundary past the edit has the
same state as the old text at the corresponding offset. Lines after that boundary are
shifted copies of the old spans. `lastRelexedLineCount` is the number of lines relexed by
the last edit; an edit inside ordinary single-line code relexes one line. After every edit
`spans()` equals the spans of a newly constructed highlighter over the same text.

## `stride.lang.kotlin`

```kotlin
val KotlinLanguage: Language                 // id "kotlin"
object KotlinFileType : LanguageFileType      // name "Kotlin", defaultExtension "kt"
object KotlinTokenTypes                       // KEYWORD, IDENTIFIER, NUMBER, STRING, CHARACTER,
                                              // LINE_COMMENT, BLOCK_COMMENT, OPERATION_SIGN,
                                              // BRACES, BRACKETS, PARENTHESES, COMMA, DOT,
                                              // SEMICOLON, BAD_CHARACTER
class KotlinLexer : Lexer
class KotlinSyntaxHighlighter : SyntaxHighlighter
object KotlinSyntaxHighlighterFactory : SyntaxHighlighterFactory
```

The lexer recognizes hard keywords and the common soft/modifier keywords, identifiers
(including backtick identifiers), decimal/hex/binary numbers with `_` separators and
`L`/`F` suffixes, floats with exponents, regular strings with escapes, `$x` and `${...}`
templates, character literals, raw strings, line comments and nested block comments,
operators and punctuation.

Multi-line constructs are resumed through the state:

| construct | state at a line start |
|---|---|
| normal code | `0` |
| nested block comment | `1 + (depth - 1)` (depth saturates at 4094) |
| raw string | `0x1000` |
| regular string continued after a template | `0x2000` |
| `${...}` template | `0x4000 + depth * 2 + rawFlag` |

Template contents are lexed as `STRING` tokens. Braces inside a template are counted for
resumption; quotes inside a template are not treated as nested strings, so a `}` inside a
quoted literal in a template closes the template early. That is the documented simplification
of this step.

## `stride.lang.json`

```kotlin
val JsonLanguage: Language                    // id "json"
object JsonFileType : LanguageFileType         // name "JSON", defaultExtension "json"
object JsonTokenTypes                          // STRING, NUMBER, KEYWORD, BRACES, BRACKETS,
                                               // COMMA, OPERATION_SIGN, BAD_CHARACTER
class JsonLexer : Lexer                        // getState() always returns 0
class JsonSyntaxHighlighter : SyntaxHighlighter
object JsonSyntaxHighlighterFactory : SyntaxHighlighterFactory
```

The lexer recognizes strings with escapes, numbers (including sign, fraction and exponent),
`true`/`false`/`null` as `KEYWORD`, braces, brackets, colon, comma, and reports every other
character (including an unknown word) as `BAD_CHARACTER`.

## Plugin descriptors

`plugins/lang-kotlin/src/main/resources/META-INF/stride-plugin.xml`:

```xml
<stride-plugin>
  <id>stride.lang.kotlin</id>
  <name>Kotlin</name>
  <depends>stride.core</depends>
  <extensions defaultExtensionNs="stride">
    <fileType extension="kt" language="kotlin" implementationClass="stride.lang.kotlin.KotlinFileType"/>
    <fileType extension="kts" language="kotlin" implementationClass="stride.lang.kotlin.KotlinFileType"/>
    <lang.syntaxHighlighterFactory language="kotlin"
                                   implementationClass="stride.lang.kotlin.KotlinSyntaxHighlighterFactory"/>
  </extensions>
</stride-plugin>
```

`plugins/lang-json/src/main/resources/META-INF/stride-plugin.xml`:

```xml
<stride-plugin>
  <id>stride.lang.json</id>
  <name>JSON</name>
  <depends>stride.core</depends>
  <extensions defaultExtensionNs="stride">
    <fileType extension="json" language="json" implementationClass="stride.lang.json.JsonFileType"/>
    <lang.syntaxHighlighterFactory language="json"
                                   implementationClass="stride.lang.json.JsonSyntaxHighlighterFactory"/>
  </extensions>
</stride-plugin>
```

Both plugins register extensions only. The two extension points are declared by the
`stride.core` descriptor in step 3, with the declarations below; the qualified names are
`stride.fileType` and `stride.lang.syntaxHighlighterFactory` because the plugin id is
`stride.core`.

```xml
<extensionPoints>
  <extensionPoint name="fileType" beanClass="stride.lang.FileTypeBean"/>
  <extensionPoint name="lang.syntaxHighlighterFactory" beanClass="stride.lang.SyntaxHighlighterFactoryBean"/>
</extensionPoints>
```

## Test coverage

| module | class | what it pins |
|---|---|---|
| `:lang` | `FileTypeRegistryTest` | extension key, case-insensitivity, last dot, plain-text fallback |
| `:lang` | `ColorSchemeTest` | logo palette, light background, fallback chain |
| `:lang` | `LangPluginIntegrationTest` | both real descriptors parsed with `PluginDescriptorParser`, extensions instantiated through a real `ExtensionArea`, file types and highlighters resolved |
| `:plugins:lang-kotlin` | `KotlinLexerTest` | token kinds, per-line multi-line tokens, resume at every line start |
| `:plugins:lang-kotlin` | `LineStateHighlighterTest` | 200 seeded random edits equal a full relex, single-line edit relexes one line |
| `:plugins:lang-json` | `JsonLexerTest` | token kinds, invalid characters, resume at every line start |
