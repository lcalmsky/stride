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

### Intentions

Language plugins contribute ⌥↩ quick fixes and intentions through the bean extension point
`stride.lang.intentionProvider`, keyed by `language` like `lang.commenter`. A provider only
reads the text and returns edits; the app applies them.

```kotlin
data class TextEdit(val start: Int, val end: Int, val replacement: String)   // offsets of the original text
enum class IntentionCategory { QUICK_FIX, INTENTION }
data class IntentionAction(val text: String, val category: IntentionCategory, val edits: List<TextEdit>)

fun interface SymbolLookup {                       // simple name → fully qualified names
    fun qualifiedNames(simpleName: String): List<String>
    companion object { val EMPTY: SymbolLookup }
}

class IntentionContext(val text: String, val caretOffset: Int, val fileName: String,
                       val symbols: SymbolLookup = SymbolLookup.EMPTY)

interface IntentionProvider {
    fun collect(context: IntentionContext): List<IntentionAction>
    companion object {
        const val EP_NAME = "stride.lang.intentionProvider"
        val EP: BeanExtensionPointName<IntentionProviderBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<IntentionProvider>
        fun collect(language: Language, context: IntentionContext,
                    area: ExtensionArea = Application.extensionArea): List<IntentionAction>
    }
}

class IntentionProviderBean : ExtensionBean() {
    override val key: String?                      // attributes["language"]
    fun provider(): IntentionProvider
}
```

`collect` runs every provider of the language in extension-point order, logs and skips a
provider that throws, and returns quick fixes before intentions while keeping the order
inside each category. Edits of one action must not overlap. `:lang` does not depend on
`:search`; the app supplies `SymbolLookup` from its declaration index.

### Import optimizers

Language plugins rewrite a document's import block through the bean extension point
`stride.lang.importOptimizer`, keyed by `language` like `lang.commenter`. An optimizer only
reads the text and returns edits; the app applies them.

```kotlin
interface ImportOptimizer {
    fun optimize(text: String): List<TextEdit>              // offsets of the original text
    companion object {
        const val EP_NAME = "stride.lang.importOptimizer"
        val EP: BeanExtensionPointName<ImportOptimizerBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): ImportOptimizer?
    }
}

class ImportOptimizerBean : ExtensionBean() {
    override val key: String?                              // attributes["language"]
    val language: String?
    fun optimizer(): ImportOptimizer
}
```

The edits use the offsets of the original text and must not overlap; an empty list means
there is nothing to change. `forLanguage` returns a freshly created optimizer for the first
bean whose `language` attribute equals `language.id`, or `null` when no bean matches, so a
file type without a registered optimizer reports that Optimize Imports is unavailable.

### Problem providers

Language plugins report brackets that do not pair up and similar text-only mistakes through the
bean extension point `stride.lang.problemProvider`, keyed by `language` like `lang.commenter`.
A provider reads the document text and its file name, nothing outside the file.

```kotlin
enum class ProblemSeverity { ERROR, WARNING }

data class Problem(val start: Int, val end: Int, val severity: ProblemSeverity, val message: String)

class ProblemContext(val text: String, val fileName: String)

interface ProblemProvider {
    fun collect(context: ProblemContext): List<Problem>
    companion object {
        const val EP_NAME = "stride.lang.problemProvider"
        val EP: BeanExtensionPointName<ProblemProviderBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<ProblemProvider>
        fun collect(language: Language, context: ProblemContext,
                    area: ExtensionArea = Application.extensionArea): List<Problem>
    }
}

class ProblemProviderBean : ExtensionBean() {
    override val key: String?                       // attributes["language"]
    val language: String?
    fun provider(): ProblemProvider
}

object BracketProblems {
    fun find(brackets: List<Pair<Int, Char>>): List<Problem>
}
```

A provider judges from the text alone: no compiler, classpath or project index stands behind it,
so a check that needs name or type resolution does not belong here and the build output reports
those instead. A provider that cannot tell a real problem from code it does not understand
reports nothing rather than guess. A problem covers `[start, end)` of the collected text; an
empty range marks a position.

`collect` runs every provider of the language in extension-point order, logs and skips a provider
that throws, and drops a problem whose range lies outside the text (`start` must be in
`0..end` and `end` at most the text length). The result is sorted by start, errors before
warnings at the same start.

