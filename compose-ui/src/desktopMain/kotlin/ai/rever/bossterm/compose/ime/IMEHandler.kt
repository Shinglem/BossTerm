package ai.rever.bossterm.compose.ime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * IME (Input Method Editor) handler for CJK (Chinese, Japanese, Korean) input.
 *
 * This component uses a transparent TextField overlay to capture IME composition events
 * and forwards the composed text to the terminal. This is a workaround for Compose Desktop's
 * limited IME support in custom keyboard event handlers.
 *
 * Implementation approach:
 * 1. Invisible TextField positioned at cursor location
 * 2. TextField captures IME composition (e.g., Pinyin for Chinese)
 * 3. On composition commit, forward text to terminal
 * 4. Clear TextField for next input
 *
 * Known limitations:
 * - TextField must have focus to receive IME events
 * - Composition window positioning may not be pixel-perfect
 * - Performance overhead from TextField rendering (minimized with transparency)
 *
 * References:
 * - https://github.com/JetBrains/compose-multiplatform/issues/3221
 * - https://github.com/JetBrains/compose-multiplatform/issues/2628
 */
@Composable
fun IMEHandler(
    enabled: Boolean,
    cursorX: Int,
    cursorY: Int,
    charWidth: Float,
    charHeight: Float,
    onTextCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    // Compose delivers the same committed text more than once (composition end + value
    // reset). Without this guard a single "你好" is written to the PTY three times.
    var lastCommitted by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Position TextField at cursor location (use roundToInt to prevent cumulative drift)
    val offsetX = (cursorX * charWidth).roundToInt()
    val offsetY = (cursorY * charHeight).roundToInt()

    Box(modifier = modifier) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                // While an IME composition is active the text is only a preview (pinyin and
                // friends). Forwarding it right away is what made Chinese input land in the
                // shell as raw letters, so wait until the composition is committed.
                if (newValue.composition == null) {
                    val text = newValue.text
                    if (text.isNotEmpty() && text != lastCommitted) {
                        lastCommitted = text
                        onTextCommit(text)
                    }
                    if (textFieldValue.text.isNotEmpty()) textFieldValue = TextFieldValue("")
                } else {
                    textFieldValue = newValue
                }
            },
            modifier = Modifier
                .offset { IntOffset(offsetX, offsetY) }
                .size(1.dp) // keep the field focusable without painting over the grid
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = Color.Transparent, // Invisible text
                fontSize = 14.sp
            ),
            singleLine = true
        )
    }

    // Auto-focus the TextField when enabled. The field must be in the layout before
    // requestFocus() is called, otherwise it throws and CJK input silently falls back to
    // raw key events (the old behaviour).
    LaunchedEffect(enabled) {
        if (enabled) {
            lastCommitted = ""
            withFrameNanos { }
            delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/**
 * State holder for IME input management.
 *
 * Usage:
 * ```
 * val imeState = remember { IMEState() }
 *
 * // Enable IME mode (e.g., when Ctrl+Space pressed)
 * imeState.enable()
 *
 * // Disable IME mode
 * imeState.disable()
 *
 * // Check if IME is active
 * if (imeState.isEnabled) { ... }
 * ```
 */
class IMEState {
    private val _isEnabled = mutableStateOf(false)
    val isEnabled: Boolean get() = _isEnabled.value

    fun enable() {
        _isEnabled.value = true
    }

    fun disable() {
        _isEnabled.value = false
    }

    fun toggle() {
        _isEnabled.value = !_isEnabled.value
    }
}
