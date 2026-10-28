package com.vitosstudios.suite.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A function the user can find with the command search in the top bar. Every editor publishes
 * its commands so that every function is reachable by name, not only through toolbars.
 */
data class Command(
    val id: String,
    val title: String,
    val group: String,
    val icon: ImageVector? = null,
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** Something other than a command that the search can open (for example a recent file). */
data class SearchTarget(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector?,
    val open: () -> Unit,
)

object CommandMatcher {
    /** Ranks commands: title prefix, word prefix, then keyword or substring matches. */
    fun filter(commands: List<Command>, query: String): List<Command> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return commands
        return commands.mapNotNull { c ->
            val title = c.title.lowercase()
            val score = when {
                title.startsWith(q) -> 0
                title.split(' ', '-', '/').any { it.startsWith(q) } -> 1
                c.keywords.any { it.lowercase().startsWith(q) } -> 2
                title.contains(q) || c.group.lowercase().contains(q) -> 3
                c.keywords.any { it.lowercase().contains(q) } -> 4
                else -> null
            }
            score?.let { it to c }
        }.sortedWith(compareBy({ it.first }, { it.second.title })).map { it.second }
    }
}