`BracketProblems.find` matches the code brackets of a document in document order — offsets are
already known and brackets inside strings, characters and comments are already removed — with a
stack. A closing bracket with no opener of its kind on the stack is `Unexpected ')'`; an opener
still on the stack when its matching closer arrives, or when the input ends, is `Unclosed '('`.
Every message is an `ERROR` over `[offset, offset + 1)`, and the result is sorted by start.
`<` and `>` are not brackets.

### Refactoring providers

Language plugins provide Introduce Variable, Extract Method and Inline through the bean extension
point `stride.lang.refactoringProvider`, keyed by `language` like `lang.commenter`. A provider reads
the document text, the selection and the file name, nothing outside the file, and answers with a
step the app shows until the user has given what the refactoring needs. Rename is not part of it.

```kotlin
enum class RefactoringKind(val title: String) {
    INTRODUCE_VARIABLE("Introduce Variable"), EXTRACT_METHOD("Extract Method"), INLINE("Inline")
}

class RefactoringContext(val text: String, val selectionStart: Int, val selectionEnd: Int, val fileName: String)

data class RefactoringEdit(val edits: List<TextEdit>, val caret: Int, val message: String? = null)

sealed interface RefactoringStep {
    data class Refused(val reason: String) : RefactoringStep
    class Choose(val title: String, val options: List<RefactoringOption>) : RefactoringStep
    class AskName(val title: String, val suggested: String, val complete: (String) -> NameCheck) : RefactoringStep
    class Apply(val edit: RefactoringEdit) : RefactoringStep
}

class RefactoringOption(val text: String, val highlights: List<IntRange> = emptyList(), val next: () -> RefactoringStep)

sealed interface NameCheck {
    data class Invalid(val message: String) : NameCheck
    class Valid(val edit: RefactoringEdit) : NameCheck
}

interface RefactoringProvider {
    fun prepare(kind: RefactoringKind, context: RefactoringContext): RefactoringStep?
    companion object {
        const val EP_NAME = "stride.lang.refactoringProvider"
        val EP: BeanExtensionPointName<RefactoringProviderBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<RefactoringProvider>
        fun prepare(language: Language?, kind: RefactoringKind, context: RefactoringContext,
                    area: ExtensionArea = Application.extensionArea): RefactoringStep
        fun notAvailable(kind: RefactoringKind, fileName: String): String
    }
}

class RefactoringProviderBean : ExtensionBean() {
    override val key: String?                       // attributes["language"]
    val language: String?
    fun provider(): RefactoringProvider
}
```

`prepare` returns `null` for a kind the provider does not implement and `Refused` when the text
does not prove the result keeps the code's meaning; it does not throw for odd input. Every
`TextEdit`, highlight range and `RefactoringEdit.caret` refers to the text the step was prepared
on, except `caret`, which is an offset in the text after the edits. Edits of one result must not
overlap. `Choose.options[i].next` and `AskName.complete` are pure: the app may call them again,
and it applies the edit only when the editor still holds the prepared text.

The companion `prepare` asks the language's providers in extension-point order and returns the
first non-null step. A provider that throws is logged and the result is
`Refused("<title> failed: <Exception>")`. With no language, no provider or no provider
implementing the kind, the result is `Refused(notAvailable(kind, fileName))`, which reads
`<title> is not available for .<ext> files`, or `for this file` for a name without an extension.

### Generate providers

Language plugins provide the members of IntelliJ's Generate menu through the bean extension point
`stride.lang.generateProvider`, keyed by `language` like `lang.refactoringProvider`. A provider
reads the document text, the caret and the file, and the declarations of other project files
only through `ProjectTypes`, which the app answers from its declaration index with the unsaved
text of open editors. It answers with the same `RefactoringStep` values as a refactoring
provider; `ChooseMembers` lets the user check several members.

```kotlin
enum class GenerateKind(val title: String) {
    SECONDARY_CONSTRUCTOR("Secondary Constructor"), EQUALS_HASH_CODE("equals() and hashCode()"),
    TO_STRING("toString()"), OVERRIDE_METHODS("Override Methods"), IMPLEMENT_METHODS("Implement Methods")
}

class TypeSource(val qualifiedName: String, val path: String, val text: String, val nameOffset: Int)

interface ProjectTypes {
    fun resolve(written: String, path: String, offset: Int): List<TypeSource>
    fun hasTopLevelFunction(name: String): Boolean
}

class GenerateContext(val text: String, val caretOffset: Int, val fileName: String, val path: String,
                      val types: ProjectTypes?)

interface GenerateProvider {
    fun prepare(kind: GenerateKind, context: GenerateContext): RefactoringStep?
    companion object {
        const val EP_NAME = "stride.lang.generateProvider"
        val EP: BeanExtensionPointName<GenerateProviderBean>
        fun forLanguage(language: Language, area: ExtensionArea = Application.extensionArea): List<GenerateProvider>
        fun prepare(language: Language?, kind: GenerateKind, context: GenerateContext,
                    area: ExtensionArea = Application.extensionArea): RefactoringStep
        fun notAvailable(kind: GenerateKind, fileName: String): String
    }
}

class GenerateProviderBean : ExtensionBean() {
    override val key: String?                       // attributes["language"]
    val language: String?
    fun provider(): GenerateProvider
}

// in RefactoringStep
class ChooseMembers(val title: String, val members: List<MemberOption>,
                    val complete: (List<MemberOption>) -> NameCheck) : RefactoringStep
class MemberOption(val text: String, val group: String? = null, val checked: Boolean = false,
                   val disabledReason: String? = null)
```

