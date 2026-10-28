package com.vitosstudios.suite.feature.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitosstudios.suite.AppContainer
import com.vitosstudios.suite.SuiteApplication
import com.vitosstudios.suite.common.AtomicFile
import com.vitosstudios.suite.data.FileKind
import com.vitosstudios.suite.feature.common.OfficeEditorViewModel
import com.vitosstudios.suite.xlsx.BorderPatch
import com.vitosstudios.suite.xlsx.BorderSide
import com.vitosstudios.suite.xlsx.CellRange
import com.vitosstudios.suite.xlsx.CellRef
import com.vitosstudios.suite.xlsx.CellView
import com.vitosstudios.suite.xlsx.ChartType
import com.vitosstudios.suite.xlsx.Csv
import com.vitosstudios.suite.xlsx.StylePatch
import com.vitosstudios.suite.xlsx.Worksheet
import com.vitosstudios.suite.xlsx.XlsxWorkbook
import java.io.File

/**
 * XLSX (and CSV) editor state. CSV files are edited as a one-sheet workbook in memory and saved
 * back as CSV, so a file keeps the format it was opened in.
 */
class SheetsViewModel(container: AppContainer, handle: SavedStateHandle) : OfficeEditorViewModel<XlsxWorkbook>(container, handle) {
    private var isCsv = false
    private var csvDelimiter = ','

    override fun openDocument(file: File): XlsxWorkbook {
        if (entry?.kind == FileKind.CSV) {
            isCsv = true
            val text = file.readText()
            csvDelimiter = Csv.detectDelimiter(text)
            return XlsxWorkbook.fromCsv(text, "Sheet1", csvDelimiter)
        }
        return XlsxWorkbook.open(file)
    }

    override fun verifyWritten(file: File) = XlsxWorkbook.verifyReadable(file)

    override fun snapshotForSave(doc: XlsxWorkbook): OfficeEditorViewModel.SaveSnapshot {
        if (!isCsv) return super.snapshotForSave(doc)
        val text = doc.exportCsv(0, csvDelimiter, displayed = false)
        val marker = doc.undo.stateMarker
        return object : OfficeEditorViewModel.SaveSnapshot {
            override val undoMarker: Long = marker
            override fun write(target: File) {
                AtomicFile.write(target, verify = { tmp -> Csv.parse(tmp.readText(), csvDelimiter) }) { it.write(text.toByteArray()) }
            }
        }
    }

    val csvMode: Boolean get() = isCsv

    var sheetIndex by mutableIntStateOf(0)
        private set
    /** Selection anchor and focus (1-based rows and columns). */
    var anchor by mutableStateOf(CellRef(1, 1))
        private set
    var focus by mutableStateOf(CellRef(1, 1))
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var editing by mutableStateOf(false)
    var editText by mutableStateOf("")

    val selection: CellRange get() = CellRange(anchor.row, anchor.col, focus.row, focus.col)

    fun sheet(): Worksheet? {
        val d = document ?: return null
        val idx = sheetIndex.coerceIn(0, (d.sheets.size - 1).coerceAtLeast(0))
        val info = d.sheets.getOrNull(idx) ?: return null
        if (info.isChartsheet || info.partName == null) return null
        return d.sheet(idx)
    }

    private val cellCache = HashMap<Long, CellView?>()
    private var cacheKey = Pair(-1L, -1)

    /** Cached cell read for drawing; cleared on every model change. */
    fun cell(row: Int, col: Int): CellView? {
        val key = version to sheetIndex
        if (key != cacheKey) { cellCache.clear(); cacheKey = key }
        val k = (row.toLong() shl 20) or col.toLong()
        return cellCache.getOrPut(k) { sheet()?.cell(row, col) }
    }

    fun selectSheet(i: Int) {
        sheetIndex = i
        anchor = CellRef(1, 1)
        focus = CellRef(1, 1)
        editing = false
    }

    fun select(row: Int, col: Int, extend: Boolean = false) {
        val r = row.coerceAtLeast(1)
        val c = col.coerceAtLeast(1)
        if (extend) focus = CellRef(r, c) else { anchor = CellRef(r, c); focus = CellRef(r, c) }
        editing = false
    }

    fun move(dRow: Int, dCol: Int) = select(focus.row + dRow, focus.col + dCol)

