package stride.lang.kotlin.generate

import stride.lang.kotlin.KotlinTokenTypes
import stride.lang.kotlin.refactor.BlockKind
import stride.lang.kotlin.refactor.KtBlock
import stride.lang.kotlin.refactor.KtParameter
import stride.lang.kotlin.refactor.KtRange
import stride.lang.kotlin.refactor.KtToken
import stride.lang.kotlin.refactor.KotlinCode
import stride.lang.kotlin.refactor.angleClose
import stride.lang.kotlin.refactor.isNameToken
import stride.lang.kotlin.refactor.parseParameter
import stride.lang.kotlin.refactor.parseParameterList

internal enum class ClassForm { CLASS, INTERFACE, OBJECT }

/** One supertype written in a class header, as written there. */
internal class KtSupertype(
    /** The qualified name without type arguments: `Base`, `a.b.Repo`. */
    val written: String,
    val simpleName: String,
    /** Offset of the name's first token in the file, the offset `ProjectTypes.resolve` takes. */
    val offset: Int,
    /** Each top-level type argument as written, `null` when the header writes none. */
    val typeArguments: List<String>?,
    /** Whether the header writes a constructor call `(…)` after the type. */
    val hasCall: Boolean,
)

/** One parameter of a primary constructor; [property] is `true` for a `val` or `var` parameter. */
internal class KtPrimaryParameter(
    val name: String,
    val nameToken: KtToken,
    val type: String?,
    val property: Boolean,
    val isVar: Boolean,
    val vararg: Boolean,
)

internal class KtPrimaryConstructor(
    val open: KtToken,
    val close: KtToken,
    val parameters: List<KtPrimaryParameter>,
)

/** One `constructor(…)` declaration of a class body. */
internal class KtSecondaryConstructor(
    val keyword: KtToken,
    val open: KtToken,
    val close: KtToken,
    val parameters: List<KtParameter>,
    val start: Int,
    val end: Int,
)

/** A `val`/`var` declaration of a class body. */
internal class KtProperty(
    val keyword: KtToken,
    /** The name without backticks, `null` for a destructuring or otherwise unnamed property. */
    val name: String?,
    val nameToken: KtToken?,
    val isVar: Boolean,
    val typeText: String?,
    val initializer: KtRange?,
    val delegated: Boolean,
    val lateinit: Boolean,
    val const: Boolean,
    val modifiers: Set<String>,
    val receiver: String?,
    val hasCustomGetter: Boolean,
    val hasCustomSetter: Boolean,
    val start: Int,
    val end: Int,
)

internal enum class MemberKind { FUNCTION, PROPERTY, CONSTRUCTOR, INIT, NESTED_CLASS }

/** One top-level declaration of a class body. [start] and [end] cover it including annotations and KDoc. */
internal class KtMember(
    val kind: MemberKind,
    val name: String,
    val start: Int,
    val end: Int,
    val function: SigFunction? = null,
    val property: KtProperty? = null,
    val constructor: KtSecondaryConstructor? = null,
    val nested: KtClazz? = null,
)

/**
 * A class, interface or object declaration read from the refactoring token model. The members are
 * the body's top-level declarations in file order; enum entries are not members.
 */