`types` is `null` while the index is not ready; a provider that needs other files refuses then.
`resolve` takes a type name as written (`Repo`, `Outer.Inner`) at an offset of the file at `path`,
that file being the current one or a `TypeSource.path`, and resolves it through that file's
package, imports and enclosing classes; it never reads libraries. `TypeSource.text` is the whole
file and `nameOffset` the declaration's name in it. `hasTopLevelFunction` answers for every
project file, whatever the package, and counts top-level properties too: the index does not tell
them apart, and `val A: (Int) -> A` is called like a function. More `GenerateKind` values may come in a minor release, so a
provider's `when` over the enum ends in `else -> null`.

`ChooseMembers.complete` receives the checked rows in list order and is pure like
`AskName.complete`; `NameCheck.Invalid` keeps the chooser open with its message, `Valid` carries
the edit. A row with a `disabledReason` cannot be checked. The companion `prepare` behaves like
`RefactoringProvider.prepare`: first non-null step in extension-point order, a throwing provider
gives `Refused("<title> failed: <Exception>")`, no provider gives
`Refused(notAvailable(kind, fileName))`. Adding `ChooseMembers` to `RefactoringStep` is additive:
providers produce steps and only the app consumes them. The rules are in `docs/api-app.md`
「Generate」 G1 to G7.

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

### Kotlin intentions

The plugin registers two providers for `kotlin`. Both read tokens from `KotlinLexer`; the
identifiers inside `${...}` templates are lexed again and `$name` templates become
identifiers, so both count as code, while an escaped `\$`, plain string bodies and comments
do not. Imports are read only from the file header (blank lines, comments, `@file:`
annotations, `package` and `import` lines), so an `import` line inside a later string is
not an import.

| provider | category | entry |
|---|---|---|
| `KotlinImportIntentions` | quick fix | `Import '<fqn>'` for the identifier at the caret when no import binds it, it is not qualified by `.`, it is not declared in the file and the lookup has candidates outside the file's package and star imports (sorted, at most 6). The import goes in sorted position among the imports, else after the `package` line with one blank line, else at the top |
| `KotlinImportIntentions` | quick fix | `Remove unused import '<path>'` and `Remove unused imports`, only with the caret in the import block. An import is unused when its alias or last segment never appears as an identifier or soft keyword (`data`, `value`, …) outside the package and import lines. Star imports and operator-convention names (`getValue`, `plus`, `component1`, `assign`, …) always count as used. Removing every import also drops the blank line after them |
| `KotlinValVarIntention` | intention | `Change 'val' to 'var'` or the reverse for the first `val`/`var` keyword on the caret's line, except `const val`, `lateinit var`, `override var`, a `when (val …)` subject and lines where `by`, `get` or `set` follows |

There is no compiler behind these checks. A name used only in a KDoc link counts as unused,
and an identifier that is really a local or a parameter can still get import candidates
when a top-level project declaration has the same name. Private top-level declarations are
not offered: `ParsedDeclaration` and `Declaration` carry `isPrivate`, set from the
declaration's own modifiers.

### Kotlin refactorings

`stride.lang.kotlin.refactor.KotlinRefactorings` implements all three kinds for `kotlin` from
`KotlinLexer` tokens with template code unfolded. What each refactoring accepts and refuses, the
messages and the text it writes are the app's contract and live in `docs/api-app.md`
「Refactorings」 R2 to R5.

Introduce Variable runs on `KotlinCode`, an internal model built once per document. It reads token
ranges only; the same instance answers every later question and never lexes the text again.
`KotlinIntroduceVariable` is R3 on this model, and Extract Method and Inline read the same model.

