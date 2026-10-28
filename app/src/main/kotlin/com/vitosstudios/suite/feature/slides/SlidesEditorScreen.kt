package com.vitosstudios.suite.feature.slides

import android.app.Activity
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop54
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitosstudios.suite.appContainer
import com.vitosstudios.suite.feature.common.EditorLifecycle
import com.vitosstudios.suite.feature.common.EditorTopActions
import com.vitosstudios.suite.feature.common.ImageLoader
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.feature.docs.shareFile
import com.vitosstudios.suite.pptx.BulletStyle
import com.vitosstudios.suite.pptx.EmuRect
import com.vitosstudios.suite.pptx.LINE_BREAK
import com.vitosstudios.suite.pptx.RunFormatPatch
import com.vitosstudios.suite.pptx.ShapeType
import com.vitosstudios.suite.pptx.SlideView
import com.vitosstudios.suite.pptx.TextAlign
import com.vitosstudios.suite.ui.components.ColorPalette
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.ContextToolbar
import com.vitosstudios.suite.ui.components.EditorBottomBar
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.components.ToolbarAction
import com.vitosstudios.suite.ui.components.ToolbarButton
import com.vitosstudios.suite.ui.theme.Sizes
import com.vitosstudios.suite.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SlidesSheet { LAYOUT_NEW, LAYOUT_CHANGE, SHAPES, NOTES, FILL, LINE, TEXT_COLOR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlidesEditorScreen(
    onBack: () -> Unit,
    onCompatibility: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPresent: (Int) -> Unit = {},
    vm: SlidesViewModel = viewModel(factory = SlidesViewModel.Factory),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    EditorLifecycle(vm, snackbar)
    var sheet by remember { mutableStateOf<SlidesSheet?>(null) }
    var menu by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { ImageLoader.readUri(context, uri) } ?: return@launch vm.notify("The image could not be read")
            val info = ImageLoader.bounds(bytes)
            if (info.width <= 0) return@launch vm.notify("This image format is not supported")
            vm.insertImage(bytes, ImageLoader.extensionFor(info.mime))
        }
    }
    val pendingPdf = remember { arrayOfNulls<File>(1) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingPdf[0]
        if (uri != null && f != null) scope.launch { vm.notify(context.appContainer.files.writeTo(uri, f) ?: "PDF exported") }
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )) { uri ->
        if (uri != null) scope.launch { vm.flush(); vm.notify(context.appContainer.files.exportCopy(vm.fileId, uri) ?: "Copy saved") }
    }
    fun renderPdf(): File? {
        val d = vm.document ?: return null
        val out = File(context.appContainer.files.exportDir(), (vm.entry?.displayName ?: "Slides").substringBeforeLast('.') + ".pdf")
        return try {
            exportSlidesPdf(d, vm.slides().filter { !it.hidden }, out)
            out
        } catch (e: Exception) {
            vm.notify("PDF export failed: ${e.message}"); null
        }
    }
    val exportPdf = { renderPdf()?.let { pendingPdf[0] = it; savePdf.launch(it.name) } }

    if (vm.presenting && vm.document != null) {
        PresentationOverlay(vm)
        return
    }

    val commands = listOf(
        Command("new", "New slide", "Slides", Icons.Default.Add, listOf("add", "layout")) { sheet = SlidesSheet.LAYOUT_NEW },
        Command("dup", "Duplicate slide", "Slides", Icons.Default.ContentCopy, listOf("copy")) { vm.duplicateSlide() },
        Command("del", "Delete slide", "Slides", Icons.Default.Delete, listOf("remove")) { vm.deleteSlide() },
        Command("up", "Move slide earlier", "Slides", null, listOf("reorder")) { vm.moveSlide(vm.current, vm.current - 1) },
        Command("down", "Move slide later", "Slides", null, listOf("reorder")) { vm.moveSlide(vm.current, vm.current + 1) },
        Command("layout", "Change layout", "Slides", Icons.Default.ViewQuilt) { sheet = SlidesSheet.LAYOUT_CHANGE },
        Command("hide", "Hide or show slide", "Slides", Icons.Default.Visibility) { vm.toggleHidden() },
        Command("textbox", "Insert text box", "Insert", Icons.Default.TextFields) { vm.insertTextBox() },
        Command("image", "Insert image", "Insert", Icons.Outlined.Photo, listOf("picture")) { pickImage.launch(arrayOf("image/*")) },
        Command("rect", "Insert rectangle", "Insert", Icons.Default.Crop54, listOf("shape")) { vm.insertShape(ShapeType.RECTANGLE) },
        Command("ellipse", "Insert ellipse", "Insert", null, listOf("shape", "circle", "oval")) { vm.insertShape(ShapeType.ELLIPSE) },
        Command("line", "Insert line", "Insert", null, listOf("shape")) { vm.insertShape(ShapeType.LINE) },
        Command("arrow", "Insert arrow", "Insert", null, listOf("shape")) { vm.insertShape(ShapeType.ARROW) },
        Command("notes", "Speaker notes", "Slides", Icons.AutoMirrored.Filled.Notes) { sheet = SlidesSheet.NOTES },
        Command("present", "Present from this slide", "Present", Icons.Default.PlayArrow, listOf("slideshow", "start")) { vm.present(vm.current) },
        Command("present0", "Present from the beginning", "Present", Icons.Default.PlayArrow, listOf("slideshow")) { vm.present(0) },
        Command("pdf", "Export as PDF", "File", Icons.Default.PictureAsPdf) { exportPdf() },
        Command("copy", "Save a copy", "File", null) { saveCopy.launch(vm.entry?.displayName ?: "Slides.pptx") },
        Command("compat", "Compatibility", "File", Icons.Default.Info) { onCompatibility() },
        Command("undo", "Undo", "Edit", null) { vm.undo() },
        Command("redo", "Redo", "Edit", null) { vm.redo() },
    )

    Scaffold(
        topBar = {
            SuiteTopBar(
                title = vm.entry?.displayName ?: "Presentation",
                subtitle = "PPTX • slide ${vm.current + 1} of ${vm.slides().size}",
                onBack = { vm.flush(); onBack() },
                commands = commands,
                actions = {
                    EditorTopActions(vm)
                    IconButton(onClick = { vm.present(vm.current) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Present") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Change layout") }, onClick = { menu = false; sheet = SlidesSheet.LAYOUT_CHANGE })
                            DropdownMenuItem(text = { Text(if (vm.currentSlide()?.hidden == true) "Show slide" else "Hide slide") }, onClick = { menu = false; vm.toggleHidden() })
                            DropdownMenuItem(text = { Text("Export as PDF") }, onClick = { menu = false; exportPdf() })
                            DropdownMenuItem(text = { Text("Share as PDF") }, onClick = { menu = false; renderPdf()?.let { shareFile(context, it, "application/pdf") } })
                            DropdownMenuItem(text = { Text("Save a copy") }, onClick = { menu = false; saveCopy.launch(vm.entry?.displayName ?: "Slides.pptx") })
                            DropdownMenuItem(text = { Text("Compatibility") }, onClick = { menu = false; onCompatibility() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Box(Modifier.imePadding()) {
                val shape = vm.selectedShape()
                if (shape != null && !vm.editingText) {
                    ContextToolbar(true, buildList {
                        if (shape.hasEditableText) add(ToolbarAction("Edit text", Icons.Default.Edit) { vm.editingText = true })
                        add(ToolbarAction("Fill colour", Icons.Default.FormatColorFill) { sheet = SlidesSheet.FILL })
                        add(ToolbarAction("Outline colour", Icons.Default.Square) { sheet = SlidesSheet.LINE })
                        add(ToolbarAction("Rotate", Icons.Default.RotateRight) { vm.rotateSelected(15.0) })
                        add(ToolbarAction("Delete", Icons.Default.Delete) { vm.deleteSelected() })
                        add(ToolbarAction("Done", Icons.Default.Check) { vm.select(null) })
                    })
                } else if (!vm.editingText) {
                    EditorBottomBar(listOf(
                        ToolbarAction("New slide", Icons.Default.Add) { sheet = SlidesSheet.LAYOUT_NEW },
                        ToolbarAction("Text", Icons.Default.TextFields) { vm.insertTextBox() },
                        ToolbarAction("Image", Icons.Outlined.Photo) { pickImage.launch(arrayOf("image/*")) },
                        ToolbarAction("Shape", Icons.Default.Crop54) { sheet = SlidesSheet.SHAPES },
                        ToolbarAction("Notes", Icons.AutoMirrored.Filled.Notes) { sheet = SlidesSheet.NOTES },
                    ))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            if (LoadingOrError(vm.document == null, vm.loadError)) {
                val renderer = remember(vm.document) { SlideRenderer(vm.document!!) }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth > 700.dp && maxWidth > maxHeight
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            Filmstrip(vm, renderer, vertical = true, modifier = Modifier.width(180.dp).fillMaxHeight())
                            Column(Modifier.weight(1f)) {
                                SlideStage(vm, renderer, Modifier.weight(1f))
                                if (vm.editingText) TextPanel(vm, Modifier.height(260.dp)) { sheet = SlidesSheet.TEXT_COLOR }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            SlideStage(vm, renderer, Modifier.weight(1f))
                            if (vm.editingText) TextPanel(vm, Modifier.weight(1f)) { sheet = SlidesSheet.TEXT_COLOR }
                            else Filmstrip(vm, renderer, vertical = false, modifier = Modifier.fillMaxWidth().height(Sizes.filmstripThumb + 24.dp))
                        }
                    }
                }
            }
        }
    }

    sheet?.let { s ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l).padding(bottom = Spacing.xl)) {
                when (s) {
                    SlidesSheet.LAYOUT_NEW, SlidesSheet.LAYOUT_CHANGE -> {
                        Text(if (s == SlidesSheet.LAYOUT_NEW) "New slide: choose a layout" else "Change layout", style = MaterialTheme.typography.titleMedium)
                        vm.layouts().forEach { l ->
                            ListItem(
                                headlineContent = { Text(l.name) },
                                supportingContent = { Text(l.type ?: "custom") },
                                leadingContent = { Icon(Icons.Default.ViewQuilt, null) },
                                modifier = Modifier.clickable {
                                    sheet = null
                                    if (s == SlidesSheet.LAYOUT_NEW) vm.addSlide(l) else vm.changeLayout(l)
                                },
                            )
                        }
                    }
                    SlidesSheet.SHAPES -> {
                        Text("Insert shape", style = MaterialTheme.typography.titleMedium)
                        listOf(ShapeType.RECTANGLE to "Rectangle", ShapeType.ELLIPSE to "Ellipse", ShapeType.LINE to "Line", ShapeType.ARROW to "Arrow").forEach { (t, n) ->
                            ListItem(headlineContent = { Text(n) }, modifier = Modifier.clickable { sheet = null; vm.insertShape(t) })
                        }
                    }
                    SlidesSheet.NOTES -> NotesEditor(vm)
                    SlidesSheet.FILL -> {
                        Text("Fill colour", style = MaterialTheme.typography.titleMedium)
                        ColorPalette(selected = null, onSelect = { c -> vm.setFill(c?.toArgb()?.and(0xFFFFFF)); sheet = null }, allowAutomatic = true)
                    }
                    SlidesSheet.LINE -> {
                        Text("Outline colour", style = MaterialTheme.typography.titleMedium)
                        ColorPalette(selected = null, onSelect = { c -> vm.setLine(c?.toArgb()?.and(0xFFFFFF)); sheet = null }, allowAutomatic = true)
                    }
                    SlidesSheet.TEXT_COLOR -> {
                        Text("Text colour", style = MaterialTheme.typography.titleMedium)
                        ColorPalette(selected = null, onSelect = { c -> c?.let { vm.format(RunFormatPatch(rgb = it.toArgb() and 0xFFFFFF)) }; sheet = null }, allowAutomatic = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesEditor(vm: SlidesViewModel) {
    val v = vm.version
    var text by remember(vm.current) { mutableStateOf(vm.notes()) }
    LaunchedEffect(v) { val n = vm.notes(); if (n != text) text = n }
    Text("Speaker notes for slide ${vm.current + 1}", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; vm.setNotes(it) },
        modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = Spacing.s),
        placeholder = { Text("Notes are shown only to the presenter") },
    )
}

/** The current slide, fitted, with selection, move and resize. */
@Composable
private fun SlideStage(vm: SlidesViewModel, renderer: SlideRenderer, modifier: Modifier) {
    val v = vm.version
    val slide = vm.currentSlide() ?: return
    val (slideW, slideH) = vm.document?.slideSize ?: return
    var drag by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(Spacing.m), contentAlignment = Alignment.Center) {
        val aspect = slideW.toFloat() / slideH
        val w = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.width(w).aspectRatio(aspect).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Slide ${slide.index + 1}" + slideText(slide) }
                    .pointerInput(slide.partName, v) {
                        detectTapGestures(
                            onTap = { pos ->
                                val s = slideW / size.width.toFloat()
                                vm.select(vm.document?.hitTest(slide.index, (pos.x * s).toLong(), (pos.y * s).toLong()))
                            },
                            onDoubleTap = { pos ->
                                val s = slideW / size.width.toFloat()
                                val hit = vm.document?.hitTest(slide.index, (pos.x * s).toLong(), (pos.y * s).toLong())
                                vm.select(hit)
                                if (hit?.hasEditableText == true) vm.editingText = true
                            },
                        )
                    }
                    .pointerInput(vm.selectedShapeId, slide.partName, v) {
                        val sel = vm.selectedShape() ?: return@pointerInput
                        detectDragGestures(
                            onDrag = { change, amount -> change.consume(); drag += amount },
                            onDragEnd = {
                                val s = slideW / size.width.toFloat()
                                val b = sel.bounds
                                vm.setBounds(sel.id, EmuRect((b.x + drag.x * s).toLong(), (b.y + drag.y * s).toLong(), b.cx, b.cy))
                                drag = Offset.Zero
                            },
                            onDragCancel = { drag = Offset.Zero },
                        )
                    },
            ) {
                drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, slide, size.width) }
                vm.selectedShape()?.let { s ->
                    val k = size.width / slideW
                    val left = s.bounds.x * k + drag.x
                    val top = s.bounds.y * k + drag.y
                    drawRect(
                        color = Color(0xFF1A73E8),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(s.bounds.cx * k + resize.x, maxOf(s.bounds.cy * k + resize.y, 2f)),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            // Resize handle (bottom-right) for the selected shape.
            vm.selectedShape()?.let { s ->
                val density = androidx.compose.ui.platform.LocalDensity.current
                val k = with(density) { w.toPx() } / slideW
                val hx = (s.bounds.right * k + drag.x).toInt()
                val hy = (s.bounds.bottom * k + drag.y).toInt()
                val handle = Sizes.touchTarget
                val half = with(density) { (handle / 2).roundToPx() }
                Box(
                    Modifier
                        .offset { IntOffset(hx - half + resize.x.toInt(), hy - half + resize.y.toInt()) }
                        .size(handle)
                        .semantics { contentDescription = "Resize handle" }
                        .pointerInput(s.id, v) {
                            detectDragGestures(
                                onDrag = { change, amount -> change.consume(); resize += amount },
                                onDragEnd = {
                                    val inv = 1f / k
                                    val b = s.bounds
                                    vm.setBounds(s.id, EmuRect(b.x, b.y, maxOf(12700L * 4, (b.cx + resize.x * inv).toLong()), maxOf(0L, (b.cy + resize.y * inv).toLong())))
                                    resize = Offset.Zero
                                },
                                onDragCancel = { resize = Offset.Zero },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(14.dp).background(Color(0xFF1A73E8)))
                }
            }
        }
    }
}

private fun slideText(slide: SlideView): String {
    val texts = slide.slideShapes.mapNotNull { it.text?.plainText?.takeIf(String::isNotBlank) }
    return if (texts.isEmpty()) "" else ": " + texts.joinToString(". ").take(300)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Filmstrip(vm: SlidesViewModel, renderer: SlideRenderer, vertical: Boolean, modifier: Modifier) {
    val v = vm.version
    val slides = vm.slides()
    val item: @Composable (Int, SlideView) -> Unit = { i, s ->
        var menu by remember { mutableStateOf(false) }
        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(Spacing.xs)
                    .combinedClickable(onClick = { vm.goTo(i) }, onLongClick = { vm.goTo(i); menu = true }, onLongClickLabel = "Slide options")
                    .semantics {
                        contentDescription = "Slide ${i + 1}" + (if (s.hidden) ", hidden" else "") + slideText(s)
                        selected = i == vm.current
                    },
            ) {
                Box(
                    Modifier
                        .width(Sizes.filmstripThumb * renderer.aspect.coerceAtMost(1.8f) / 1.33f)
                        .aspectRatio(renderer.aspect)
                        .border(if (i == vm.current) 3.dp else 1.dp, if (i == vm.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, s, size.width, imageMaxEdge = 480) }
                        if (s.hidden) drawRect(Color(0x88FFFFFF))
                    }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicateSlide() })
                DropdownMenuItem(text = { Text("Move earlier") }, enabled = i > 0, onClick = { menu = false; vm.moveSlide(i, i - 1) })
                DropdownMenuItem(text = { Text("Move later") }, enabled = i < slides.size - 1, onClick = { menu = false; vm.moveSlide(i, i + 1) })
                DropdownMenuItem(text = { Text(if (s.hidden) "Show slide" else "Hide slide") }, onClick = { menu = false; vm.toggleHidden() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.deleteSlide() })
            }
        }
    }
    Surface(tonalElevation = 2.dp, modifier = modifier) {
        if (vertical) {
            LazyColumn(contentPadding = PaddingValues(Spacing.s)) { itemsIndexed(slides, key = { _, s -> s.partName }) { i, s -> item(i, s) } }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(slides, key = { _, s -> s.partName }) { i, s -> item(i, s) }
            }
        }
    }
}

