package ai.rever.bossterm.compose

/**
 * Base interface for context menu elements.
 *
 * Extracted from `EmbeddableTerminal.kt` so the terminal core can build menus
 * without pulling in the application-level embedding shell.
 */
sealed interface ContextMenuElement

/**
 * A clickable context menu item.
 *
 * @param id Unique identifier for the menu item
 * @param label Display text for the menu item
 * @param enabled Whether the item is clickable (default: true)
 * @param action Callback invoked when the item is clicked
 */
data class ContextMenuItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val action: () -> Unit
) : ContextMenuElement

/**
 * A section separator in the context menu.
 * Use this to visually group related menu items.
 *
 * @param id Unique identifier for the section
 * @param label Optional label displayed above the separator (section header)
 */
data class ContextMenuSection(
    val id: String,
    val label: String? = null
) : ContextMenuElement

/**
 * A submenu containing nested menu items.
 *
 * @param id Unique identifier for the submenu
 * @param label Display text for the submenu
 * @param items Nested menu elements (can include items, sections, or more submenus)
 */
data class ContextMenuSubmenu(
    val id: String,
    val label: String,
    val items: List<ContextMenuElement>
) : ContextMenuElement
