# XLSX compatibility

Status of every spreadsheet element the app knows about. "Preserved" means the element is kept
exactly as it was in the file (byte-identical when its part is not edited, element-identical when
the part is edited). Anything not listed is preserved the same way: the app never drops content
it does not understand.

| Element | Status | Notes |
|---|---|---|
| Numbers, text, booleans, errors | Editable | Typed input is interpreted like a spreadsheet: numbers with thousands separators, `12%`, `$1,234.50`, `10 €`, ISO (`2024-03-15`) and US (`3/15/2024`) dates, times, `TRUE`/`FALSE`, `'` forces text. |
| Dates and times | Editable | Stored as serial numbers; 1900 (with its leap-year bug) and 1904 date systems. |
| Shared strings | Editable | New text is appended; existing entries (rich text, phonetic runs) are never rewritten. |
| Inline strings | Read-only → Editable | Shown; replaced by a shared string when the cell is edited. |
| Rich text inside a cell | Read-only | Shown as plain text; runs kept until the cell is edited. |
| Formulas: SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, IFERROR, ROUND, ROUNDUP, ROUNDDOWN, VLOOKUP, CONCAT, CONCATENATE, AND, OR, NOT, ABS, INT, MOD, PRODUCT, SQRT, POWER, LEN, UPPER, LOWER, TRIM, LEFT, RIGHT, MID, TEXT, ISBLANK, ISNUMBER, ISTEXT, ISERROR | Editable | Calculated in the app with spreadsheet semantics (coercion, blanks, error propagation, implicit intersection, wildcards in exact VLOOKUP). All 1,027 such formulas in the test corpus reproduce the values Excel/LibreOffice cached. |
| Operators `+ - * / ^ & % = <> < > <= >=`, A1/absolute refs, ranges, whole rows/columns, other-sheet refs, defined names that are references | Editable | |
| Other functions (e.g. NPV, XLOOKUP, SUMIFS) | Read-only | Formula text kept; the cached value is shown and used by dependent formulas. `fullCalcOnLoad` makes the next spreadsheet application recalculate them. |
| Shared formulas | Editable | Dependents are evaluated by translating the master. Editing the master first gives each dependent its own formula. |
| Array formulas, data tables | Read-only | Kept; cells inside an array range cannot be edited one by one. |
| Structured references (`Table1[Col]`), 3D refs, external refs, array constants, intersection operator | Read-only | Kept with cached values. |
| Calculation chain (`calcChain.xml`) | Preserved / removed | Kept unless a formula is removed or sheets are deleted/moved; then it is dropped (it is optional and must not list cells without formulas). |
| Number formats (built-in 0–49 and custom codes) | Editable | Sections, conditions, literals, `_`/`*`, colours, currency tags, thousands/scaling commas, percent, scientific, fractions, `@`, dates/times incl. `[h]` and fractions of seconds. All 440 Excel `TEXT()` results in the corpus are reproduced. Presets: number, integer, currency, percent, date, time, scientific, text. |
| Fonts: bold, italic, underline, strike, size, colour, name | Editable | New font records are appended; shared records are never modified. |
| Fill colour (solid) | Editable | Pattern and gradient fills are shown by their foreground colour and kept. |
| Borders (left, right, top, bottom; thin/medium/thick/dashed/dotted/double) | Editable | Diagonal borders kept. |
| Alignment (horizontal, vertical), wrap text | Editable | Indent, rotation and shrink-to-fit kept. |
| Theme and indexed colours, tint | Read-only | Resolved for display; kept. |
| Cell styles (named), differential formats (dxf) | Preserved | |
| Multiple sheets: add, rename, delete, move | Editable | Rename updates formulas on every sheet, defined names, conditional-format and validation formulas and chart references. Delete turns references into `#REF!`, fixes sheet-scoped names and removes the sheet's private parts. The last visible sheet cannot be deleted. |
| Hidden / very hidden sheets | Read-only | State shown and kept. |
| Chart sheets | Read-only | Listed and kept. |
| Column width, row height | Editable | Column ranges (`<col min max>`) are split when one column changes. |
| Hidden rows and columns | Read-only (filter edits rows) | |
| Freeze panes | Editable | |
| Split panes (not frozen) | Preserved | |
| Insert / delete rows and columns | Editable (with conditions) | Cells, formulas on all sheets, names, chart references, merged ranges, CF/DV ranges and formulas, auto-filter, hyperlinks, page breaks, widths and dimension move along. Refused, with the reason shown, on sheets with drawings, comments, tables, pivot tables, array formulas, extension-list ranges or embedded objects. |
| Merged cells | Read-only | Shown; merge/unmerge not available; sorting refuses merged ranges. |
| Sort | Editable | One key column, ascending/descending, optional header row; values and formatting move with their rows. Refused for ranges containing formulas or merged cells. |
| Simple filter (auto-filter, list of values) | Editable | Writes `autoFilter`/`filterColumn`, hides non-matching rows and maintains `_xlnm._FilterDatabase`. Custom, top-10, colour and dynamic filters are kept but not edited. |
| Conditional formatting | Preserved (listed read-only) | Rules are listed in the compatibility screen; not evaluated for display. Extended (x14) rules kept. |
| Data validation | Preserved | Not enforced in the app. |
| Hyperlinks | Read-only | Targets shown; kept. |
| Comments / notes (legacy and threaded) | Preserved | Counted in the compatibility report. |
| Tables (ListObjects) | Read-only | Cells editable; table definition kept. |
| Pivot tables and caches | Preserved | Not refreshed. |
| Charts: bar, column, line, pie | Read-only (drawn) | Data read live from the sheet (falls back to the chart cache). |
| Other chart types and combo charts | Read-only | Placeholder; kept unchanged. |
| New charts: bar, column, line, pie | Editable (create) | New drawing (or the sheet's existing one), chart part with references and caches. |
| Pictures | Read-only | Anchor and image part exposed for display; kept. |
| Shapes, text boxes, groups, connectors | Preserved | Counted; not drawn. |
| Sparklines, slicers, timelines | Preserved | |
| External links | Preserved | Never refreshed (the app makes no network calls). |
| Macros (`vbaProject.bin`) | Preserved | Byte-identical; never run. |
| Page setup, margins, headers/footers, print areas, printer settings | Preserved | Print areas follow row/column inserts as defined names. |
| Workbook and sheet protection | Preserved | Not enforced. |
| Document properties (`docProps/*`) | Preserved | |
| CSV import | Editable | RFC 4180, delimiter detection (`,` `;` tab), BOM; typed like input but never as formulas. Into a new workbook or a new sheet. |
| CSV export | Editable | Displayed or raw values, CRLF, quoting as needed. |
| ISO Strict files | Read-only → Editable | Strict namespaces are understood; new elements use the file's namespace variant. |
