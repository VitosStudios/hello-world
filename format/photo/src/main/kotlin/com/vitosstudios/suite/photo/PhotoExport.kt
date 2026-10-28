package com.vitosstudios.suite.photo

/** Output encodings offered by "Export". */
enum class PhotoFormat(val mimeType: String, val extension: String, val supportsQuality: Boolean, val supportsAlpha: Boolean) {
    JPEG("image/jpeg", "jpg", supportsQuality = true, supportsAlpha = false),
    PNG("image/png", "png", supportsQuality = false, supportsAlpha = true),
    WEBP("image/webp", "webp", supportsQuality = true, supportsAlpha = true);

    companion object {
        /** Format matching a source MIME type, for "save in the original format". */
        fun forMimeType(mime: String?): PhotoFormat? = when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> JPEG
            "image/png" -> PNG
            "image/webp" -> WEBP
            else -> null
        }
    }
}

/** Size presets: the long edge is limited, the image is never enlarged. */
enum class PhotoExportSize(val label: String, val maxLongEdge: Int?) {
    ORIGINAL("Original", null),
    MAX_4096("4096 px", 4096),
    MAX_2048("2048 px", 2048),
    MAX_1080("1080 px", 1080),
}

data class PhotoExportOptions(
    val format: PhotoFormat = PhotoFormat.JPEG,
    /** 1..100; ignored for PNG. */
    val quality: Int = 92,
    val size: PhotoExportSize = PhotoExportSize.ORIGINAL,
    /** Copy camera and date metadata from the source. Orientation is always written as 1. */
    val keepMetadata: Boolean = true,
    /** Copy GPS tags as well. Off by default so exported files do not leak where they were taken. */
    val keepLocation: Boolean = false,
) {
    fun validated(): PhotoExportOptions = copy(quality = quality.coerceIn(1, 100))

    /** Scale applied to the edit's output size. */
    fun scale(editOutput: Size): Double = PhotoGeometry.scaleForLongEdge(editOutput, size.maxLongEdge)

    /** Final pixel size of the exported file. */
    fun targetSize(editOutput: Size): Size = PhotoGeometry.scaledSize(editOutput, scale(editOutput))

    /** "holiday.jpg" -> "holiday-edited.webp" for the chosen format. */
    fun fileName(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "photo" }
        return "$base-edited.${format.extension}"
    }
}

/**
 * Which EXIF tags survive an export. Tag names are the EXIF names used by
 * `androidx.exifinterface.media.ExifInterface` (its `TAG_*` constants have these values).
 *
 * Never copied: Orientation (the orientation is baked into the pixels, 1 is written instead),
 * dimension tags (the size changed), thumbnails and maker notes (they reference byte offsets in
 * the original file and would be corrupt).
 */
object ExifPolicy {
    const val TAG_ORIENTATION = "Orientation"

    val CAMERA_AND_DATE_TAGS: List<String> = listOf(
        "DateTime", "DateTimeOriginal", "DateTimeDigitized",
        "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
        "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
        "Make", "Model", "LensMake", "LensModel", "BodySerialNumber",
        "ExposureTime", "FNumber", "PhotographicSensitivity", "ExposureProgram", "ExposureBiasValue",
        "MeteringMode", "Flash", "FocalLength", "FocalLengthIn35mmFilm", "WhiteBalance",
        "Artist", "Copyright", "ImageDescription", "UserComment",
    )

    val LOCATION_TAGS: List<String> = listOf(
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
        "GPSProcessingMethod", "GPSImgDirection", "GPSImgDirectionRef",
    )

    val NEVER_COPIED: List<String> = listOf(
        TAG_ORIENTATION, "ImageWidth", "ImageLength", "PixelXDimension", "PixelYDimension",
        "ThumbnailImageWidth", "ThumbnailImageLength", "JPEGInterchangeFormat", "JPEGInterchangeFormatLength",
        "MakerNote",
    )

    /** Tags to copy from the source file into the exported file. */
    fun tagsToCopy(options: PhotoExportOptions): List<String> = when {
        !options.keepMetadata -> emptyList()
        options.keepLocation -> CAMERA_AND_DATE_TAGS + LOCATION_TAGS
        else -> CAMERA_AND_DATE_TAGS
    }

    /** Tags the app sets itself on every export. */
    fun tagsToWrite(target: Size): Map<String, String> = mapOf(
        TAG_ORIENTATION to ExifOrientation.NORMAL.toString(),
        "PixelXDimension" to target.width.toString(),
        "PixelYDimension" to target.height.toString(),
    )
}
