package com.vitosstudios.suite.docx

import com.vitosstudios.suite.opc.OpcPackage
import com.vitosstudios.suite.xml.XmlEscaping

enum class DocxTemplate(val title: String) {
    BLANK("Blank document"),
    REPORT("Report"),
    LETTER("Letter"),
}

/**
 * The app's own document templates, written from scratch (no third-party template files). They use
 * the conventional built-in style ids so that other word processors map them to their own styles.
 */
internal object DocxTemplates {
    /** Name written to docProps/app.xml for new files. */
    var applicationName: String = "ArtCraft Mobile"

    private const val DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\r\n"
    private const val W = Wml.W
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun build(template: DocxTemplate): OpcPackage {
        val entries = LinkedHashMap<String, ByteArray>()
        fun put(name: String, xml: String) { entries[name] = (DECL + xml).toByteArray(Charsets.UTF_8) }
        put("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""" +
            """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
            """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
            """</Types>""")
        put("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
            """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
            """</Relationships>""")
        put("word/_rels/document.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""" +
            """</Relationships>""")
        put("word/document.xml", """<w:document xmlns:w="$W" xmlns:r="$R"><w:body>${body(template)}""" +
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>""" +
            """<w:cols w:space="708"/></w:sectPr></w:body></w:document>""")
        put("word/styles.xml", styles())
        put("word/settings.xml", """<w:settings xmlns:w="$W"><w:defaultTabStop w:val="708"/><w:characterSpacingControl w:val="doNotCompress"/>""" +
            """<w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>""")
        put("docProps/core.xml", """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:title>${XmlEscaping.escapeText(template.title)}</dc:title></cp:coreProperties>""")
        put("docProps/app.xml", """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">""" +
            """<Application>${XmlEscaping.escapeText(applicationName)}</Application></Properties>""")
        return OpcPackage.fromEntries(entries)
    }

    private fun p(text: String, style: String? = null): String {
        val pPr = if (style != null) """<w:pPr><w:pStyle w:val="$style"/></w:pPr>""" else ""
        val run = if (text.isEmpty()) "" else """<w:r><w:t xml:space="preserve">${XmlEscaping.escapeText(text)}</w:t></w:r>"""
        return "<w:p>$pPr$run</w:p>"
    }

    private fun body(t: DocxTemplate): String = when (t) {
        DocxTemplate.BLANK -> p("")
        DocxTemplate.REPORT -> p("Report title", "Title") + p("Summary", "Heading1") +
            p("Write a short summary of the report here.") + p("Details", "Heading1") + p("Background", "Heading2") +
            p("Describe the background.") + p("Findings", "Heading2") + p("List the findings.")
        DocxTemplate.LETTER -> p("Your name") + p("Street and number") + p("Postcode and city") + p("") +
            p("Date") + p("") + p("Dear recipient,") + p("") + p("Write your letter here.") + p("") +
            p("Kind regards,") + p("") + p("Your name")
    }

    private fun styles(): String {
        val sb = StringBuilder()
        sb.append("""<w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr>""")
        sb.append("""<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US"/>""")
        sb.append("""</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""")
        sb.append("""<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""")
        sb.append("""<w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont"><w:name w:val="Default Paragraph Font"/><w:uiPriority w:val="1"/><w:semiHidden/><w:unhideWhenUsed/></w:style>""")
        sb.append("""<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:uiPriority w:val="99"/><w:semiHidden/><w:unhideWhenUsed/>""" +
            """<w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""")
        sb.append("""<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="10"/><w:qFormat/>""" +
            """<w:pPr><w:spacing w:after="80" w:line="240" w:lineRule="auto"/><w:contextualSpacing/></w:pPr><w:rPr><w:sz w:val="56"/><w:szCs w:val="56"/></w:rPr></w:style>""")
        val sizes = listOf(32, 28, 24, 22, 22, 22)
        val colors = listOf("1F3864", "1F3864", "1F3864", "2F5496", "2F5496", "2F5496")
        for (level in 1..6) {
            sb.append("""<w:style w:type="paragraph" w:styleId="Heading$level"><w:name w:val="heading $level"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/>""")
            if (level > 2) sb.append("<w:unhideWhenUsed/>")
            sb.append("""<w:qFormat/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="${if (level == 1) 360 else 160}" w:after="80"/><w:outlineLvl w:val="${level - 1}"/></w:pPr>""")
            sb.append("""<w:rPr>${if (level <= 2) "<w:b/>" else ""}${if (level >= 4) "<w:i/>" else ""}<w:color w:val="${colors[level - 1]}"/><w:sz w:val="${sizes[level - 1]}"/><w:szCs w:val="${sizes[level - 1]}"/></w:rPr></w:style>""")
        }
        sb.append("""<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="34"/><w:qFormat/><w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>""")
        sb.append("""<w:style w:type="paragraph" w:styleId="Header"><w:name w:val="header"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:pPr><w:tabs><w:tab w:val="center" w:pos="4513"/><w:tab w:val="right" w:pos="9026"/></w:tabs><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:style>""")
        sb.append("""<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:pPr><w:tabs><w:tab w:val="center" w:pos="4513"/><w:tab w:val="right" w:pos="9026"/></w:tabs><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:style>""")
        sb.append("""<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:basedOn w:val="DefaultParagraphFont"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>""")
        sb.append("""<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:basedOn w:val="TableNormal"/><w:uiPriority w:val="39"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>""" +
            """<w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/>""" +
            """<w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders></w:tblPr></w:style>""")
        sb.append("</w:styles>")
        return sb.toString()
    }
}
