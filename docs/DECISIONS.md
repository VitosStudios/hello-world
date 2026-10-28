# Decisions

Every non-obvious choice and its reason. Format-specific decisions follow the general ones.

## General

**D-001. One format-agnostic round-trip engine, written for this app.** Apache POI was evaluated
first (and docx4j/OpenXML-based libraries by reputation). POI's OOXML support depends on XMLBeans
and its schema jars (several MB, reflection-heavy, StAX/`javax.xml` dependencies that Android does
not ship), and its slide/sheet APIs expose `java.awt` types (`Rectangle2D`, `Color`, font metrics
for auto-sizing). More importantly, POI re-serialises every part it loads through XMLBeans, which
rewrites namespace declarations, attribute order and markup-compatibility blocks, so "save
without edits" is not byte-identical. The requirements (byte-identical untouched parts, lossless
edited parts) are met more simply by a small lossless XML tree (`core:xml`) and an OPC package
model (`core:opc`) on top of `java.util.zip`, both available on Android and any JVM.

**D-002. Pure Kotlin/JVM modules for everything that is not UI.** `core:*` and `format:*` have no
Android dependency. They build and test on any JVM in seconds, which also made it possible to
develop and test them in an environment without the Android SDK. `buildSrc` hosts every Gradle
plugin on one class loader (Kotlin, Compose compiler, KSP, and AGP/Roborazzi only when an Android
SDK is found), so the JVM modules build even where Google's Maven repository is unreachable;
`settings.gradle.kts` includes `:app` only when `ANDROID_HOME`, `ANDROID_SDK_ROOT` or
`local.properties` point to an SDK.

**D-003. Lossless XML instead of a DOM.** The parser keeps the exact source text of every node:
attribute order, quotes, whitespace between attributes, entity references, empty-element syntax,
comments, processing instructions, BOM and encoding. Serialising an unmodified tree reproduces the
input byte for byte (`XmlCodec.parseVerified` checks this before a part becomes editable; a part
that fails is kept read-only and copied unchanged). Every XML part of the 70-file corpus passes.

**D-004. Undo through recorded DOM mutations.** Every mutation (attribute, child insert/remove,
text) records its inverse in the package's `UndoManager`, grouped into one transaction per user
action. Undo is therefore exact and unlimited, works for any edit of any format, and a
transaction that throws is rolled back so a failed edit never leaves half-applied changes.
Typing in one paragraph within 1.5 s coalesces into one step. Photo and video use snapshot
history of small immutable states instead.

**D-005. Schema order for new elements.** OOXML consumers (Word in particular) reject children in
the wrong sequence even when other readers accept them. Every element the app creates is inserted
with `insertInOrder` using the ECMA-376 sequence of its parent; unknown siblings keep their place.

**D-006. Saving: snapshot, temporary file, verification, atomic rename, write-back.** The model is
only touched on the main thread. A save snapshots the modified parts there (unmodified parts are
streamed from the source ZIP later), writes the package to a temporary file on a background
thread, re-opens it with the package verifier *and* the format's own reader, and only then
renames it over the private working copy (`AtomicFile`, `ATOMIC_MOVE`). A crash at any point
leaves the previous working copy intact; stale temporary files are removed on the next start.

**D-007. Working copies and write-back for Storage Access Framework files.** Document providers
cannot rename over a file atomically. The app therefore edits a private working copy and streams
the verified copy to the user's file (`openOutputStream(uri, "wt")`), then reads it back and
compares SHA-256 hashes. If the write or the check fails, the file is marked "pending", the error
is shown with a retry, and the write is retried on the next start. The file as it was when opened
is kept as a backup until a write-back succeeded. New files live in app storage until the user
chooses "Save a copy" or a location.

**D-008. Continuous autosave.** Edits are saved 1.5 s after the last change and immediately when
the app goes to the background; view models survive rotation, and process death loses at most the
last 1.5 s. Photo and video autosave their edit recipe / timeline (small files) rather than
re-encoding media.

**D-009. Unsupported content is protected, not just preserved.** Locked inline objects (charts,
SmartArt, fields, footnote references...) are kept even when the user deletes text across them,
and structural edits that could damage them (deleting an animated shape, sorting ranges with
formulas, editing merged-table structure) are refused with an explanation instead of approximated.