internal class KtClazz(
    val code: KotlinCode,
    val keyword: KtToken,
    val form: ClassForm,
    /** Without backticks; an unnamed companion object reads `Companion`. */
    val name: String,
    val nameToken: KtToken?,
    val modifiers: Set<String>,
    val typeParameters: List<String>,
    val primaryConstructor: KtPrimaryConstructor?,
    val supertypes: List<KtSupertype>,
    val headerStart: Int,
    val headerEnd: Int,
    val body: KtBlock?,
    val members: List<KtMember>,
    val declarationStart: Int,
    /** The offset of the `;` that ends an enum's entries, `null` when it has none. */
    val enumEntriesEnd: Int?,
    /** Whether the enum body has at least one entry. */
    val hasEnumEntries: Boolean,
) {
    val isInterface: Boolean get() = form == ClassForm.INTERFACE

    val isObject: Boolean get() = form == ClassForm.OBJECT

    val isEnum: Boolean get() = form == ClassForm.CLASS && "enum" in modifiers

    val isAnnotation: Boolean get() = form == ClassForm.CLASS && "annotation" in modifiers

    val isValue: Boolean get() = form == ClassForm.CLASS && ("value" in modifiers || "inline" in modifiers)

    val isData: Boolean get() = form == ClassForm.CLASS && "data" in modifiers

    val isCompanion: Boolean get() = form == ClassForm.OBJECT && "companion" in modifiers

    val isFunInterface: Boolean get() = form == ClassForm.INTERFACE && "fun" in modifiers

    val ownSecondaryConstructors: List<KtSecondaryConstructor>
        get() = members.mapNotNull { it.constructor }

    val properties: List<KtProperty>
        get() = members.mapNotNull { it.property }

    val functions: List<SigFunction>
        get() = members.mapNotNull { it.function }

    val nestedClasses: List<KtClazz>
        get() = members.mapNotNull { it.nested }

    /** The name token as written, with backticks when the declaration uses them. */
    fun writtenName(): String = nameToken?.text ?: name

    /**
     * Whether an unparenthesized supertype can be a class. A class supertype writes `()` in the
     * header, so only a class without a primary constructor but with a secondary constructor can
     * delegate `: super(…)` (G4, G5, G6).
     */
    fun mayDelegateSuperCall(): Boolean = primaryConstructor == null && ownSecondaryConstructors.isNotEmpty()

    /** A member named [name] here: a function, a body property or a primary constructor property. */
    fun declaresMemberNamed(name: String): Boolean =
        functions.any { it.name == name } ||
            properties.any { it.name == name } ||
            primaryConstructor?.parameters.orEmpty().any { it.property && it.name == name }

    /** Whether [supertype], written in this class's header without `(…)`, may still be a class. */
    fun supertypeMayBeClass(supertype: KtSupertype): Boolean =
        supertype.hasCall || mayDelegateSuperCall()

    override fun toString(): String = "$form $name"
}

/** Every named class declaration of [code], nested ones included, sorted by their first token. */
internal fun kotlinClasses(code: KotlinCode): List<KtClazz> {
    val result = ArrayList<KtClazz>()
    for (token in code.code) {
        if (token.type != KotlinTokenTypes.KEYWORD) continue
        if (token.text != "class" && token.text != "interface" && token.text != "object") continue
        parseClassAt(code, token.index, withMembers = false)?.let { result.add(it) }
    }
    return result.sortedBy { it.declarationStart }
}

/**
 * The innermost class declaration whose header or body holds [offset]. An object expression is not
 * a declaration, so a caret inside one finds no class.
 */
internal fun classAt(code: KotlinCode, offset: Int): KtClazz? {
    val declarations = kotlinClasses(code)
    val headerHits = declarations.filter { klass ->
        val bodyStart = klass.body?.open?.start ?: (klass.headerEnd + 1)
        offset >= klass.declarationStart && offset < bodyStart
    }
    headerHits.maxByOrNull { it.declarationStart }?.let { return parseClassAt(code, it.keyword.index) }
    val undeclaredBody = code.blocks
        .filter { it.kind == BlockKind.CLASS_BODY && it.contains(offset) }
        .filter { block -> declarations.none { it.body?.open?.index == block.open.index } }
        .maxByOrNull { it.open.start }
    val declaredBody = declarations
        .filter { it.body?.contains(offset) == true }
        .maxByOrNull { it.body!!.open.start }
    if (undeclaredBody != null && (declaredBody == null || undeclaredBody.open.start > declaredBody.body!!.open.start)) {
        return null
    }
    declaredBody ?: return null
    return parseClassAt(code, declaredBody.keyword.index)
}

