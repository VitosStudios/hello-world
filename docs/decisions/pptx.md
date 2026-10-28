# PPTX decisions

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
