package stride.app.files

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.AppState
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal const val FILE_OPERATION_POPUP_KEY: String = "popup-file-operation"
const val FILE_OPERATION_POPUP_TAG: String = "file-operation-popup"
const val FILE_OPERATION_FIELD_TAG: String = "file-operation-field"
const val FILE_OPERATION_ERROR_TAG: String = "file-operation-error"
const val FILE_OPERATION_ROW_TAG_PREFIX: String = "file-operation-row-"
const val FILE_OPERATION_KIND_TAG_PREFIX: String = "file-operation-kind-"

private val fileOperationWidth = 420.dp

/**
 * The one popup of the project tree's file operations: New's list and name pages, the Kotlin
 * page, Rename, Move, Copy and the Delete confirmation. A successful operation closes it and
 * calls [onFinish] with the created file, or `null` when nothing new was opened.
 */
@Composable
fun FileOperationPopup(request: FileOperationRequest, state: AppState, onFinish: (Path?) -> Unit) {
    val session = state.session
    val files = session?.files
    val root = session?.project?.root?.toAbsolutePath()?.normalize()
    val current = state.fileOperation ?: request
    val page = current.page
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val panelFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var fired by remember { mutableStateOf(false) }

    fun update(block: (FileOperationRequest) -> FileOperationRequest) {
        val now = state.fileOperation ?: return
        state.fileOperation = block(now)
    }

    fun validate(now: FileOperationRequest): String? =
        if (files == null || root == null) null else validationError(now, files, root)

    fun submit(result: () -> FileOperationResult, opensCreatedFile: Boolean) {
        if (fired) return
        fired = true
        when (val outcome = result()) {
            is FileOperationResult.Success -> {
                state.statusMessage = outcome.message
                onFinish(if (opensCreatedFile) outcome.path else null)
            }
            is FileOperationResult.Failure -> {
                fired = false
                state.statusMessage = outcome.message
                update { it.copy(error = outcome.message) }
            }
            is FileOperationResult.OpenCopyPopup -> {
                fired = false
                state.statusMessage = outcome.message
                update { it.copy(error = outcome.message) }
            }
        }
    }

    fun chooseNewKind() {
        val now = state.fileOperation ?: return
        when (now.newKind) {
            NewKind.KOTLIN -> update {
                it.copy(page = FileOperationPage.KOTLIN, text = TextFieldValue(), error = null)
            }
            NewKind.FILE -> update {
                it.copy(page = FileOperationPage.NEW_NAME, text = TextFieldValue(), newDirectory = false, error = null)
            }
            NewKind.DIRECTORY -> update {
                it.copy(page = FileOperationPage.NEW_NAME, text = TextFieldValue(), newDirectory = true, error = null)
            }
        }
    }

    fun backToList() = update { it.copy(page = FileOperationPage.NEW_LIST, error = null) }

    fun moveNewKind(step: Int) {
        val now = state.fileOperation ?: return
        val entries = NewKind.entries
        val next = (now.newKind.ordinal + step).coerceIn(0, entries.lastIndex)
        update { it.copy(newKind = entries[next]) }
    }

    fun moveKotlinKind(step: Int) {
        val now = state.fileOperation ?: return
        val entries = KotlinFileKind.entries
        val next = (now.kotlinKind.ordinal + step).coerceIn(0, entries.lastIndex)
        update { it.copy(kotlinKind = entries[next]) }
    }

    fun submitTextPage() {
        val f = files ?: return
        val now = state.fileOperation ?: return
        val value = now.text.text
        if (now.page != FileOperationPage.MOVE && value.isEmpty()) return
        validate(now)?.let {
            update { request -> request.copy(error = it) }
            return
        }
        when (now.page) {
            FileOperationPage.NEW_NAME -> submit(
                result = {
                    if (now.newDirectory) {
                        f.createDirectory(now.target, value)
                    } else {
                        f.createFile(now.target, value)
                    }
                },
                opensCreatedFile = !now.newDirectory,
            )
            FileOperationPage.KOTLIN -> submit(
                result = { f.createKotlin(now.target, value, now.kotlinKind) },
                opensCreatedFile = true,
            )
            FileOperationPage.RENAME -> {
                val parent = now.target.parent ?: root ?: now.target
                submit(result = { f.move(now.target, parent.resolve(value).normalize()) }, opensCreatedFile = false)
            }
            FileOperationPage.MOVE -> {
                val base = root ?: return
                val directory = if (value.isEmpty()) base else base.resolve(value).normalize()
                val destination = directory.resolve(now.target.fileName).normalize()
                submit(result = { f.move(now.target, destination) }, opensCreatedFile = false)
            }
            FileOperationPage.COPY -> {
                val base = root ?: return
                submit(
                    result = { f.copy(now.target, base.resolve(value).normalize()) },
                    opensCreatedFile = false,
                )
            }
            else -> Unit
        }
    }

    fun submitDelete() {
        val f = files ?: return
        val now = state.fileOperation ?: return
        if (!f.trashSupported()) return
        val sessionNow = session ?: return
        val rows = sessionNow.tree.rows()
        val key = now.target.toAbsolutePath().normalize()
        val index = rows.indexOfFirst { it.file.path.toAbsolutePath().normalize() == key }
        fun atOrBelow(path: Path) = path.toAbsolutePath().normalize().startsWith(key)
        val next = rows.drop(index + 1).firstOrNull { !atOrBelow(it.file.path) }?.file
            ?: rows.take(index.coerceAtLeast(0)).lastOrNull { !atOrBelow(it.file.path) }?.file
        submit(
            result = {
                val outcome = f.delete(now.target)
                if (outcome is FileOperationResult.Success && next != null) {
                    sessionNow.tree.select(next)
                }
                outcome
            },
            opensCreatedFile = false,
        )
    }

    LaunchedEffect(page) {
        fired = false
        withFrameNanos { }
        val target = if (page in FIELD_PAGES) fieldFocus else panelFocus
        runCatching { target.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(FILE_OPERATION_POPUP_TAG)
            .pointerInput(Unit) { detectTapGestures { onFinish(null) } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(fileOperationWidth)
                .popupPanel(FILE_OPERATION_POPUP_KEY, shape)
                .focusRequester(panelFocus)
                .focusable()
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (page) {
                            FileOperationPage.NEW_LIST -> when (event.key) {
                                Key.Escape -> {
                                    onFinish(null)
                                    true
                                }
                                Key.DirectionDown -> {
                                    moveNewKind(1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    moveNewKind(-1)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    chooseNewKind()
                                    true
                                }
                                else -> false
                            }
                            FileOperationPage.NEW_NAME -> when (event.key) {
                                Key.Escape -> {
                                    backToList()
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    submitTextPage()
                                    true
                                }
                                else -> false
                            }
                            FileOperationPage.KOTLIN -> when (event.key) {
                                Key.Escape -> {
                                    backToList()
                                    true
                                }
                                Key.DirectionDown -> {
                                    moveKotlinKind(1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    moveKotlinKind(-1)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    submitTextPage()
                                    true
                                }
                                else -> false
                            }
                            FileOperationPage.RENAME,
                            FileOperationPage.MOVE,
                            FileOperationPage.COPY,
                            -> when (event.key) {
                                Key.Escape -> {
                                    onFinish(null)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    submitTextPage()
                                    true
                                }
                                else -> false
                            }
                            FileOperationPage.DELETE -> when (event.key) {
                                Key.Escape -> {
                                    onFinish(null)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    submitDelete()
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                FileOperationHeader(current, root)
                when (page) {
                    FileOperationPage.NEW_LIST -> NewListPage(
                        selected = current.newKind,
                        onChoose = { kind ->
                            update { it.copy(newKind = kind) }
                            chooseNewKind()
                        },
                    )
                    FileOperationPage.NEW_NAME, FileOperationPage.RENAME, FileOperationPage.MOVE,
                    FileOperationPage.COPY,
                    -> {
                        if (page == FileOperationPage.COPY) {
                            CopyNote(files, current.target)
                        }
                        FileOperationField(
                            value = current.text,
                            focusRequester = fieldFocus,
                            onValueChange = { value ->
                                val withText = current.copy(text = value)
                                update { it.copy(text = value, error = validate(withText)) }
                            },
                        )
                        ErrorLine(current.error)
                    }
                    FileOperationPage.KOTLIN -> {
                        FileOperationField(
                            value = current.text,
                            focusRequester = fieldFocus,
                            onValueChange = { value ->
                                val withText = current.copy(text = value)
                                update { it.copy(text = value, error = validate(withText)) }
                            },
                        )
                        KotlinKindList(
                            selected = current.kotlinKind,
                            onChoose = { kind -> update { it.copy(kotlinKind = kind) } },
                        )
                        ErrorLine(current.error)
                    }
                    FileOperationPage.DELETE -> DeletePage(files, current.target)
                }
                PopupHintBar(*hints(page, files).toTypedArray())
            }
        }
    }
}

private val FIELD_PAGES = setOf(
    FileOperationPage.NEW_NAME,
    FileOperationPage.KOTLIN,
    FileOperationPage.RENAME,
    FileOperationPage.MOVE,
    FileOperationPage.COPY,
)

private fun hints(page: FileOperationPage, files: FileOperations?): List<String> = when (page) {
    FileOperationPage.NEW_LIST -> listOf("↑↓ Navigate", "↵ Next", "Esc Close")
    FileOperationPage.NEW_NAME -> listOf("↵ Create", "Esc Back")
    FileOperationPage.KOTLIN -> listOf("↑↓ Kind", "↵ Create", "Esc Back")
    FileOperationPage.RENAME -> listOf("↵ Rename", "Esc Cancel")
    FileOperationPage.MOVE -> listOf("↵ Move", "Esc Cancel")
    FileOperationPage.COPY -> listOf("↵ Copy", "Esc Cancel")
    FileOperationPage.DELETE -> buildList {
        if (files == null || files.trashSupported()) add("↵ Move to Trash")
        add("Esc Cancel")
    }
}

/** P6: the popup's live error line, from the file-operation checks; `null` while the text is empty. */
internal fun validationError(request: FileOperationRequest, files: FileOperations, root: Path): String? {
    val text = request.text.text
    if (text.isEmpty()) return null
    return when (request.page) {
        FileOperationPage.NEW_NAME -> files.relativePathError(text) ?: files.existsError(request.target.resolve(text))
        FileOperationPage.KOTLIN -> files.kotlinNameError(text)
            ?: files.existsError(request.target.resolve("${text.removeSuffix(".kt")}.kt"))
        FileOperationPage.RENAME -> {
            files.nameError(text) ?: run {
                val parent = request.target.parent ?: root
                val destination = parent.resolve(text).normalize()
                if (destination == request.target) null else files.existsError(destination)
            }
        }
        FileOperationPage.MOVE -> {
            files.relativePathError(text)
                ?: moveConflictError(files, root, request.target, text)
        }
        FileOperationPage.COPY -> {
            files.relativePathError(text)
                ?: copyConflictError(files, root, request.target, text)
        }
        else -> null
    }
}

private fun moveConflictError(files: FileOperations, root: Path, source: Path, text: String): String? {
    val name = source.fileName?.toString() ?: return null
    val directory = root.resolve(text).normalize()
    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) &&
        (directory == source || directory.startsWith(source))
    ) {
        return "Cannot move $name into itself"
    }
    val destination = directory.resolve(name).normalize()
    return if (destination == source) null else files.existsError(destination)
}

private fun copyConflictError(files: FileOperations, root: Path, source: Path, text: String): String? {
    val name = source.fileName?.toString() ?: return null
    val destination = root.resolve(text).normalize()
    if (destination == source || destination.startsWith(source)) {
        return "Cannot copy $name into itself"
    }
    return files.existsError(destination)
}

@Composable
private fun FileOperationHeader(request: FileOperationRequest, root: Path?) {
    val target = request.target
    val title = when (request.page) {
        FileOperationPage.NEW_LIST, FileOperationPage.KOTLIN -> "New"
        FileOperationPage.NEW_NAME -> if (request.newDirectory) "New Directory" else "New File"
        FileOperationPage.RENAME -> "Rename"
        FileOperationPage.MOVE -> "Move"
        FileOperationPage.COPY -> "Copy"
        FileOperationPage.DELETE -> "Move ${target.fileName} to the Trash?"
    }
    val detail = when (request.page) {
        FileOperationPage.NEW_LIST, FileOperationPage.NEW_NAME, FileOperationPage.KOTLIN ->
            root?.let { runCatching { it.relativize(target).toString() }.getOrNull() }
        FileOperationPage.MOVE, FileOperationPage.COPY -> root?.let {
            runCatching { it.relativize(target).toString() }.getOrNull()
        }
        FileOperationPage.RENAME, FileOperationPage.DELETE -> null
    }?.takeIf { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                detail,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DesignTokens.space8),
            )
        }
    }
}

