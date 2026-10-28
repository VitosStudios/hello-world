package com.vitosstudios.suite.feature.photo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitosstudios.suite.feature.common.CollectMessages
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.photo.AdjustmentKind
import com.vitosstudios.suite.photo.AspectPreset
import com.vitosstudios.suite.photo.CropMath
import com.vitosstudios.suite.photo.CropRect
import com.vitosstudios.suite.photo.FilterPresets
import com.vitosstudios.suite.photo.PhotoExportOptions
import com.vitosstudios.suite.photo.PhotoExportSize
import com.vitosstudios.suite.photo.PhotoFormat
import com.vitosstudios.suite.photo.PhotoGeometry
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.ConfirmDialog
import com.vitosstudios.suite.ui.components.LabeledSlider
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoEditorScreen(onBack: () -> Unit, vm: PhotoViewModel = viewModel(factory = PhotoViewModel.Factory)) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    var exportSheet by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(PhotoExportOptions()) }
    // One launcher per format so the system file picker proposes the right type.
    val exportJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri -> if (uri != null) vm.export(options, uri) }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) vm.export(options, uri) }
    val exportWebp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/webp")) { uri -> if (uri != null) vm.export(options, uri) }
    val exportLauncher = when (options.format) {
        PhotoFormat.JPEG -> exportJpeg
        PhotoFormat.PNG -> exportPng
        PhotoFormat.WEBP -> exportWebp
    }
    val commands = buildList {
        add(Command("crop", "Crop", "Photo", Icons.Default.Crop, listOf("aspect", "trim")) { vm.tool = PhotoTool.CROP })
        add(Command("rotl", "Rotate left", "Photo", Icons.Default.RotateLeft) { vm.rotate(false) })
        add(Command("rotr", "Rotate right", "Photo", Icons.Default.RotateRight) { vm.rotate(true) })
        add(Command("fliph", "Flip horizontally", "Photo", Icons.Default.Flip, listOf("mirror")) { vm.flip(true) })
        add(Command("flipv", "Flip vertically", "Photo", Icons.Default.Flip, listOf("mirror")) { vm.flip(false) })
        add(Command("adjust", "Adjust light and colour", "Photo", Icons.Default.Tune, AdjustmentKind.entries.map { it.label }) { vm.tool = PhotoTool.ADJUST })
        add(Command("filters", "Filter presets", "Photo", Icons.Default.FilterVintage, FilterPresets.ALL.map { it.name }) { vm.tool = PhotoTool.FILTERS })
        FilterPresets.ALL.forEach { p -> add(Command("preset-${p.id}", "Filter: ${p.name}", "Filters", null) { vm.applyPreset(p) }) }
        add(Command("reset", "Reset all edits", "Photo", Icons.Default.RestartAlt) { vm.resetAll() })
        add(Command("export", "Export", "File", Icons.Default.Upload, listOf("save as", "jpg", "png", "webp")) { exportSheet = true })
        add(Command("save", "Save over original", "File", Icons.Default.Save) { confirmSave = true })
        add(Command("undo", "Undo", "Edit", null) { vm.undo() })
        add(Command("redo", "Redo", "Edit", null) { vm.redo() })
    }
    Scaffold(
        topBar = {
            SuiteTopBar(
                title = vm.entry?.displayName ?: "Photo",
                subtitle = vm.entry?.kind?.label,
                onBack = onBack,
                commands = commands,
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { exportSheet = true }) { Icon(Icons.Default.Upload, "Export") }
                },
            )
        },
        bottomBar = {
            Column {
                ToolPanel(vm)
                NavigationBar {
                    NavigationBarItem(selected = vm.tool == PhotoTool.CROP, onClick = { vm.tool = PhotoTool.CROP }, icon = { Icon(Icons.Default.Crop, null) }, label = { Text("Crop") })
                    NavigationBarItem(selected = vm.tool == PhotoTool.ADJUST, onClick = { vm.tool = PhotoTool.ADJUST }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Adjust") })
                    NavigationBarItem(selected = vm.tool == PhotoTool.FILTERS, onClick = { vm.tool = PhotoTool.FILTERS }, icon = { Icon(Icons.Default.FilterVintage, null) }, label = { Text("Filters") })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(Color(0xFF111111))) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (LoadingOrError(vm.preview == null && vm.loadError == null, vm.loadError)) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(Spacing.m), contentAlignment = Alignment.Center) {
                    if (vm.tool == PhotoTool.CROP) CropView(vm) else PreviewView(vm)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    // Press and hold to compare with the original.
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .padding(horizontal = Spacing.s)
                            .pointerInput(Unit) { detectTapGestures(onPress = { vm.showBefore = true; tryAwaitRelease(); vm.showBefore = false }) }
                            .semantics { contentDescription = "Hold to compare with the original" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Compare, null, tint = Color.White)
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Hold to compare", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    TextButton(onClick = { confirmSave = true }) { Text("Save over original", color = Color.White) }
                }
            }
        }
    }
    if (confirmSave) {
        ConfirmDialog(
            title = "Save over the original?",
            text = "The photo is saved in its own format (${vm.entry?.kind?.label}). Use Export to keep the original and create a new file instead.",
            confirm = "Save",
            onConfirm = vm::saveOverOriginal,
            onDismiss = { confirmSave = false },
        )
    }
    if (exportSheet) {
        ModalBottomSheet(onDismissRequest = { exportSheet = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l).padding(bottom = Spacing.xl)) {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Text("Format", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PhotoFormat.entries.forEach { f -> FilterChip(selected = options.format == f, onClick = { options = options.copy(format = f) }, label = { Text(f.extension.uppercase()) }) }
                }
                if (options.format.supportsQuality) {
                    LabeledSlider("Quality", options.quality.toFloat(), 10f..100f, onChange = { options = options.copy(quality = it.toInt()) })
                }
                Text("Size (long edge)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PhotoExportSize.entries.forEach { s -> FilterChip(selected = options.size == s, onClick = { options = options.copy(size = s) }, label = { Text(s.label) }) }
                }
                val out = vm.sourceSize?.let { PhotoGeometry.outputSize(it, vm.state.geometry) }
                if (out != null) Text("Preview size: ${options.targetSize(out).width} × ${options.targetSize(out).height} px (final size scales with the original resolution)", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.m)) {
                    Text("Keep camera and date metadata", modifier = Modifier.weight(1f))
                    Switch(checked = options.keepMetadata, onCheckedChange = { options = options.copy(keepMetadata = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep location", modifier = Modifier.weight(1f))
                    Switch(checked = options.keepLocation, enabled = options.keepMetadata, onCheckedChange = { options = options.copy(keepLocation = it) })
                }
                Button(onClick = { exportSheet = false; exportLauncher.launch(options.fileName(vm.entry?.displayName ?: "photo.jpg")) }, modifier = Modifier.padding(top = Spacing.l)) {
                    Text("Export ${options.format.extension.uppercase()}")
                }
            }
        }
    }
}

