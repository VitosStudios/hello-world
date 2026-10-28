package com.vitosstudios.suite.feature.docs

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitosstudios.suite.appContainer
import com.vitosstudios.suite.docx.Alignment as DocAlignment
import com.vitosstudios.suite.docx.AtomKind
import com.vitosstudios.suite.docx.Block
import com.vitosstudios.suite.docx.DocxImage
import com.vitosstudios.suite.docx.DocxTable
import com.vitosstudios.suite.docx.InlineChars
import com.vitosstudios.suite.docx.Paragraph
import com.vitosstudios.suite.feature.common.EditorLifecycle
import com.vitosstudios.suite.feature.common.EditorTopActions
import com.vitosstudios.suite.feature.common.ImageLoader
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.feature.common.rememberBitmap
import com.vitosstudios.suite.ui.components.ContextToolbar
import com.vitosstudios.suite.ui.components.EditorBottomBar
import com.vitosstudios.suite.ui.components.LockedPlaceholder
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.components.ToolbarAction
import com.vitosstudios.suite.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which bottom sheet of the document editor is open. */
enum class DocsSheet { INSERT, TEXT, PARAGRAPH, PAGE, HEADER_FOOTER, TABLE, LINK, WORD_COUNT }

@Composable
fun DocsEditorScreen(
    onBack: () -> Unit,
    onCompatibility: () -> Unit,
    vm: DocsViewModel = viewModel(factory = DocsViewModel.Factory),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    EditorLifecycle(vm, snackbar)
    var sheet by remember { mutableStateOf<DocsSheet?>(null) }
    var finding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { ImageLoader.readUri(context, uri) }
            if (bytes == null) { vm.notify("The image could not be read"); return@launch }
            val info = ImageLoader.bounds(bytes)
            if (info.width <= 0) { vm.notify("This image format is not supported"); return@launch }
            vm.insertImage(bytes, ImageLoader.extensionFor(info.mime), info.width, info.height)
        }
    }
    val exporter = rememberPdfExport(vm)
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )) { uri ->
        if (uri != null) scope.launch {
            vm.flush()
            val err = context.appContainer.files.exportCopy(vm.fileId, uri)
            vm.notify(err ?: "Copy saved")
        }
    }

    val commands = docsCommands(
        vm,
        openSheet = { sheet = it },
        startFind = { finding = true },
        pickImage = { pickImage.launch(arrayOf("image/*")) },
        exportPdf = { exporter.export() },
        saveCopy = { saveCopy.launch(vm.entry?.displayName ?: "Document.docx") },
        onCompatibility = onCompatibility,
    )

    Scaffold(
        topBar = {
            if (finding) {
                FindBar(vm, listState, onClose = { finding = false; vm.closeFind() })
            } else {
                SuiteTopBar(
                    title = vm.entry?.displayName ?: "Document",
                    subtitle = "DOCX",
                    onBack = { vm.flush(); onBack() },
                    commands = commands,
                    actions = {
                        EditorTopActions(vm)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Find and replace") }, leadingIcon = { Icon(Icons.Default.FindReplace, null) }, onClick = { menu = false; finding = true })
                                DropdownMenuItem(text = { Text("Word count") }, onClick = { menu = false; sheet = DocsSheet.WORD_COUNT })
                                DropdownMenuItem(text = { Text("Page setup") }, onClick = { menu = false; sheet = DocsSheet.PAGE })
                                DropdownMenuItem(text = { Text("Header and footer") }, onClick = { menu = false; sheet = DocsSheet.HEADER_FOOTER })
                                DropdownMenuItem(text = { Text("Zoom in") }, leadingIcon = { Icon(Icons.Default.ZoomIn, null) }, onClick = { vm.zoomBy(1.15f) })
                                DropdownMenuItem(text = { Text("Zoom out") }, leadingIcon = { Icon(Icons.Default.ZoomOut, null) }, onClick = { vm.zoomBy(1 / 1.15f) })
                                DropdownMenuItem(text = { Text("Export as PDF") }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, onClick = { menu = false; exporter.export() })
                                DropdownMenuItem(text = { Text("Share as PDF") }, onClick = { menu = false; exporter.share() })
                                DropdownMenuItem(text = { Text("Save a copy") }, onClick = { menu = false; saveCopy.launch(vm.entry?.displayName ?: "Document.docx") })
                                DropdownMenuItem(text = { Text("Compatibility") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; onCompatibility() })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            Box(Modifier.imePadding()) {
                DocsBottomArea(vm, openSheet = { sheet = it }, pickImage = { pickImage.launch(arrayOf("image/*")) }, startFind = { finding = true }, exportPdf = { exporter.export() })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            if (LoadingOrError(vm.document == null, vm.loadError)) {
                DocumentView(vm, listState)
            }
        }
    }

    sheet?.let { s ->
        DocsSheetHost(vm, s, onDismiss = { sheet = null }, openSheet = { sheet = it }, pickImage = { pickImage.launch(arrayOf("image/*")) })
    }
}

@Composable
private fun DocsBottomArea(vm: DocsViewModel, openSheet: (DocsSheet) -> Unit, pickImage: () -> Unit, startFind: () -> Unit, exportPdf: () -> Unit) {
    val v = vm.version // refresh toggle states after edits
    val image = vm.selectedImage
    val p = vm.focused
    when {
        image != null -> ContextToolbar(true, listOf(
            ToolbarAction("Smaller", Icons.Default.ZoomOut) { vm.resizeSelectedImage(0.8f) },
            ToolbarAction("Larger", Icons.Default.ZoomIn) { vm.resizeSelectedImage(1.25f) },
            ToolbarAction("Delete image", Icons.Default.Delete) { vm.deleteSelectedImage() },
            ToolbarAction("Done", Icons.Default.Check) { vm.clearSelection() },
        ))
        p != null -> {
            val fmt = remember(v, vm.selection, p) { vm.caretFormat() }
            val editable = p.isTextEditable
            val inTable = remember(v, p) { vm.cellOfFocused() != null }
            ContextToolbar(true, buildList {
                add(ToolbarAction("Bold", Icons.Default.FormatBold, selected = fmt?.bold == true, enabled = editable) { vm.toggleBold() })
                add(ToolbarAction("Italic", Icons.Default.FormatItalic, selected = fmt?.italic == true, enabled = editable) { vm.toggleItalic() })
                add(ToolbarAction("Underline", Icons.Default.FormatUnderlined, selected = fmt?.underline == true, enabled = editable) { vm.toggleUnderline() })
                add(ToolbarAction("Strikethrough", Icons.Default.FormatStrikethrough, selected = fmt?.strike == true, enabled = editable) { vm.toggleStrike() })
                add(ToolbarAction("Text format", Icons.Default.TextFields, enabled = editable) { openSheet(DocsSheet.TEXT) })
                add(ToolbarAction("Paragraph format", Icons.Default.FormatPaint) { openSheet(DocsSheet.PARAGRAPH) })
                add(ToolbarAction("Bulleted list", Icons.AutoMirrored.Filled.FormatListBulleted) { vm.toggleBullets() })
                add(ToolbarAction("Numbered list", Icons.Default.FormatListNumbered) { vm.toggleNumbering() })
                add(ToolbarAction("Decrease indent", Icons.AutoMirrored.Filled.FormatIndentDecrease) { vm.changeIndent(false) })
                add(ToolbarAction("Increase indent", Icons.AutoMirrored.Filled.FormatIndentIncrease) { vm.changeIndent(true) })
                add(ToolbarAction("Link", Icons.Default.Link, enabled = editable) { openSheet(DocsSheet.LINK) })
                if (inTable) add(ToolbarAction("Table", Icons.Default.TableChart) { openSheet(DocsSheet.TABLE) })
                add(ToolbarAction("Insert", Icons.Default.Add) { openSheet(DocsSheet.INSERT) })
                add(ToolbarAction("Done", Icons.Default.Check) { vm.clearSelection() })
            })
        }
        else -> EditorBottomBar(listOf(
            ToolbarAction("Insert", Icons.Default.Add) { openSheet(DocsSheet.INSERT) },
            ToolbarAction("Image", Icons.Outlined.Photo) { pickImage() },
            ToolbarAction("Find", Icons.Default.FindReplace) { startFind() },
            ToolbarAction("Page", Icons.Outlined.Description) { openSheet(DocsSheet.PAGE) },
            ToolbarAction("Header", Icons.Default.ViewDay) { openSheet(DocsSheet.HEADER_FOOTER) },
            ToolbarAction("PDF", Icons.Default.PictureAsPdf) { exportPdf() },
        ))
    }
}

/** The reflowed document: blocks on a paper-coloured column, centred and width-limited on tablets. */
@Composable
private fun DocumentView(vm: DocsViewModel, listState: androidx.compose.foundation.lazy.LazyListState) {
    val v = vm.version
    val blocks = remember(v, vm.document) { vm.blocks() }
    val labels = remember(v, vm.document) { vm.listLabels() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val pageWidth = minOf(maxWidth, 760.dp)
        Surface(
            color = DocsText.paperColor,
            shadowElevation = 1.dp,
            modifier = Modifier.width(pageWidth).fillMaxHeight(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.l),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(blocks, key = { vm.keyOf(it.element) }) { b ->
                    BlockView(vm, b, labels, pageWidth - Spacing.l * 2)
                }
                item {
                    // Tapping below the last paragraph continues writing at the end.
                    Spacer(
                        Modifier.fillMaxWidth().height(160.dp).clickable(onClickLabel = "Continue writing") {
                            val last = vm.document?.bodyParagraphs()?.lastOrNull()
                            if (last != null && last.isTextEditable) vm.focus(last, last.text.length) else vm.insertParagraphBelow()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun BlockView(vm: DocsViewModel, block: Block, labels: Map<Paragraph, String>, width: Dp) {
    when (block) {
        is Block.Para -> ParagraphView(vm, block.paragraph, labels[block.paragraph], width)
        is Block.Table -> TableView(vm, block.table, labels, width)
        is Block.Locked -> LockedPlaceholder(block.label, block.explanation, Modifier.padding(vertical = Spacing.xs))
    }
}

@Composable
private fun ParagraphView(vm: DocsViewModel, p: Paragraph, label: String?, width: Dp) {
    val v = vm.version
    val zoom = vm.zoom
    val fmt = remember(v, p) { p.format }
    val focused = vm.focused == p
    val scale = 0.55f * zoom
    val indentStart = (fmt.indentLeft * scale).coerceIn(0f, width.value * 0.6f).dp
    val firstLine = fmt.indentFirstLine * scale
    val markFormat = remember(v, p) { p.formatAt(0) }
    val fontSizeSp = markFormat.sizePt * DocsText.PT_TO_SP * zoom
    val lineHeight = if (fmt.lineExactPt != null) (fmt.lineExactPt!! * DocsText.PT_TO_SP * zoom).sp else (fmt.lineMultiple * 1.2f).em
    val baseStyle = TextStyle(
        color = DocsText.autoTextColor,
        fontSize = fontSizeSp.sp,
        textAlign = when (fmt.alignment) {
            DocAlignment.CENTER -> TextAlign.Center
            DocAlignment.RIGHT -> TextAlign.End
            DocAlignment.JUSTIFY -> TextAlign.Justify
            DocAlignment.LEFT -> TextAlign.Start
        },
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textIndent = if (label == null && firstLine > 0) TextIndent(firstLine = firstLine.sp) else null,
    )
    val shading = DocsText.color(fmt.shading)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = (fmt.spaceBefore * scale).dp, bottom = (fmt.spaceAfter * scale).dp)
            .then(if (shading != null) Modifier.background(shading) else Modifier)
            .then(if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else Modifier),
    ) {
        if (fmt.pageBreakBefore || p.atoms.any { it.second.kind == AtomKind.PAGE_BREAK && it.first == 0 }) PageBreakMarker()
        Row(Modifier.padding(start = indentStart)) {
            if (label != null) {
                Text(label, style = baseStyle.copy(textAlign = TextAlign.Start), modifier = Modifier.widthIn(min = (18 * zoom).dp).padding(end = 6.dp))
            }
            if (focused && p.isTextEditable) {
                EditableParagraph(vm, p, baseStyle)
            } else {
                DisplayParagraph(vm, p, baseStyle, width - indentStart)
            }
        }
        if (focused && !p.isTextEditable) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "Contains ${p.lockReasons.joinToString()}: text is read-only, paragraph formatting still works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageBreakMarker() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s).semantics { contentDescription = "Page break" }) {
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF9E9E9E)))
        Text(" Page break ", style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF9E9E9E)))
    }
}

@Composable
private fun DisplayParagraph(vm: DocsViewModel, p: Paragraph, baseStyle: TextStyle, width: Dp) {
    val v = vm.version
    val text = remember(v, p) { p.text }
    val spans = remember(v, p) { p.spans() }
    val atoms = remember(v, p) { p.atoms }
    val matchRanges = vm.matches.filter { it.paragraph == p }.map { it.start until it.end }
    val current = vm.matches.getOrNull(vm.currentMatch)?.takeIf { it.paragraph == p }?.let { it.start until it.end }
    val annotated = remember(text, spans, vm.zoom, matchRanges, current) {
        DocsText.annotate(text, spans, vm.zoom, matchRanges, current)
    }
    val density = LocalDensity.current
    val inline = remember(atoms, vm.zoom, width) {
        val map = HashMap<String, InlineTextContent>()
        for ((pos, atom) in atoms) {
            val img = atom.image
            if (img != null) {
                var w = img.widthPt * vm.zoom * 1.2f
                var h = img.heightPt * vm.zoom * 1.2f
                val maxW = width.value - 8
                if (w > maxW && w > 0) { h = h * maxW / w; w = maxW }
                val wSp = with(density) { w.dp.toSp() }
                val hSp = with(density) { h.dp.toSp() }
                map["atom$pos"] = InlineTextContent(Placeholder(wSp, hSp, PlaceholderVerticalAlign.TextBottom)) {
                    InlineImage(vm, p, img)
                }
            } else if (atom.kind != AtomKind.PAGE_BREAK) {
                val label = when (atom.kind) {
                    AtomKind.FIELD -> atom.label.take(24)
                    AtomKind.SYMBOL -> atom.label
                    AtomKind.FOOTNOTE_REFERENCE, AtomKind.ENDNOTE_REFERENCE -> atom.label
                    else -> "🔒 " + atom.label
                }
                val em = (label.length * 0.62f + 0.4f)
                map["atom$pos"] = InlineTextContent(Placeholder(em.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().background(Color(0xFFE8EAED)).semantics { contentDescription = atom.label },
                    ) {
                        Text(label, fontSize = baseStyle.fontSize * 0.8f, color = Color(0xFF3C4043), maxLines = 1)
                    }
                }
            }
        }
        map
    }
    val withInline = remember(annotated, atoms) {
        if (atoms.isEmpty()) annotated else androidx.compose.ui.text.buildAnnotatedString {
            var last = 0
            for ((pos, atom) in atoms) {
                if (pos < last || pos >= annotated.length) continue
                append(annotated.subSequence(last, pos))
                if (atom.kind == AtomKind.PAGE_BREAK) append(" ") else appendInlineContent("atom$pos", InlineChars.OBJECT.toString())
                last = pos + 1
            }
            append(annotated.subSequence(last, annotated.length))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = if (text.isEmpty()) androidx.compose.ui.text.AnnotatedString(" ") else withInline,
        style = baseStyle,
        inlineContent = inline,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(p) {
                detectTapGestures { pos ->
                    val offset = layout?.getOffsetForPosition(pos) ?: 0
                    vm.focus(p, offset.coerceIn(0, p.text.length))
                }
            },
    )
}

@Composable
private fun InlineImage(vm: DocsViewModel, p: Paragraph, img: DocxImage) {
    val key = (vm.document?.imagePartName(p.part, img) ?: img.relId)
    val bmp = rememberBitmap(key, 1600) { vm.imageBytes(p, img) }
    val selected = vm.selectedImage?.second?.drawing === img.drawing
    Box(
        Modifier
            .fillMaxSize()
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .clickable(onClickLabel = "Select image") { vm.selectImage(p, img) }
            .semantics { contentDescription = img.description ?: "Image" },
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFFEEEEEE)))
        }
    }
}

