package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.features.shouldUseNativeMenus
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.TerminalSessionStack
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalModelListener
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * Creates a [TerminalTab] with a fresh buffer, display, emulator and data stream.
 *
 * This is the supported entry point for embedding the terminal: build the tab once,
 * hand it to the composable, then call [startTerminalProcess] to launch the shell.
 */
fun createTerminalTab(settings: TerminalSettings): TerminalTab {
    // The host owns the settings (Hermes keeps them in state.json): run the settings manager in
    // memory so no `~/.bossterm/settings.json` is read or written.
    SettingsManager.installInMemory(settings)

    val styleState = StyleState()
    val textBuffer = TerminalTextBuffer(80, 24, styleState, settings.bufferMaxLines)
    val display = ComposeTerminalDisplay(settings)
    val terminal = BossTerminal(display, textBuffer, styleState)

    textBuffer.addModelListener(object : TerminalModelListener {
        override fun modelChanged() {
            display.requestImmediateRedraw()
        }
    })

    terminal.setCharacterEncoding(settings.characterEncoding)

    val dataStream = BlockingTerminalDataStream(
        performanceMode = PerformanceMode.fromString(settings.performanceMode)
    )

    // Batch a whole PTY chunk into one buffer update so a clear+write sequence repaints once.
    dataStream.onChunkStart = { textBuffer.beginBatch() }
    dataStream.onChunkEnd = { textBuffer.endBatch() }

    val stack = TerminalSessionStack.create(settings, display, textBuffer, terminal, dataStream)
    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    return TerminalTab(
        id = UUID.randomUUID().toString(),
        title = mutableStateOf("Terminal"),
        terminal = terminal,
        textBuffer = textBuffer,
        display = display,
        dataStream = dataStream,
        emulator = stack.emulator,
        processHandle = mutableStateOf(null),
        workingDirectory = mutableStateOf(null),
        connectionState = mutableStateOf(ConnectionState.Initializing),
        coroutineScope = coroutineScope,
        isFocused = mutableStateOf(true),
        scrollOffset = mutableStateOf(0),
        searchVisible = mutableStateOf(false),
        searchQuery = mutableStateOf(""),
        searchMatches = mutableStateOf(emptyList()),
        currentSearchMatchIndex = mutableStateOf(-1),
        selectionClipboard = mutableStateOf(null),
        imeState = IMEState(),
        contextMenuController = ContextMenuController(
            nativePreferred = {
                shouldUseNativeMenus(
                    settingEnabled = true,
                    isMacOs = ShellCustomizationUtils.isMacOS()
                )
            }
        ),
        hyperlinks = mutableStateOf(emptyList()),
        hoveredHyperlink = mutableStateOf(null)
    ).apply { sessionStack = stack }
}

/**
 * Visible column count of this tab's buffer.
 *
 * Exposed so hosts can report resize without depending on the core buffer type
 * (which `compose-ui` keeps as an implementation detail).
 */
fun TerminalTab.columns(): Int = textBuffer.width

/**
 * Text currently selected in this tab, or null when there is no selection.
 *
 * Selection rows are absolute buffer rows (history + screen), which is what the renderer
 * and [ai.rever.bossterm.terminal.model.TerminalSelection] both use.
 */
fun TerminalTab.selectedText(): String? {
    val snapshot = textBuffer.createIncrementalSnapshot()
    val resolved = selectionTracker.resolveToCoordinates(snapshot) ?: return null

    // resolveToCoordinates yields snapshot rows (screen >= 0, history < 0); the buffer's
    // getLine() wants absolute rows starting at the oldest history line.
    val history = snapshot.historyLinesCount
    val forward = resolved.startRow <= resolved.endRow
    val startRow = (minOf(resolved.startRow, resolved.endRow) + history).coerceAtLeast(0)
    val endRow = (maxOf(resolved.startRow, resolved.endRow) + history).coerceAtLeast(0)
    val startCol = if (forward) resolved.startCol else resolved.endCol
    val endCol = if (forward) resolved.endCol else resolved.startCol

    return buildString {
        for (row in startRow..endRow) {
            val line = textBuffer.getLine(row) ?: continue
            val text = line.text
            val from = if (row == startRow) startCol.coerceIn(0, text.length) else 0
            val to = if (row == endRow) endCol.coerceIn(0, text.length) else text.length
            if (to > from) append(text, from, to)
            if (row < endRow) append('\n')
        }
    }.takeIf { it.isNotEmpty() }
}

