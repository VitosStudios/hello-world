package com.vitosstudios.suite.feature.docs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vitosstudios.suite.docx.Alignment as DocAlignment
import com.vitosstudios.suite.docx.Block
import com.vitosstudios.suite.docx.HeaderFooter
import com.vitosstudios.suite.docx.PageSize
import com.vitosstudios.suite.docx.RunPatch
import com.vitosstudios.suite.ui.components.ColorPalette
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.ToolbarAction
import com.vitosstudios.suite.ui.components.ToolbarButton
import com.vitosstudios.suite.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsSheetHost(vm: DocsViewModel, sheet: DocsSheet, onDismiss: () -> Unit, openSheet: (DocsSheet) -> Unit, pickImage: () -> Unit) {
    when (sheet) {
        DocsSheet.LINK -> { LinkDialog(vm, onDismiss); return }
        DocsSheet.WORD_COUNT -> { WordCountDialog(vm, onDismiss); return }
        else -> {}
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == DocsSheet.HEADER_FOOTER)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l)
                .navigationBarsPadding()
                .padding(bottom = Spacing.l),
        ) {
            when (sheet) {
                DocsSheet.INSERT -> InsertSheet(vm, onDismiss, openSheet, pickImage)
                DocsSheet.TEXT -> TextSheet(vm)
                DocsSheet.PARAGRAPH -> ParagraphSheet(vm)
                DocsSheet.PAGE -> PageSheet(vm)
                DocsSheet.HEADER_FOOTER -> HeaderFooterSheet(vm)
                DocsSheet.TABLE -> TableSheet(vm, onDismiss)
                else -> {}
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() })
}

@Composable
private fun SheetSection(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs))
}

@Composable
private fun InsertSheet(vm: DocsViewModel, onDismiss: () -> Unit, openSheet: (DocsSheet) -> Unit, pickImage: () -> Unit) {
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }
    SheetTitle("Insert")
    ListItem(
        headlineContent = { Text("Image") },
        supportingContent = { Text("From your photos or files; the original image is kept") },
        leadingContent = { Icon(Icons.Outlined.Photo, null) },
        modifier = Modifier.clickable { onDismiss(); pickImage() },
    )
    ListItem(
        headlineContent = { Text("Page break") },
        leadingContent = { Icon(Icons.Default.HorizontalRule, null) },
        modifier = Modifier.clickable { onDismiss(); vm.insertPageBreak() },
    )
    ListItem(
        headlineContent = { Text("Link") },
        leadingContent = { Icon(Icons.Default.Link, null) },
        modifier = Modifier.clickable { openSheet(DocsSheet.LINK) },
    )
    ListItem(
        headlineContent = { Text("Paragraph below") },
        leadingContent = { Icon(Icons.Default.Add, null) },
        modifier = Modifier.clickable { onDismiss(); vm.insertParagraphBelow() },
    )
    ListItem(
        headlineContent = { Text("Header and footer") },
        leadingContent = { Icon(Icons.Default.ViewDay, null) },
        modifier = Modifier.clickable { openSheet(DocsSheet.HEADER_FOOTER) },
    )
    HorizontalDivider()
    SheetSection("Table")
    Stepper("Rows", rows, 1..50) { rows = it }
    Stepper("Columns", cols, 1..12) { cols = it }
    OutlinedButton(onClick = { onDismiss(); vm.insertTable(rows, cols) }, modifier = Modifier.padding(top = Spacing.s)) {
        Icon(Icons.Default.TableChart, null)
        Spacer(Modifier.width(Spacing.s))
        Text("Insert $rows × $cols table")
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Default.Remove, "Fewer $label") }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp))
        IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Default.Add, "More $label") }
    }
}

private val highlightChoices = listOf(
    "none" to "No highlight", "yellow" to "Yellow", "green" to "Bright green", "cyan" to "Turquoise",
    "magenta" to "Pink", "lightGray" to "Light grey", "red" to "Red", "blue" to "Blue",
)

