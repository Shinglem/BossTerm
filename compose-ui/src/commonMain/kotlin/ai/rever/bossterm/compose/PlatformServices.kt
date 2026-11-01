package ai.rever.bossterm.compose

/**
 * Platform-specific services abstraction.
 * Uses expect/actual pattern for platform-specific implementations.
 */
interface PlatformServices {
    /**
     * Clipboard operations
     */
    interface ClipboardService {
        /**
         * Copy text to clipboard
         */
        suspend fun copyText(text: String): Boolean

        /**
         * Get text from clipboard
         */
        suspend fun pasteText(): String?

        /**
         * Check if clipboard has text content
         */
        suspend fun hasText(): Boolean
    }

    /**
     * File system operations
     */
    interface FileSystemService {
        /**
         * Check if file exists
         */
        suspend fun fileExists(path: String): Boolean

        /**
         * Read file as text
         */
        suspend fun readTextFile(path: String): String?

        /**
         * Write text to file
         */
        suspend fun writeTextFile(path: String, content: String): Boolean

        /**
         * Get user home directory
         */
        fun getUserHomeDirectory(): String

        /**
         * Get temporary directory
         */
        fun getTempDirectory(): String
    }

    /**
     * Process/PTY operations
     */
    interface ProcessService {
        data class ProcessConfig(
            val command: String,
            val arguments: List<String>,
            val environment: Map<String, String> = emptyMap(),
            val workingDirectory: String? = null,
            /**
             * PTY 的初始窗口尺寸。0 = 由实现挑一个安全默认值。
             *
             * 必须在 spawn 时就给对：子进程（尤其 PowerShell 7 启动时打印更新通知）
             * 读的是**创建时**的窗口宽度，等到 Compose 量出真实 grid 再 resize 已经晚了 ——
             * 宽度异常时 PowerShell 的 `PadRight(width - N)` 会拿到负数并 FailFast。
             */
            val initialColumns: Int = 0,
            val initialRows: Int = 0,
        )

        /**
         * Spawn a new PTY process
         */
        suspend fun spawnProcess(config: ProcessConfig): ProcessHandle?

        /**
         * Process handle for managing spawned processes
         */
        interface ProcessHandle {
            /**
             * Write data to process stdin
             */
            suspend fun write(data: String)

            /**
             * Write raw bytes to process stdin.
             * Use this for sending control characters or binary data.
             */
            suspend fun writeBytes(data: ByteArray)

            /**
             * Read available data from process stdout
             */
            suspend fun read(): String?

            /**
             * Check if process is alive
             */
            fun isAlive(): Boolean

            /**
             * Kill the process
             */
            suspend fun kill()

            /**
             * Wait for the process to exit and return its exit code.
             * This is a blocking operation that should be called from a coroutine context
             * suitable for blocking operations (e.g., Dispatchers.IO).
             *
             * @return the exit code of the process
             */
            suspend fun waitFor(): Int

            /**
             * Resize PTY
             */
            suspend fun resize(columns: Int, rows: Int)

            /**
             * Get process exit code (null if still running)
             */
            fun getExitCode(): Int?

            /**
             * Get process ID (PID) of the spawned process.
             * Returns null if PID cannot be determined.
             */
            fun getPid(): Long?

            /**
             * Get the current working directory of the process.
             * This reads from /proc/<pid>/cwd on Linux or uses other OS-specific methods.
             * Returns null if the directory cannot be determined.
             */
            fun getWorkingDirectory(): String?
        }
    }

    /**
     * Platform information
     */
    interface PlatformInfo {
        /**
         * Platform name (Android, iOS, Desktop, Web)
         */
        fun getPlatformName(): String

        /**
         * OS name (Linux, macOS, Windows, Android, iOS, Browser)
         */
        fun getOSName(): String

        /**
         * OS version
         */
        fun getOSVersion(): String

        /**
         * Check if running on mobile platform
         */
        fun isMobile(): Boolean

        /**
         * Check if running on desktop platform
         */
        fun isDesktop(): Boolean

        /**
         * Check if running in browser
         */
        fun isWeb(): Boolean
    }

    /**
     * URL/Browser operations
     */
    interface BrowserService {
        /**
         * Open URL in browser
         */
        suspend fun openUrl(url: String): Boolean
    }

    /**
     * System notifications
     */
    interface NotificationService {
        /**
         * Show a system notification
         */
        suspend fun showNotification(title: String, message: String)

        /**
         * Play system beep sound
         */
        fun beep()
    }

    /**
     * Get clipboard service
     */
    fun getClipboardService(): ClipboardService

    /**
     * Get file system service
     */
    fun getFileSystemService(): FileSystemService

    /**
     * Get process service
     */
    fun getProcessService(): ProcessService

    /**
     * Get platform info
     */
    fun getPlatformInfo(): PlatformInfo

    /**
     * Get browser service
     */
    fun getBrowserService(): BrowserService

    /**
     * Get notification service
     */
    fun getNotificationService(): NotificationService
}

/**
 * Get platform services instance for current platform
 */
expect fun getPlatformServices(): PlatformServices
