package com.vitosstudios.suite.feature.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitosstudios.suite.data.FileEntry
import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.feature.common.CollectMessages
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.EmptyState
import com.vitosstudios.suite.ui.components.FormatBadge
import com.vitosstudios.suite.ui.components.SearchTarget
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.theme.Corners
import com.vitosstudios.suite.ui.theme.Sizes
import com.vitosstudios.suite.ui.theme.Spacing

@Composable
fun HomeScreen(
    onOpenFile: (String) -> Unit,
    onCompatibility: (String) -> Unit,
    onAbout: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val files by vm.files.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(vm) { vm.openEvents.collect(onOpenFile) }

    val openAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }
    val launchOpen = { openAny.launch(FileKind.openableMimeTypes) }
    val launchImage = { openImage.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
    val launchVideo = { openVideo.launch(arrayOf("video/mp4")) }

    val templates = NewFileTemplates.all
    val commands = buildList {
        add(Command("open", "Open file", "Files", Icons.Default.FolderOpen, listOf("browse", "storage")) { launchOpen() })
        add(Command("photo", "Edit a photo", "Photo", FileKind.Editor.PHOTO.icon(), listOf("image", "picture", "jpg", "png")) { launchImage() })
        add(Command("video", "Edit a video", "Video", FileKind.Editor.VIDEO.icon(), listOf("mp4", "movie", "clip")) { launchVideo() })
        templates.forEach { t ->
            add(Command("new-${t.id}", "New ${t.title.lowercase()}", "Create", t.kind.editor.icon(), listOf("new", "create", t.kind.label)) { vm.create(t) })
        }
        add(Command("about", "About and licenses", "App", Icons.Default.Info, listOf("license", "privacy")) { onAbout() })
    }
    val searchFiles: (String) -> List<SearchTarget> = { q ->
        files.filter { it.displayName.contains(q, ignoreCase = true) }.map { f ->
            SearchTarget(f.id, f.displayName, f.kind.label, f.kind.editor.icon()) { onOpenFile(f.id) }
        }
    }

    Scaffold(
        topBar = {
            SuiteTopBar(
                title = "ArtCraft Mobile",
                commands = commands,
                searchTargets = searchFiles,
                searchHint = "Search files and commands",
                actions = { IconButton(onClick = onAbout) { Icon(Icons.Default.Info, contentDescription = "About and licenses") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = launchOpen,
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                text = { Text("Open file") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Create new") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        items(templates, key = { it.id }) { t ->
                            TemplateCard(t.title, t.description, t.kind.editor) { vm.create(t) }
                        }
                        item { TemplateCard("Edit a photo", "JPG, PNG or WebP", FileKind.Editor.PHOTO, launchImage) }
                        item { TemplateCard("Edit a video", "MP4 clips", FileKind.Editor.VIDEO, launchVideo) }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Recent files") }
                if (files.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.heightIn(min = 240.dp)) {
                            EmptyState(
                                Icons.Outlined.Description,
                                "No files yet",
                                "Open a DOCX, XLSX, PPTX, PDF, image or MP4 file, or create a new one from a template.",
                            )
                        }
                    }
                } else {
                    items(files, key = { it.id }) { f ->
                        FileRow(
                            f,
                            onOpen = { onOpenFile(f.id) },
                            onPin = { vm.setPinned(f.id, !f.pinned) },
                            onForget = { vm.forget(f.id) },
                            onCompatibility = { onCompatibility(f.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun TemplateCard(title: String, description: String, editor: FileKind.Editor, onClick: () -> Unit) {
    val accent = editor.accent()
    Card(
        onClick = onClick,
        modifier = Modifier.width(156.dp).semantics(mergeDescendants = true) { contentDescription = "$title, $description" },
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(Sizes.touchTarget).clip(RoundedCornerShape(Corners.m)).background(accent.container),
            ) {
                Icon(editor.icon(), contentDescription = null, tint = accent.onContainer)
            }
            Spacer(Modifier.size(Spacing.s))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun FileRow(f: FileEntry, onOpen: () -> Unit, onPin: () -> Unit, onForget: () -> Unit, onCompatibility: () -> Unit) {
    val accent = f.kind.editor.accent()
    var menu by remember { mutableStateOf(false) }
    val location = if (f.uri == null) "On this device" else "From your storage"
    val time = DateUtils.getRelativeTimeSpanString(f.lastOpenedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .semantics(mergeDescendants = true) {
                contentDescription = "${f.displayName}, ${f.kind.label}, $location, opened $time" +
                    (if (f.pendingWriteBack) ", changes not yet saved to the original" else "") + (if (f.pinned) ", pinned" else "")
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(Sizes.thumbnail).clip(RoundedCornerShape(Corners.m)).background(accent.container),
        ) {
            Icon(f.kind.editor.icon(), contentDescription = null, tint = accent.onContainer)
        }
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(f.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(f.kind.label, accent.container, accent.onContainer)
                Spacer(Modifier.width(Spacing.s))
                Text("$location • $time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (f.pinned) Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(Sizes.iconSmall), tint = MaterialTheme.colorScheme.primary)
        if (f.pendingWriteBack) Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(Sizes.iconSmall), tint = MaterialTheme.colorScheme.tertiary)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options for ${f.displayName}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (f.pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onPin() })
                if (f.kind.editor in setOf(FileKind.Editor.DOCUMENTS, FileKind.Editor.SHEETS, FileKind.Editor.SLIDES)) {
                    DropdownMenuItem(text = { Text("Compatibility") }, onClick = { menu = false; onCompatibility() })
                }
                DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; onForget() })
            }
        }
    }
}