private val fontChoices = listOf("Calibri", "Arial", "Times New Roman", "Georgia", "Verdana", "Courier New", "Liberation Sans", "Liberation Serif")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSheet(vm: DocsViewModel) {
    val v = vm.version
    val fmt = remember(v, vm.selection) { vm.caretFormat() }
    SheetTitle("Text")
    Row {
        ToolbarButton(ToolbarAction("Bold", Icons.Default.FormatBold, selected = fmt?.bold == true) { vm.toggleBold() })
        ToolbarButton(ToolbarAction("Italic", Icons.Default.FormatItalic, selected = fmt?.italic == true) { vm.toggleItalic() })
        ToolbarButton(ToolbarAction("Underline", Icons.Default.FormatUnderlined, selected = fmt?.underline == true) { vm.toggleUnderline() })
        ToolbarButton(ToolbarAction("Strikethrough", Icons.Default.FormatStrikethrough, selected = fmt?.strike == true) { vm.toggleStrike() })
        ToolbarButton(ToolbarAction("Clear formatting", Icons.Default.FormatClear) { vm.applyRunPatch(RunPatch(clearDirect = true), "Clear formatting") })
    }
    SheetSection("Size")
    val size = fmt?.sizePt ?: 11f
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.applyRunPatch(RunPatch(sizePt = (size - 1).coerceAtLeast(1f)), "Font size") }) { Icon(Icons.Default.Remove, "Smaller text") }
        Text("${"%.1f".format(size).removeSuffix(".0")} pt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(72.dp))
        IconButton(onClick = { vm.applyRunPatch(RunPatch(sizePt = (size + 1).coerceAtMost(400f)), "Font size") }) { Icon(Icons.Default.Add, "Larger text") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf(9f, 10f, 11f, 12f, 14f, 16f, 20f, 24f, 32f).forEach { s ->
            FilterChip(selected = size == s, onClick = { vm.applyRunPatch(RunPatch(sizePt = s), "Font size") }, label = { Text("${s.toInt()}") })
        }
    }
    SheetSection("Font")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        fontChoices.forEach { f ->
            FilterChip(selected = fmt?.fontFamily == f, onClick = { vm.applyRunPatch(RunPatch(fontFamily = f), "Font") }, label = { Text(f) })
        }
    }
    SheetSection("Colour")
    ColorPalette(
        selected = DocsText.color(fmt?.color),
        onSelect = { c ->
            val hex = c?.let { "%02X%02X%02X".format((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) } ?: "auto"
            vm.applyRunPatch(RunPatch(color = hex), "Text colour")
        },
    )
    SheetSection("Highlight")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        highlightChoices.forEach { (id, name) ->
            FilterChip(selected = (fmt?.highlight ?: "none") == id, onClick = { vm.applyRunPatch(RunPatch(highlight = id), "Highlight") }, label = { Text(name) })
        }
    }
    SheetSection("Position")
    Row {
        FilterChip(selected = fmt?.superscript == true, onClick = { vm.applyRunPatch(RunPatch(superscript = fmt?.superscript != true, subscript = false), "Superscript") }, label = { Text("Superscript") })
        Spacer(Modifier.width(Spacing.s))
        FilterChip(selected = fmt?.subscript == true, onClick = { vm.applyRunPatch(RunPatch(subscript = fmt?.subscript != true, superscript = false), "Subscript") }, label = { Text("Subscript") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParagraphSheet(vm: DocsViewModel) {
    val v = vm.version
    val p = vm.focused
    val fmt = remember(v, p) { p?.format }
    val styles = remember(v) { vm.document?.styles?.paragraphStylesForPicker().orEmpty() }
    SheetTitle("Paragraph")
    SheetSection("Style")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FilterChip(selected = p?.headingLevel == 0 && (fmt?.styleId == null || fmt.styleId == vm.document?.styles?.defaultParagraphStyleId), onClick = { vm.setHeading(0) }, label = { Text("Normal text") })
        (1..6).forEach { level ->
            FilterChip(selected = p?.headingLevel == level, onClick = { vm.setHeading(level) }, label = { Text("Heading $level") })
        }
    }
    val others = styles.filter { it.headingLevel == null && !it.isDefault }.take(16)
    if (others.isNotEmpty()) {
        SheetSection("Document styles")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            others.forEach { s -> FilterChip(selected = fmt?.styleId == s.styleId, onClick = { vm.setStyle(s.styleId) }, label = { Text(s.name) }) }
        }
    }
    SheetSection("Alignment")
    Row {
        ToolbarButton(ToolbarAction("Align left", Icons.AutoMirrored.Filled.FormatAlignLeft, selected = fmt?.alignment == DocAlignment.LEFT) { vm.setAlignment(DocAlignment.LEFT) })
        ToolbarButton(ToolbarAction("Centre", Icons.Default.FormatAlignCenter, selected = fmt?.alignment == DocAlignment.CENTER) { vm.setAlignment(DocAlignment.CENTER) })
        ToolbarButton(ToolbarAction("Align right", Icons.AutoMirrored.Filled.FormatAlignRight, selected = fmt?.alignment == DocAlignment.RIGHT) { vm.setAlignment(DocAlignment.RIGHT) })
        ToolbarButton(ToolbarAction("Justify", Icons.Default.FormatAlignJustify, selected = fmt?.alignment == DocAlignment.JUSTIFY) { vm.setAlignment(DocAlignment.JUSTIFY) })
    }
    SheetSection("Lists and indentation")
    Row {
        ToolbarButton(ToolbarAction("Bulleted list", Icons.AutoMirrored.Filled.FormatListBulleted) { vm.toggleBullets() })
        ToolbarButton(ToolbarAction("Numbered list", Icons.Default.FormatListNumbered) { vm.toggleNumbering() })
        ToolbarButton(ToolbarAction("Decrease indent", Icons.AutoMirrored.Filled.FormatIndentDecrease) { vm.changeIndent(false) })
        ToolbarButton(ToolbarAction("Increase indent", Icons.AutoMirrored.Filled.FormatIndentIncrease) { vm.changeIndent(true) })
    }
    SheetSection("Line spacing")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf(1f to "Single", 1.15f to "1.15", 1.5f to "1.5", 2f to "Double").forEach { (m, name) ->
            FilterChip(selected = fmt?.lineExactPt == null && fmt?.lineMultiple?.let { kotlin.math.abs(it - m) < 0.02f } == true, onClick = { vm.setLineSpacing(m) }, label = { Text(name) })
        }
    }
    SheetSection("Space before / after")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf(0f, 6f, 12f, 18f).forEach { pt ->
            FilterChip(selected = fmt?.spaceBefore == pt, onClick = { vm.setSpacing(pt, null) }, label = { Text("Before ${pt.toInt()} pt") })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf(0f, 6f, 8f, 12f).forEach { pt ->
            FilterChip(selected = fmt?.spaceAfter == pt, onClick = { vm.setSpacing(null, pt) }, label = { Text("After ${pt.toInt()} pt") })
        }
    }
    SheetSection("Arrange")
    Row {
        ToolbarButton(ToolbarAction("Move paragraph up", Icons.Default.KeyboardArrowUp) { vm.moveFocused(true) })
        ToolbarButton(ToolbarAction("Move paragraph down", Icons.Default.KeyboardArrowDown) { vm.moveFocused(false) })
        ToolbarButton(ToolbarAction("Page break before", Icons.Default.HorizontalRule, selected = fmt?.pageBreakBefore == true) {
            vm.edit("Page break before") { p?.setPageBreakBefore(fmt?.pageBreakBefore != true) }
        })
        ToolbarButton(ToolbarAction("Delete paragraph", Icons.Default.Delete) { vm.deleteFocusedParagraph() })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageSheet(vm: DocsViewModel) {
    val v = vm.version
    val s = remember(v) { vm.currentSection() }
    SheetTitle("Page setup")
    Text("Applies to the whole document. The page layout is used for PDF export.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SheetSection("Size")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PageSize.entries.forEach { ps ->
            val w = minOf(s?.pageWidthPt ?: 0f, s?.pageHeightPt ?: 0f)
            val h = maxOf(s?.pageWidthPt ?: 0f, s?.pageHeightPt ?: 0f)
            val sel = kotlin.math.abs(w - ps.widthPt) < 2 && kotlin.math.abs(h - ps.heightPt) < 2
            FilterChip(selected = sel, onClick = { vm.setPageSize(ps) }, label = { Text(ps.label) })
        }
    }
    SheetSection("Orientation")
    Row {
        FilterChip(selected = s?.isLandscape == false, onClick = { vm.setOrientation(false) }, label = { Text("Portrait") })
        Spacer(Modifier.width(Spacing.s))
        FilterChip(selected = s?.isLandscape == true, onClick = { vm.setOrientation(true) }, label = { Text("Landscape") })
    }
    SheetSection("Margins")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf(
            "Normal (2.54 cm)" to listOf(72f, 72f, 72f, 72f),
            "Narrow (1.27 cm)" to listOf(36f, 36f, 36f, 36f),
            "Moderate" to listOf(72f, 54f, 72f, 54f),
            "Wide" to listOf(72f, 144f, 72f, 144f),
        ).forEach { (name, m) ->
            val sel = s != null && kotlin.math.abs(s.marginTopPt - m[0]) < 1 && kotlin.math.abs(s.marginRightPt - m[1]) < 1 && kotlin.math.abs(s.marginLeftPt - m[3]) < 1
            FilterChip(selected = sel, onClick = { vm.setMargins(m[0], m[1], m[2], m[3]) }, label = { Text(name) })
        }
    }
}

