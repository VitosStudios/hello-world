package com.vitosstudios.suite.feature.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.BorderClear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitosstudios.suite.appContainer
import com.vitosstudios.suite.feature.common.EditorLifecycle
import com.vitosstudios.suite.feature.common.EditorTopActions
import com.vitosstudios.suite.feature.common.LoadingOrError
import com.vitosstudios.suite.ui.components.ColorPalette
import com.vitosstudios.suite.ui.components.Command
import com.vitosstudios.suite.ui.components.ConfirmDialog
import com.vitosstudios.suite.ui.components.ContextToolbar
import com.vitosstudios.suite.ui.components.LabeledSlider
import com.vitosstudios.suite.ui.components.SuiteTopBar
import com.vitosstudios.suite.ui.components.ToolbarAction
import com.vitosstudios.suite.ui.theme.FormulaTextStyle
import com.vitosstudios.suite.ui.theme.Spacing
import com.vitosstudios.suite.xlsx.ChartType
import com.vitosstudios.suite.xlsx.NumberFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SheetsSheet { FORMAT, FILL, TEXT_COLOR, NUMBER, SIZE, SORT_FILTER, CHART, SHEET_OPTIONS, INFO }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SheetsEditorScreen(
    onBack: () -> Unit,
    onCompatibility: () -> Unit,
    vm: SheetsViewModel = viewModel(factory = SheetsViewModel.Factory),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    EditorLifecycle(vm, snackbar)
    var sheet by remember { mutableStateOf<SheetsSheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    val csvOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val f = File(context.appContainer.files.exportDir(), "export.csv").apply { writeText(vm.exportCsv()) }
            vm.notify(context.appContainer.files.writeTo(uri, f) ?: "CSV exported")
        }
    }
    val csvIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            if (text == null) vm.notify("The file could not be read") else vm.importCsv(text, uri.lastPathSegment?.substringAfterLast('/') ?: "Imported")
        }
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(
        if (vm.csvMode) "text/csv" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )) { uri ->
        if (uri != null) scope.launch { vm.flush(); vm.notify(context.appContainer.files.exportCopy(vm.fileId, uri) ?: "Copy saved") }
    }

    val commands = buildList {
        add(Command("edit", "Edit cell", "Cells", Icons.Default.Edit, listOf("type", "enter")) { vm.startEditing() })
        add(Command("bold", "Bold", "Format", Icons.Default.FormatBold) { vm.toggleBold() })
        add(Command("italic", "Italic", "Format", Icons.Default.FormatItalic) { vm.toggleItalic() })
        add(Command("underline", "Underline", "Format", Icons.Default.FormatUnderlined) { vm.toggleUnderline() })
        add(Command("wrap", "Wrap text", "Format", Icons.AutoMirrored.Filled.WrapText) { vm.toggleWrap() })
        add(Command("fill", "Fill colour", "Format", Icons.Default.FormatColorFill, listOf("background")) { sheet = SheetsSheet.FILL })
        add(Command("color", "Text colour", "Format", Icons.Default.FormatColorText) { sheet = SheetsSheet.TEXT_COLOR })
        add(Command("borders", "Borders", "Format", Icons.Default.BorderAll) { vm.borders(true) })
        add(Command("noborders", "Remove borders", "Format", Icons.Default.BorderClear) { vm.borders(null) })
        add(Command("left", "Align left", "Format", Icons.AutoMirrored.Filled.FormatAlignLeft) { vm.align("left") })
        add(Command("center", "Centre", "Format", Icons.Default.FormatAlignCenter, listOf("center")) { vm.align("center") })
        add(Command("right", "Align right", "Format", Icons.AutoMirrored.Filled.FormatAlignRight) { vm.align("right") })
        add(Command("numfmt", "Number format", "Format", Icons.Default.Numbers, listOf("currency", "percent", "date", "decimal")) { sheet = SheetsSheet.NUMBER })
        add(Command("currency", "Currency format", "Format", null) { vm.numberFormat(NumberFormats.CURRENCY) })
        add(Command("percent", "Percent format", "Format", null) { vm.numberFormat(NumberFormats.PERCENT) })
        add(Command("date", "Date format", "Format", null) { vm.numberFormat(NumberFormats.DATE_ISO) })
        add(Command("size", "Column width and row height", "Cells", Icons.Default.ViewColumn, listOf("resize")) { sheet = SheetsSheet.SIZE })
        add(Command("freeze", "Freeze panes at selection", "View", null, listOf("lock rows", "header")) { vm.freezeAtSelection() })
        add(Command("unfreeze", "Unfreeze panes", "View", null) { vm.unfreeze() })
        add(Command("rowabove", "Insert row above", "Cells", Icons.Default.TableRows) { vm.insertRow(true) })
        add(Command("rowbelow", "Insert row below", "Cells", Icons.Default.TableRows) { vm.insertRow(false) })
        add(Command("colleft", "Insert column left", "Cells", Icons.Default.ViewColumn) { vm.insertColumn(true) })
        add(Command("colright", "Insert column right", "Cells", Icons.Default.ViewColumn) { vm.insertColumn(false) })
        add(Command("delrow", "Delete rows", "Cells", null) { vm.deleteRow() })
        add(Command("delcol", "Delete columns", "Cells", null) { vm.deleteColumn() })
        add(Command("clear", "Clear cells", "Cells", null, listOf("delete contents")) { vm.clearSelection() })
        add(Command("sortaz", "Sort A to Z", "Data", Icons.AutoMirrored.Filled.Sort, listOf("ascending")) { vm.sort(true, true) })
        add(Command("sortza", "Sort Z to A", "Data", Icons.AutoMirrored.Filled.Sort, listOf("descending")) { vm.sort(false, true) })
        add(Command("filter", "Filter", "Data", Icons.Default.FilterList) { sheet = SheetsSheet.SORT_FILTER })
        add(Command("clearfilter", "Clear filter", "Data", null) { vm.clearFilter() })
        add(Command("bar", "Insert bar chart", "Insert", Icons.Default.BarChart, listOf("chart", "graph", "column")) { vm.insertChart(ChartType.COLUMN) })
        add(Command("line", "Insert line chart", "Insert", null, listOf("chart", "graph")) { vm.insertChart(ChartType.LINE) })
        add(Command("pie", "Insert pie chart", "Insert", null, listOf("chart", "graph")) { vm.insertChart(ChartType.PIE) })
        add(Command("addsheet", "Add sheet", "Sheets", Icons.Default.Add, listOf("tab", "new")) { vm.addSheet() })
        add(Command("csvout", "Export sheet as CSV", "File", null, listOf("comma")) { csvOut.launch((vm.entry?.displayName ?: "sheet").substringBeforeLast('.') + ".csv") })
        add(Command("csvin", "Import CSV as new sheet", "File", null, listOf("comma")) { csvIn.launch(arrayOf("text/*")) })
        add(Command("copy", "Save a copy", "File", null) { saveCopy.launch(vm.entry?.displayName ?: "Sheet.xlsx") })
        add(Command("info", "Conditional formats and sheet info", "Data", null, listOf("rules")) { sheet = SheetsSheet.INFO })
        add(Command("compat", "Compatibility", "File", null) { onCompatibility() })
        add(Command("zoomin", "Zoom in", "View", null) { vm.zoomBy(1.15f) })
        add(Command("zoomout", "Zoom out", "View", null) { vm.zoomBy(1 / 1.15f) })
        add(Command("undo", "Undo", "Edit", null) { vm.undo() })
        add(Command("redo", "Redo", "Edit", null) { vm.redo() })
    }

    Scaffold(
        topBar = {
            SuiteTopBar(
                title = vm.entry?.displayName ?: "Spreadsheet",
                subtitle = if (vm.csvMode) "CSV" else "XLSX",
                onBack = { vm.flush(); onBack() },
                commands = commands,
                actions = {
                    EditorTopActions(vm)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Freeze panes at selection") }, onClick = { menu = false; vm.freezeAtSelection() })
                            DropdownMenuItem(text = { Text("Unfreeze") }, onClick = { menu = false; vm.unfreeze() })
                            DropdownMenuItem(text = { Text("Export sheet as CSV") }, onClick = { menu = false; csvOut.launch((vm.entry?.displayName ?: "sheet").substringBeforeLast('.') + ".csv") })
                            if (!vm.csvMode) DropdownMenuItem(text = { Text("Import CSV as new sheet") }, onClick = { menu = false; csvIn.launch(arrayOf("text/*")) })
                            DropdownMenuItem(text = { Text("Save a copy") }, onClick = { menu = false; saveCopy.launch(vm.entry?.displayName ?: "Sheet.xlsx") })
                            DropdownMenuItem(text = { Text("Sheet info") }, onClick = { menu = false; sheet = SheetsSheet.INFO })
                            DropdownMenuItem(text = { Text("Compatibility") }, onClick = { menu = false; onCompatibility() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                ContextToolbar(true, listOf(
                    ToolbarAction("Edit cell", Icons.Default.Edit) { vm.startEditing() },
                    ToolbarAction("Bold", Icons.Default.FormatBold, selected = vm.cell(vm.focus.row, vm.focus.col)?.style?.bold == true) { vm.toggleBold() },
                    ToolbarAction("Italic", Icons.Default.FormatItalic, selected = vm.cell(vm.focus.row, vm.focus.col)?.style?.italic == true) { vm.toggleItalic() },
                    ToolbarAction("Fill colour", Icons.Default.FormatColorFill) { sheet = SheetsSheet.FILL },
                    ToolbarAction("Text colour", Icons.Default.FormatColorText) { sheet = SheetsSheet.TEXT_COLOR },
                    ToolbarAction("Number format", Icons.Default.Numbers) { sheet = SheetsSheet.NUMBER },
                    ToolbarAction("Cell format", Icons.Default.BorderAll) { sheet = SheetsSheet.FORMAT },
                    ToolbarAction("Rows and columns", Icons.Default.TableRows) { sheet = SheetsSheet.SIZE },
                    ToolbarAction("Sort and filter", Icons.Default.FilterList) { sheet = SheetsSheet.SORT_FILTER },
                    ToolbarAction("Chart", Icons.Default.BarChart) { sheet = SheetsSheet.CHART },
                ))
                SheetTabs(vm, onRename = { renameIndex = it }, onDelete = { deleteIndex = it }, onOptions = { sheet = SheetsSheet.SHEET_OPTIONS })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (LoadingOrError(vm.document == null, vm.loadError)) {
                FormulaBar(vm)
                val v = vm.version
                if (vm.sheet() == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("This is a chart sheet. It is shown in the list and kept unchanged.", modifier = Modifier.padding(Spacing.xl))
                    }
                } else {
                    SheetGrid(vm, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                }
            }
        }
    }

    renameIndex?.let { i ->
        var name by remember(i) { mutableStateOf(vm.document?.sheets?.getOrNull(i)?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text("Rename sheet") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { renameIndex = null; vm.renameSheet(i, name) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameIndex = null }) { Text("Cancel") } },
        )
    }
    deleteIndex?.let { i ->
        ConfirmDialog("Delete sheet?", "“${vm.document?.sheets?.getOrNull(i)?.name}” and its content will be removed. You can undo this.", "Delete",
            onConfirm = { vm.deleteSheet(i) }, onDismiss = { deleteIndex = null })
    }

    sheet?.let { s ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l).padding(bottom = Spacing.xl)) {
                when (s) {
                    SheetsSheet.FILL -> {
                        Text("Fill colour", style = MaterialTheme.typography.titleMedium)
                        ColorPalette(selected = null, onSelect = { vm.fill(it?.toArgb()); sheet = null })
                    }
                    SheetsSheet.TEXT_COLOR -> {
                        Text("Text colour", style = MaterialTheme.typography.titleMedium)
                        ColorPalette(selected = null, onSelect = { vm.fontColor(it?.toArgb()); sheet = null })
                    }
                    SheetsSheet.NUMBER -> {
                        Text("Number format", style = MaterialTheme.typography.titleMedium)
                        listOf(
                            "General" to NumberFormats.GENERAL, "Number (1,234.56)" to NumberFormats.NUMBER, "Whole number (1,235)" to NumberFormats.INTEGER,
                            "Currency ($1,234.56)" to NumberFormats.CURRENCY, "Percent (12%)" to NumberFormats.PERCENT, "Percent (12.34%)" to NumberFormats.PERCENT_2,
                            "Date (2024-03-15)" to NumberFormats.DATE_ISO, "Date (3/15/2024)" to NumberFormats.DATE_US, "Time (13:30)" to NumberFormats.TIME,
                            "Date and time" to NumberFormats.DATE_TIME, "Scientific" to NumberFormats.SCIENTIFIC, "Text" to NumberFormats.TEXT,
                        ).forEach { (label, code) ->
                            ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { vm.numberFormat(code); sheet = null })
                        }
                    }
                    SheetsSheet.FORMAT -> {
                        Text("Cell format", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            val st = vm.cell(vm.focus.row, vm.focus.col)?.style
                            FilterChip(selected = st?.bold == true, onClick = vm::toggleBold, label = { Text("Bold") })
                            FilterChip(selected = st?.italic == true, onClick = vm::toggleItalic, label = { Text("Italic") })
                            FilterChip(selected = st?.underline == true, onClick = vm::toggleUnderline, label = { Text("Underline") })
                            FilterChip(selected = st?.wrapText == true, onClick = vm::toggleWrap, label = { Text("Wrap text") })
                            FilterChip(selected = st?.horizontal == "left", onClick = { vm.align("left") }, label = { Text("Left") })
                            FilterChip(selected = st?.horizontal == "center", onClick = { vm.align("center") }, label = { Text("Centre") })
                            FilterChip(selected = st?.horizontal == "right", onClick = { vm.align("right") }, label = { Text("Right") })
                            FilterChip(selected = false, onClick = { vm.borders(true) }, label = { Text("All borders") })
                            FilterChip(selected = false, onClick = { vm.borders(null) }, label = { Text("No borders") })
                            FilterChip(selected = false, onClick = { vm.fontSize(-1.0) }, label = { Text("Smaller text") })
                            FilterChip(selected = false, onClick = { vm.fontSize(1.0) }, label = { Text("Larger text") })
                            FilterChip(selected = false, onClick = { vm.clearSelection(); sheet = null }, label = { Text("Clear contents") })
                        }
                    }
                    SheetsSheet.SIZE -> {
                        val ws = vm.sheet()
                        var width by remember { mutableStateOf((ws?.columnWidth(vm.focus.col) ?: 8.43).toFloat()) }
                        var height by remember { mutableStateOf((ws?.rowHeight(vm.focus.row) ?: 15.0).toFloat()) }
                        Text("Rows and columns", style = MaterialTheme.typography.titleMedium)
                        LabeledSlider("Column width (characters)", width, 1f..60f, onChange = { width = it }, onChangeFinished = { vm.setColumnWidth(width.toDouble()) }, format = { "%.1f".format(it) })
                        LabeledSlider("Row height (points)", height, 8f..120f, onChange = { height = it }, onChangeFinished = { vm.setRowHeight(height.toDouble()) })
                        val blocker = ws?.structureEditBlocker
                        if (blocker != null) Text(blocker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            FilterChip(selected = false, enabled = blocker == null, onClick = { vm.insertRow(true) }, label = { Text("Row above") })
                            FilterChip(selected = false, enabled = blocker == null, onClick = { vm.insertRow(false) }, label = { Text("Row below") })
                            FilterChip(selected = false, enabled = blocker == null, onClick = { vm.insertColumn(true) }, label = { Text("Column left") })
                            FilterChip(selected = false, enabled = blocker == null, onClick = { vm.insertColumn(false) }, label = { Text("Column right") })
                            FilterChip(selected = false, enabled = blocker == null, onClick = { vm.deleteRow() }, label = { Text("Delete rows") })
                            FilterChip(selected = false, enabled = blocker == null, onClick = { vm.deleteColumn() }, label = { Text("Delete columns") })
                            FilterChip(selected = false, onClick = { vm.freezeAtSelection() }, label = { Text("Freeze above and left") })
                            FilterChip(selected = false, onClick = { vm.unfreeze() }, label = { Text("Unfreeze") })
                        }
                    }
                    SheetsSheet.SORT_FILTER -> SortFilterPanel(vm) { sheet = null }
                    SheetsSheet.CHART -> {
                        Text("Insert chart from the selection", style = MaterialTheme.typography.titleMedium)
                        Text("Select the data first (long-press and drag). The first row or column is used for labels.", style = MaterialTheme.typography.bodySmall)
                        listOf(ChartType.COLUMN to "Bar chart (vertical)", ChartType.BAR to "Bar chart (horizontal)", ChartType.LINE to "Line chart", ChartType.PIE to "Pie chart").forEach { (t, n) ->
                            ListItem(headlineContent = { Text(n) }, modifier = Modifier.clickable { vm.insertChart(t); sheet = null })
                        }
                    }
                    SheetsSheet.SHEET_OPTIONS -> {
                        Text("Sheet: ${vm.document?.sheets?.getOrNull(vm.sheetIndex)?.name}", style = MaterialTheme.typography.titleMedium)
                        ListItem(headlineContent = { Text("Rename") }, modifier = Modifier.clickable { sheet = null; renameIndex = vm.sheetIndex })
                        ListItem(headlineContent = { Text("Move left") }, modifier = Modifier.clickable { vm.moveSheet(vm.sheetIndex, vm.sheetIndex - 1) })
                        ListItem(headlineContent = { Text("Move right") }, modifier = Modifier.clickable { vm.moveSheet(vm.sheetIndex, vm.sheetIndex + 1) })
                        ListItem(headlineContent = { Text("Delete") }, modifier = Modifier.clickable { sheet = null; deleteIndex = vm.sheetIndex })
                    }
                    SheetsSheet.INFO -> {
                        val ws = vm.sheet()
                        Text("Sheet information", style = MaterialTheme.typography.titleMedium)
                        Text("Conditional formatting rules are kept and shown here; editing them is not supported in this version.", style = MaterialTheme.typography.bodySmall)
                        val cfs = ws?.conditionalFormats.orEmpty()
                        if (cfs.isEmpty()) Text("No conditional formatting.") else cfs.forEach { Text("• $it") }
                        HorizontalDivider(Modifier.padding(vertical = Spacing.s))
                        Text("Merged ranges: ${ws?.mergedRanges?.size ?: 0} (structure kept)")
                        Text("Data validation rules: ${ws?.dataValidationCount ?: 0} (kept, not enforced)")
                        Text("Tables: ${ws?.tables?.size ?: 0} • Comments: ${ws?.commentCount ?: 0} (kept)")
                        Text("Charts: ${ws?.charts()?.size ?: 0} • Pictures: ${ws?.pictures()?.size ?: 0}")
                        ws?.autoFilterRange?.let { Text("Filter range: ${it}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormulaBar(vm: SheetsViewModel) {
    val v = vm.version
    val cell = vm.cell(vm.focus.row, vm.focus.col)
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.selection.isSingleCell) vm.focus.a1 else vm.selection.toString(),
                style = FormulaTextStyle,
                modifier = Modifier.width(76.dp).semantics { contentDescription = "Selected ${vm.focus.a1}" },
            )
            Icon(Icons.Default.Functions, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.editing) {
                OutlinedTextField(
                    value = vm.editText,
                    onValueChange = { vm.editText = it },
                    singleLine = true,
                    textStyle = FormulaTextStyle,
                    modifier = Modifier.weight(1f).padding(start = Spacing.s),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.commitEdit() }),
                )
                IconButton(onClick = { vm.commitEdit() }) { Icon(Icons.Default.Check, contentDescription = "Apply") }
                IconButton(onClick = { vm.cancelEdit() }) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
            } else {
                Text(
                    vm.inputTextOf(cell),
                    style = FormulaTextStyle,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Edit cell") { vm.startEditing() }
                        .padding(start = Spacing.s, top = 14.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetTabs(vm: SheetsViewModel, onRename: (Int) -> Unit, onDelete: (Int) -> Unit, onOptions: () -> Unit) {
    val v = vm.version
    val sheets = vm.document?.sheets.orEmpty()
    Surface(tonalElevation = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f)) {
                itemsIndexed(sheets, key = { _, s -> s.sheetId }) { i, s ->
                    val selected = i == vm.sheetIndex
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(onClick = { vm.selectSheet(i) }, onLongClick = { vm.selectSheet(i); onOptions() }, onLongClickLabel = "Sheet options")
                            .padding(horizontal = Spacing.l, vertical = 14.dp)
                            .semantics { this.selected = selected; contentDescription = "Sheet ${s.name}" + if (s.state.name != "VISIBLE") ", hidden" else "" },
                    ) {
                        Text(s.name + if (s.isChartsheet) " (chart)" else "", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            IconButton(onClick = vm::addSheet) { Icon(Icons.Default.Add, contentDescription = "Add sheet") }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = onRename to onDelete
}

@Composable
private fun SortFilterPanel(vm: SheetsViewModel, close: () -> Unit) {
    var header by remember { mutableStateOf(true) }
    val values = remember { vm.filterValues() }
    val chosen = remember { mutableStateOf(values.toSet()) }
    Text("Sort and filter by column ${com.vitosstudios.suite.xlsx.CellRef.columnName(vm.focus.col)}", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = header, onCheckedChange = { header = it })
        Text("First row is a header")
    }
    Row {
        TextButton(onClick = { vm.sort(true, header); close() }) { Text("Sort A → Z") }
        TextButton(onClick = { vm.sort(false, header); close() }) { Text("Sort Z → A") }
    }
    HorizontalDivider()
    Text("Show only rows with these values", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.s))
    values.take(200).forEach { value ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
            chosen.value = if (value in chosen.value) chosen.value - value else chosen.value + value
        }) {
            Checkbox(checked = value in chosen.value, onCheckedChange = null)
            Text(value.ifEmpty { "(empty)" })
        }
    }
    Row {
        TextButton(onClick = { vm.applyFilter(chosen.value); close() }) { Text("Apply filter") }
        TextButton(onClick = { vm.clearFilter(); close() }) { Text("Clear filter") }
    }
}
