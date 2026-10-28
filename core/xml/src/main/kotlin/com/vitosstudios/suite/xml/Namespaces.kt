package com.vitosstudios.suite.xml

/**
 * Namespace URIs used by the format layers. Lookups compare canonical URIs so that documents
 * saved in ISO "Strict" conformance (purl.oclc.org namespaces) are understood like transitional
 * ones. New declarations are written with the variant the document already uses.
 */
object Namespaces {
    const val XML = "http://www.w3.org/XML/1998/namespace"

    // OPC
    const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val PKG_RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    // Shared
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    const val DGM = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
    const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    const val VML = "urn:schemas-microsoft-com:vml"
    const val VML_OFFICE = "urn:schemas-microsoft-com:office:office"
    const val EXTENDED_PROPERTIES = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"

    // Formats
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"

    // Common extension namespaces (kept untouched, listed for prefix choice only)
    const val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
    const val WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"

    private val strictToTransitional = mapOf(
        "http://purl.oclc.org/ooxml/wordprocessingml/main" to W,
        "http://purl.oclc.org/ooxml/spreadsheetml/main" to S,
        "http://purl.oclc.org/ooxml/presentationml/main" to P,
        "http://purl.oclc.org/ooxml/drawingml/main" to A,
        "http://purl.oclc.org/ooxml/drawingml/picture" to PIC,
        "http://purl.oclc.org/ooxml/drawingml/chart" to C,
        "http://purl.oclc.org/ooxml/drawingml/diagram" to DGM,
        "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing" to WP,
        "http://purl.oclc.org/ooxml/drawingml/spreadsheetDrawing" to XDR,
        "http://purl.oclc.org/ooxml/officeDocument/relationships" to R,
        "http://purl.oclc.org/ooxml/officeDocument/math" to M,
        "http://purl.oclc.org/ooxml/officeDocument/extendedProperties" to EXTENDED_PROPERTIES,
    )
    private val transitionalToStrict = strictToTransitional.entries.associate { (k, v) -> v to k }

    private val preferred = mapOf(
        W to "w", S to null, P to "p", A to "a", PIC to "pic", C to "c", R to "r", WP to "wp",
        XDR to "xdr", M to "m", MC to "mc", DGM to "dgm", VML to "v", VML_OFFICE to "o",
        W14 to "w14", WPS to "wps",
    )

    fun canonical(uri: String): String = strictToTransitional[uri] ?: uri

    fun isStrict(uri: String): Boolean = uri in strictToTransitional

    fun preferredPrefix(nsUri: String): String? = preferred[nsUri]

    /** The URI to write when declaring [canonicalUri] near [context]: strict if the document is strict. */
    fun serializedUri(canonicalUri: String, context: XElement): String {
        val strict = transitionalToStrict[canonicalUri] ?: return canonicalUri
        var root: XElement = context
        while (true) root = root.parent ?: break
        val usesStrict = root.attributes.any { it.isNamespaceDeclaration && isStrict(it.value) }
        return if (usesStrict) strict else canonicalUri
    }
}
