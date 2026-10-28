package com.vitosstudios.suite.opc

/** How the app treats one kind of content in an opened file. */
enum class SupportLevel {
    /** Shown and editable. */
    EDITABLE,

    /** Shown (possibly as a placeholder) but locked; saved unchanged. */
    READ_ONLY,

    /** Not shown; kept byte-identical (or element-identical) on save. */
    PRESERVED,
}

data class CompatibilityItem(
    /** Short user-facing name, e.g. "Tables" or "SmartArt". */
    val feature: String,
    val level: SupportLevel,
    /** How many occurrences the file contains (0 = feature known but absent). */
    val count: Int,
    /** One sentence explaining what happens to this content. */
    val detail: String,
)

/** Per-file report behind the "Compatibility" screen. */
data class CompatibilityReport(
    /** Technical format name: "DOCX", "XLSX", "PPTX". */
    val format: String,
    val items: List<CompatibilityItem>,
    /** Parts that could not be parsed losslessly and are therefore kept untouched. */
    val unparsedParts: List<String> = emptyList(),
) {
    val present: List<CompatibilityItem> get() = items.filter { it.count > 0 }
    fun level(feature: String): SupportLevel? = items.firstOrNull { it.feature == feature }?.level
}

/** Common surface of the three office formats, used by the app shell. */
interface OfficeDocument : AutoCloseable {
    val pkg: OpcPackage

    /** Builds the compatibility report for the current content. */
    fun compatibility(): CompatibilityReport

    /**
     * Saves crash-safely to [target] in the same format. The written file is re-opened with this
     * format's own reader before it replaces [target].
     */
    fun save(target: java.io.File)

    override fun close() = pkg.close()
}
