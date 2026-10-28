package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.opc.CompatibilityItem
import com.vitosstudios.suite.opc.CompatibilityReport
import com.vitosstudios.suite.opc.PackageVerifier
import com.vitosstudios.suite.opc.RelTypes
import com.vitosstudios.suite.opc.SupportLevel.EDITABLE
import com.vitosstudios.suite.opc.SupportLevel.PRESERVED
import com.vitosstudios.suite.opc.SupportLevel.READ_ONLY
import com.vitosstudios.suite.xml.Namespaces.A
import com.vitosstudios.suite.xml.Namespaces.P
import com.vitosstudios.suite.xml.XElement

/** Counts what a deck contains and how this app treats each kind of content. */
internal object PptxCompatibility {
    fun report(pres: PptxPresentation): CompatibilityReport {
        val pkg = pres.pkg
        val counts = HashMap<String, Int>()
        fun add(key: String, n: Int = 1) { counts[key] = (counts[key] ?: 0) + n }

        val slides = pres.slideParts()
        for (part in slides) {
            val root = readRoot(part) ?: continue
            if (root.attr("show") == "0") add("hidden")
            if (root.element(P, "transition") != null ||
                root.elements().any { it.localName == "AlternateContent" && it.descendants().any { d -> d.localName == "transition" } }
            ) add("transition")
            if (root.element(P, "timing") != null) add("animation")
            val tree = root.element(P, "cSld")?.element(P, "spTree") ?: continue
            countShapes(tree, ::add)
            for (d in root.descendants()) {
                if (d.localName == "hlinkClick" && d.ns == A) add("hyperlink")
            }
        }
        val notes = slides.count { pkg.related(it, RelTypes.NOTES_SLIDE) != null }
        val presentation = pres.presentation
        val sections = presentation.element(P, "extLst")?.descendants()?.count { it.localName == "section" } ?: 0
        val customShows = presentation.element(P, "custShowLst")?.elements(P, "custShow")?.count() ?: 0
        val embeddedFonts = presentation.element(P, "embeddedFontLst")?.elements()?.count() ?: 0
        val partsOf = { needle: String -> pkg.contentParts.count { (it.contentType ?: "").contains(needle) } }
        val comments = partsOf("presentationml.comments+xml") + partsOf("powerpoint.comments+xml")
        val vba = pkg.contentParts.count { it.name.endsWith("vbaProject.bin", ignoreCase = true) }
        val handout = partsOf("handoutMaster+xml")
        val tags = partsOf("presentationml.tags+xml")
        val unparsed = pkg.parts.filter { !it.isDirectory && isSlidePart(it.contentType) && PackageVerifier.isXml(it) && it.xmlOrNull() == null }
            .map { it.name }

        fun c(key: String) = counts[key] ?: 0
        val items = listOf(
            CompatibilityItem("Slides", EDITABLE, slides.size, "Insert, delete, duplicate, reorder and change layout."),
            CompatibilityItem("Text boxes and placeholders", EDITABLE, c("textbox"), "Text, font, size, bold, italic, underline, color, bullets, alignment."),
            CompatibilityItem("Shapes", EDITABLE, c("shape"), "Move, resize, rotate, fill and outline color; text inside shapes."),
            CompatibilityItem("Lines and connectors", EDITABLE, c("connector"), "Move and resize; connections to other shapes are kept as stored."),
            CompatibilityItem("Pictures", EDITABLE, c("picture"), "Insert, move, resize, delete. Crop and effects are kept but not editable."),
            CompatibilityItem("Groups", EDITABLE, c("group"), "Moved and resized as one; text inside grouped shapes is editable."),
            CompatibilityItem("Speaker notes", EDITABLE, notes, "View and edit the notes text."),
            CompatibilityItem("Hidden slides", EDITABLE, c("hidden"), "Shown in the slide list; skipped in presentation mode."),
            CompatibilityItem("Hyperlinks", READ_ONLY, c("hyperlink"), "Shown and followed; addresses are kept unchanged."),
            CompatibilityItem("Tables", READ_ONLY, c("table"), "Shown with their text; saved unchanged."),
            CompatibilityItem("Charts", READ_ONLY, c("chart"), "Shown as a placeholder; data and formatting saved unchanged."),
            CompatibilityItem("SmartArt", READ_ONLY, c("smartart"), "Shown as a placeholder; saved unchanged."),
            CompatibilityItem("Embedded objects", READ_ONLY, c("ole"), "Shown as their preview image or a placeholder; saved unchanged."),
            CompatibilityItem("Audio and video", READ_ONLY, c("media"), "Shown as the poster frame; media saved unchanged."),
            CompatibilityItem("Ink", READ_ONLY, c("ink"), "Shown as a placeholder; saved unchanged."),
            CompatibilityItem("Newer-format content", READ_ONLY, c("alternate"), "Content written for newer readers is shown from its fallback and saved unchanged."),
            CompatibilityItem("Transitions", PRESERVED, c("transition"), "Kept exactly as stored; not editable here."),
            CompatibilityItem("Animations", PRESERVED, c("animation"), "Kept exactly as stored; animated shapes cannot be deleted here."),
            CompatibilityItem("Sections", PRESERVED, sections, "Kept; new and moved slides join the section of the slide before them."),
            CompatibilityItem("Custom shows", PRESERVED, customShows, "Kept; deleted slides are removed from them."),
            CompatibilityItem("Comments", PRESERVED, comments, "Kept unchanged; not shown."),
            CompatibilityItem("Embedded fonts", PRESERVED, embeddedFonts, "Kept unchanged; text is drawn with device fonts."),
            CompatibilityItem("Handout master", PRESERVED, handout, "Kept unchanged."),
            CompatibilityItem("Tags and add-in data", PRESERVED, tags, "Kept unchanged."),
            CompatibilityItem("Macros (VBA)", PRESERVED, vba, "Kept unchanged and never run."),
        )
        return CompatibilityReport("PPTX", items, unparsed)
    }