/** The class declared at [nameOffset] of [text], the declaration a `TypeSource` names. */
internal fun classInSource(text: String, nameOffset: Int): KtClazz? {
    val code = KotlinCode(text)
    val found = kotlinClasses(code).firstOrNull { it.nameToken?.start == nameOffset } ?: return null
    return parseClassAt(code, found.keyword.index)
}

internal class ParsedHeader(
    val primary: KtPrimaryConstructor?,
    val supertypes: List<KtSupertype>,
    val headerEnd: Int,
    val bodyOpenIndex: Int,
)

internal fun parseClassAt(code: KotlinCode, keywordIndex: Int, withMembers: Boolean = true): KtClazz? {
    val keyword = code.code.getOrNull(keywordIndex) ?: return null
    if (keyword.type != KotlinTokenTypes.KEYWORD) return null
    if (keyword.text != "class" && keyword.text != "interface" && keyword.text != "object") return null
    val previous = code.previousToken(keyword)
    if (previous != null && (previous.type == KotlinTokenTypes.DOT || previous.text == "::" || previous.text == "@")) return null

    val form = when (keyword.text) {
        "interface" -> ClassForm.INTERFACE
        "object" -> ClassForm.OBJECT
        else -> ClassForm.CLASS
    }

    val modifiers = LinkedHashSet<String>()
    var firstIndex = keywordIndex
    var cursor = keywordIndex - 1
    while (cursor >= 0) {
        val token = code.code[cursor]
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
            modifiers.add(token.text)
            firstIndex = cursor
            cursor--
            continue
        }
        if (token.type == KotlinTokenTypes.KEYWORD && token.text == "fun" && form == ClassForm.INTERFACE) {
            modifiers.add("fun")
            firstIndex = cursor
            cursor--
            continue
        }
        val annotation = annotationStartEndingAt(code, cursor)
        if (annotation >= 0) {
            firstIndex = annotation
            cursor = annotation - 1
            continue
        }
        break
    }

    var index = keywordIndex + 1
    var nameToken: KtToken? = null
    val next = code.code.getOrNull(index)
    if (next != null && isNameToken(next)) {
        nameToken = next
        index++
    } else if (form == ClassForm.CLASS || form == ClassForm.INTERFACE) {
        return null
    } else if (!modifiers.contains("companion")) {
        // `object : Runnable { … }` is an object expression, not a declaration.
        return null
    }
    val name = nameToken?.text?.removeSurrounding("`") ?: "Companion"

    val typeParameters = ArrayList<String>()
    if (code.code.getOrNull(index)?.text == "<") {
        val close = angleClose(code, index)
        if (close < 0) return null
        typeParameters.addAll(signatureTypeParameterNames(code, index, close))
        index = close + 1
    }

    val header = parseHeader(code, index)
    // A primary constructor's `)` makes the refactoring model read the class body as a function
    // body; the brace right after a class header is the class body whatever kind it got.
    val body = header.bodyOpenIndex.takeIf { it >= 0 }?.let { openIndex ->
        code.blocks.firstOrNull { it.open.index == openIndex }
    }
    val declarationStart = declarationLineStart(code, code.code[firstIndex].start)
    val enumInfo = enumInfo(code, body, form == ClassForm.CLASS && "enum" in modifiers)
    val members = if (withMembers && body != null) {
        parseMembers(code, body, enumInfo?.entriesEnd)
    } else {
        emptyList()
    }
    return KtClazz(
        code = code,
        keyword = keyword,
        form = form,
        name = name,
        nameToken = nameToken,
        modifiers = modifiers,
        typeParameters = typeParameters,
        primaryConstructor = header.primary,
        supertypes = header.supertypes,
        headerStart = code.code[firstIndex].start,
        headerEnd = header.headerEnd,
        body = body,
        members = members,
        declarationStart = declarationStart,
        enumEntriesEnd = enumInfo?.entriesEnd,
        hasEnumEntries = enumInfo?.hasEntries == true,
    )
}

