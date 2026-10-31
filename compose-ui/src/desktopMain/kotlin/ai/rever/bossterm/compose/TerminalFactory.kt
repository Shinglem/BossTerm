package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.features.shouldUseNativeMenus
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.TerminalSessionStack
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