```kotlin
internal class KotlinCode(text: String)
val tokens: List<KtToken>              // code, comments and string pieces; KtToken.isCode
val code: List<KtToken>                // code tokens only, non-overlapping
val bracketsMatch: Boolean             // false refuses every refactoring
fun tokenAt(offset: Int): KtToken?; fun matching(index: Int): KtToken?
enum class BlockKind { FUNCTION_BODY, INITIALIZER, CONTROL_BODY, WHEN_BODY, CLASS_BODY, LAMBDA }
val blocks: List<KtBlock>; fun blockAt(offset: Int): KtBlock?; fun blockChain(offset: Int): List<KtBlock>
val statements: List<KtStatement>; fun statementAt(offset: Int): KtStatement?
val functions: List<KtFunction>        // name, modifiers, annotations, type parameters, receiver,
                                        // parameters, return type, body, container, declaration starts
val locals: List<KtLocal>; val references: List<KtReference>   // KtReference.resolved
val calls: List<KtCall>; val effects: List<KtEffect>
fun KotlinCode.callsIn(range) / effectsIn(range) / effectsBefore(range)
fun KotlinCode.checkExpression(range): ExpressionProblem?
fun KotlinCode.isStable(range) / isReadOnly(range) / isAtomic(range) / isConditional(range)
fun KotlinCode.smartCastShape(local, from, to): KtSmartCast?   // the first R2 shape narrowing local
fun KotlinCode.nameScope(offset): KtRange?; fun KotlinCode.isNameUsedIn(scope, name): Boolean
enum class LocalKind { VAL, VAR, PARAMETER, LAMBDA_PARAMETER, FOR, CATCH, WHEN_SUBJECT, LOCAL_FUN, LOCAL_CLASS }
```

An unknown shape is not guessed: a missing bracket pair, an expression outside a statement or an
unresolvable split keeps the judgment conservative, so the refactoring refuses instead of writing
text whose meaning it cannot prove.

### Kotlin import optimizer

`KotlinImportOptimizer` rewrites the import block with the same decisions as the quick fix
above: it shares `parseImports`, `usedIdentifiersIn` and `collapseBlankLine` with
`KotlinImportIntentions`, so an import the quick fix would remove is exactly one the
optimizer drops. An import is unused when its alias or last segment never appears as an
identifier or soft keyword outside the package and import lines; star imports and
operator-convention names (`getValue`, `plus`, `component1`, `assign`, …) always count as
used, and a repeated `path as alias` is kept once.

The kept imports are sorted by group — everything else, `java.`, `javax.`, `kotlin.`, then
aliases — and by path inside a group, matching the default Kotlin layout. The block keeps
its own line endings (`\r\n` or `\n`). A comment on a line between imports turns sorting
off: only the removed import lines are deleted and the remaining order stays as it is, which
also keeps the blank line after the block. When every import goes, the blank line after the
block is collapsed unless a comment still sits in it. A file whose block is already in that
shape returns no edits.

The limits are the intentions' limits: there is no compiler, a name used only in a KDoc link
counts as unused, and nothing is reordered around comments.

### Kotlin problems

`KotlinProblemProvider` registers for `kotlin` and reports two kinds of problem, both from
`KotlinLexer` tokens:

| severity | message | range |
|---|---|---|
| error | `Unexpected ')'`, `Unclosed '('` | the one bracket character |
| warning | `Unused import directive` | the import directive, without its line break |

Brackets are counted only from the `BRACES`, `BRACKETS` and `PARENTHESES` tokens of
`codeTokens`, so brackets in regular strings, raw strings, characters and nested block comments
are never counted, while identifiers inside `${...}` templates are lexed again as code. The
matching itself is `BracketProblems.find`.

An import is unused by the same rule as the quick fix and the optimizer: `unusedImports` in
`KotlinImportList.kt` returns the imports whose alias or last segment never appears as an
identifier or soft keyword outside the package and import lines, star imports and
operator-convention names always count as used, and the provider reports one warning per such
import. The limits are the intentions' limits: there is no compiler, and a name used only in a
KDoc link counts as unused, so its import gets a warning too.

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