private fun parseHeader(code: KotlinCode, startIndex: Int): ParsedHeader {
    var index = skipAnnotationsAndModifiers(code, startIndex)
    var primary: KtPrimaryConstructor? = null

    if (code.code.getOrNull(index)?.text == "constructor") {
        index = skipAnnotationsAndModifiers(code, index + 1)
        val open = code.code.getOrNull(index)
        if (open != null && open.text == "(") {
            val close = code.matchingIndex(index)
            if (close >= 0) {
                primary = primaryConstructor(code, index, close)
                index = close + 1
            }
        }
    } else if (code.code.getOrNull(index)?.text == "(") {
        val close = code.matchingIndex(index)
        if (close >= 0) {
            primary = primaryConstructor(code, index, close)
            index = close + 1
        }
    }

    val supertypes = ArrayList<KtSupertype>()
    val colon = code.code.getOrNull(index)
    if (colon != null && colon.text == ":") {
        index = skipAnnotationsAndModifiers(code, index + 1)
        val segments = ArrayList<IntRange>()
        var segmentStart = index
        var cursor = index
        var angleDepth = 0
        var lastIndex = index - 1
        while (cursor < code.code.size) {
            val token = code.code[cursor]
            if (angleDepth == 0 && (token.text == "{" || token.text == ";" || token.text == "}")) break
            if (token.text == "(" || token.text == "[") {
                val close = code.matchingIndex(cursor)
                if (close < 0) break
                cursor = close + 1
                lastIndex = close
                continue
            }
            if (token.text == "<") angleDepth++
            if (token.text.isNotEmpty() && token.text.all { it == '>' }) angleDepth = (angleDepth - token.text.length).coerceAtLeast(0)
            if (angleDepth == 0 && token.text == ",") {
                if (cursor > segmentStart) segments.add(segmentStart until cursor)
                segmentStart = cursor + 1
            } else if (lastIndex >= index - 1 && code.splitsAfter(code.code[lastIndex], token)) {
                break
            }
            lastIndex = cursor
            cursor++
        }
        if (cursor > segmentStart) segments.add(segmentStart until cursor)
        for (segment in segments) {
            parseSupertype(code, segment)?.let { supertypes.add(it) }
        }
        index = lastIndex + 1
    }

    val bodyOpen = code.code.getOrNull(index)?.takeIf { it.text == "{" }?.index ?: -1
    val headerEnd = if (bodyOpen >= 0) {
        code.code[bodyOpen].start
    } else {
        code.code.getOrNull(index - 1)?.end ?: code.text.length
    }
    return ParsedHeader(primary, supertypes, headerEnd, bodyOpen)
}

private fun skipAnnotationsAndModifiers(code: KotlinCode, from: Int): Int {
    var index = from
    while (index < code.code.size) {
        val token = code.code[index]
        if (token.text == "@") {
            index = annotationEnd(code, index) + 1
            continue
        }
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
            index++
            continue
        }
        break
    }
    return index
}

private fun primaryConstructor(code: KotlinCode, openIndex: Int, closeIndex: Int): KtPrimaryConstructor {
    val parameters = ArrayList<KtPrimaryParameter>()
    for (segment in parameterSegmentRanges(code, openIndex, closeIndex)) {
        val parsed = parseParameter(code, segment.first, segment.last + 1) ?: continue
        var property = false
        var isVar = false
        for (index in segment) {
            val token = code.code[index]
            if (token.text == "val") property = true
            if (token.text == "var") {
                property = true
                isVar = true
            }
        }
        parameters.add(KtPrimaryParameter(parsed.name, parsed.nameToken, parsed.type, property, isVar, parsed.vararg))
    }
    return KtPrimaryConstructor(code.code[openIndex], code.code[closeIndex], parameters)
}

