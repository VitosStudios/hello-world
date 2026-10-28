# Compatibility

What the app does with every element of the formats it opens. The same information is shown
per file in the app (file menu, "Compatibility"), with the number of occurrences in that file.

Core guarantees for DOCX, XLSX and PPTX:

- Every ZIP entry the app does not edit is written back with byte-identical content, in the original order.
- Edited XML parts are re-serialised by a lossless writer: unknown elements, attributes, namespace
  declarations, comments and whitespace are kept as they were.
- Content that is not supported is shown as a locked placeholder (or listed here as preserved) and saved unchanged.
- A file is always saved in the format it was opened in. PDF is an export, never a silent conversion.
- Every save is written to a temporary file, re-opened with the format reader, and only then moved over the
  working copy; the working copy is then streamed to the original file and read back to confirm.

## DOCX

Status meanings are the same for every format: **Editable** (shown and changed by the app),
**Read-only** (shown, often as a locked placeholder with an explanation, and saved unchanged),
**Preserved** (not shown, saved unchanged), **Not supported** (not shown and not edited, but still
saved unchanged: the app never deletes content it does not understand).

Parts the app does not edit are written back byte-identical. Inside an edited part, unknown
elements and attributes (`mc:AlternateContent`, `extLst`, `w14:*`, custom namespaces, rsid
attributes) are kept.

| Element | Status | Notes |
|---|---|---|
| Paragraphs and runs (`w:p`, `w:r`, `w:t`) | Editable | Typing, deleting, splitting (Enter) and joining (Backspace). Inserted text takes the formatting of the character before the cursor. |
| Bold, italic, underline, strikethrough | Editable | Toggles compare against the style, so turning bold off in a bold heading writes `w:val="0"` and otherwise removes the direct property. |
| Font size, colour, highlight, font family, superscript/subscript | Editable | Colour writes `w:color/@w:val` and drops theme colour attributes that would override it. Fonts are shown with the closest generic family (no fonts are bundled). |
| Clear formatting | Editable | Removes direct run formatting, keeps the character style. |
| Character styles (`w:rStyle`) | Read-only | Resolved for display; the hyperlink style is applied to new links. |
| Headings 1-6 | Editable | Uses the document's own heading styles (found by name or id). If a level is missing, a heading style based on the default paragraph style is added to `styles.xml`. |
| Other paragraph styles | Editable | Picker lists the document's visible paragraph styles. |
| Bulleted and numbered lists, nesting | Editable | Labels computed from `numbering.xml` (decimal, letters, roman, ordinal, bullets incl. symbol-font bullets). New lists reuse a matching definition or add one; a numbering part is created if missing. Indent/outdent changes the list level. |
| List styles from paragraph styles ("List Bullet") | Editable | Removing such a list resets the paragraph style. |
| Alignment, indentation (left, right, first line, hanging) | Editable | |
| Line spacing (multiple), space before/after | Editable | Exact/at-least line spacing is shown and kept. |
| Page break before, keep with next, outline level | Editable / Read-only | Page break before is editable; the others are kept. |
| Tables: insert, cell text | Editable | New tables use the document's "Table Grid" style when present, otherwise explicit borders; a paragraph always follows a table. |
| Tables: add/remove rows and columns | Editable | Grid widths are rescaled so the table keeps its width. |
| Tables with merged cells (`gridSpan`, `vMerge`) or tracked changes | Read-only (structure) | Cell text stays editable; structure edits are refused with an explanation. |
| Nested tables | Editable (text) | Shown inside their cell. |
| Table styles, cell shading, borders, widths | Read-only | Shading and borders are shown. |
| Inline images (`wp:inline`) | Editable | Insert (PNG, JPEG, GIF, WebP, BMP), resize, delete. The original image bytes are stored unchanged; resizing changes only the extent. |
| Floating images (`wp:anchor`) | Editable (size) / Read-only (position) | Shown inline in the reflowed view; wrapping and position are kept. |
| Charts, SmartArt, shapes, text boxes, groups, canvases, ink | Read-only | Locked placeholder with the object type; saved unchanged and protected from deletion. |
| Legacy VML (`w:pict`), OLE objects (`w:object`), equations (`m:oMath`) | Read-only | Locked placeholder. |
| Headers and footers | Editable | Text editing like body paragraphs; a default header or footer is created when the document has none. First-page and even-page variants are kept. |
| Hyperlinks (external and to bookmarks) | Editable | Insert on a selection, change the address (a shared relationship is not retargeted), remove (text kept). |
| Page breaks (`w:br w:type="page"`) | Editable | Inserted at the cursor (the paragraph is split). Column breaks are kept. |
| Sections: page size, orientation, margins | Editable | Size presets Letter, A4, Legal, A5. Applies to every section. |
| Multiple columns, page borders, line numbering, section types | Preserved | Shown in one column. |
| Find and replace | Editable | Match case, whole words; replacements skip read-only paragraphs and report how many. Headers and footers are searched. |
| Word count | Editable | Words, characters with and without spaces, paragraphs; CJK ideographs count as words. Headers, footers and notes are not counted. |
| Fields (`w:fldSimple`, complex fields) | Read-only | The cached result is shown. Paragraphs with complex fields are locked for text editing; page numbers are filled in on PDF export. |
| Tracked changes (`w:ins`, `w:del`, property changes) | Read-only | Paragraphs that contain revisions are locked for text editing; paragraph formatting still works. Revisions are kept. |
| Footnotes and endnotes | Read-only | Reference marks shown and protected; note text kept. |
| Comments | Preserved | Ranges and references kept at their positions. |
| Bookmarks, permission ranges, proofing marks | Preserved | Zero-width markers stay in place. |
| Content controls (`w:sdt`) | Editable (text) | Control properties kept. |
| Custom XML parts, glossary, theme, settings, font table, embedded fonts | Preserved | Byte-identical. |
| Macros (`.docm`, `vbaProject.bin`) | Preserved | Never run. |
| Digital signatures | Read-only | Kept; any edit invalidates them (shown in the compatibility screen). |
| Document protection, compatibility settings | Preserved | Not enforced. |
| ISO Strict documents | Editable | Strict namespaces are understood; new elements use the file's own namespace variant. |
| Export as PDF | Editable | Page size and margins from the document; run formatting, alignment, indentation, spacing, lists, tables, images, page breaks, headers and footers. Locked content appears as a labelled box. |

