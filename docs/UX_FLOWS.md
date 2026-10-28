# UX flows

Core flows per app. They follow the same principles everywhere:

- **One primary task per screen.** Home lists files; each editor edits one file.
- **Context toolbars appear on selection.** A paragraph, cell, shape or image selection brings up
  a toolbar of what applies to it, above the keyboard; with nothing selected the bottom bar shows
  the primary actions.
- **Formatting and settings live in bottom sheets** (text, paragraph, page setup, number format,
  layouts, export options).
- **Command search in the top bar** (magnifier) finds every function of the current screen by
  name or keyword; on Home it also finds files.
- **Continuous autosave and unlimited undo/redo** (undo/redo and a save-status indicator sit in the
  top bar). A failed save shows a red status with the reason and "Retry"; nothing is lost.
- **Touch targets ≥ 48 dp, TalkBack labels on every control, WCAG AA colours.** Wide screens
  (tablets, landscape) get side-by-side layouts (slides filmstrip on the left, centred document
  column, multi-column home grid).

## Home

1. **Open an existing file.** Home → *Open file* (FAB) → system picker (any storage provider) →
   the file opens in its editor; it appears in *Recent files* with its format badge.
2. **Create from a template.** Home → *Create new* row → choose a template (Blank document,
   Report, Letter, Blank spreadsheet, Budget, Blank presentation) → the editor opens on a new file
   stored on the device. *Save a copy* (editor menu) places it anywhere.
3. **Find a file or a command.** Home → search → type part of a name or "new", "photo"... →
   tap a result (or press Enter for the first).
4. **Check what is supported.** Recent file → ⋮ → *Compatibility* → list of editable, read-only
   and preserved content with counts.
5. **Open from another app.** Share or "Open with" a DOCX/XLSX/PPTX/PDF/image/MP4/CSV → the
   editor opens directly.

## Documents (DOCX)

1. **Edit text.** Tap a paragraph → the cursor appears where you tapped → type; Enter starts a
   new paragraph, Backspace at the start joins it with the previous one. Locked paragraphs (tracked
   changes, fields) show a lock note and only allow paragraph formatting.
2. **Format.** Select text (or put the cursor in a word) → context toolbar: bold, italic,
   underline, strikethrough, *Text format* sheet (size, font, colour, highlight, super/subscript,
   clear), *Paragraph format* sheet (Normal / Heading 1-6 / document styles, alignment, lists,
   line spacing, spacing, move or delete paragraph).
3. **Insert.** *Insert* (bottom bar or toolbar) → image (picker), table (rows × columns), page
   break, link, paragraph below, header and footer. Tap an image to resize or delete it; inside a
   table, *Table* offers row/column insert and delete.
4. **Find and replace.** Menu → *Find and replace* → type → step through highlighted matches →
   *Replace* / *All* (read-only paragraphs are skipped and counted).
5. **Page setup and export.** *Page* sheet (size, orientation, margins) → menu *Export as PDF*
   → choose where to save (or *Share as PDF*).

## Sheets (XLSX, CSV)

1. **Enter data.** Tap a cell → formula bar shows its content → tap the bar (or double-tap the
   cell) → type a value, `12%`, `$1,200`, `2024-03-15` or `=SUM(B2:B9)` → Done; the cursor moves
   down and dependent formulas recalculate.
2. **Format.** Select a cell or long-press and drag a range → toolbar: bold, italic, fill, text
   colour, *Number format* (currency, percent, date...), *Cell format* (alignment, wrap, borders,
   size).
3. **Organise.** *Rows and columns* → column width, row height, insert/delete rows and columns,
   freeze panes. Sheet tabs → tap to switch, long-press to rename, move or delete, **+** to add.
4. **Sort and filter.** Select a cell in the column → *Sort and filter* → A→Z / Z→A (header row
   option) or tick the values to show → *Apply filter*; *Clear filter* restores the rows.
5. **Charts and CSV.** Select data → *Chart* → bar, line or pie; existing charts are drawn on the
   grid. Menu → *Export sheet as CSV* / *Import CSV as new sheet*.

## Slides (PPTX)

1. **Browse and present.** Filmstrip (bottom on phones, left on tablets) → tap a slide → ▶ in the
   top bar presents full screen from that slide; tap right/left or swipe, Back to exit.
2. **Edit text.** Tap a text box → *Edit text* (or double-tap) → each paragraph is a field below
   the slide; formatting row: bold, italic, underline, size, colour, alignment, bullets, numbering,
   level.
3. **Arrange slides.** *New slide* → choose one of the deck's layouts; long-press a thumbnail →
   duplicate, move earlier/later, hide, delete; menu → *Change layout*.
4. **Insert and arrange content.** *Text*, *Image*, *Shape* (rectangle, ellipse, line, arrow) →
   drag to move, drag the corner handle to resize, *Rotate*, fill and outline colour, delete.
5. **Notes and export.** *Notes* → type speaker notes; menu → *Export as PDF* / *Share as PDF*.

## Photo (JPG, PNG, WebP)

1. **Crop and straighten.** *Crop* tab → drag corners or the frame, choose an aspect ratio, rotate
   left/right, flip, straighten slider.
2. **Adjust.** *Adjust* tab → exposure, contrast, highlights, shadows, saturation, warmth,
   sharpness; each slider drag is one undo step.
3. **Filters and compare.** *Filters* tab → tap a preset thumbnail; hold *Hold to compare* to see
   the original.
4. **Export.** Export (top bar) → format JPG/PNG/WebP, quality, size, keep metadata / location →
   choose where to save. *Save over original* asks for confirmation and keeps the file's format.

## Video (MP4)

1. **Trim and split.** Play or scrub to a point → *Split*; select a clip in the timeline →
   *Trim* → drag the start/end handles.
2. **Assemble.** *Add* → pick more clips (joined at the end); select a clip → *Move earlier/later*
   or *Delete clip*.
3. **Audio.** *Volume* → clip volume 0-200 % or mute; *Music* → choose a track, volume, loop.
4. **Text.** *Text* → type → *Add at playhead* → adjust time range, size, colour, position, bold.
5. **Export.** Export (top bar) → 1080p, 720p or vertical 9:16 → progress bar → choose where to
   save (or share). The source clips are never changed.