    /** Text shown in the formula bar for the focused cell. */
    fun inputTextOf(c: CellView?): String = when {
        c == null -> ""
        c.formula != null -> "=" + c.formula
        c.isDate -> c.display
        c.value is Double -> {
            val d = c.value as Double
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        c.value is Boolean -> if (c.value == true) "TRUE" else "FALSE"
        else -> c.value?.toString() ?: ""
    }

    fun startEditing(initial: String? = null) {
        editText = initial ?: inputTextOf(sheet()?.cell(focus.row, focus.col))
        editing = true
    }

    fun commitEdit(moveDown: Boolean = true) {
        if (!editing) return
        val text = editText
        edit("Edit cell") { sheet()?.setCellInput(focus.row, focus.col, text) }
        editing = false
        if (moveDown) move(1, 0)
    }

    fun cancelEdit() { editing = false }

    fun clearSelection() = edit("Clear") { sheet()?.clearRange(selection) }

    fun style(label: String, patch: StylePatch) = edit(label) { sheet()?.applyStyle(selection, patch) }

    fun toggleBold() = style("Bold", StylePatch(bold = !(cell(focus.row, focus.col)?.style?.bold ?: false)))
    fun toggleItalic() = style("Italic", StylePatch(italic = !(cell(focus.row, focus.col)?.style?.italic ?: false)))
    fun toggleUnderline() = style("Underline", StylePatch(underline = !(cell(focus.row, focus.col)?.style?.underline ?: false)))
    fun toggleWrap() = style("Wrap text", StylePatch(wrapText = !(cell(focus.row, focus.col)?.style?.wrapText ?: false)))
    fun align(h: String) = style("Alignment", StylePatch(horizontal = h))
    fun numberFormat(code: String) = style("Number format", StylePatch(numberFormat = code))
    fun fill(argb: Int?) = style("Fill", if (argb == null) StylePatch(clearFill = true) else StylePatch(fillColor = argb))
    fun fontColor(argb: Int?) = style("Text colour", if (argb == null) StylePatch(clearFontColor = true) else StylePatch(fontColor = argb))
    fun fontSize(delta: Double) = style("Font size", StylePatch(fontSize = ((cell(focus.row, focus.col)?.style?.fontSize ?: 11.0) + delta).coerceIn(6.0, 72.0)))

    fun borders(all: Boolean?) {
        val patch = when (all) {
            null -> BorderPatch(BorderSide.entries.toSet(), null)
            else -> BorderPatch(BorderSide.entries.toSet(), "thin")
        }
        style("Borders", StylePatch(border = patch))
    }

    fun setColumnWidth(width: Double) = edit("Column width") { (selection.left..selection.right).forEach { sheet()?.setColumnWidth(it, width) } }
    fun setRowHeight(height: Double) = edit("Row height") { (selection.top..selection.bottom).forEach { sheet()?.setRowHeight(it, height) } }
    fun freezeAtSelection() = edit("Freeze panes") { sheet()?.freezePanes(focus.row - 1, focus.col - 1) }
    fun unfreeze() = edit("Unfreeze") { sheet()?.freezePanes(0, 0) }

    fun insertRow(above: Boolean) = edit("Insert row") { sheet()?.insertRows(if (above) focus.row else focus.row + 1, 1) }
    fun deleteRow() = edit("Delete row") { sheet()?.deleteRows(selection.top, selection.rowCount) }
    fun insertColumn(left: Boolean) = edit("Insert column") { sheet()?.insertColumns(if (left) focus.col else focus.col + 1, 1) }
    fun deleteColumn() = edit("Delete column") { sheet()?.deleteColumns(selection.left, selection.colCount) }

    /** Range to sort or filter: the selection, or the used range when a single cell is selected. */
    fun dataRange(): CellRange? {
        val s = sheet() ?: return null
        return if (selection.isSingleCell) s.usedRange() else selection
    }

    fun sort(ascending: Boolean, hasHeader: Boolean) {
        val range = dataRange() ?: return
        edit("Sort") { sheet()?.sortRange(range, focus.col, ascending, hasHeader) }
    }

    fun filterValues(): List<String> {
        val range = dataRange() ?: return emptyList()
        return sheet()?.filterValues(range, focus.col).orEmpty()
    }

    fun applyFilter(allowed: Set<String>) {
        val range = dataRange() ?: return
        edit("Filter") { sheet()?.setFilter(range, focus.col, allowed) }
    }

    fun clearFilter() = edit("Clear filter") { sheet()?.clearFilter() }

    fun insertChart(type: ChartType) {
        val data = selection.takeIf { !it.isSingleCell } ?: dataRange() ?: return notify("Select the data for the chart first")
        val anchor = CellRange(data.top, data.right + 2, data.top + 15, data.right + 9)
        edit("Insert chart") { sheet()?.createChart(type, data, anchor, null) }
    }

    fun addSheet() {
        val d = document ?: return
        edit("Add sheet") { d.addSheet() }
        selectSheet(d.sheets.size - 1)
    }

    fun renameSheet(i: Int, name: String) = edit("Rename sheet") { it.renameSheet(i, name) }

    fun deleteSheet(i: Int) {
        edit("Delete sheet") { it.deleteSheet(i) }
        selectSheet((i - 1).coerceAtLeast(0))
    }

    fun moveSheet(from: Int, to: Int) {
        val d = document ?: return
        if (to !in d.sheets.indices) return
        edit("Move sheet") { it.moveSheet(from, to) }
        selectSheet(to)
    }

    fun importCsv(text: String, name: String) {
        val d = document ?: return
        edit("Import CSV") { d.importCsvAsSheet(text, name.substringBeforeLast('.').take(31)) }
        selectSheet(d.sheets.size - 1)
    }

    fun exportCsv(): String = document?.exportCsv(sheetIndex, ',', displayed = true) ?: ""

    fun zoomBy(f: Float) { zoom = (zoom * f).coerceIn(0.5f, 2.5f) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SuiteApplication
                SheetsViewModel(app.container, createSavedStateHandle())
            }
        }
    }
}
