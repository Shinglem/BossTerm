package ai.rever.bossterm.hermesdemo

import ai.rever.bossterm.compose.createTerminalTab
import ai.rever.bossterm.compose.startTerminalProcess
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.ui.ProperTerminal
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val settings = remember { TerminalSettings() }
    val tab = remember { createTerminalTab(settings) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        startTerminalProcess(
            tab = tab,
            settings = settings,
            workingDirectory = System.getProperty("user.home"),
        )
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "BossTerm core (hermes demo)",
    ) {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(4.dp)) {
                    Button(onClick = { showDialog = true }) {
                        Text("open dialog (z-order check)")
                    }
                    Button(onClick = { tab.imeState.enable() }, Modifier.padding(start = 8.dp)) {
                        Text("IME")
                    }
                }
                Box(Modifier.weight(1f)) {
                    ProperTerminal(
                        tab = tab,
                        isActiveTab = true,
                        autoFocus = true,
                        sharedFont = FontFamily.Monospace,
                        onTabTitleChange = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    confirmButton = { TextButton(onClick = { showDialog = false }) { Text("OK") } },
                    title = { Text("z-order") },
                    text = { Text("能盖住终端说明不再是 heavyweight。") },
                )
            }
        }
    }
}
