# XLSX decisions

**X-01 Own SpreadsheetML layer instead of a library.** The format layer works directly on the
lossless package model. Unchanged parts are copied byte-identical and edited parts keep every
element and attribute the app does not model.

**X-02 One row/column index per sheet, kept in sync by the edits.** `Worksheet` builds a
`TreeMap` index over `sheetData` on first access. Edits update it in place and mark it as
current. Changes made behind its back (undo, redo) change the part's modification count, which
triggers a rebuild. Recalculating a 10,000-row sheet therefore never re-indexes per written value.

**X-03 Recalculate everything the engine supports after each value edit.** A dependency graph
would be faster for very large models. Evaluating every supported formula with memoisation
(dependencies first, in row order) is simple, cannot miss a dependency, and finishes the
10,000-row corpus file (open, read 50,000 cells, edit, recalculate, save) in about one second.
Only results that changed are written, so unedited parts stay untouched. Evaluation runs on a
thread with a 64 MB stack, so long dependency chains cannot overflow.

**X-04 Unsupported formulas keep their cached value.** A formula is evaluated only if every
function, name and construct in it is supported. Otherwise its last cached value is shown and
used by dependents. Every value edit also sets `calcPr/@fullCalcOnLoad="1"`, so the next
spreadsheet application recalculates whatever the app could not.

**X-05 Formulas typed by the user are normalised.** Function names and references are
upper-cased. Functions introduced after the 2007 file format (CONCAT and others) get the
`_xlfn.` prefix that Excel expects. The corpus shows LibreOffice returning `#NAME?` for an
unprefixed `CONCAT`. A formula with a syntax error is rejected before anything changes.

**X-06 The calculation chain is dropped when formulas disappear.** `calcChain.xml` must not list
cells that no longer hold a formula: Excel reports such a file as damaged. The chain is
optional, so it is removed when a formula is replaced or cleared, and when sheets are deleted or
moved (the chain refers to sheets by position). Adding formulas keeps it.

**X-07 Styles are appended, never modified.** Cell formats (`xf`), fonts, fills and borders are
usually shared by many cells. A style change copies the cell's record, patches the copy, and
reuses an equal existing record or appends a new one. Unused records are left in place, which
is harmless. A workbook without a style sheet gets a minimal one on its first formatting edit.

**X-08 Typed input applies a number format only to "General" cells.** Entering `12%`, `$5` or
`2024-03-15` stores the number. It sets a matching format only if the cell still uses General,
so a cell's existing format is never overridden.

**X-09 Shared formula groups are split, not re-mastered.** Before the master cell of a shared
group changes, every dependent receives its own plain formula (the translated master text).
This writes more XML than promoting a new master, but the result is always valid.

**X-10 Structural edits are refused rather than approximated.** Inserting or deleting rows and
columns moves everything this code knows how to address: formulas on every sheet, names, chart
references, merged, CF, DV and filter ranges, hyperlinks, breaks and widths. Sheets with
drawings, comments (VML anchors), tables, pivot tables, array formulas, extension-list ranges or
embedded objects are refused with a reason (`Worksheet.structureEditBlocker`), because moving
their content correctly is not implemented. Shared formula groups that could straddle the change
are split first.

**X-11 Sorting and filtering stay simple.** Sorting moves values and formats of whole rows
within the range by one key column. Blanks go last in both directions; numbers sort before
text, text before booleans, and text compares without case. Ranges with formulas are refused,
because relative references would point elsewhere after the move. Merged ranges are refused
too. The filter is a value list on one column: it hides rows and records the criteria in
`autoFilter`, so spreadsheet applications show the same filter.

**X-12 CSV import never creates formulas.** A field such as `=1+1` is stored as text. This
prevents formula injection from untrusted CSV files.

**X-13 Number formatting follows Excel's observable behaviour.** The formatter was checked
against the Excel-computed `TEXT()` results in the POI test workbooks. Two details matter:
values are rounded to 15 significant digits before decimal rounding, and General uses an
11-character display budget. Fraction formats with literals inside reproduce Excel's own
results. Locale is en-US (month and day names, `$` for built-in currency formats).

**X-14 Charts are drawn from live data.** Bar, column, line and pie charts are read for display
from the cells their series reference, so edits show immediately. The chart's own cache is the
fallback. Existing chart parts are never modified, except for their references when a sheet is
renamed or rows or columns move. New charts get a drawing part (or reuse the sheet's existing
one) with ids above existing ones.

**X-15 Unparseable sheets are read-only.** If a sheet part does not survive a lossless XML round
trip, it is parsed separately for display and every edit to it is refused. The part is then
written back byte-identical.

**X-16 Thread model.** A workbook is not thread-safe. The UI layer must serialise access, for
example with one coroutine dispatcher per open document. Recalculation's helper thread is joined
before control returns.
