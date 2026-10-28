package com.vitosstudios.suite.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.vitosstudios.suite.ui.theme.Corners
import com.vitosstudios.suite.ui.theme.Sizes
import com.vitosstudios.suite.ui.theme.Spacing

/** One button of a toolbar. [selected] non-null makes it a toggle (announced as on/off). */
data class ToolbarAction(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Toolbar that appears only while something is selected (text, a cell, a shape). It scrolls
 * horizontally when the actions do not fit.
 */
@Composable
fun ContextToolbar(visible: Boolean, actions: List<ToolbarAction>, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(Corners.l),
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
        ) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .heightIn(min = Sizes.contextToolbarHeight)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { ToolbarButton(it) }
            }
        }
    }
}

@Composable
fun ToolbarButton(action: ToolbarAction) {
    if (action.selected != null) {
        FilledTonalIconToggleButton(
            checked = action.selected,
            onCheckedChange = { action.onClick() },
            enabled = action.enabled,
            modifier = Modifier.sizeIn(minWidth = Sizes.touchTarget, minHeight = Sizes.touchTarget).semantics {
                stateDescription = if (action.selected) "On" else "Off"
            },
        ) {
            Icon(action.icon, contentDescription = action.label)
        }
    } else {
        IconButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = Modifier.sizeIn(minWidth = Sizes.touchTarget, minHeight = Sizes.touchTarget),
        ) {
            Icon(action.icon, contentDescription = action.label)
        }
    }
}

/** Bottom bar with the primary actions of an editor (labelled icons). */
@Composable
fun EditorBottomBar(actions: List<ToolbarAction>, modifier: Modifier = Modifier) {
    BottomAppBar(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { a -> LabeledAction(a) }
        }
    }
}

@Composable
private fun LabeledAction(a: ToolbarAction) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .sizeIn(minWidth = 64.dp, minHeight = Sizes.touchTarget)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = a.label
            },
    ) {
        IconButton(onClick = a.onClick, enabled = a.enabled) { Icon(a.icon, contentDescription = null) }
        Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Places a context toolbar above the system navigation bar inside a Box. */
@Composable
fun BottomOverlay(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) { content() }
}