@Composable
private fun EditableParagraph(vm: DocsViewModel, p: Paragraph, baseStyle: TextStyle) {
    val v = vm.version
    val requester = remember { FocusRequester() }
    var field by remember(p) {
        val t = p.text
        val c = vm.pendingCaret.coerceIn(0, t.length)
        mutableStateOf(TextFieldValue(t, TextRange(c)))
    }
    // Model changes from elsewhere (undo, toolbar actions) refresh the field.
    LaunchedEffect(v, p) {
        val t = p.text
        if (t != field.text) {
            field = TextFieldValue(t, TextRange(field.selection.start.coerceIn(0, t.length), field.selection.end.coerceIn(0, t.length)))
        }
    }
    LaunchedEffect(p) { requester.requestFocus() }
    val spans = remember(v, p, field.text) { if (p.text == field.text) p.spans() else emptyList() }
    BasicTextField(
        value = field,
        onValueChange = { nv ->
            val old = field.text
            if (nv.text != old) {
                vm.onTextChanged(p, old, nv.text, nv.selection)
                if (vm.focused != p) return@BasicTextField
                val modelText = p.text
                field = if (modelText == nv.text) nv else TextFieldValue(modelText, TextRange(nv.selection.start.coerceIn(0, modelText.length)))
            } else {
                field = nv
            }
            vm.selection = field.selection
        },
        textStyle = baseStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = ParagraphVisualTransformation(spans, vm.zoom),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && field.selection.collapsed && field.selection.start == 0) {
                    vm.mergeBackward(p)
                    true
                } else {
                    false
                }
            }
            .semantics { contentDescription = "Paragraph editor" },
    )
}