/** Text editing for the selected shape: one field per paragraph plus a formatting row. */
@Composable
private fun TextPanel(vm: SlidesViewModel, modifier: Modifier, openTextColor: () -> Unit) {
    val v = vm.version
    val shape = vm.selectedShape()
    val body = shape?.text
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ToolbarButton(ToolbarAction("Bold", Icons.Default.FormatBold) { vm.format(RunFormatPatch(bold = !(firstRun(vm)?.bold ?: false))) })
                ToolbarButton(ToolbarAction("Italic", Icons.Default.FormatItalic) { vm.format(RunFormatPatch(italic = !(firstRun(vm)?.italic ?: false))) })
                ToolbarButton(ToolbarAction("Underline", Icons.Default.FormatUnderlined) { vm.format(RunFormatPatch(underline = !(firstRun(vm)?.underline ?: false))) })
                ToolbarButton(ToolbarAction("Strikethrough", Icons.Default.FormatStrikethrough) { vm.format(RunFormatPatch(strike = !(firstRun(vm)?.strike ?: false))) })
                ToolbarButton(ToolbarAction("Smaller text", Icons.Default.Remove) { vm.format(RunFormatPatch(sizePt = ((firstRun(vm)?.sizePt ?: 18.0) - 2).coerceAtLeast(6.0))) })
                ToolbarButton(ToolbarAction("Larger text", Icons.Default.Add) { vm.format(RunFormatPatch(sizePt = (firstRun(vm)?.sizePt ?: 18.0) + 2)) })
                ToolbarButton(ToolbarAction("Text colour", Icons.Default.FormatColorFill) { openTextColor() })
                ToolbarButton(ToolbarAction("Align left", Icons.AutoMirrored.Filled.FormatAlignLeft) { vm.alignment(TextAlign.LEFT) })
                ToolbarButton(ToolbarAction("Centre", Icons.Default.FormatAlignCenter) { vm.alignment(TextAlign.CENTER) })
                ToolbarButton(ToolbarAction("Align right", Icons.AutoMirrored.Filled.FormatAlignRight) { vm.alignment(TextAlign.RIGHT) })
                ToolbarButton(ToolbarAction("Bullets", Icons.AutoMirrored.Filled.FormatListBulleted) { vm.bullets(BulletStyle.Char("•")) })
                ToolbarButton(ToolbarAction("Numbering", Icons.Default.FormatListNumbered) { vm.bullets(BulletStyle.Numbered()) })
                ToolbarButton(ToolbarAction("No bullets", Icons.Default.Close) { vm.bullets(BulletStyle.None) })
                ToolbarButton(ToolbarAction("Decrease level", Icons.AutoMirrored.Filled.FormatIndentDecrease) { vm.level(-1) })
                ToolbarButton(ToolbarAction("Increase level", Icons.AutoMirrored.Filled.FormatIndentIncrease) { vm.level(1) })
                ToolbarButton(ToolbarAction("Done", Icons.Default.Check) { vm.editingText = false })
            }
            if (body == null) {
                Text("This shape has no text.", modifier = Modifier.padding(Spacing.l))
                return@Column
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                body.paragraphs.forEachIndexed { i, p ->
                    key(shape.id, i) {
                        ParagraphField(vm, i, p.text, p.bullet.label, v)
                    }
                }
            }
        }
    }
}