**D-010. Same format on save; exports are explicit.** DOCX/XLSX/PPTX/CSV save back to their own
format; PDF, CSV export, image export and video export always create a new file chosen by the
user. Photos are only overwritten after a confirmation; videos are never overwritten.

**D-011. Reflowed editing view for documents.** A phone cannot show a full page legibly, so the
editor shows a continuous, reflowed view (one text field only for the paragraph being edited,
read-only text elsewhere, which keeps 200-page documents fast). Page layout is used for PDF export.
Fonts are mapped to the closest generic family because no fonts are bundled.

**D-012. Canvas-drawn spreadsheet grid.** Only visible cells are read and drawn, with a per-version
cache, so a 10,000-row sheet scrolls smoothly. TalkBack gets the selected cell's address, value and
formula plus custom actions to move and edit, and the formula bar is a normal text field.

**D-013. Working name and trademarks.** The ArtCraft desktop project's NOTICE states that "the
ArtCraft name, wordmark and logos are trademarks of the ArtCraft Team" and are not covered by its
MIT/Apache-2.0 licence. "ArtCraft Mobile" is used only as the requested working name, in one
string resource and the README; the package name (`com.vitosstudios.suite`) and all visuals are
neutral and original. Before any public release, rename the app or obtain permission.

**D-014. Reference projects.** The storytold/artcraft repository is an AI image/video creation app
(Rust/Tauri/TypeScript), dual-licensed MIT OR Apache-2.0. It was reviewed for ideas only; no code,
assets, names or visuals were copied (none would have applied to an Android office editor). Other
repositories of the organisation could not be listed from the build environment, so none were used.

**D-015. Test corpus sources.** Files authored by Word, Excel and PowerPoint come from Apache POI's
test data (Apache-2.0, see `test/corpus/README.md`). python-docx, openpyxl and python-pptx generate
feature files; LibreOffice re-exports them. No Google Docs export could be produced in this
environment; the corpus README says how to add one.

**D-016. targetSdk 36.** The toolchain is pinned to AGP 8.13 / Kotlin 2.2.21, whose newest
supported API level is 36 (Android 16), which is also the level Google Play requires for new apps
in 2026. Raising it means bumping `agp`, `compileSdk` and `targetSdk` in `gradle/libs.versions.toml`.

**D-017. No network, no accounts, no analytics.** The manifest does not request `INTERNET`.
Nothing in the app can make a network call.

**D-018. Manual dependency injection.** One `AppContainer` (database, repository, scopes) is
created by the `Application`; view models get it through `viewModelFactory`. A DI framework would
add build complexity without benefit at this size.

**D-019. Material 3 with dynamic colour, own fallback palette and accents.** Dynamic colour on
Android 12+; an original blue-grey palette below. Each editor has an accent pair (container /
on-container) checked for 4.5:1 contrast; documents are drawn on white "paper" with automatic text
colour in black so document colours keep their meaning in dark mode.

**D-020. Screenshot tests record in CI.** Roborazzi records component screenshots on every CI run
and uploads them as an artifact. Golden images are not committed yet because they must be produced
on the CI image; once reviewed, commit them and switch the step to `verifyRoborazziDebug`.
`EditorSmokeTest` drives the whole app (intent, repository, Room, navigation, editor) in
Robolectric. It checks wiring and threading, not device rendering or performance; it found that
the "Open with" handler navigated from a background thread, which is now fixed.

**D-021. Video preview.** The preview plays the clipped clips as an ExoPlayer playlist with
per-clip volume and draws overlays in Compose. Background music is mixed only in the export, which
keeps the preview simple and avoids audio sync drift between two players.

**D-022. Large files.** `android:largeHeap` is enabled because a 12 MP photo needs about 48 MB per
ARGB buffer during export. Documents and sheets are parsed once; parts are read lazily from the ZIP.
Measured on the JVM: 200-page DOCX open + full read about 0.1 s; 10,000-row XLSX open, read, edit,
recalculate, save about 1 s; 12 MP colour pipeline about 0.7 s.

## DOCX

**W-1. Text model with object characters.** A paragraph's text is the concatenation of its runs;
tabs are `\t`, line breaks U+2028 and every inline object (image, field, footnote reference,
symbol, page break, locked drawing) one U+FFFC. Editing computes a minimal replace and maps it onto
runs, so run properties, bookmarks and other zero-width markers stay where they are.

**W-2. Which paragraphs are text-editable.** A paragraph is locked for text editing when it holds
tracked changes, complex fields or elements the app does not classify. Paragraph formatting is
still allowed because it never touches inline content.

