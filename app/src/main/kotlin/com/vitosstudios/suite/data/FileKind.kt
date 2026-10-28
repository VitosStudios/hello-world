package com.vitosstudios.suite.data

/** The file types the app opens, described by their technical format names. */
enum class FileKind(
    val label: String,
    val extensions: List<String>,
    val mimeTypes: List<String>,
    /** Which editor opens it. */
    val editor: Editor,
) {
    DOCX("DOCX", listOf("docx", "docm"), listOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-word.document.macroEnabled.12",
    ), Editor.DOCUMENTS),
    XLSX("XLSX", listOf("xlsx", "xlsm"), listOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel.sheet.macroEnabled.12",
    ), Editor.SHEETS),
    PPTX("PPTX", listOf("pptx", "pptm"), listOf(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
    ), Editor.SLIDES),
    PDF("PDF", listOf("pdf"), listOf("application/pdf"), Editor.PDF),
    JPEG("JPG", listOf("jpg", "jpeg"), listOf("image/jpeg"), Editor.PHOTO),
    PNG("PNG", listOf("png"), listOf("image/png"), Editor.PHOTO),
    WEBP("WebP", listOf("webp"), listOf("image/webp"), Editor.PHOTO),
    MP4("MP4", listOf("mp4", "m4v"), listOf("video/mp4"), Editor.VIDEO),
    CSV("CSV", listOf("csv"), listOf("text/csv", "text/comma-separated-values"), Editor.SHEETS),
    ;

    val primaryMime: String get() = mimeTypes.first()
    val primaryExtension: String get() = extensions.first()

    enum class Editor { DOCUMENTS, SHEETS, SLIDES, PHOTO, VIDEO, PDF }

    companion object {
        fun from(name: String?, mime: String?): FileKind? {
            val ext = name?.substringAfterLast('.', "")?.lowercase()
            if (!ext.isNullOrEmpty()) entries.firstOrNull { ext in it.extensions }?.let { return it }
            if (mime != null) entries.firstOrNull { mime.lowercase() in it.mimeTypes }?.let { return it }
            return null
        }

        /** MIME types offered in the system file picker. */
        val openableMimeTypes: Array<String> = entries.flatMap { it.mimeTypes }.distinct().toTypedArray()
    }
}
