package com.vitosstudios.suite.opc

/** Relationship types (transitional form; strict forms are mapped by [canonical]). */
object RelTypes {
    private const val BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
    private const val STRICT_BASE = "http://purl.oclc.org/ooxml/officeDocument/relationships/"

    const val OFFICE_DOCUMENT = BASE + "officeDocument"
    const val CORE_PROPERTIES = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
    const val EXTENDED_PROPERTIES = BASE + "extended-properties"
    const val THUMBNAIL = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail"
    const val STYLES = BASE + "styles"
    const val NUMBERING = BASE + "numbering"
    const val THEME = BASE + "theme"
    const val SETTINGS = BASE + "settings"
    const val FONT_TABLE = BASE + "fontTable"
    const val HEADER = BASE + "header"
    const val FOOTER = BASE + "footer"
    const val FOOTNOTES = BASE + "footnotes"
    const val ENDNOTES = BASE + "endnotes"
    const val COMMENTS = BASE + "comments"
    const val IMAGE = BASE + "image"
    const val HYPERLINK = BASE + "hyperlink"
    const val CHART = BASE + "chart"
    const val DIAGRAM_DATA = BASE + "diagramData"
    const val OLE_OBJECT = BASE + "oleObject"
    const val PACKAGE = BASE + "package"
    const val DRAWING = BASE + "drawing"
    const val VML_DRAWING = BASE + "vmlDrawing"
    const val WORKSHEET = BASE + "worksheet"
    const val CHARTSHEET = BASE + "chartsheet"
    const val SHARED_STRINGS = BASE + "sharedStrings"
    const val CALC_CHAIN = BASE + "calcChain"
    const val TABLE = BASE + "table"
    const val PIVOT_TABLE = BASE + "pivotTable"
    const val SLIDE = BASE + "slide"
    const val SLIDE_LAYOUT = BASE + "slideLayout"
    const val SLIDE_MASTER = BASE + "slideMaster"
    const val NOTES_SLIDE = BASE + "notesSlide"
    const val NOTES_MASTER = BASE + "notesMaster"
    const val HANDOUT_MASTER = BASE + "handoutMaster"
    const val PRES_PROPS = BASE + "presProps"
    const val VIEW_PROPS = BASE + "viewProps"
    const val TABLE_STYLES = BASE + "tableStyles"
    const val MEDIA = "http://schemas.microsoft.com/office/2007/relationships/media"
    const val VIDEO = BASE + "video"
    const val AUDIO = BASE + "audio"

    fun canonical(type: String): String =
        if (type.startsWith(STRICT_BASE)) BASE + type.substring(STRICT_BASE.length) else type
}

/** Content types written for parts the app creates. */
object ContentTypeNames {
    const val RELATIONSHIPS = "application/vnd.openxmlformats-package.relationships+xml"
    const val XML = "application/xml"
    const val PNG = "image/png"
    const val JPEG = "image/jpeg"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"

    const val WML_NUMBERING = "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
    const val WML_HEADER = "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"
    const val WML_FOOTER = "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"
    const val WML_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
    const val SML_WORKSHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    const val SML_SHARED_STRINGS = "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"
    const val SML_STYLES = "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"
    const val DRAWING = "application/vnd.openxmlformats-officedocument.drawing+xml"
    const val CHART = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
    const val PML_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    const val PML_NOTES_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"
    const val PML_NOTES_MASTER = "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"
    const val THEME = "application/vnd.openxmlformats-officedocument.theme+xml"

    fun forImageExtension(ext: String): String? = when (ext.lowercase()) {
        "png" -> PNG
        "jpg", "jpeg" -> JPEG
        "gif" -> GIF
        "webp" -> WEBP
        "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "emf" -> "image/x-emf"
        "wmf" -> "image/x-wmf"
        "svg" -> "image/svg+xml"
        else -> null
    }
}
