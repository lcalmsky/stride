package stride.app.vcs

import java.nio.file.Path

/** One file of a diff popup; [path] is null for a side the file does not exist on. */
data class DiffFile(
    val path: Path?,
    val displayPath: String,
    val old: BaseText,
    val new: BaseText,
    val openable: Boolean,
)

data class DiffRequest(
    val title: String,
    val files: List<DiffFile>,
    val initialFile: Int = 0,
    val initialLine: Int? = null,
    /** Files a commit changed beyond the ones [files] carries, shown as `… <n> more files`. */
    val moreFiles: Int = 0,
)
