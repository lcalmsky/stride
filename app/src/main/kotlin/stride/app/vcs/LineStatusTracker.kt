package stride.app.vcs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import stride.app.editor.TextFileEditor
import stride.core.services.Project
import stride.core.services.service

/**
 * One publish of [LineStatusTracker]: the text [hunks] were computed for, the base answer they
 * compare against and the base lines themselves. Readers take this value whole, so a hunk is
 * never paired with another text or another base.
 */
internal data class LineStatus(
    val text: String,
    val answer: BaseText?,
    val baseLines: List<String>,
    val hunks: List<LineHunk>,
)

/**
 * One open text editor's comparison of its document with the file at HEAD: the base is fetched
 * off the UI thread, and the in-process diff runs 100 ms after the last edit and is cancelled by
 * the next one. The result is published to the editor's gutter state.
 */
internal class LineStatusTracker(project: Project, private val editor: TextFileEditor) {

    private val git = project.service<GitService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disposed = AtomicBoolean(false)
    private val edits = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var status = LineStatus(editor.value.text, null, emptyList(), emptyList())

    /** The last base answer; `null` until the first fetch finishes. */
    private var baseAnswer by mutableStateOf<BaseText?>(null)

    /** The last publish, or the same base with hunks computed for [text] when it is for another text. */
    fun statusFor(text: String): LineStatus {
        val published = status
        val answer = published.answer ?: baseAnswer
        if (published.text == text && published.answer == answer) return published
        return live(text, answer)
    }

    /** The last publish: the text, the base answer, the base lines and the hunks, all together. */
    fun document(): LineStatus = status

    /** The last [GitService.baseText] answer, or `null` before it arrives. */
    fun baseState(): BaseText? = baseAnswer

    /** The document changed; the diff runs once the edits stop for the debounce. */
    fun textChanged() {
        edits.trySend(Unit)
    }

    init {
        scope.launch {
            // The base changes with HEAD, with the repository root, and with this file's own
            // status: a file added to the index compares against "" and one removed from it is
            // untracked, while HEAD stays the same.
            snapshotFlow { baseKey() }
                .distinctUntilChanged()
                .collectLatest {
                    val answer = git.baseText(editor.file.path)
                    if (disposed.get()) return@collectLatest
                    Snapshot.withMutableSnapshot { baseAnswer = answer }
                    textChanged()
                }
        }
        scope.launch {
            edits.receiveAsFlow().collectLatest {
                delay(DEBOUNCE_MILLIS)
                recompute()
            }
        }
    }

    fun detach() {
        if (!disposed.compareAndSet(false, true)) return
        edits.close()
        scope.cancel()
    }

    private fun baseKey(): BaseKey {
        val snapshot = git.snapshot
        val path = editor.file.path.toAbsolutePath().normalize()
        val change = snapshot.changes.firstOrNull { it.path.toAbsolutePath().normalize() == path }
        return BaseKey(snapshot.repository?.root, snapshot.branch?.head, change?.kind)
    }

    private fun recompute() {
        if (disposed.get()) return
        publish(live(editor.value.text, baseAnswer))
    }

    private fun live(text: String, answer: BaseText?): LineStatus {
        val base = (answer as? BaseText.Text)?.content?.let(LineDiff::split).orEmpty()
        val hunks = if (answer is BaseText.Text) LineDiff.diff(base, LineDiff.split(text)).orEmpty() else emptyList()
        return LineStatus(text, answer, base, hunks)
    }

    private fun publish(status: LineStatus) {
        if (disposed.get()) return
        this.status = status
        Snapshot.withMutableSnapshot { editor.vcsHunks = status.hunks }
    }

    private data class BaseKey(val root: Path?, val head: String?, val kind: ChangeKind?)

    private companion object {
        const val DEBOUNCE_MILLIS: Long = 100
    }
}