**W-3. New text inherits the formatting before the cursor.** Like word processors; in an empty
paragraph the paragraph mark's run properties are used.

**W-4. Splitting keeps sections and styles consistent.** The section break stays with the second
half; pressing Enter at the end of a heading applies the style's "next" style. Joining is refused
across a section break.

**W-5. Formatting toggles are style-aware.** Turning a property off removes the direct property
when the style does not set it, otherwise writes an explicit "off", so headings keep their style.

**W-6. Lists reuse the document's definitions.** A new bulleted list reuses an existing bullet
definition; a new numbered list creates a new instance (`w:num`) of an existing decimal definition
with a restart, so numbering starts at 1. Definitions are only added when none fit.

**W-7. Images keep the original bytes.** Inserted images are stored as-is; resizing only changes
the extent. Image size defaults to 96 dpi and is capped to the text width.

**W-8. Table structure is locked when cells are merged.** Inserting a column into a table with
horizontal and vertical merges has several valid outcomes; rather than guess, structure editing is
disabled for such tables while text stays editable.

## XLSX

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

## PPTX

**P-1. Read model built from the XML on demand.** `PptxPresentation.slideView(i)` resolves inheritance every time it is called instead of caching a separate object model. The XML tree is the single source of truth, so undo/redo (which replays XML changes) can never leave a stale model behind. Parsed parts are cached by the package, so repeated calls cost little.

**P-2. Shapes are addressed by `cNvPr` id.** Ids are unique within a slide in valid files and survive edits, unlike list positions. Layout and master shapes are shown but never returned as editable.

**P-3. Placeholder matching.** Slide → layout matches by `idx` first, then by type (and `obj` falls back to `body`). Layout → master matches by type family only (`title`/`ctrTitle` → title; date, footer and slide number by type; everything else → body). This mirrors how presentation software resolves inheritance and handles decks whose layouts renumber `idx`.

**P-4. Text style chain.** Run properties resolve in this order: run `a:rPr`, the shape's `a:lstStyle` level, the layout placeholder's level, the master placeholder's level, then the master `p:txStyles` (title, body or other style). Text in non-placeholder shapes uses the presentation's `p:defaultTextStyle` and then `otherStyle`. A shape style's `fontRef` color applies before the master styles.

**P-5. Line breaks are U+000B.** The same character presentation software uses for a soft return inside a paragraph, so plain-text clipboard content round-trips; `\n` always means a new paragraph.

**P-6. Fields are atomic.** A slide-number or date field is removed only when a deletion covers it completely. Partially editing the cached field text would produce text that the next recalculation overwrites.

**P-7. Animated shapes cannot be deleted.** `p:timing` refers to shapes by id. Deleting a target leaves a dangling reference that presentation software reports as damage. Editing the timing tree is out of scope, so the app refuses the delete and says why (`isShapeAnimated` lets the UI disable the button).

**P-8. Duplicate copies owned parts, shares shared parts.** Notes pages, charts (with embedded workbooks), diagrams, OLE objects and comments belong to one slide and are copied. Layouts, masters, themes, media, tags and slide links are referenced. Two slides pointing at one chart part would make editing one change the other.

**P-9. Deleting a slide cleans up after itself.** The slide part, its notes page and owned parts that nothing else references are removed; custom-show entries and section entries for the slide are removed too, so no relationship id or slide id dangles.

**P-10. Sections stay contiguous.** Slides that are inserted, duplicated or moved join the section of the slide before them (the first section at position 0). Each section's list is rewritten in slide order only when it actually changes.

**P-11. Notes master is created only on demand.** Adding notes to a deck without a notes master creates a minimal one (slide image and body placeholders, `p:clrMap`, `p:notesStyle`) with its own copy of the slide master's theme, because a notes master must have its own theme relationship.

**P-12. New presentations are generated in code.** `BlankDeck` writes a 16:9 deck with an original theme and four layouts. No third-party template file is bundled, so there is no licensing question and the XML is reviewed like code.

**P-13. Colors are resolved in HSL.** `lumMod`/`lumOff`/`sat*`/`hue*` are applied in HSL; `tint` and `shade` blend toward white/black. This matches presentation software within a unit or two per channel (tested against a known value).

**P-14. Characters XML cannot hold are dropped.** Control characters, U+FFFE/U+FFFF and unpaired surrogates are removed from typed or pasted text before it reaches the XML; otherwise the saved file would be unreadable.