## XLSX
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

## PPTX
Status meanings:

- **Editable**: shown and changed by the app.
- **Read-only**: shown (sometimes as a labelled placeholder) and saved unchanged.
- **Preserved**: not shown, saved unchanged.
- **Not supported**: neither shown nor edited, but still saved unchanged. The app never deletes content it does not understand.

Every part the app does not edit is written back byte-identical. Within edited slide XML, unknown elements and attributes (`p:transition`, `p:timing`, `mc:AlternateContent`, `extLst`, custom namespaces) are kept as they are.

### Package and presentation

| Element | Status | Notes |
|---|---|---|
| Slide list (`p:sldIdLst`) | Editable | Insert, delete, duplicate, reorder. The last slide cannot be deleted. |
| Slide size (`p:sldSz`) | Read-only | Used for layout and export. |
| Slide masters and layouts | Read-only | Shown through inheritance; new slides and "change layout" use the deck's own layouts. |
| Themes (colors, fonts, format scheme) | Read-only | Theme colors, color maps, `lumMod`/`lumOff`/`tint`/`shade`/`alpha`/`sat*` modifiers and `+mj`/`+mn` fonts are resolved for display. |
| Notes master | Read-only | Created only when notes are added to a deck that has none (own copy of the slide theme). |
| Handout master | Preserved | |
| Sections (`p14:sectionLst`) | Preserved | Kept consistent: new or moved slides join the section of the slide before them; deleted slides are removed. |
| Custom shows (`p:custShowLst`) | Preserved | Deleted slides are removed from them so no dangling reference remains. |
| Embedded fonts | Preserved | Text is drawn with device fonts. |
| Comments (legacy and modern) | Preserved | Not shown. Copied with a duplicated slide. |
| VBA project (`.pptm`) | Preserved | Never executed. |
| Tags, custom data, add-in data | Preserved | |
| Document properties | Preserved | Slide counts in `docProps/app.xml` are not rewritten. |
| Thumbnail | Preserved | |
| Strict OOXML namespaces | Read-only / Editable | Read through namespace aliases; new elements use the namespace variant the file already uses. |

### Slides