private fun firstRun(vm: SlidesViewModel) = vm.selectedShape()?.text?.paragraphs?.let { ps ->
    (vm.textParagraph?.let { ps.getOrNull(it) } ?: ps.firstOrNull())?.runs?.firstOrNull()
}

@Composable
private fun ParagraphField(vm: SlidesViewModel, index: Int, modelText: String, bullet: String, version: Long) {
    val display = modelText.replace(LINE_BREAK, ' ')
    var field by remember { mutableStateOf(TextFieldValue(display, TextRange(display.length))) }
    LaunchedEffect(version, display) { if (field.text != display) field = field.copy(text = display, selection = TextRange(field.selection.start.coerceIn(0, display.length))) }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = Spacing.xs)) {
        Text(bullet.ifEmpty { "¶" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        BasicTextField(
            value = field,
            onValueChange = { nv ->
                val old = field.text
                field = nv
                vm.textParagraph = index
                vm.textSelection = nv.selection.start..nv.selection.end
                if (nv.text != old) vm.onParagraphText(index, old, nv.text)
            },
            textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(Spacing.s)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && field.selection.collapsed && field.selection.start == 0 && index > 0) {
                        vm.mergeIntoPrevious(index); true
                    } else false
                }
                .semantics { contentDescription = "Paragraph ${index + 1}" },
        )
    }
}

