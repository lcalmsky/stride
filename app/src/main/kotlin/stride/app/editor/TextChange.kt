package stride.app.editor

/**
 * One replacement [offset, offset + oldLength) -> [newText], derived from a common prefix
 * and suffix.
 */
data class TextChange(val offset: Int, val oldLength: Int, val newText: String)

fun computeTextChange(old: String, new: String): TextChange {
    if (old == new) return TextChange(0, 0, "")
    val maxPrefix = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

    val maxSuffix = minOf(old.length - prefix, new.length - prefix)
    var suffix = 0
    while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

    return TextChange(
        offset = prefix,
        oldLength = old.length - prefix - suffix,
        newText = new.substring(prefix, new.length - suffix),
    )
}