| Element | Status | Notes |
|---|---|---|
| Slide background (`p:bg`, solid / gradient / picture / theme reference) | Read-only | Inherited from layout and master when the slide has none. |
| Hidden slide (`show="0"`) | Editable | Hide or show. |
| Transitions (`p:transition`, also inside `mc:AlternateContent`) | Preserved | Byte-identical inside the edited slide. |
| Animations (`p:timing`, `p:bldLst`) | Preserved | Byte-identical. Shapes that are animation targets cannot be deleted (it would leave a dangling target). |
| Speaker notes | Editable | Plain text, one paragraph per line; formatting of the first run is reused. |
| Master/layout decorations | Read-only | Drawn behind slide content unless `showMasterSp="0"`. |
| Header/footer placeholders (date, footer, slide number) | Read-only | Shown when the slide has them; not created for new slides. |

### Shapes

| Element | Status | Notes |
|---|---|---|
| Placeholders (`p:sp` with `p:ph`) | Editable | Geometry, body properties and text styles inherited slide → layout → master (match by `idx`, then type). Moving one writes an explicit `a:xfrm`. |
| Text boxes (`txBox="1"`) | Editable | Insert, edit, move, resize, rotate, delete. |
| Preset shapes (`a:prstGeom`) | Editable | Insert rectangle, ellipse, line, right arrow. Any preset is shown by name; the renderer draws the common ones. Fill and outline color, outline width. |
| Custom geometry (`a:custGeom`) | Read-only | Shown by bounds; kept unchanged. |
| Connectors (`p:cxnSp`) | Editable | Move, resize, outline. Connection sites are kept as stored. |
| Groups (`p:grpSp`) | Editable | Children mapped to slide coordinates; grouped text is editable; moving the group scales the child frame. Ungroup is not offered. |
| Pictures (`p:pic`) | Editable | Insert (PNG, JPEG, GIF, WebP, BMP), move, resize, rotate, delete. Media part removed when no longer referenced. Crop (`a:srcRect`) shown, not editable. |
| Linked pictures (`r:link`) | Read-only | Not loaded. |
| Tables (`a:tbl`) | Read-only | Cell text shown. |
| Charts (`c:chart`) | Read-only | Placeholder box. Duplicating a slide copies the chart part and its embedded workbook. |
| SmartArt (`dgm`) | Read-only | Placeholder box. |
| Embedded OLE objects | Read-only | Preview image if present, otherwise a placeholder. |
| Audio and video | Read-only | Poster frame shown. |
| Ink (`p:contentPart`) | Read-only | Placeholder. |
| `mc:AlternateContent` shapes | Read-only | Fallback content shown, whole block kept unchanged. |
| Shape effects (shadow, glow, 3-D: `a:effectLst`, `a:scene3d`, `a:sp3d`) | Preserved | Not drawn. |
| Hyperlinks on shapes and text (`a:hlinkClick`) | Read-only | Resolved target exposed to the UI. |
| Alt text, shape names | Preserved | |

### Text

| Element | Status | Notes |
|---|---|---|
| Paragraph text, runs (`a:r`) | Editable | Insert/replace keeps the formatting at the insertion point. |
| Line breaks (`a:br`) | Editable | Represented as U+000B in paragraph text. |
| Fields (`a:fld`: slide number, date) | Read-only | Kept as one unit; removed only when a deletion covers them completely. |
| Bold, italic, underline, strikethrough | Editable | `b`, `i`, `u="sng"`, `strike="sngStrike"`. |
| Font size | Editable | `sz` in hundredths of a point. |
| Font color | Editable | Written as `a:solidFill/a:srgbClr`. Theme colors are shown resolved. |
| Typeface | Editable | `a:latin`. East Asian and complex-script fonts kept. |
| Highlight, character spacing, kerning, caps, baseline | Preserved | Baseline (super/subscript) is shown. |
| Alignment | Editable | Left, center, right, justify, distributed. |
| Bullets | Editable | None, character, auto-number; numbering continues per level. Picture bullets shown as a dot. |
| List level / indent | Editable | `lvl` 0–8; margins from the level style. |
| Line spacing, space before/after | Read-only | Shown. |
| Autofit (`normAutofit fontScale`, `spAutoFit`) | Read-only | Font scale applied when drawing. |
| Vertical text, columns | Preserved | |
| Tabs (`a:tabLst`) | Preserved | |

Status of the pure-Kotlin cores (`format/photo`, `format/video`). The Android layer decodes and
encodes media and draws previews; everything listed here is computed by the cores and covered by
JVM unit tests.

Legend: **Editable** = available in the editor; **Kept** = preserved on export; **Not supported** = out of scope in this version.

## Photo (JPG, PNG, WebP)