/** Full-screen slide show over visible slides. Tap right half or swipe to advance. */
@Composable
private fun PresentationOverlay(vm: SlidesViewModel) {
    val visible = vm.slides().filter { !it.hidden }
    val renderer = remember(vm.document) { SlideRenderer(vm.document!!) }
    val start = visible.indexOfFirst { it.index >= vm.presentStart }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { visible.size }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { vm.presenting = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val slide = visible[page]
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val w = minOf(maxWidth, maxHeight * renderer.aspect)
                Canvas(
                    Modifier
                        .width(w)
                        .aspectRatio(renderer.aspect)
                        .semantics { contentDescription = "Slide ${page + 1} of ${visible.size}" + slideText(slide) }
                        .pointerInput(page) {
                            detectTapGestures { pos ->
                                scope.launch {
                                    if (pos.x > size.width / 2) {
                                        if (page < visible.size - 1) pager.animateScrollToPage(page + 1) else vm.presenting = false
                                    } else if (page > 0) pager.animateScrollToPage(page - 1)
                                }
                            }
                        },
                ) {
                    drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, slide, size.width, imageMaxEdge = 2400) }
                }
            }
        }
        IconButton(onClick = { vm.presenting = false }, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.s)) {
            Icon(Icons.Default.Close, contentDescription = "End presentation", tint = Color.White.copy(alpha = 0.7f))
        }
        Text("${pager.currentPage + 1} / ${visible.size}", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.m))
    }
}

/** Renders slides to a PDF with one page per slide at the slide's own size. */
fun exportSlidesPdf(pres: com.vitosstudios.suite.pptx.PptxPresentation, slides: List<SlideView>, out: File) {
    val renderer = SlideRenderer(pres)
    val wPt = pres.slideSize.first / 12700f
    val hPt = pres.slideSize.second / 12700f
    val pdf = PdfDocument()
    try {
        slides.forEachIndexed { i, s ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(wPt.toInt(), hPt.toInt(), i + 1).create())
            renderer.draw(page.canvas, s, wPt, imageMaxEdge = 2000)
            pdf.finishPage(page)
        }
        out.outputStream().use { pdf.writeTo(it) }
    } finally {
        pdf.close()
    }
}