@Composable
private fun NewListPage(selected: NewKind, onChoose: (NewKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = DesignTokens.space4)) {
        NewKind.entries.forEach { kind ->
            FileOperationRow(
                label = kind.label,
                selected = kind == selected,
                tag = FILE_OPERATION_ROW_TAG_PREFIX + kind.name.lowercase(),
                onClick = { onChoose(kind) },
            )
        }
    }
}

@Composable
private fun KotlinKindList(selected: KotlinFileKind, onChoose: (KotlinFileKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = DesignTokens.space4)) {
        KotlinFileKind.entries.forEach { kind ->
            FileOperationRow(
                label = kotlinKindLabel(kind),
                selected = kind == selected,
                tag = FILE_OPERATION_KIND_TAG_PREFIX + kind.name.lowercase(),
                onClick = { onChoose(kind) },
            )
        }
    }
}

private fun kotlinKindLabel(kind: KotlinFileKind): String = when (kind) {
    KotlinFileKind.CLASS -> "Class"
    KotlinFileKind.FILE -> "File"
    KotlinFileKind.INTERFACE -> "Interface"
    KotlinFileKind.DATA_CLASS -> "Data class"
    KotlinFileKind.ENUM_CLASS -> "Enum class"
    KotlinFileKind.OBJECT -> "Object"
}