/** Token index ranges of each comma-separated parameter, parentheses and angles balanced. */
internal fun parameterSegmentRanges(code: KotlinCode, openIndex: Int, closeIndex: Int): List<IntRange> {
    val result = ArrayList<IntRange>()
    var segmentStart = openIndex + 1
    var index = openIndex + 1
    var angleDepth = 0
    while (index < closeIndex) {
        val token = code.code[index]
        when {
            code.isOpener(token) -> {
                val close = code.matchingIndex(index)
                index = if (close in (index + 1) until closeIndex) close + 1 else index + 1
                continue
            }
            token.text == "<" -> angleDepth++
            token.text == ">" -> if (angleDepth > 0) angleDepth--
            token.text.isNotEmpty() && token.text.all { it == '>' } -> angleDepth = (angleDepth - token.text.length).coerceAtLeast(0)
            token.text == "," && angleDepth == 0 -> {
                if (index > segmentStart) result.add(segmentStart until index)
                segmentStart = index + 1
            }
        }
        index++
    }
    if (closeIndex > segmentStart) result.add(segmentStart until closeIndex)
    return result
}

private fun parseSupertype(code: KotlinCode, segment: IntRange): KtSupertype? {
    var index = segment.first
    while (index <= segment.last) {
        val token = code.code[index]
        if (token.text == "@") {
            index = annotationEnd(code, index) + 1
            continue
        }
        if (token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS) {
            index++
            continue
        }
        break
    }
    if (index > segment.last) return null
    val nameStart = code.code[index]
    if (!isNameToken(nameStart)) return null
    var lastNameIndex = index
    index++
    while (index + 1 <= segment.last && code.code[index].type == KotlinTokenTypes.DOT &&
        isNameToken(code.code[index + 1])
    ) {
        lastNameIndex = index + 1
        index += 2
    }
    val written = code.text.substring(nameStart.start, code.code[lastNameIndex].end)
    var arguments: List<String>? = null
    if (index <= segment.last && code.code[index].text == "<") {
        val close = angleClose(code, index)
        if (close in index..segment.last) {
            arguments = splitTypeArguments(code, index, close)
            index = close + 1
        }
    }
    val hasCall = index <= segment.last && code.code[index].text == "("
    return KtSupertype(written, code.code[lastNameIndex].text.removeSurrounding("`"), nameStart.start, arguments, hasCall)
}

internal fun splitTypeArguments(code: KotlinCode, openIndex: Int, closeIndex: Int): List<String> {
    val result = ArrayList<String>()
    var segmentStart = openIndex + 1
    var index = openIndex + 1
    var angleDepth = 0
    var parenDepth = 0
    while (index < closeIndex) {
        val token = code.code[index]
        when {
            token.text == "(" -> parenDepth++
            token.text == ")" -> parenDepth--
            token.text == "<" -> angleDepth++
            token.text.isNotEmpty() && token.text.all { it == '>' } -> angleDepth = (angleDepth - token.text.length).coerceAtLeast(0)
            token.text == "," && angleDepth == 0 && parenDepth == 0 -> {
                if (index > segmentStart) {
                    result.add(code.text.substring(code.code[segmentStart].start, code.code[index - 1].end).trim())
                }
                segmentStart = index + 1
            }
        }
        index++
    }
    if (closeIndex > segmentStart) {
        result.add(code.text.substring(code.code[segmentStart].start, code.code[closeIndex - 1].end).trim())
    }
    return result
}

private class EnumInfo(val hasEntries: Boolean, val entriesEnd: Int?)

private fun enumInfo(code: KotlinCode, body: KtBlock?, isEnum: Boolean): EnumInfo? {
    if (!isEnum || body == null) return null
    val level = memberLevelTokens(code, body)
    if (level.isEmpty()) return EnumInfo(false, null)
    val semicolon = level.firstOrNull { it.text == ";" }
    val beforeSemicolon = if (semicolon == null) level else level.filter { it.start < semicolon.start }
    val declarationKeywords = setOf("val", "var", "fun", "init", "constructor", "class", "interface", "object")
    val hasEntries = beforeSemicolon.any { it.type == KotlinTokenTypes.IDENTIFIER } &&
        beforeSemicolon.none { it.type == KotlinTokenTypes.KEYWORD && it.text in declarationKeywords }
    return EnumInfo(hasEntries, semicolon?.start)
}