**P-15. New elements follow the schema sequence.** Every inserted child uses an order list from ECMA-376 (`Schema.kt`); presentation software rejects out-of-order children even when other readers accept them.

## Photo and video

**PV-1. Pure Kotlin cores, platform glue in the app.** `format/photo` and `format/video` have no
Android, AWT or ImageIO dependency. The app converts `Bitmap` ⇄ `IntArray` (ARGB_8888, the layout
of `Bitmap.getPixels`) and maps the video model onto Media3. This keeps all editing logic unit
tested on the JVM in seconds.

**PV-2. Non-destructive photo edits.** The editor keeps the decoded upright source untouched and an
immutable `PhotoEditState` (geometry + adjustments). Previews and exports render from the source,
so undo/redo is just a `HistoryStack` of small states and never accumulates resampling loss.

**PV-3. Geometry order.** Flips → quarter turns → straighten → crop, all about the image centre.
The crop is stored normalised to the *visible* frame, so the crop overlay needs no conversion.
"Rotate" and "Flip" act on what the user sees: they also turn/mirror the crop and, for flips,
negate the straighten angle (a mirror reverses rotation). Tests check these operations commute
with rendering.

**PV-4. Straighten crops automatically.** The frame is scaled to the largest rectangle with the
same aspect ratio that fits inside the rotated image. Users never see empty corners and no fill
colour has to be chosen. Pixel scale is kept (no upscaling), so the output gets smaller.

**PV-5. One Android-compatible matrix.** `PhotoGeometry.matrix` returns a row-major 3×3 affine
matrix in the layout of `android.graphics.Matrix.setValues`. The app draws the source once with
bilinear filtering; `Raster` contains a reference implementation used by the tests. The crop is
centred inside the rounded output size so mirrored edits stay pixel-symmetric.

**PV-6. EXIF orientation is baked in on open.** Orientation 1–8 is applied when the photo is
decoded; the editor works on upright pixels and exports orientation 1. This avoids double rotation
in viewers that ignore or apply the tag differently.

**PV-7. Colour pipeline in integer arithmetic.** Exposure (in linear light) and contrast fold into
one 256-entry curve; highlights/shadows are a luminance gain table; saturation and warmth are
integer operations; sharpness is an unsharp mask on a 3×3 binomial blur. Every curve is monotonic
(tested) so no setting inverts tones, and neutral stages are skipped so an identity edit returns
the exact input. Rows are processed in parallel chunks. Measured on a 4-core x86 development container (JVM 21):
12 MP with every adjustment ≈0.7 s, without sharpness ≈0.3 s. Live previews run the same code on a
downscaled buffer.

**PV-8. Location is opt-in.** "Keep metadata" (default on) copies date and camera tags; GPS tags
are copied only if "Keep location" is also on, so a shared export does not reveal where a photo
was taken by accident. Maker notes and thumbnails are never copied because they point at byte
offsets of the original file.

**PV-9. Immutable timeline with deterministic ids.** Every video operation is a pure function
returning a new `Timeline`; new clip/overlay ids are the next free `cN`/`tN`. Undo/redo is a
`HistoryStack`, and tests can compare whole timelines.

**PV-10. Minimum lengths instead of slivers.** Trim, split and delete never create clips shorter
than 100 ms (a split near a boundary is a no-op; tiny remainders of a deleted range are dropped).
Very short clips decode unreliably and are impossible to select on a phone.

**PV-11. Overlays live on the timeline.** Overlay times are timeline times. Deleting content maps
them through the surviving segments (content after a cut moves left, an overlay spanning a cut
shrinks, one inside a deleted range disappears). Reordering clips leaves overlays where they are:
titles usually belong to a position (intro, ending), not to a clip.

**PV-12. Muting keeps an audio track.** A muted clip is exported with volume 0 rather than with its
audio removed, because Media3 sequences need consistent tracks across items.

**PV-13. Non-looping music is cut at the video end.** Otherwise a longer music sequence would make
Media3 extend the export beyond the last clip.

**PV-14. Hand-written JSON for the project file.** The timeline autosave is a small JSON document
written by a ~150-line writer/reader (no serialization plugin or library). Unknown keys are
ignored, newer versions are rejected with a clear error, and `TimelineStore.save` decodes the
temporary file and compares it with the in-memory timeline before atomically replacing the
project file.