@Composable
private fun HeaderFooterSheet(vm: DocsViewModel) {
    val v = vm.version
    SheetTitle("Header and footer")
    Text("Edit the text shown at the top and bottom of every page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (kind in HeaderFooter.Kind.entries) {
        val name = if (kind == HeaderFooter.Kind.HEADER) "Header" else "Footer"
        SheetSection(name)
        val hf = remember(v, kind) { vm.headerFooter(kind, create = false) }
        if (hf == null) {
            OutlinedButton(onClick = { vm.headerFooter(kind, create = true) }) { Text("Add $name") }
        } else {
            val labels = remember(v, hf.part) { vm.document?.listLabels(hf.paragraphs).orEmpty() }
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                hf.blocks.forEach { b -> BlockView(vm, b, labels, 600.dp) }
            }
        }
    }
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun TableSheet(vm: DocsViewModel, onDismiss: () -> Unit) {
    val v = vm.version
    val pos = remember(v, vm.focused) { vm.cellOfFocused() }
    SheetTitle("Table")
    if (pos == null) {
        Text("Tap a table cell first.")
        return
    }
    val editable = pos.table.isStructureEditable
    if (!editable) {
        Text(
            "This table has merged cells. Its structure is kept unchanged; cell text can still be edited.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    @Composable
    fun item(label: String, enabled: Boolean, action: () -> Unit) {
        ListItem(
            headlineContent = { Text(label, fontWeight = if (enabled) FontWeight.Normal else FontWeight.Light) },
            modifier = Modifier.clickable(enabled = enabled) { action() },
        )
    }
    item("Insert row above", editable) { vm.tableAction("Insert row") { it.table.insertRow(it.row) } }
    item("Insert row below", editable) { vm.tableAction("Insert row") { it.table.insertRow(it.row + 1) } }
    item("Insert column left", editable) { vm.tableAction("Insert column") { it.table.insertColumn(it.column) } }
    item("Insert column right", editable) { vm.tableAction("Insert column") { it.table.insertColumn(it.column + 1) } }
    item("Delete row", editable) { vm.tableAction("Delete row") { it.table.deleteRow(it.row) } }
    item("Delete column", editable) { vm.tableAction("Delete column") { it.table.deleteColumn(it.column) } }
    item("Delete table", true) { onDismiss(); vm.deleteTable() }
}

@Composable
private fun LinkDialog(vm: DocsViewModel, onDismiss: () -> Unit) {
    val existing = remember { vm.hyperlinkAtCaret() }
    var url by remember { mutableStateOf(existing ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit link" else "Insert link") },
        text = {
            Column {
                if (existing == null) Text("The link is added to the selected text or the word at the cursor.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) { vm.setLink(url); onDismiss() } }),
                )
            }
        },
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { vm.setLink(url); onDismiss() }) { Text("Apply") } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.removeLink(); onDismiss() }) { Icon(Icons.Default.LinkOff, null); Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun WordCountDialog(vm: DocsViewModel, onDismiss: () -> Unit) {
    val wc = remember { vm.wordCount() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Word count") },
        text = {
            Column {
                Text("Words: ${wc?.words ?: 0}")
                Text("Characters (with spaces): ${wc?.characters ?: 0}")
                Text("Characters (no spaces): ${wc?.charactersNoSpaces ?: 0}")
                Text("Paragraphs: ${wc?.paragraphs ?: 0}")
                Text("Headers, footers and notes are not counted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Top bar replacement for find and replace. */
@Composable
fun FindBar(vm: DocsViewModel, listState: LazyListState, onClose: () -> Unit) {
    var showReplace by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(vm.currentMatch) {
        val m = vm.matches.getOrNull(vm.currentMatch) ?: return@LaunchedEffect
        val blocks = vm.blocks()
        val idx = blocks.indexOfFirst { b ->
            when (b) {
                is Block.Para -> b.paragraph == m.paragraph
                is Block.Table -> generateSequence(m.paragraph.element.parent) { it.parent }.any { it === b.element }
                else -> false
            }
        }
        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
    }
    androidx.compose.material3.Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.l, bottom = Spacing.xs).padding(horizontal = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close find") }
                TextField(
                    value = vm.findQuery, onValueChange = { vm.find(it) }, singleLine = true, placeholder = { Text("Find") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.nextMatch(true) }),
                )
                val n = vm.matches.size
                Text(if (n == 0) "0" else "${vm.currentMatch + 1}/$n", modifier = Modifier.padding(horizontal = Spacing.s))
                IconButton(onClick = { vm.nextMatch(false) }, enabled = n > 0) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = { vm.nextMatch(true) }, enabled = n > 0) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l)) {
                Text("Match case", style = MaterialTheme.typography.bodySmall)
                Switch(checked = vm.matchCase, onCheckedChange = { vm.matchCase = it; vm.refreshFind() }, modifier = Modifier.padding(horizontal = Spacing.s))
                Text("Whole words", style = MaterialTheme.typography.bodySmall)
                Switch(checked = vm.wholeWord, onCheckedChange = { vm.wholeWord = it; vm.refreshFind() }, modifier = Modifier.padding(horizontal = Spacing.s))
                TextButton(onClick = { showReplace = !showReplace }) { Text(if (showReplace) "Hide replace" else "Replace") }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l)) {
                    TextField(value = vm.replaceText, onValueChange = { vm.replaceText = it }, singleLine = true, placeholder = { Text("Replace with") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::replaceCurrent, enabled = vm.matches.isNotEmpty()) { Text("Replace") }
                    TextButton(onClick = vm::replaceAll, enabled = vm.matches.isNotEmpty()) { Text("All") }
                }
            }
        }
    }
}

/** Every document function, searchable from the top bar. */
fun docsCommands(
    vm: DocsViewModel,
    openSheet: (DocsSheet) -> Unit,
    startFind: () -> Unit,
    pickImage: () -> Unit,
    exportPdf: () -> Unit,
    saveCopy: () -> Unit,
    onCompatibility: () -> Unit,
): List<Command> {
    val g = "Document"
    val f = "Format"
    val i = "Insert"
    return buildList {
        add(Command("bold", "Bold", f, Icons.Default.FormatBold, listOf("strong")) { vm.toggleBold() })
        add(Command("italic", "Italic", f, Icons.Default.FormatItalic) { vm.toggleItalic() })
        add(Command("underline", "Underline", f, Icons.Default.FormatUnderlined) { vm.toggleUnderline() })
        add(Command("strike", "Strikethrough", f, Icons.Default.FormatStrikethrough, listOf("cross out")) { vm.toggleStrike() })
        add(Command("clear", "Clear formatting", f, Icons.Default.FormatClear, listOf("reset", "plain")) { vm.applyRunPatch(RunPatch(clearDirect = true), "Clear formatting") })
        add(Command("text", "Font, size and colour", f, null, listOf("font", "size", "color", "colour", "highlight")) { openSheet(DocsSheet.TEXT) })
        add(Command("normal", "Normal text", f, null, listOf("body", "style")) { vm.setHeading(0) })
        for (l in 1..6) add(Command("h$l", "Heading $l", f, null, listOf("title", "style")) { vm.setHeading(l) })
        add(Command("paragraph", "Paragraph format", f, null, listOf("alignment", "spacing", "indent", "style")) { openSheet(DocsSheet.PARAGRAPH) })
        add(Command("left", "Align left", f, Icons.AutoMirrored.Filled.FormatAlignLeft) { vm.setAlignment(DocAlignment.LEFT) })
        add(Command("center", "Centre", f, Icons.Default.FormatAlignCenter, listOf("center")) { vm.setAlignment(DocAlignment.CENTER) })
        add(Command("right", "Align right", f, Icons.AutoMirrored.Filled.FormatAlignRight) { vm.setAlignment(DocAlignment.RIGHT) })
        add(Command("justify", "Justify", f, Icons.Default.FormatAlignJustify) { vm.setAlignment(DocAlignment.JUSTIFY) })
        add(Command("bullets", "Bulleted list", f, Icons.AutoMirrored.Filled.FormatListBulleted, listOf("list")) { vm.toggleBullets() })
        add(Command("numbers", "Numbered list", f, Icons.Default.FormatListNumbered, listOf("list", "numbering")) { vm.toggleNumbering() })
        add(Command("indent+", "Increase indent", f, Icons.AutoMirrored.Filled.FormatIndentIncrease) { vm.changeIndent(true) })
        add(Command("indent-", "Decrease indent", f, Icons.AutoMirrored.Filled.FormatIndentDecrease) { vm.changeIndent(false) })
        add(Command("single", "Single line spacing", f, Icons.Default.FormatLineSpacing, listOf("line spacing")) { vm.setLineSpacing(1f) })
        add(Command("double", "Double line spacing", f, Icons.Default.FormatLineSpacing, listOf("line spacing")) { vm.setLineSpacing(2f) })
        add(Command("table", "Insert table", i, Icons.Default.TableChart) { openSheet(DocsSheet.INSERT) })
        add(Command("image", "Insert image", i, Icons.Outlined.Photo, listOf("picture", "photo")) { pickImage() })
        add(Command("link", "Insert link", i, Icons.Default.Link, listOf("hyperlink", "url")) { openSheet(DocsSheet.LINK) })
        add(Command("pagebreak", "Insert page break", i, Icons.Default.HorizontalRule) { vm.insertPageBreak() })
        add(Command("newpara", "New paragraph below", i, Icons.Default.Add) { vm.insertParagraphBelow() })
        add(Command("header", "Edit header and footer", i, Icons.Default.ViewDay, listOf("footer", "page number")) { openSheet(DocsSheet.HEADER_FOOTER) })
        add(Command("tableops", "Table rows and columns", i, Icons.Default.TableChart, listOf("row", "column", "cell")) { openSheet(DocsSheet.TABLE) })
        add(Command("find", "Find and replace", g, null, listOf("search", "replace")) { startFind() })
        add(Command("count", "Word count", g, null, listOf("words", "characters", "statistics")) { openSheet(DocsSheet.WORD_COUNT) })
        add(Command("page", "Page setup", g, null, listOf("margins", "orientation", "size", "landscape", "portrait", "a4", "letter")) { openSheet(DocsSheet.PAGE) })
        add(Command("pdf", "Export as PDF", g, Icons.Default.PictureAsPdf, listOf("print", "export")) { exportPdf() })
        add(Command("copy", "Save a copy", g, null, listOf("save as", "duplicate")) { saveCopy() })
        add(Command("undo", "Undo", g, null) { vm.undo() })
        add(Command("redo", "Redo", g, null) { vm.redo() })
        add(Command("zoomin", "Zoom in", g, null, listOf("larger")) { vm.zoomBy(1.15f) })
        add(Command("zoomout", "Zoom out", g, null, listOf("smaller")) { vm.zoomBy(1 / 1.15f) })
        add(Command("delpara", "Delete paragraph", g, Icons.Default.Delete) { vm.deleteFocusedParagraph() })
        add(Command("compat", "Compatibility", g, null, listOf("preserved", "read-only", "supported")) { onCompatibility() })
    }
}
