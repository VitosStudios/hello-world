package com.vitosstudios.suite.pptx

/** A rectangle in English Metric Units (914400 EMU = 1 inch, 12700 EMU = 1 point). */
data class EmuRect(val x: Long, val y: Long, val cx: Long, val cy: Long) {
    val right: Long get() = x + cx
    val bottom: Long get() = y + cy

    companion object {
        const val EMU_PER_INCH = 914400L
        const val EMU_PER_POINT = 12700L
        val ZERO = EmuRect(0, 0, 0, 0)
    }
}

/** A fill as the renderer needs it. Colors are ARGB ints. */
sealed class FillView {
    data object None : FillView()
    data class Solid(val argb: Int) : FillView()

    /** Gradient stops as (position 0..1, ARGB) and the angle of a linear gradient. */
    data class Gradient(val stops: List<Pair<Double, Int>>, val angleDegrees: Double) : FillView()

    /** An image fill; [partName] is the media part (e.g. "/ppt/media/image1.png"). */
    data class Image(val partName: String) : FillView()

    /** A fill this layer does not render (pattern, group fill); [fallbackArgb] approximates it. */
    data class Unsupported(val kind: String, val fallbackArgb: Int?) : FillView()
}

data class LineView(val argb: Int?, val widthEmu: Long, val dash: String?)

enum class ShapeKind { TEXT_BOX, SHAPE, PICTURE, GROUP, CONNECTOR, TABLE, CHART, SMARTART, OLE, MEDIA, INK, OTHER }

/** Where a rendered shape lives. Only [SLIDE] shapes are editable. */
enum class ShapeSource { SLIDE, LAYOUT, MASTER }

enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY, DISTRIBUTED }

enum class TextAnchor { TOP, MIDDLE, BOTTOM }

enum class Autofit { NONE, SHRINK, RESIZE_SHAPE }

data class PlaceholderInfo(val type: String, val index: Int?)

data class ImageRef(
    val partName: String,
    /** Source crop as fractions (0..1) of the image: left, top, right, bottom. */
    val cropLeft: Double = 0.0,
    val cropTop: Double = 0.0,
    val cropRight: Double = 0.0,
    val cropBottom: Double = 0.0,
)

enum class BulletType { NONE, CHAR, NUMBER, PICTURE }

data class BulletView(
    val type: BulletType,
    val char: String? = null,
    /** DrawingML auto-number scheme, e.g. "arabicPeriod", "alphaLcParenR". */
    val scheme: String? = null,
    val startAt: Int = 1,
    /** The number shown for this paragraph (for [BulletType.NUMBER]). */
    val number: Int = 0,
    val typeface: String? = null,
    val argb: Int? = null,
    /** Bullet size relative to the text, 1.0 = 100 %. */
    val sizeRatio: Double = 1.0,
) {
    /** The bullet text to draw ("•", "3.", "c)"). */
    val label: String
        get() = when (type) {
            BulletType.CHAR -> char ?: "•"
            BulletType.NUMBER -> AutoNumber.format(scheme ?: "arabicPeriod", number)
            BulletType.PICTURE -> "•"
            BulletType.NONE -> ""
        }

    companion object {
        val NONE = BulletView(BulletType.NONE)
    }
}

enum class RunKind { TEXT, BREAK, FIELD }

/**
 * A run of text with fully resolved formatting. [start]/[end] are offsets into
 * [ParagraphView.text]; a line break is one character ([LINE_BREAK]).
 */
data class RunView(
    val start: Int,
    val end: Int,
    val text: String,
    val kind: RunKind,
    val sizePt: Double,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val argb: Int,
    val typeface: String,
    val hyperlink: String? = null,
    /** Superscript (> 0) or subscript (< 0) offset in percent. */
    val baseline: Int = 0,
    val fieldType: String? = null,
)

/** The character that stands for a:br in paragraph text (PowerPoint uses the same). */
const val LINE_BREAK = '\u000B'

/** Line spacing either as a multiple (1.0 = single) or in points. */
data class LineSpacing(val multiple: Double? = null, val points: Double? = null)

data class ParagraphView(
    val index: Int,
    val text: String,
    val runs: List<RunView>,
    val level: Int,
    val alignment: TextAlign,
    val bullet: BulletView,
    val marginLeftEmu: Long,
    val indentEmu: Long,
    val lineSpacing: LineSpacing?,
    val spaceBeforePt: Double,
    val spaceAfterPt: Double,
    /** Size of the paragraph mark; gives empty paragraphs their height. */
    val endSizePt: Double,
)