private fun memberLevelTokens(code: KotlinCode, body: KtBlock): List<KtToken> {
    val result = ArrayList<KtToken>()
    for (token in code.code) {
        if (token.start <= body.start || token.end >= body.end) continue
        val innermost = code.blockAt(token.start) ?: continue
        if (innermost.open.index == body.open.index) result.add(token)
    }
    return result
}

private fun parseMembers(code: KotlinCode, body: KtBlock, enumEntriesEnd: Int?): List<KtMember> {
    val level = code.tokens
        .filter { !it.isComment && it.start > body.start && it.end < body.end }
        .filter { code.blockAt(it.start)?.open?.index == body.open.index }
        .filter { enumEntriesEnd == null || it.start > enumEntriesEnd }
        .sortedBy { it.start }
    val heads = level.filter { isMemberHead(code, it) }
    val boundaries = heads.map { memberFirstToken(code, level, it).start }
    val members = ArrayList<KtMember>()
    for ((index, head) in heads.withIndex()) {
        val start = boundaries[index]
        val nextStart = boundaries.getOrNull(index + 1) ?: Int.MAX_VALUE
        val slice = level.filter { it.start >= start && it.start < nextStart }
        parseMember(code, slice, head, declarationLineStart(code, start))?.let { members.add(it) }
    }
    return members
}

private fun memberFirstToken(code: KotlinCode, level: List<KtToken>, head: KtToken): KtToken {
    var first = head
    var index = level.indexOfFirst { it.start == head.start } - 1
    while (index >= 0) {
        val token = level[index]
        val modifier = token.type == KotlinTokenTypes.KEYWORD && token.text in KotlinCode.MODIFIER_WORDS
        val annotationStart = if (token.index >= 0) annotationStartEndingAt(code, token.index) else -1
        val annotation = if (annotationStart >= 0) level.indexOfFirst { it.index == annotationStart } else -1
        if (!modifier && annotation < 0) break
        if (blankLineBetween(code.text, token.end, first.start)) break
        if (annotation >= 0) {
            first = level[annotation]
            index = annotation - 1
        } else {
            first = token
            index--
        }
    }
    return first
}

private val MEMBER_HEADS = setOf("val", "var", "fun", "class", "interface", "object", "init", "constructor")

private val ANONYMOUS_FUN_PRECEDERS = setOf(
    "=", "(", ",", "return", ":", "->", "&&", "||", "?:", "!", "+", "-", "*", "/",
    "%", "[", "else", "throw", "as", "is", "in", "..",
)

private fun isMemberHead(code: KotlinCode, token: KtToken): Boolean {
    if (token.type != KotlinTokenTypes.KEYWORD || token.text !in MEMBER_HEADS) return false
    val previous = code.previousToken(token)
    if (previous != null && (previous.type == KotlinTokenTypes.DOT || previous.text == "::" || previous.text == "@")) return false
    if (token.text == "interface") return previous?.text != "fun"
    if (token.text == "fun") return previous == null || previous.text !in ANONYMOUS_FUN_PRECEDERS
    if (token.text == "object") {
        val companion = previous != null && previous.text == "companion"
        val next = code.nextToken(token)
        if (!companion && next != null && (next.text == ":" || next.text == "{")) return false
        if (!companion && previous != null && previous.text in setOf("=", "(", ",", "return", "->", "by")) return false
    }
    return true
}

private fun blankLineBetween(text: String, from: Int, to: Int): Boolean {
    var breaks = 0
    for (index in from until to.coerceAtMost(text.length)) {
        if (text[index] == '\n') breaks++
        if (breaks > 1) return true
    }
    return false
}