@Composable
private fun FileOperationRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .testTag(tag)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileOperationField(
    value: TextFieldValue,
    focusRequester: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = 6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(FILE_OPERATION_FIELD_TAG),
            singleLine = true,
            textStyle = DesignTokens.chromeTextStyle.merge(
                TextStyle(
                    color = DesignTokens.text,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                ),
            ),
            cursorBrush = SolidColor(DesignTokens.accent),
        )
    }
}

@Composable
private fun ErrorLine(error: String?) {
    if (error == null) return
    Text(
        error,
        color = DesignTokens.errorOnPopupPlate,
        fontSize = DesignTokens.labelTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4)
            .testTag(FILE_OPERATION_ERROR_TAG),
    )
}

@Composable
private fun CopyNote(files: FileOperations?, source: Path) {
    val unsaved = remember(source) { files?.unsavedEditorCount(source) ?: 0 }
    if (unsaved == 0) return
    Text(
        "$unsaved unsaved editors: the saved content is copied",
        color = DesignTokens.neonAmber,
        fontSize = DesignTokens.labelTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
    )
}

@Composable
private fun DeletePage(files: FileOperations?, target: Path) {
    val isDirectory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
    val countText = remember(target) { if (isDirectory) files?.fileCountText(target) else null }
    val tabs = remember(target) { files?.openTabCount(target) ?: 0 }
    val unsaved = remember(target) { files?.unsavedTabCount(target) ?: 0 }
    val trash = files == null || files.trashSupported()
    Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8)) {
        if (countText != null) {
            Text(
                countText,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
        if (tabs > 0) {
            Text(
                "Closes $tabs tabs",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
        if (unsaved > 0) {
            Text(
                "$unsaved with unsaved changes will be lost",
                color = DesignTokens.neonAmber,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
        if (!trash) {
            Spacer(Modifier.height(DesignTokens.space4))
            Text(
                "This system has no Trash: delete it in the terminal",
                color = DesignTokens.neonAmber,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}