/** One snapshot of the scrollback + screen, used by the host's `terminal.read` handler. */
data class TerminalSnapshot(
    val totalLines: Int,
    val start: Int,
    val end: Int,
    val viewportRows: Int,
    val cursorRow: Int,
    val text: String,
)

/**
 * Text of the buffer between absolute rows [start] and [start] + [count].
 *
 * Nulls follow the old JediTerm contract: [start] defaults to `total - count`,
 * trailing blank lines are dropped, and [cursorRow] is absolute.
 */
fun TerminalTab.snapshot(start: Int? = null, count: Int? = null, defaultLines: Int = 200): TerminalSnapshot {
    textBuffer.lock()
    try {
        val history = textBuffer.historyLinesCount
        val viewport = textBuffer.height
        val total = history + viewport
        val from = (start ?: maxOf(0, total - (count ?: defaultLines))).coerceIn(0, total)
        val to = minOf(total, from + (count ?: (total - from)))
        val lines = ArrayList<String>(to - from)
        for (row in from until to) {
            lines += textBuffer.getLine(row)?.text.orEmpty()
        }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        val cursorRow = runCatching { history + terminal.cursorY }.getOrDefault(history)
        return TerminalSnapshot(
            totalLines = total,
            start = from,
            end = to,
            viewportRows = viewport,
            cursorRow = cursorRow,
            text = lines.joinToString("\n"),
        )
    } finally {
        textBuffer.unlock()
    }
}

/** Last [lines] lines of the buffer (scrollback included), newline separated. */
fun TerminalTab.tailText(lines: Int = 200): String {
    val total = textBuffer.height + textBuffer.historyLinesCount
    if (total <= 0) return ""
    val start = (total - lines).coerceAtLeast(0)
    return buildString {
        for (row in start until total) {
            val line = textBuffer.getLine(row) ?: continue
            append(line.text.trimEnd())
            append('\n')
        }
    }
}

/**
 * Starts the shell process for a tab returned by [createTerminalTab].
 *
 * @param command executable to run; null uses the platform default shell.
 * @param workingDirectory initial cwd; null uses the process working directory.
 * @param onExit called with the exit code when the shell terminates.
 */
fun startTerminalProcess(
    tab: TerminalTab,
    settings: TerminalSettings,
    command: String? = null,
    workingDirectory: String? = null,
    environment: Map<String, String>? = null,
    platformServices: PlatformServices = getPlatformServices(),
    initialCommand: String? = null,
    onExit: ((Int) -> Unit)? = null,
): TerminalSessionEngine {
    lateinit var engine: TerminalSessionEngine
    engine = TerminalSessionEngine(
        id = tab.id,
        settings = settings,
        workingDir = workingDirectory,
        command = command,
        stack = checkNotNull(tab.sessionStack) { "tab was not created by createTerminalTab()" },
        platformServices = platformServices,
        initialCommand = initialCommand,
        environmentOverrides = localSessionEnvironment(settings, environment.orEmpty()),
        parentScope = tab.coroutineScope,
        onStateChanged = { state ->
            if (state is TerminalSessionEngine.State.Error) {
                tab.connectionState.value = ConnectionState.Error(state.message, state.cause)
            } else if (state is TerminalSessionEngine.State.Initializing) {
                tab.connectionState.value = ConnectionState.Initializing
            }
        },
        onConnected = { actual, _, _ ->
            val handle = engine.processHandleAdapter(actual)
            tab.processHandle.value = handle
            tab.connectionState.value = ConnectionState.Connected(handle)
        },
        onProcessExit = { code ->
            val exitCode = code ?: -1
            tab.connectionState.value = ConnectionState.Error("Process exited with code $exitCode")
            onExit?.invoke(exitCode)
        },
    )
    tab.attachEngine(engine)
    engine.start()
    return engine
}