private fun parseMember(code: KotlinCode, slice: List<KtToken>, head: KtToken, start: Int): KtMember? {
    var end = slice.maxOfOrNull { it.end } ?: head.end
    for (token in slice) {
        if (token.index >= 0 && code.isOpener(token)) {
            val close = code.matching(token.index)
            if (close != null) end = maxOf(end, close.end)
        }
    }
    return when (head.text) {
        "val", "var" -> {
            val property = parseProperty(code, slice, head, start, end) ?: return null
            KtMember(MemberKind.PROPERTY, property.name ?: "", start, end, property = property)
        }
        "constructor" -> {
            val open = slice.firstOrNull { it.index > head.index && it.text == "(" } ?: return null
            val closeIndex = code.matchingIndex(open.index)
            if (closeIndex < 0) return null
            val close = code.code[closeIndex]
            val parameters = parseParameterList(code, open.index, closeIndex)
            val constructor = KtSecondaryConstructor(head, open, close, parameters, start, end)
            KtMember(MemberKind.CONSTRUCTOR, "constructor", start, end, constructor = constructor)
        }
        "init" -> KtMember(MemberKind.INIT, "init", start, end)
        "class", "interface", "object" -> {
            val nested = parseClassAt(code, head.index)
            KtMember(MemberKind.NESTED_CLASS, nested?.name ?: head.text, start, end, nested = nested)
        }
        "fun" -> {
            if (slice.getOrNull(slice.indexOfFirst { it.index == head.index } + 1)?.text == "interface") {
                val nested = parseClassAt(code, head.index + 1)
                return KtMember(MemberKind.NESTED_CLASS, nested?.name ?: "interface", start, end, nested = nested)
            }
            val function = signatureFunctionAt(code, head.index) ?: return null
            KtMember(MemberKind.FUNCTION, function.name, start, maxOf(end, function.end), function = function)
        }
        else -> null
    }
}

private fun parseProperty(
    code: KotlinCode,
    segment: List<KtToken>,
    keyword: KtToken,
    start: Int,
    end: Int,
): KtProperty? {
    var index = segment.indexOfFirst { it.index == keyword.index } + 1
    if (index <= 0) return null
    if (segment.getOrNull(index)?.text == "(") return null

    var typeParametersEnd = -1
    if (segment.getOrNull(index)?.text == "<") {
        val close = angleClose(code, segment[index].index)
        if (close < 0) return null
        typeParametersEnd = close
        index = segment.indexOfFirst { it.index > close }.let { if (it < 0) segment.size else it }
    }

    var nameToken: KtToken? = null
    var lastDot = -1
    var stop = -1
    var angleDepth = 0
    var scan = index
    while (scan < segment.size) {
        val token = segment[scan]
        if (token.text == "<") angleDepth++
        if (token.text.isNotEmpty() && token.text.all { it == '>' }) angleDepth = (angleDepth - token.text.length).coerceAtLeast(0)
        if (angleDepth == 0 && (token.text == ":" || token.text == "=" || token.text == "by" ||
                token.text == "get" || token.text == "set")
        ) {
            stop = scan
            break
        }
        if (token.type == KotlinTokenTypes.DOT) lastDot = scan
        if (angleDepth == 0 && isNameToken(token)) nameToken = token
        scan++
    }
    val name = nameToken ?: return null
    val namePosition = segment.indexOfFirst { it.index == name.index }
    val receiver = if (lastDot >= 0 && lastDot < namePosition && index < lastDot) {
        code.text.substring(segment[index].start, segment[lastDot - 1].end).trim()
    } else {
        null
    }

    var typeText: String? = null
    var initializer: KtRange? = null
    var delegated = false
    var hasGetter = false
    var hasSetter = false
    var boundary = segment.size
    var i = namePosition + 1
    var colon = -1
    while (i < segment.size) {
        val token = segment[i]
        if (token.text == ":") {
            if (colon < 0) colon = i
        } else if (token.text == "=") {
            boundary = i
            break
        } else if (token.text == "by") {
            boundary = i
            delegated = true
            break
        } else if (token.text == "get") {
            boundary = i
            hasGetter = true
            break
        } else if (token.text == "set") {
            boundary = i
            hasSetter = true
            break
        }
        i++
    }
    if (colon >= 0 && boundary > colon + 1) {
        typeText = code.text.substring(segment[colon + 1].start, segment[boundary - 1].end).trim()
    }
    val assignment = segment.getOrNull(boundary)?.takeIf { it.text == "=" }
    if (assignment != null) {
        if (end > assignment.end) initializer = KtRange(assignment.end, end)
        for (j in boundary + 1 until segment.size) {
            if (segment[j].text == "get" || segment[j].text == "set") {
                if (segment[j].text == "get") hasGetter = true else hasSetter = true
            }
        }
    }
    if (boundary < segment.size && segment[boundary].text.let { it == "get" || it == "set" }) {
        for (j in boundary until segment.size) {
            if (segment[j].text == "get") hasGetter = true
            if (segment[j].text == "set") hasSetter = true
        }
    }
    val texts = segment.map { it.text }
    return KtProperty(
        keyword = keyword,
        name = name.text.removeSurrounding("`"),
        nameToken = name,
        isVar = keyword.text == "var",
        typeText = typeText,
        initializer = initializer,
        delegated = delegated,
        lateinit = "lateinit" in texts,
        const = "const" in texts,
        modifiers = segment.filter { it.type == KotlinTokenTypes.KEYWORD && it.text in KotlinCode.MODIFIER_WORDS }
            .map { it.text }.toSet(),
        receiver = receiver?.takeIf { typeParametersEnd < 0 || it.isNotEmpty() },
        hasCustomGetter = hasGetter,
        hasCustomSetter = hasSetter,
        start = start,
        end = end,
    )
}

