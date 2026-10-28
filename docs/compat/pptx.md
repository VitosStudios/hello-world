# PPTX compatibility

Status meanings:

- **Editable**: shown and changed by the app.
- **Read-only**: shown (sometimes as a labelled placeholder) and saved unchanged.
- **Preserved**: not shown, saved unchanged.
- **Not supported**: neither shown nor edited, but still saved unchanged. The app never deletes content it does not understand.

Every part the app does not edit is written back byte-identical. Within edited slide XML, unknown elements and attributes (`p:transition`, `p:timing`, `mc:AlternateContent`, `extLst`, custom namespaces) are kept as they are.

## Package and presentation

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

## Slides

| Element | Status | Notes |
|---|---|---|
| Slide background (`p:bg`, solid / gradient / picture / theme reference) | Read-only | Inherited from layout and master when the slide has none. |
| Hidden slide (`show="0"`) | Editable | Hide or show. |
| Transitions (`p:transition`, also inside `mc:AlternateContent`) | Preserved | Byte-identical inside the edited slide. |
| Animations (`p:timing`, `p:bldLst`) | Preserved | Byte-identical. Shapes that are animation targets cannot be deleted (it would leave a dangling target). |
| Speaker notes | Editable | Plain text, one paragraph per line; formatting of the first run is reused. |
| Master/layout decorations | Read-only | Drawn behind slide content unless `showMasterSp="0"`. |
| Header/footer placeholders (date, footer, slide number) | Read-only | Shown when the slide has them; not created for new slides. |

## Shapes

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

## Text

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
