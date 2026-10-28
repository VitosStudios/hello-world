package com.vitosstudios.suite.feature.video

import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vitosstudios.suite.feature.common.CollectMessages
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.feature.docs.shareFile
import com.vitosstudios.suite.ui.components.ColorPalette
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.EditorBottomBar
import com.vitosstudios.suite.ui.components.LabeledSlider
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.components.ToolbarAction
import com.vitosstudios.suite.ui.theme.Spacing
import com.vitosstudios.suite.video.ExportPreset
import com.vitosstudios.suite.video.OverlayAnchor
import com.vitosstudios.suite.video.Timeline
import kotlinx.coroutines.delay
import java.io.File

private enum class VideoSheet { TRIM, VOLUME, MUSIC, TEXT, EXPORT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoEditorScreen(onBack: () -> Unit, vm: VideoViewModel = viewModel(factory = VideoViewModel.Factory)) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    var sheet by remember { mutableStateOf<VideoSheet?>(null) }
    var exported by remember { mutableStateOf<File?>(null) }
    val addClip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addClip) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setMusic) }
    val saveVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val f = exported
        if (uri != null && f != null) vm.writeExport(f, uri)
    }

    val player = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    // Rebuild the preview playlist whenever the clips change.
    LaunchedEffect(vm.timeline.clips) {
        val t = vm.timeline
        val items = t.clips.map { c ->
            MediaItem.Builder().setUri(c.sourceUri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inMs).setEndPositionMs(c.outMs).build(),
            ).build()
        }
        val loc = t.locate(vm.positionMs)
        player.setMediaItems(items, loc?.index ?: 0, loc?.offsetInClipMs ?: 0L)
        player.prepare()
    }
    LaunchedEffect(vm.timeline.clips) {
        // Per-clip volume in the preview.
        while (true) {
            val idx = player.currentMediaItemIndex
            val clip = vm.timeline.clips.getOrNull(idx)
            player.volume = (clip?.effectiveVolume ?: 1f).coerceIn(0f, 1f)
            if (player.isPlaying) vm.positionMs = vm.timeline.clipStartMs(idx.coerceIn(0, (vm.timeline.clips.size - 1).coerceAtLeast(0))) + player.currentPosition
            playing = player.isPlaying
            delay(100)
        }
    }
    fun seek(ms: Long) {
        vm.positionMs = ms
        val loc = vm.timeline.locate(ms) ?: return
        player.seekTo(loc.index, loc.offsetInClipMs)
    }

    val commands = listOf(
        Command("add", "Add clip", "Video", Icons.Default.Add, listOf("join", "append")) { addClip.launch(arrayOf("video/mp4")) },
        Command("split", "Split at playhead", "Video", Icons.Default.ContentCut, listOf("cut")) { vm.split() },
        Command("trim", "Trim clip", "Video", Icons.Default.Crop) { sheet = VideoSheet.TRIM },
        Command("delete", "Delete clip", "Video", Icons.Default.Delete, listOf("remove")) { vm.deleteSelected() },
        Command("mark", "Mark segment start", "Video", null, listOf("range", "in point")) { vm.markSegmentStart() },
        Command("delseg", "Delete segment (mark to playhead)", "Video", Icons.Default.Delete, listOf("cut", "range", "remove")) { vm.deleteSegment() },
        Command("left", "Move clip earlier", "Video", Icons.AutoMirrored.Filled.ArrowBack, listOf("reorder")) { vm.moveSelected(-1) },
        Command("right", "Move clip later", "Video", Icons.AutoMirrored.Filled.ArrowForward, listOf("reorder")) { vm.moveSelected(1) },
        Command("volume", "Clip volume", "Audio", Icons.Default.VolumeUp, listOf("mute", "sound")) { sheet = VideoSheet.VOLUME },
        Command("music", "Background music", "Audio", Icons.Default.LibraryMusic, listOf("audio", "song")) { sheet = VideoSheet.MUSIC },
        Command("text", "Text overlay", "Video", Icons.Default.TextFields, listOf("title", "caption")) { sheet = VideoSheet.TEXT },
        Command("export", "Export video", "File", Icons.Default.Upload, listOf("1080p", "720p", "vertical", "save")) { sheet = VideoSheet.EXPORT },
        Command("undo", "Undo", "Edit", null) { vm.undo() },
        Command("redo", "Redo", "Edit", null) { vm.redo() },
    )

    Scaffold(
        topBar = {
            SuiteTopBar(
                title = vm.entry?.displayName ?: "Video",
                subtitle = "MP4 • " + formatMs(vm.timeline.durationMs),
                onBack = onBack,
                commands = commands,
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { sheet = VideoSheet.EXPORT }) { Icon(Icons.Default.Upload, "Export video") }
                },
            )
        },
        bottomBar = {
            EditorBottomBar(listOf(
                ToolbarAction("Add", Icons.Default.Add) { addClip.launch(arrayOf("video/mp4")) },
                ToolbarAction("Split", Icons.Default.ContentCut) { vm.split() },
                ToolbarAction("Trim", Icons.Default.Crop) { sheet = VideoSheet.TRIM },
                ToolbarAction("Volume", Icons.Default.VolumeUp) { sheet = VideoSheet.VOLUME },
                ToolbarAction("Music", Icons.Default.LibraryMusic) { sheet = VideoSheet.MUSIC },
                ToolbarAction("Text", Icons.Default.TextFields) { sheet = VideoSheet.TEXT },
            ))
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.exporting) {
                LinearProgressIndicator(progress = { vm.exportProgress }, modifier = Modifier.fillMaxWidth())
                Text("Exporting… ${(vm.exportProgress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.l))
            }
            if (LoadingOrError(vm.timeline.clips.isEmpty() && vm.loadError == null, vm.loadError)) {
                // Preview with overlays drawn on top at their positions.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    val aspect = vm.timeline.preset.width.toFloat() / vm.timeline.preset.height
                    val w = minOf(maxWidth, maxHeight * aspect)
                    Box(Modifier.width(w).aspectRatio(aspect)) {
                        AndroidView(factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } }, modifier = Modifier.fillMaxSize())
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            vm.timeline.overlaysAt(vm.positionMs).forEach { o ->
                                Text(
                                    o.text,
                                    color = Color(o.colorArgb),
                                    fontSize = (o.fontSizeSp * (maxWidth.value / 360f)).sp,
                                    fontWeight = if (o.bold) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.offset(x = maxWidth * o.x - maxWidth * 0.2f * o.anchor.fx * 2, y = maxHeight * o.y - 24.dp * o.anchor.fy * 2),
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.s),
                    ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White) }
                }
                // Scrubber.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.l)) {
                    Text(formatMs(vm.positionMs), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = vm.positionMs.toFloat(),
                        onValueChange = { seek(it.toLong()) },
                        valueRange = 0f..vm.timeline.durationMs.coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.s).semantics { contentDescription = "Playhead" },
                    )
                    Text(formatMs(vm.timeline.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                TimelineStrip(vm.timeline, vm.selectedClip, vm.positionMs) { id ->
                    vm.selectedClip = id
                    seek(vm.timeline.clipStartMs(vm.timeline.clipIndex(id)))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { vm.markSegmentStart() }) { Text(vm.segmentStartMs?.let { "Mark: " + formatMs(it) } ?: "Mark start") }
                    TextButton(onClick = { vm.deleteSegment() }, enabled = vm.segmentStartMs != null) { Text("Delete segment") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { vm.moveSelected(-1) }, enabled = vm.selectedIndex() > 0) { Text("Move earlier") }
                    TextButton(onClick = { vm.deleteSelected() }, enabled = vm.selectedIndex() >= 0) { Text("Delete clip") }
                    TextButton(onClick = { vm.moveSelected(1) }, enabled = vm.selectedIndex() in 0 until vm.timeline.clips.size - 1) { Text("Move later") }
                }
            }
        }
    }

    sheet?.let { s ->
        ModalBottomSheet(onDismissRequest = { sheet = null; vm.endDrag() }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l).padding(bottom = Spacing.xl)) {
                when (s) {
                    VideoSheet.TRIM -> {
                        val i = vm.selectedIndex().takeIf { it >= 0 } ?: vm.timeline.locate(vm.positionMs)?.index ?: 0
                        val c = vm.timeline.clips.getOrNull(i)
                        Text("Trim clip ${i + 1}", style = MaterialTheme.typography.titleMedium)
                        if (c != null) {
                            Text("${formatMs(c.inMs)} – ${formatMs(c.outMs)} of ${formatMs(c.sourceDurationMs)}")
                            RangeSlider(
                                value = c.inMs.toFloat()..c.outMs.toFloat(),
                                onValueChange = { r -> vm.drag { it.trim(i, r.start.toLong(), r.endInclusive.toLong()) } },
                                onValueChangeFinished = { vm.endDrag() },
                                valueRange = 0f..c.sourceDurationMs.toFloat(),
                                modifier = Modifier.semantics { contentDescription = "Clip start and end" },
                            )
                        }
                    }
                    VideoSheet.VOLUME -> {
                        val i = vm.selectedIndex().takeIf { it >= 0 } ?: vm.timeline.locate(vm.positionMs)?.index ?: 0
                        val c = vm.timeline.clips.getOrNull(i)
                        Text("Volume of clip ${i + 1}", style = MaterialTheme.typography.titleMedium)
                        if (c != null) {
                            LabeledSlider("Volume", c.volume * 100, 0f..200f, onChange = { v -> vm.drag { it.setClipVolume(i, v / 100f) } }, onChangeFinished = { vm.endDrag() }, format = { "${it.toInt()} %" })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Mute", modifier = Modifier.weight(1f))
                                Switch(checked = c.muted, onCheckedChange = { m -> vm.apply { it.setClipMuted(i, m) } })
                            }
                        }
                    }
                    VideoSheet.MUSIC -> {
                        val m = vm.timeline.music
                        Text("Background music", style = MaterialTheme.typography.titleMedium)
                        Text("One audio track is mixed under the video when you export.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            OutlinedButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) { Text(if (m == null) "Choose music" else "Replace music") }
                            if (m != null) TextButton(onClick = { vm.apply { it.removeMusic() } }) { Text("Remove") }
                        }
                        if (m != null) {
                            LabeledSlider("Music volume", m.volume * 100, 0f..200f, onChange = { v -> vm.drag { it.setMusicVolume(v / 100f) } }, onChangeFinished = { vm.endDrag() }, format = { "${it.toInt()} %" })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Loop music", modifier = Modifier.weight(1f))
                                Switch(checked = m.loop, onCheckedChange = { l -> vm.apply { it.setMusicLoop(l) } })
                            }
                        }
                    }
                    VideoSheet.TEXT -> TextOverlayPanel(vm)
                    VideoSheet.EXPORT -> {
                        Text("Export video", style = MaterialTheme.typography.titleMedium)
                        Text("The original clips are not changed; a new MP4 file is created.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            ExportPreset.entries.forEach { p ->
                                FilterChip(selected = vm.timeline.preset == p, onClick = { vm.apply { it.withPreset(p) } }, label = { Text("${p.label} (${p.width}×${p.height})") })
                            }
                        }
                        Button(
                            enabled = !vm.exporting,
                            onClick = {
                                sheet = null
                                player.pause()
                                vm.startExport(context, vm.timeline.preset) { f -> exported = f; saveVideo.launch(f.name) }
                            },
                            modifier = Modifier.padding(top = Spacing.l),
                        ) { Text("Export ${vm.timeline.preset.label}") }
                        exported?.let { f ->
                            TextButton(onClick = { shareFile(context, f, "video/mp4") }) { Text("Share last export") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineStrip(t: Timeline, selected: String?, positionMs: Long, onSelect: (String) -> Unit) {
    val total = t.durationMs.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        val width = maxOf(maxWidth, (t.clips.size * 72).dp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(width).fillMaxHeight()) {
                Row(Modifier.fillMaxSize()) {
                    t.clips.forEachIndexed { i, c ->
                        val isSel = c.id == selected
                        Box(
                            Modifier
                                .width(width * (c.durationMs.toFloat() / total))
                                .fillMaxHeight()
                                .padding(end = 2.dp)
                                .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                                .border(if (isSel) 2.dp else 0.dp, MaterialTheme.colorScheme.primary)
                                .clickable(onClickLabel = "Select clip") { onSelect(c.id) }
                                .semantics { this.selected = isSel; contentDescription = "Clip ${i + 1}, ${formatMs(c.durationMs)}" + if (c.muted) ", muted" else "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}\n${formatMs(c.durationMs)}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                    }
                }
                // Playhead.
                Box(Modifier.offset(x = width * (positionMs.toFloat() / total)).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextOverlayPanel(vm: VideoViewModel) {
    var text by remember { mutableStateOf("") }
    Text("Text overlays", style = MaterialTheme.typography.titleMedium)
    vm.timeline.overlays.forEach { o ->
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s).border(1.dp, MaterialTheme.colorScheme.outlineVariant).padding(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("“${o.text}” ${formatMs(o.startMs)}–${formatMs(o.endMs)}", modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.apply { it.removeOverlay(o.id) } }) { Icon(Icons.Default.Delete, "Remove overlay") }
            }
            RangeSlider(
                value = o.startMs.toFloat()..o.endMs.toFloat(),
                onValueChange = { r -> vm.drag { t -> t.updateOverlay(o.id) { it.copy(startMs = r.start.toLong(), endMs = r.endInclusive.toLong()) } } },
                onValueChangeFinished = { vm.endDrag() },
                valueRange = 0f..vm.timeline.durationMs.coerceAtLeast(1).toFloat(),
            )
            LabeledSlider("Size", o.fontSizeSp, 12f..96f, onChange = { v -> vm.drag { t -> t.updateOverlay(o.id) { it.copy(fontSizeSp = v) } } }, onChangeFinished = { vm.endDrag() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf(OverlayAnchor.TOP_CENTER to (0.5f to 0.1f), OverlayAnchor.CENTER to (0.5f to 0.5f), OverlayAnchor.BOTTOM_CENTER to (0.5f to 0.9f),
                    OverlayAnchor.TOP_LEFT to (0.05f to 0.08f), OverlayAnchor.BOTTOM_RIGHT to (0.95f to 0.92f)).forEach { (a, pos) ->
                    FilterChip(selected = o.anchor == a, onClick = { vm.apply { t -> t.updateOverlay(o.id) { it.copy(anchor = a, x = pos.first, y = pos.second) } } },
                        label = { Text(a.name.lowercase().replace('_', ' ')) })
                }
                FilterChip(selected = o.bold, onClick = { vm.apply { t -> t.updateOverlay(o.id) { it.copy(bold = !it.bold) } } }, label = { Text("Bold") })
            }
            ColorPalette(selected = Color(o.colorArgb), onSelect = { c -> c?.let { col -> vm.apply { t -> t.updateOverlay(o.id) { it.copy(colorArgb = col.toArgb()) } } } }, allowAutomatic = false)
        }
    }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("New text") }, modifier = Modifier.fillMaxWidth())
    Button(
        enabled = text.isNotBlank(),
        onClick = {
            val start = vm.positionMs
            vm.apply { it.addOverlay(text, start, minOf(start + 3000, it.durationMs)) }
            text = ""
        },
        modifier = Modifier.padding(top = Spacing.s),
    ) { Text("Add at playhead (3 s)") }
}

fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

@Suppress("unused")
private val keepPlayer = Player::class