private fun declarationLineStart(code: KotlinCode, firstOffset: Int): Int {
    var earliest = code.lineStart(firstOffset)
    val before = code.tokens.lastOrNull { it.index < 0 && it.isComment && it.end <= firstOffset }
    if (before != null && before.type == KotlinTokenTypes.BLOCK_COMMENT && before.text.startsWith("/**") &&
        code.isBlank(before.end, firstOffset)
    ) {
        earliest = minOf(earliest, code.lineStart(before.start))
    }
    return earliest
}

/** Index of the `@` opening the annotation whose last token is [endIndex], or `-1`. */
internal fun annotationStartEndingAt(code: KotlinCode, endIndex: Int): Int {
    if (endIndex < 0) return -1
    val token = code.code[endIndex]
    if (token.text == ")") {
        val open = code.matchingIndex(endIndex)
        if (open < 0) return -1
        return annotationStartEndingAt(code, open - 1)
    }
    if (!isNameToken(token)) return -1
    val before = code.code.getOrNull(endIndex - 1) ?: return -1
    if (before.text == "@") return endIndex - 1
    if (before.text == ":") {
        val target = code.code.getOrNull(endIndex - 2) ?: return -1
        val at = code.code.getOrNull(endIndex - 3) ?: return -1
        if (isNameToken(target) && at.text == "@") return endIndex - 3
    }
    return -1
}

/** Index of the last token of the annotation starting at the `@` at [atIndex]. */
internal fun annotationEnd(code: KotlinCode, atIndex: Int): Int {
    var index = atIndex + 1
    if (index < code.code.size && code.code[index].text == ":") index++
    if (index < code.code.size && isNameToken(code.code[index])) {
        index++
        while (index + 1 < code.code.size && code.code[index].type == KotlinTokenTypes.DOT &&
            isNameToken(code.code[index + 1])
        ) {
            index += 2
        }
    }
    if (index < code.code.size && code.code[index].text == "(") {
        val close = code.matchingIndex(index)
        if (close >= 0) index = close + 1
    }
    return (index - 1).coerceAtLeast(atIndex)
}
