package com.vitosstudios.suite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitosstudios.suite.ui.theme.Corners
import com.vitosstudios.suite.ui.theme.Sizes
import com.vitosstudios.suite.ui.theme.Spacing

/**
 * Placeholder for content the app keeps but does not edit (charts it cannot edit, SmartArt,
 * embedded objects...). Tapping it explains what happens to the content.
 */
@Composable
fun LockedPlaceholder(label: String, explanation: String, modifier: Modifier = Modifier, minHeight: androidx.compose.ui.unit.Dp = 72.dp) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Corners.m)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(minHeight, Sizes.touchTarget))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClickLabel = "Explain") { open = true }
            .padding(Spacing.m)
            .semantics(mergeDescendants = true) { contentDescription = "$label, kept unchanged. Double tap for details." },
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.m))
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("Kept unchanged • tap for details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text(label) },
            text = { Text(explanation) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } },
        )
    }
}

enum class SaveState { SAVED, SAVING, UNSAVED, FAILED }

/** Small status chip in the top bar: saved, saving, unsaved, or failed with retry. */
@Composable
fun SaveStatus(state: SaveState, error: String?, onRetry: () -> Unit) {
    val (icon, text, tint) = when (state) {
        SaveState.SAVED -> Triple(Icons.Default.CloudDone, "Saved", MaterialTheme.colorScheme.onSurfaceVariant)
        SaveState.SAVING -> Triple(Icons.Default.Sync, "Saving", MaterialTheme.colorScheme.onSurfaceVariant)
        SaveState.UNSAVED -> Triple(Icons.Default.Sync, "Unsaved changes", MaterialTheme.colorScheme.onSurfaceVariant)
        SaveState.FAILED -> Triple(Icons.Default.ErrorOutline, "Save failed, retry", MaterialTheme.colorScheme.error)
    }
    var showError by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Sizes.touchTarget)
            .clickable(role = Role.Button, onClickLabel = text) { if (state == SaveState.FAILED) showError = true }
            .semantics { contentDescription = text },
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
    if (showError) {
        AlertDialog(
            onDismissRequest = { showError = false },
            title = { Text("The file could not be saved") },
            text = { Text((error ?: "Unknown error") + "\n\nYour changes are kept in the app and will be saved again automatically.") },
            confirmButton = { TextButton(onClick = { showError = false; onRetry() }) { Text("Retry now") } },
            dismissButton = { TextButton(onClick = { showError = false }) { Text("Close") } },
        )
    }
}

/** Colour choices with names so that TalkBack announces them. */
val NamedColors: List<Pair<String, Color>> = listOf(
    "Black" to Color(0xFF000000),
    "Dark grey" to Color(0xFF595959),
    "Red" to Color(0xFFC00000),
    "Orange" to Color(0xFFE36C09),
    "Gold" to Color(0xFFBF8F00),
    "Green" to Color(0xFF00843D),
    "Teal" to Color(0xFF00807F),
    "Blue" to Color(0xFF1F5FBF),
    "Indigo" to Color(0xFF3F3FA6),
    "Purple" to Color(0xFF7030A0),
    "Pink" to Color(0xFFC2185B),
    "White" to Color(0xFFFFFFFF),
)

@Composable
fun ColorPalette(selected: Color?, onSelect: (Color?) -> Unit, allowAutomatic: Boolean = true) {
    val rows = NamedColors.chunked(6)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (allowAutomatic) {
            TextButton(onClick = { onSelect(null) }) { Text("Automatic") }
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { (name, c) ->
                    val isSel = selected == c
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(Sizes.touchTarget)
                            .clip(CircleShape)
                            .background(c)
                            .border(if (isSel) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable(role = Role.RadioButton) { onSelect(c) }
                            .semantics {
                                contentDescription = name
                                this.selected = isSel
                            },
                    ) {
                        if (isSel) Icon(Icons.Default.Check, contentDescription = null, tint = if (c.luminance() > 0.5f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Slider with a label and current value, announced with its value for TalkBack. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit = {},
    format: (Float) -> String = { "%.0f".format(it) },
    steps: Int = 0,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics { stateDescription = "$label ${format(value)}" },
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(Spacing.xl),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.l))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.s))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(Spacing.l))
            action()
        }
    }
}

/** Rounded badge with the technical format name (DOCX, XLSX...). */
@Composable
fun FormatBadge(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(Corners.s), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs))
    }
}

/** Simple confirmation dialog for destructive actions. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
