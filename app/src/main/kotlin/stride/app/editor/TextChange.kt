package stride.app.editor

/**
 * One replacement of the [oldLength] characters at [offset] with [newText]. The offset counts from
 * the start of the text as it is before this replacement; a list of changes is ascending, each
 * entry already in the coordinates left by the ones before it.
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
