package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.CompatibilityItem
import com.vitosstudios.suite.opc.CompatibilityReport
import com.vitosstudios.suite.opc.PackageVerifier
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.opc.SupportLevel
import com.vitosstudios.suite.xml.Namespaces

internal object DocxCompatibility {
    fun report(doc: DocxDocument): CompatibilityReport {
        val items = ArrayList<CompatibilityItem>()
        fun add(feature: String, level: SupportLevel, count: Int, detail: String) {
            items += CompatibilityItem(feature, level, count, detail)
        }
        val roots = doc.storyParts().mapNotNull { it.xmlOrNull()?.root }
        fun countW(local: String) = roots.sumOf { r -> r.descendants(Wml.W, local).count() }
        fun count(ns: String, local: String) = roots.sumOf { r -> r.descendants(ns, local).count() }

        val paragraphs = doc.allParagraphs()
        val locked = paragraphs.count { !it.isTextEditable }
        val images = paragraphs.sumOf { p -> p.atoms.count { it.second.kind == AtomKind.IMAGE } }
        val drawings = paragraphs.flatMap { p -> p.atoms.filter { it.second.kind == AtomKind.LOCKED_DRAWING }.map { it.second.label } }
        val tables = doc.body.descendants(Wml.W, "tbl").toList()
        val mergedTables = tables.count { t -> DocxTable(doc, t, doc.mainPart).hasMergedCells }
        val headings = doc.bodyParagraphs().count { it.headingLevel > 0 }
        val lists = doc.bodyParagraphs().count { it.format.numId != null && it.format.numId != "0" }

        add("Paragraphs and text", SupportLevel.EDITABLE, paragraphs.size - locked, "Text, bold, italic, underline, strikethrough, size, colour, highlight and font can be edited.")
        add("Paragraphs with fields or tracked changes", SupportLevel.READ_ONLY, locked, "Shown with a lock. Their text cannot be edited, but alignment and spacing can; content is saved unchanged.")
        add("Headings and paragraph styles", SupportLevel.EDITABLE, headings, "Headings 1-6 use the document's own heading styles.")
        add("Lists", SupportLevel.EDITABLE, lists, "Bulleted and numbered lists, including nesting levels.")
        add("Alignment, indentation and spacing", SupportLevel.EDITABLE, paragraphs.size, "Paragraph alignment, indents, line and paragraph spacing.")
        add("Tables", SupportLevel.EDITABLE, tables.size - mergedTables, "Cell text, inserting and deleting rows and columns.")
        add("Tables with merged cells", SupportLevel.READ_ONLY, mergedTables, "Cell text is editable; the table structure is locked.")
        add("Images", SupportLevel.EDITABLE, images, "Insert and resize. The original image data is kept.")
        add("Headers and footers", SupportLevel.EDITABLE, doc.headersFooters().size, "Text in headers and footers can be edited.")
        add("Hyperlinks", SupportLevel.EDITABLE, countW("hyperlink"), "Add, change and remove links.")
        add("Page breaks", SupportLevel.EDITABLE, roots.sumOf { r -> r.descendants(Wml.W, "br").count { it.wAttr("type") == "page" } }, "Insert and delete page breaks.")
        add("Page setup", SupportLevel.EDITABLE, doc.sections().size, "Page size, margins and orientation per section.")
        add("Charts", SupportLevel.READ_ONLY, drawings.count { it == "Chart" }, "Shown as a locked placeholder and saved unchanged.")
        add("SmartArt", SupportLevel.READ_ONLY, drawings.count { it == "SmartArt" }, "Shown as a locked placeholder and saved unchanged.")
        add("Shapes and text boxes", SupportLevel.READ_ONLY, drawings.count { it == "Shape" || it == "Group" || it == "Drawing canvas" } +
            count(Namespaces.MC, "AlternateContent"), "Shown as a locked placeholder and saved unchanged.")
        add("Legacy drawings (VML)", SupportLevel.READ_ONLY, countW("pict"), "Shown as a locked placeholder and saved unchanged.")
        add("Embedded objects (OLE)", SupportLevel.READ_ONLY, countW("object"), "Shown as a locked placeholder and saved unchanged.")
        add("Equations", SupportLevel.READ_ONLY, count(Namespaces.M, "oMath"), "Shown as a locked placeholder and saved unchanged.")
        add("Fields", SupportLevel.READ_ONLY, countW("fldSimple") + roots.sumOf { r -> r.descendants(Wml.W, "fldChar").count { it.wAttr("fldCharType") == "begin" } },
            "Field results are shown; fields are not updated or edited.")
        add("Tracked changes", SupportLevel.READ_ONLY, countW("ins") + countW("del"), "Paragraphs with revisions are locked; revisions are kept.")
        add("Footnotes and endnotes", SupportLevel.READ_ONLY, countW("footnoteReference") + countW("endnoteReference"), "Reference marks are shown; note text is kept unchanged.")
        add("Comments", SupportLevel.PRESERVED, countW("commentReference"), "Comments are kept unchanged but not shown.")
        add("Bookmarks", SupportLevel.PRESERVED, countW("bookmarkStart"), "Kept at their position.")
        add("Content controls", SupportLevel.EDITABLE, countW("sdt"), "Text inside content controls is editable; control properties are kept.")
        add("Multiple columns", SupportLevel.PRESERVED, doc.sections().count { it.columns > 1 }, "Column layout is kept; text is shown in one column.")
        val rels = doc.pkg.relationships(doc.mainPart)
        add("Custom XML data", SupportLevel.PRESERVED, doc.pkg.parts.count { it.name.startsWith("/customXml/", true) && it.name.endsWith(".xml", true) && !it.name.contains("itemProps", true) }, "Kept byte-identical.")
        add("Macros", SupportLevel.PRESERVED, doc.pkg.parts.count { it.name.endsWith("vbaProject.bin", true) }, "Kept byte-identical; never run.")
        add("Embedded fonts", SupportLevel.PRESERVED, rels.ofType(RelTypes.FONT_TABLE).sumOf { r -> doc.pkg.relationships(rels.targetPart(r)).all().size }, "Kept byte-identical.")
        add("Digital signatures", SupportLevel.READ_ONLY, doc.pkg.parts.count { it.name.startsWith("/_xmlsignatures/", true) && it.name.endsWith(".xml", true) },
            "Kept, but any edit invalidates the signature.")
        add("Theme and settings", SupportLevel.PRESERVED, listOf(RelTypes.THEME, RelTypes.SETTINGS).count { rels.firstOfType(it) != null }, "Kept byte-identical.")

        val unparsed = doc.pkg.parts.filter { !it.isDirectory && PackageVerifier.isXml(it) && it.isParsed.not() && it.name.startsWith("/word/") }
            .filter { it.xmlOrNull() == null }.map { it.name }
        return CompatibilityReport("DOCX", items, unparsed)
    }
}