`JsonProblemProvider` registers for `json` and reports `Unexpected ']'` and `Unclosed '{'` errors
for the `BRACES` and `BRACKETS` tokens through `BracketProblems.find`. A `BAD_CHARACTER` token
that starts with `/` — a comment — means the file is not strict JSON, so the provider reports
nothing instead of judging a document it does not understand.

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
    <lang.commenter language="kotlin" implementationClass="stride.lang.kotlin.KotlinCommenter"/>
    <lang.intentionProvider language="kotlin" implementationClass="stride.lang.kotlin.KotlinImportIntentions"/>
    <lang.intentionProvider language="kotlin" implementationClass="stride.lang.kotlin.KotlinValVarIntention"/>
    <lang.importOptimizer language="kotlin" implementationClass="stride.lang.kotlin.KotlinImportOptimizer"/>
    <lang.problemProvider language="kotlin" implementationClass="stride.lang.kotlin.KotlinProblemProvider"/>
    <lang.refactoringProvider language="kotlin" implementationClass="stride.lang.kotlin.refactor.KotlinRefactorings"/>
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
    <lang.commenter language="json" implementationClass="stride.lang.json.JsonCommenter"/>
    <lang.problemProvider language="json" implementationClass="stride.lang.json.JsonProblemProvider"/>
  </extensions>
</stride-plugin>
```

Both plugins register extensions only. The language extension points are declared by the
`stride.core` descriptor in step 3, with the declarations below; the qualified names start
with `stride.` (`stride.fileType`, `stride.lang.syntaxHighlighterFactory`, ...) because the
plugin id is `stride.core`.

```xml
<extensionPoints>
  <extensionPoint name="fileType" beanClass="stride.lang.FileTypeBean"/>
  <extensionPoint name="lang.syntaxHighlighterFactory" beanClass="stride.lang.SyntaxHighlighterFactoryBean"/>
  <extensionPoint name="lang.commenter" beanClass="stride.lang.CommenterBean"/>
  <extensionPoint name="lang.intentionProvider" beanClass="stride.lang.IntentionProviderBean"/>
  <extensionPoint name="lang.importOptimizer" beanClass="stride.lang.ImportOptimizerBean"/>
  <extensionPoint name="lang.problemProvider" beanClass="stride.lang.ProblemProviderBean"/>
  <extensionPoint name="lang.refactoringProvider" beanClass="stride.lang.RefactoringProviderBean"/>
