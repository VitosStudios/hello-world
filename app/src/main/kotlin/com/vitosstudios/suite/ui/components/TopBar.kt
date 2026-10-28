package com.vitosstudios.suite.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitosstudios.suite.ui.theme.Sizes
import com.vitosstudios.suite.ui.theme.Spacing

/**
 * Top bar used by every screen. The search button turns the bar into a command search that finds
 * every function of the current screen (and, on the home screen, files).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuiteTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    commands: List<Command> = emptyList(),
    searchTargets: (String) -> List<SearchTarget> = { emptyList() },
    searchHint: String = "Search commands",
    actions: @Composable () -> Unit = {},
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    if (searching) {
        CommandSearch(
            commands = commands,
            searchTargets = searchTargets,
            hint = searchHint,
            onClose = { searching = false },
        )
        return
    }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = {
            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, contentDescription = searchHint) }
            actions()
        },
    )
}

/** Full-width search over commands and other targets. Results run on tap or with the IME action. */
@Composable
fun CommandSearch(
    commands: List<Command>,
    searchTargets: (String) -> List<SearchTarget>,
    hint: String,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val matches = CommandMatcher.filter(commands, query).filter { it.enabled }.take(40)
    val targets = if (query.isBlank()) emptyList() else searchTargets(query).take(20)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    colors = TextFieldDefaults.colors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val first = matches.firstOrNull()
                        if (first != null) { onClose(); first.run() } else targets.firstOrNull()?.let { onClose(); it.open() }
                    }),
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (targets.isNotEmpty()) {
                    item { SectionLabel("Files") }
                    items(targets, key = { "t-" + it.id }) { t ->
                        ResultRow(t.title, t.subtitle, t.icon) { onClose(); t.open() }
                    }
                }
                if (matches.isNotEmpty()) {
                    item { SectionLabel("Commands") }
                    items(matches, key = { "c-" + it.id }) { c ->
                        ResultRow(c.title, c.group, c.icon ?: Icons.Default.Bolt) { onClose(); c.run() }
                    }
                }
                if (matches.isEmpty() && targets.isEmpty()) {
                    item {
                        Text("Nothing matches “$query”", modifier = Modifier.padding(Spacing.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
    )
}

@Composable
private fun ResultRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $subtitle" },
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(Sizes.icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