@Composable
private fun TableView(vm: DocsViewModel, table: DocxTable, labels: Map<Paragraph, String>, width: Dp) {
    val v = vm.version
    val rows = remember(v, table.element) { table.rows }
    val widths = remember(v, table.element) { table.columnWidthsTwips }
    val totalTwips = widths.sum().coerceAtLeast(1)
    val tableWidth = maxOf(width, (widths.size * 72).dp)
    val borders = remember(v, table.element) { table.hasBorders }
    val lineColor = if (borders) Color(0xFF5F6368) else Color(0x33000000)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = Spacing.xs)) {
        Column(Modifier.width(tableWidth).border(1.dp, lineColor)) {
            rows.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    var col = 0
                    row.cells.forEach { cell ->
                        val span = cell.gridSpan
                        val twips = (col until col + span).sumOf { widths.getOrElse(it) { totalTwips / maxOf(widths.size, 1) } }
                        col += span
                        val w = tableWidth * (twips.toFloat() / totalTwips)
                        val shade = DocsText.color(cell.shading)
                        Box(
                            Modifier
                                .width(w)
                                .fillMaxHeight()
                                .border(0.5.dp, lineColor)
                                .then(if (shade != null) Modifier.background(shade) else Modifier)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            if (cell.verticalMerge != "continue") {
                                Column {
                                    cell.blocks.forEach { b -> BlockView(vm, b, labels, w - 8.dp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Opens a share sheet for an exported file. */
fun shareFile(context: android.content.Context, file: java.io.File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Suppress("unused")
private val unusedAlignIcon = Icons.AutoMirrored.Filled.FormatAlignLeft