data class TextBodyView(
    val paragraphs: List<ParagraphView>,
    val insetLeft: Long,
    val insetTop: Long,
    val insetRight: Long,
    val insetBottom: Long,
    val anchor: TextAnchor,
    val autofit: Autofit,
    /** Font scale applied by "shrink text on overflow" (1.0 = none). */
    val fontScale: Double,
    val wrap: Boolean,
    val vertical: Boolean,
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.text }
}

data class TableView(val rows: List<List<String>>, val columnWidths: List<Long>, val rowHeights: List<Long>)

data class ShapeView(
    /** cNvPr id, unique within the slide (layout and master shapes have their own id spaces). */
    val id: Int,
    val name: String,
    val kind: ShapeKind,
    val source: ShapeSource,
    /** Bounds in slide coordinates (group transforms applied). */
    val bounds: EmuRect,
    val rotation: Double = 0.0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Preset geometry ("rect", "ellipse", "rightArrow" ...), "custom" for custGeom, null if none. */
    val geometry: String? = null,
    val fill: FillView = FillView.None,
    val line: LineView? = null,
    val image: ImageRef? = null,
    val text: TextBodyView? = null,
    val children: List<ShapeView> = emptyList(),
    val table: TableView? = null,
    val placeholder: PlaceholderInfo? = null,
    val isLocked: Boolean = false,
    val lockReason: String? = null,
    val hyperlink: String? = null,
    val hidden: Boolean = false,
) {
    val isEditable: Boolean get() = source == ShapeSource.SLIDE && !isLocked
    val hasEditableText: Boolean get() = isEditable && text != null && kind in setOf(ShapeKind.TEXT_BOX, ShapeKind.SHAPE)
}

data class SlideView(
    val index: Int,
    val partName: String,
    val layoutPartName: String?,
    val hidden: Boolean,
    val background: FillView,
    /** Draw order: master decorations, layout decorations, then the slide's own shapes. */
    val shapes: List<ShapeView>,
    val hasTransition: Boolean,
    val hasAnimations: Boolean,
) {
    val slideShapes: List<ShapeView> get() = shapes.filter { it.source == ShapeSource.SLIDE }

    /** The slide's own shape with [id], searching inside groups. */
    fun shape(id: Int): ShapeView? {
        fun find(list: List<ShapeView>): ShapeView? {
            for (s in list) {
                if (s.source == ShapeSource.SLIDE && s.id == id) return s
                find(s.children)?.let { return it }
            }
            return null
        }
        return find(shapes)
    }
}

data class LayoutInfo(val partName: String, val name: String, val type: String?, val masterPartName: String)

/** Formatting change for a run range; null fields are left as they are. */
data class RunFormatPatch(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val sizePt: Double? = null,
    /** RGB as 0xRRGGBB (alpha ignored). */
    val rgb: Int? = null,
    val typeface: String? = null,
)

sealed class BulletStyle {
    data object None : BulletStyle()
    data class Char(val char: String = "•") : BulletStyle()
    data class Numbered(val scheme: String = "arabicPeriod", val startAt: Int = 1) : BulletStyle()
}

enum class ShapeType(val preset: String, val defaultName: String) {
    RECTANGLE("rect", "Rectangle"),
    ELLIPSE("ellipse", "Oval"),
    LINE("line", "Straight Connector"),
    ARROW("rightArrow", "Right Arrow"),
}

class PptxEditException(message: String) : Exception(message)

/** Formats DrawingML auto-number schemes. */
object AutoNumber {
    fun format(scheme: String, n: Int): String {
        val core = when {
            scheme.startsWith("arabic") -> n.toString()
            scheme.startsWith("alphaLc") -> alpha(n).lowercase()
            scheme.startsWith("alphaUc") -> alpha(n)
            scheme.startsWith("romanLc") -> roman(n).lowercase()
            scheme.startsWith("romanUc") -> roman(n)
            else -> n.toString()
        }
        return when {
            scheme.endsWith("ParenBoth") -> "($core)"
            scheme.endsWith("ParenR") -> "$core)"
            scheme.endsWith("Period") -> "$core."
            scheme.endsWith("Plain") -> core
            scheme.endsWith("Minus") -> "- $core -"
            else -> "$core."
        }
    }

    private fun alpha(n: Int): String {
        if (n <= 0) return n.toString()
        val letter = 'A' + (n - 1) % 26
        return letter.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in values.indices) while (x >= values[i]) { sb.append(symbols[i]); x -= values[i] }
        return sb.toString()
    }
}