</extensionPoints>
```

## Test coverage

| module | class | what it pins |
|---|---|---|
| `:lang` | `FileTypeRegistryTest` | extension key, case-insensitivity, last dot, plain-text fallback |
| `:lang` | `ColorSchemeTest` | logo palette, light background, fallback chain |
| `:lang` | `LangPluginIntegrationTest` | both real descriptors parsed with `PluginDescriptorParser`, extensions instantiated through a real `ExtensionArea`, file types, highlighters and problem providers resolved |
| `:lang` | `BracketProblemsTest` | empty and matched input, an unexpected closer, openers above a match and left at the end reported unclosed, results sorted by start |
| `:lang` | `IntentionProviderEpTest` | providers resolved by language key, quick fixes sorted before intentions across providers, a throwing provider skipped, unknown language empty |
| `:lang` | `ImportOptimizerEpTest` | the Kotlin descriptor registers one import optimizer, the bean resolves through the real descriptor, an unknown language resolves to `null` |
| `:lang` | `RefactoringProviderEpTest` | providers resolved by language key, the first provider implementing the kind answers, a kind or file without a provider refuses with `not available`, a throwing provider refuses with `failed`, names without an extension, name checks carrying the edit or the message |
| `:plugins:lang-kotlin` | `KotlinLexerTest` | token kinds, per-line multi-line tokens, resume at every line start |
| `:plugins:lang-kotlin` | `LineStateHighlighterTest` | 200 seeded random edits equal a full relex, single-line edit relexes one line |
| `:plugins:lang-kotlin` | `KotlinImportIntentionsTest` | import insertion positions, same-package, imported, alias, star and qualified skips, caret in strings and comments, the candidate limit, unused import removal with operator names, aliases, stars and template usages |
| `:plugins:lang-kotlin` | `KotlinImportOptimizerTest` | unused and duplicate removal, the java/javax/kotlin/alias group order, blank-line collapse, a comment inside the block keeping the order and the blank line after it, star and KDoc-link cases, an already optimized file and a file with no imports reporting no edits |
| `:plugins:lang-kotlin` | `KotlinProblemProviderTest` | brackets in templates, raw strings, characters, strings, comments and nested comments ignored, unexpected and unclosed messages and ranges, unused import warnings and the alias, star, operator and template exceptions |
| `:plugins:lang-kotlin` | `KotlinValVarIntentionTest` | `val`/`var` toggle, `const val` and `lateinit var` skipped, keywords in comments ignored |
| `:plugins:lang-kotlin` | `KotlinCodeModelTest` | block kinds and loop bodies, statement splitting and continuation lines (a next-line `{` included), locals and resolution with shadowing, template references, named arguments, calls and effect offsets, expression rules (declaration names, keyword operators, call groups and string boundaries too), stability, read-only and atomic checks (booleans and `null` included), conditional positions and markers inside the first `if`, a `when` subject and a `for` iterable, effects before an expression, unbalanced brackets, a model reused for one text instance, modifier words read as names outside a declaration position, unread spellings, expression first and last operand tokens with type argument pairs, line-end infix statement joins, type arguments, nested type arguments, a nullable type and a type parameter list ending the statement at a line end while a comparison does not, a function type's arrow inside type arguments ending the statement, a next-line block joining a function or class header that ends with its type (merged `>`, `>>`, `*>` or `?>`, and a trailing `?` on a declaration header only), the R2 smart-cast shapes of locals, reassignments, members and chains with the equality and declaration-initializer exceptions, a function and a block after a string literal, an index after a string that is no call and the smart-cast operands, chains and check-call arguments a string literal keeps out |
| `:plugins:lang-kotlin` | `KotlinScopesTest` | receiver sensitive names (`this`, `super` and names that do not resolve to a local, member names, named arguments and labels left out), receiver scopes for lambdas, class bodies and object expressions, block and expression body local functions, a top-level function below a class body |
| `:plugins:lang-kotlin` | `KotlinIntroduceVariableTest` | R3 selections, trimming, string, template and boundary selections, missing statement block and expression body refusals, shared lines, conditional and effect refusals, markers inside the first `if`, a `when` subject and a `for` iterable, occurrence roles, receiver scopes and deferred read-only occurrences, declaration and call-group refusals, nested `if` else pairing, loop statement candidates, occurrence choosers and their highlights, candidate order, suggested and unique names, name checks, multi-line text and CRLF, a template-code selection that ends with a name, a comma-separated argument list refused, expression edge refusals and prefix or operand-end allowances, a name spelled by an infix use, selections the model misread before refusing or round-tripping through Inline, a selected read narrowed by a reassignment or a member |
| `:plugins:lang-kotlin` | `KotlinExtractMethodTest` | R4 selections, statement and expression shapes, expression-position blocks and their values, refusals for lambda, local function, initializer, accessor, constructor, class body, expression body and receiver-less calls without written type arguments, input order and written, constructor and literal types, vararg, local declarations and type parameters, declared and assigned outputs and their call forms, plusAssign on a val, returns and labeled returns, break and continue inside and outside the selection, receiver, suspend and annotation copies, placement in a class body and at the top level, suggested and checked names, multi-line reindentation, tabs and CRLF, raw string lines kept as written, Int-range and unsigned literals, constructor types that write type arguments or name a class in this file, delegated locals, a var captured in a lambda, a one-line container, outer labels, literal-only and lambda expressions, `finally` blocks, modifier words in the name check, string and comment selections in a class body, a trailing `;` or comment, template-code expressions and comma refusals, nullable inputs, inline, operator and infix functions, a `when` value and object, companion object and interface placement, expression edge refusals, prefix and operand-end selections, a line-end infix second line, the file name check counting every spelling, a reassigned, equality-branched or member read narrowed before the selection, a two-line local declaration extracted without a broken header, a local function whose header ends with its type and whose body starts on the next line |
| `:plugins:lang-kotlin` | `KotlinInlineTest` | R5 targets (only a function name without a local, parameter, overload or import), variable refusals including a closing `;`, stable and effectful initializer substitution, receiver scopes at stable initializers and late class body or expression body local function uses, template and parenthesis rules, several line template refusals, function bodies and refusals, calls and default arguments, a call passing only a trailing lambda, argument stability and order (the shared conditional judgment and body effects included), name capture and receiver scopes in the body, an unused function, function, written and implicit labels on `return@`, a `when` subject, both modes and annotation or KDoc removal, a modifier word used as an infix function, a backticked use, unspellable tokens refusing a variable and a function's all mode while comments and strings do not, a member chain read by an initializer and a reassigned argument narrowed before the call, a string return body, string-valued named arguments and a function declared after a string expression body |
| `:plugins:lang-json` | `JsonLexerTest` | token kinds, invalid characters, resume at every line start |
| `:plugins:lang-json` | `JsonProblemProviderTest` | valid input, an unclosed brace, an unexpected bracket, brackets inside a string, a commented file reporting nothing |