@Composable
private fun PreviewView(vm: PhotoViewModel) {
    val bmp = if (vm.showBefore) vm.original else vm.preview
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = if (vm.showBefore) "Original photo" else "Edited photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Shows the rotated, straightened frame with a draggable crop rectangle. */
@Composable
private fun CropView(vm: PhotoViewModel) {
    val frame = vm.frame ?: return
    val crop = vm.state.geometry.crop
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = frame.width.toFloat() / frame.height
        val w = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.width(w).aspectRatio(aspect)) {
            Image(frame.asImageBitmap(), contentDescription = "Photo to crop", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            var active by remember { mutableStateOf(-1) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Crop area. Drag the corners to crop." }
                    .pointerInput(frame, vm.aspect) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val c = vm.state.geometry.crop
                                val corners = listOf(Offset(c.left, c.top), Offset(c.right, c.top), Offset(c.left, c.bottom), Offset(c.right, c.bottom))
                                    .map { Offset(it.x * size.width, it.y * size.height) }
                                val nearest = corners.withIndex().minByOrNull { (it.value - pos).getDistance() }
                                active = if (nearest != null && (nearest.value - pos).getDistance() < 80f) nearest.index else 4
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val c = vm.state.geometry.crop
                                val dx = amount.x / size.width
                                val dy = amount.y / size.height
                                var next = when (active) {
                                    0 -> CropRect(c.left + dx, c.top + dy, c.right, c.bottom)
                                    1 -> CropRect(c.left, c.top + dy, c.right + dx, c.bottom)
                                    2 -> CropRect(c.left + dx, c.top, c.right, c.bottom + dy)
                                    3 -> CropRect(c.left, c.top, c.right + dx, c.bottom + dy)
                                    else -> {
                                        val mx = dx.coerceIn(-c.left, 1f - c.right)
                                        val my = dy.coerceIn(-c.top, 1f - c.bottom)
                                        CropRect(c.left + mx, c.top + my, c.right + mx, c.bottom + my)
                                    }
                                }
                                next = CropRect(next.left.coerceIn(0f, 1f), next.top.coerceIn(0f, 1f), next.right.coerceIn(0f, 1f), next.bottom.coerceIn(0f, 1f))
                                if (next.width > 0.05f && next.height > 0.05f) {
                                    val src = vm.sourceSize
                                    val ratio = src?.let { vm.aspect.ratioFor(PhotoGeometry.frameSizePx(it, vm.state.geometry), it) }
                                    if (ratio != null && src != null && active in 0..3) {
                                        next = CropMath.fit(next, ratio, PhotoGeometry.frameSizePx(src, vm.state.geometry))
                                    }
                                    vm.setCrop(next, live = true)
                                }
                            },
                            onDragEnd = { active = -1; vm.endDrag() },
                            onDragCancel = { active = -1; vm.endDrag() },
                        )
                    },
            ) {
                val l = crop.left * size.width
                val t = crop.top * size.height
                val r = crop.right * size.width
                val b = crop.bottom * size.height
                val shade = Color(0x99000000)
                drawRect(shade, Offset.Zero, GSize(size.width, t))
                drawRect(shade, Offset(0f, b), GSize(size.width, size.height - b))
                drawRect(shade, Offset(0f, t), GSize(l, b - t))
                drawRect(shade, Offset(r, t), GSize(size.width - r, b - t))
                drawRect(Color.White, Offset(l, t), GSize(r - l, b - t), style = Stroke(2.dp.toPx()))
                for (i in 1..2) {
                    drawLine(Color(0x88FFFFFF), Offset(l + (r - l) * i / 3, t), Offset(l + (r - l) * i / 3, b))
                    drawLine(Color(0x88FFFFFF), Offset(l, t + (b - t) * i / 3), Offset(r, t + (b - t) * i / 3))
                }
                val k = 14.dp.toPx()
                for ((x, y) in listOf(l to t, r to t, l to b, r to b)) drawRect(Color.White, Offset(x - k / 2, y - k / 2), GSize(k, k))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolPanel(vm: PhotoViewModel) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        when (vm.tool) {
            PhotoTool.CROP -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.rotate(false) }) { Icon(Icons.Default.RotateLeft, "Rotate left") }
                    IconButton(onClick = { vm.rotate(true) }) { Icon(Icons.Default.RotateRight, "Rotate right") }
                    IconButton(onClick = { vm.flip(true) }) { Icon(Icons.Default.Flip, "Flip horizontally") }
                    IconButton(onClick = { vm.flip(false) }) { Icon(Icons.Default.Flip, "Flip vertically", modifier = Modifier.semantics { }) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.setCrop(CropRect(0f, 0f, 1f, 1f), live = false) }) { Text("Reset crop") }
                }
                LabeledSlider(
                    "Straighten", vm.state.geometry.straightenDegrees, -45f..45f,
                    onChange = { vm.straighten(it) }, onChangeFinished = { vm.endDrag() }, format = { "%.1f°".format(it) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AspectPreset.entries.forEach { p -> FilterChip(selected = vm.aspect == p, onClick = { vm.chooseAspect(p) }, label = { Text(p.label) }) }
                }
            }
            PhotoTool.ADJUST -> {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    AdjustmentKind.entries.forEach { kind ->
                        val value = when (kind) {
                            AdjustmentKind.EXPOSURE -> vm.state.adjustments.exposure
                            AdjustmentKind.CONTRAST -> vm.state.adjustments.contrast
                            AdjustmentKind.HIGHLIGHTS -> vm.state.adjustments.highlights
                            AdjustmentKind.SHADOWS -> vm.state.adjustments.shadows
                            AdjustmentKind.SATURATION -> vm.state.adjustments.saturation
                            AdjustmentKind.WARMTH -> vm.state.adjustments.warmth
                            AdjustmentKind.SHARPNESS -> vm.state.adjustments.sharpness
                        }
                        LabeledSlider(
                            kind.label, value, kind.min..kind.max,
                            onChange = { vm.setAdjustment(kind, it) }, onChangeFinished = { vm.endDrag() },
                            format = { if (kind == AdjustmentKind.EXPOSURE) "%+.1f EV".format(it) else "%+.0f".format(it) },
                        )
                    }
                }
            }
            PhotoTool.FILTERS -> {
                val thumbSource = remember(vm.preview) { vm.preview?.let { android.graphics.Bitmap.createScaledBitmap(it, 160, (160f * it.height / it.width).toInt().coerceAtLeast(1), true) } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), contentPadding = PaddingValues(vertical = Spacing.s)) {
                    items(FilterPresets.ALL, key = { it.id }) { p ->
                        val thumb = remember(thumbSource, p.id) { thumbSource?.let { vm.presetThumbnail(p, it) } }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { vm.applyPreset(p) }.semantics { contentDescription = "Filter ${p.name}" },
                        ) {
                            Box(Modifier.size(80.dp).border(if (vm.state.presetId == p.id) 3.dp else 0.dp, MaterialTheme.colorScheme.primary)) {
                                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Text(p.name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun unusedAlert() = AlertDialog::class
