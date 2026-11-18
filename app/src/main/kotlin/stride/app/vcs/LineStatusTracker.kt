package stride.app.vcs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import stride.app.editor.TextFileEditor
import stride.core.services.Project
import stride.core.services.service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One open text editor's comparison of its document with the file at HEAD: the base is fetched
 * off the UI thread, and the in-process diff runs 100 ms after the last edit and is cancelled by
 * the next one. The result is published to the editor's gutter state.
 */
internal class LineStatusTracker(project: Project, private val editor: TextFileEditor) {

    private val git = project.service<GitService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var currentBase: List<String> = emptyList()

    @Volatile
    private var currentHunks: List<LineHunk> = emptyList()

    /** The last base answer; `null` until the first fetch finishes. */
    private var baseAnswer by mutableStateOf<BaseText?>(null)

    val baseLines: List<String> get() = currentBase

    val hunks: List<LineHunk> get() = currentHunks

    /** The last [GitService.baseText] answer, or `null` before it arrives. */
    fun baseState(): BaseText? = baseAnswer

    init {
        scope.launch {
            // A new HEAD (and a repository appearing or disappearing) changes the base; status
            // refreshes within one HEAD reuse the service's per-HEAD cache.
            snapshotFlow { git.snapshot.repository?.root to git.snapshot.branch?.head }
                .distinctUntilChanged()
                .collectLatest {
                    val answer = git.baseText(editor.file.path)
                    if (disposed.get()) return@collectLatest
                    Snapshot.withMutableSnapshot { baseAnswer = answer }
                }
        }
        scope.launch {
            snapshotFlow { baseAnswer to editor.value.text }
                .distinctUntilChanged()
                .collectLatest {
                    delay(DEBOUNCE_MILLIS)
                    recompute()
                }
        }
    }

    fun detach() {
        if (!disposed.compareAndSet(false, true)) return
        scope.cancel()
    }

    private fun recompute() {
        if (disposed.get()) return
        val answer = baseAnswer
        val base = (answer as? BaseText.Text)?.content?.let(LineDiff::split).orEmpty()
        val current = LineDiff.split(editor.value.text)
        val result = if (answer is BaseText.Text) LineDiff.diff(base, current).orEmpty() else emptyList()
        publish(base, result)
    }

    private fun publish(base: List<String>, hunks: List<LineHunk>) {
        if (disposed.get()) return
        currentBase = base
        currentHunks = hunks
        Snapshot.withMutableSnapshot { editor.vcsHunks = hunks }
    }

    private companion object {
        const val DEBOUNCE_MILLIS: Long = 100
    }
}