    private fun isSlidePart(ct: String?) = ct != null && ct.contains("presentationml")

    private fun countShapes(tree: XElement, add: (String, Int) -> Unit) {
        for (e in tree.elements()) {
            when (e.localName) {
                "sp" -> {
                    val txBox = e.element(P, "nvSpPr")?.element(P, "cNvSpPr")?.attr("txBox") == "1"
                    val ph = e.element(P, "nvSpPr")?.element(P, "nvPr")?.element(P, "ph") != null
                    val geom = e.element(P, "spPr")?.element(A, "prstGeom")?.attr("prst")
                    add(if (txBox || ph || (geom == null || geom == "rect") && e.element(P, "style") == null && e.element(P, "txBody") != null) "textbox" else "shape", 1)
                }
                "cxnSp" -> add("connector", 1)
                "pic" -> {
                    val nv = e.element(P, "nvPicPr")?.element(P, "nvPr")
                    val media = nv != null && nv.descendants().any { it.localName in setOf("videoFile", "audioFile", "quickTimeFile", "media") }
                    add(if (media) "media" else "picture", 1)
                }
                "grpSp" -> {
                    add("group", 1)
                    countShapes(e, add)
                }
                "graphicFrame" -> {
                    val uri = e.element(A, "graphic")?.element(A, "graphicData")?.attr("uri") ?: ""
                    add(when {
                        uri.endsWith("/table") -> "table"
                        uri.endsWith("/chart") || uri.contains("chartex") -> "chart"
                        uri.endsWith("/diagram") -> "smartart"
                        uri.endsWith("/ole") -> "ole"
                        else -> "alternate"
                    }, 1)
                }
                "contentPart" -> add("ink", 1)
                "AlternateContent" -> {
                    val choice = e.elements().firstOrNull()?.elements()?.firstOrNull()
                    add(if (choice?.localName == "contentPart") "ink" else "alternate", 1)
                }
            }
        }
    }
}
