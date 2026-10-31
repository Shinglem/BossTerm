package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.osc.OpenTargetToken
import ai.rever.bossterm.compose.settings.TerminalSettings

/** GUI host context injected into the shared engine; explicit pre-connect values win. */
@Suppress("UNUSED_PARAMETER")
internal fun localSessionEnvironment(settings: TerminalSettings, overrides: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
    put("BOSSTERM_OPEN_TOKEN", OpenTargetToken.value)
    putAll(overrides)
}