In the app, photo edits are non-destructive: the edit recipe is autosaved continuously and the
image is re-encoded only when you choose **Save over original** (same format, after a
confirmation) or **Export** (new file). The original is never overwritten silently.

| Feature | Status | Notes |
|---|---|---|
| Crop (free and 1:1, 4:3, 3:2, 16:9, original ratio) | Editable | Normalised to the visible frame; non-square ratios follow the frame orientation. |
| Rotate 90° left/right | Editable | The crop turns with the image. |
| Flip horizontal / vertical | Editable | Mirrors what is on screen, also after a rotation or with straighten. |
| Straighten (−45°..45°) | Editable | Automatic inscribed crop: no empty corners. |
| Exposure (−2..2 EV) | Editable | Applied in linear light. |
| Contrast | Editable | S-curve around mid-grey; negative values flatten. |
| Highlights / Shadows | Editable | Luminance-masked; pure black and white never move. |
| Saturation | Editable | Luma-preserving; −100 is greyscale. |
| Warmth | Editable | Red/blue shift strongest in mid-tones. |
| Sharpness (0..100) | Editable | Unsharp mask on a 3×3 binomial blur. |
| Filter presets | Editable | None, Natural, Vivid, Warm, Cool, Mono, Fade, Crisp, Noir; sliders stay adjustable. |
| Before/after comparison | Editable | `PhotoEditState.isIdentity` / `withoutAdjustments()`. |
| Undo/redo | Editable | Unlimited per session; a slider drag is one step. |
| EXIF orientation (values 1–8) | Kept | Applied to the pixels on open; exported files carry orientation 1. |
| EXIF camera/date metadata | Kept (optional) | Copied when "Keep metadata" is on (default). |
| GPS location | Kept (opt-in) | Copied only when "Keep location" is also on. |
| Maker notes, embedded thumbnails | Not kept | They reference offsets in the original file. |
| Export JPG / PNG / WebP, quality, size presets | Editable | Original, 4096, 2048, 1080 px long edge; never upscales. |
| Alpha channel | Kept | Untouched by every adjustment; JPEG export has no alpha. |
| Layers, masks, local adjustments, healing | Not supported | Out of scope in this version. |
| RAW, HEIF, colour profiles other than sRGB | Not supported | Pixels are treated as sRGB. |

## Video (MP4)

In the app, a video is edited as a timeline project that is autosaved and restored after the app
is closed. The source clips are never copied or modified; the result is exported as a new MP4
with Media3 Transformer. The preview plays the clips with their trims and volumes and shows the
text overlays; background music is heard in the export only.

| Feature | Status | Notes |
|---|---|---|
| Trim clip start/end | Editable | Minimum clip length 100 ms. |
| Split at playhead | Editable | No-op within 100 ms of a clip boundary. |
| Delete segment (range, across clips) | Editable | Overlays after the range move with the content. |
| Delete clip | Editable | |
| Reorder clips | Editable | Overlays keep their timeline times. |
| Join clips (add more sources) | Editable | One video track. |
| Clip volume (0–200 %) and mute | Editable | Muted clips keep a silent audio track. |
| Background music (one audio track) | Editable | Volume, loop, start offset; non-looping music stops with the video. |
| Text overlays | Editable | Text, size, colour, bold, position + anchor, start/end time. |
| Export 1080p / 720p / vertical 9:16 | Editable | Pure `ExportPlan` mapped onto Media3 Transformer by the app. |
| Autosave / restore after process death | Editable | Project JSON written with `AtomicFile`, verified before replace. |
| Undo/redo | Editable | Unlimited per session; handle drags are one step. |
| Transitions, colour grading, keyframes, effects | Not supported | Out of scope in this version. |
| Multiple video tracks, picture-in-picture | Not supported | Single video track by design. |

## PDF

| Feature | Status | Notes |
|---|---|---|
| View pages | Read-only | Rendered with the platform PDF renderer. |
| Password-protected PDFs | Not supported | A message explains why they cannot be shown. |
| Editing, annotations, forms | Not supported | PDFs are never modified. |

## CSV

| Feature | Status | Notes |
|---|---|---|
| Open a CSV file | Editable | Loaded into a one-sheet workbook; delimiter detected (`,` `;` tab). |
| Save | Editable | Saved back as CSV with the same delimiter (raw values; formatting and formulas cannot be stored in CSV, so a formula is saved as its result). |
| Import a CSV as a new sheet of an XLSX, export a sheet as CSV | Editable | Imported fields are never turned into formulas. |
