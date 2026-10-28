package com.vitosstudios.suite.pptx

import com.vitosstudios.suite.xml.Namespaces.A
import com.vitosstudios.suite.xml.XElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The parts of a DrawingML theme the renderer needs. */
internal class Theme(
    val colors: Map<String, Int>,
    val majorLatin: String,
    val minorLatin: String,
    val fillStyles: List<XElement>,
    val lineStyles: List<XElement>,
    val bgFillStyles: List<XElement>,
) {
    fun typeface(raw: String?): String? = when {
        raw == null -> null
        raw.startsWith("+mj") -> majorLatin
        raw.startsWith("+mn") -> minorLatin
        else -> raw
    }

    companion object {
        private val DEFAULT_COLORS = mapOf(
            "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(),
            "lt2" to 0xFFE7E6E6.toInt(), "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(),
            "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(),
            "accent6" to 0xFF70AD47.toInt(), "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
        )

        val DEFAULT = Theme(DEFAULT_COLORS, "Calibri", "Calibri", emptyList(), emptyList(), emptyList())

        fun read(root: XElement?): Theme {
            if (root == null) return DEFAULT
            val elements = root.element(A, "themeElements") ?: return DEFAULT
            val colors = HashMap(DEFAULT_COLORS)
            elements.element(A, "clrScheme")?.elements()?.forEach { slot ->
                val c = slot.elements().firstOrNull() ?: return@forEach
                Colors.baseColor(c)?.let { colors[slot.localName] = it }
            }
            val fonts = elements.element(A, "fontScheme")
            val major = fonts?.element(A, "majorFont")?.element(A, "latin")?.attr("typeface")?.ifEmpty { null } ?: "Calibri"
            val minor = fonts?.element(A, "minorFont")?.element(A, "latin")?.attr("typeface")?.ifEmpty { null } ?: "Calibri"
            val fmt = elements.element(A, "fmtScheme")
            return Theme(
                colors, major, minor,
                fmt?.element(A, "fillStyleLst")?.elements()?.toList().orEmpty(),
                fmt?.element(A, "lnStyleLst")?.elements()?.toList().orEmpty(),
                fmt?.element(A, "bgFillStyleLst")?.elements()?.toList().orEmpty(),
            )
        }
    }
}

/**
 * Resolves DrawingML colors: scheme colors go through the color map (bg1 -> lt1 ...) to the
 * theme; modifiers (lumMod, lumOff, tint, shade, alpha, satMod ...) are applied in HSL.
 */
internal class ColorContext(val theme: Theme, private val clrMap: Map<String, String>) {
    /** Resolves a color element (srgbClr, schemeClr, sysClr, prstClr, scrgbClr, hslClr). */
    fun resolve(color: XElement, phClr: Int? = null): Int? {
        val base: Int = when (color.localName) {
            "schemeClr" -> {
                val v = color.attr("val") ?: return null
                if (v == "phClr") phClr ?: return null
                else {
                    val mapped = clrMap[v] ?: v
                    theme.colors[mapped] ?: theme.colors[v] ?: return null
                }
            }
            else -> Colors.baseColor(color) ?: return null
        }
        return Colors.applyModifiers(base, color)
    }

    /** The first color child of [parent] (for example the srgbClr inside a solidFill). */
    fun resolveChild(parent: XElement?, phClr: Int? = null): Int? {
        val c = parent?.elements()?.firstOrNull { it.localName in Colors.COLOR_ELEMENTS } ?: return null
        return resolve(c, phClr)
    }

    fun withOverride(overrideMapping: XElement?): ColorContext {
        if (overrideMapping == null) return this
        val map = overrideMapping.attributes.filter { !it.isNamespaceDeclaration }.associate { it.localName to it.value }
        return if (map.isEmpty()) this else ColorContext(theme, map)
    }

    companion object {
        val DEFAULT_MAP = mapOf(
            "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2", "accent1" to "accent1",
            "accent2" to "accent2", "accent3" to "accent3", "accent4" to "accent4", "accent5" to "accent5",
            "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink",
        )

        fun fromClrMap(theme: Theme, clrMap: XElement?): ColorContext {
            val map = clrMap?.attributes?.filter { !it.isNamespaceDeclaration }?.associate { it.localName to it.value }
            return ColorContext(theme, if (map.isNullOrEmpty()) DEFAULT_MAP else map)
        }
    }
}

internal object Colors {
    val COLOR_ELEMENTS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")

    private val PRESET = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "gray" to 0x808080, "grey" to 0x808080,
        "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3, "orange" to 0xFFA500, "purple" to 0x800080,
        "navy" to 0x000080, "maroon" to 0x800000, "olive" to 0x808000, "teal" to 0x008080, "silver" to 0xC0C0C0,
        "lime" to 0x00FF00, "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "gold" to 0xFFD700,
        "darkBlue" to 0x00008B, "darkRed" to 0x8B0000, "darkGreen" to 0x006400, "lightBlue" to 0xADD8E6,
    )

    /** Base ARGB of a non-scheme color element, without modifiers. */
    fun baseColor(c: XElement): Int? = when (c.localName) {
        "srgbClr" -> c.attr("val")?.let(::parseHex)
        "sysClr" -> c.attr("lastClr")?.let(::parseHex) ?: when (c.attr("val")) {
            "window", "btnHighlight", "highlightText", "menu", "infoBk" -> 0xFFFFFFFF.toInt()
            else -> 0xFF000000.toInt()
        }
        "prstClr" -> PRESET[c.attr("val")]?.let { it or 0xFF000000.toInt() }
        "scrgbClr" -> {
            fun ch(name: String) = ((c.attr(name)?.toDoubleOrNull() ?: 0.0) / 100000.0).let(::linearToSrgb)
            argb(1.0, ch("r"), ch("g"), ch("b"))
        }
        "hslClr" -> {
            val h = (c.attr("hue")?.toDoubleOrNull() ?: 0.0) / 60000.0 / 360.0
            val s = (c.attr("sat")?.toDoubleOrNull() ?: 0.0) / 100000.0
            val l = (c.attr("lum")?.toDoubleOrNull() ?: 0.0) / 100000.0
            hslToArgb(h, s, l, 1.0)
        }
        else -> null
    }

    fun parseHex(hex: String): Int? = if (hex.length == 6) hex.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt()) else null

    fun hex(argb: Int): String = String.format("%06X", argb and 0xFFFFFF)

    fun applyModifiers(base: Int, color: XElement): Int {
        val mods = color.elements().toList()
        if (mods.isEmpty()) return base
        var a = ((base ushr 24) and 0xFF) / 255.0
        var r = ((base shr 16) and 0xFF) / 255.0
        var g = ((base shr 8) and 0xFF) / 255.0
        var b = (base and 0xFF) / 255.0
        for (m in mods) {
            val v = (m.attr("val")?.toDoubleOrNull() ?: 0.0) / 100000.0
            when (m.localName) {
                "alpha" -> a = v
                "alphaMod" -> a *= v
                "alphaOff" -> a += v
                "lumMod", "lumOff", "satMod", "satOff", "hueMod", "hueOff", "lum", "sat", "hue" -> {
                    val hsl = rgbToHsl(r, g, b)
                    when (m.localName) {
                        "lumMod" -> hsl[2] *= v
                        "lumOff" -> hsl[2] += v
                        "lum" -> hsl[2] = v
                        "satMod" -> hsl[1] *= v
                        "satOff" -> hsl[1] += v
                        "sat" -> hsl[1] = v
                        "hueMod" -> hsl[0] = (hsl[0] * v) % 1.0
                        "hueOff" -> hsl[0] = (hsl[0] + (m.attr("val")?.toDoubleOrNull() ?: 0.0) / 60000.0 / 360.0) % 1.0
                        "hue" -> hsl[0] = (m.attr("val")?.toDoubleOrNull() ?: 0.0) / 60000.0 / 360.0
                    }
                    val rgb = hslToRgb(hsl[0], clamp(hsl[1]), clamp(hsl[2]))
                    r = rgb[0]; g = rgb[1]; b = rgb[2]
                }
                "tint" -> { r = 1 - (1 - r) * v; g = 1 - (1 - g) * v; b = 1 - (1 - b) * v }
                "shade" -> { r *= v; g *= v; b *= v }
                "inv" -> { r = 1 - r; g = 1 - g; b = 1 - b }
                "gray" -> { val y = 0.299 * r + 0.587 * g + 0.114 * b; r = y; g = y; b = y }
                "comp" -> {
                    val hsl = rgbToHsl(r, g, b)
                    val rgb = hslToRgb((hsl[0] + 0.5) % 1.0, hsl[1], hsl[2])
                    r = rgb[0]; g = rgb[1]; b = rgb[2]
                }
                "red" -> r = v
                "green" -> g = v
                "blue" -> b = v
                "redMod" -> r *= v
                "greenMod" -> g *= v
                "blueMod" -> b *= v
                "redOff" -> r += v
                "greenOff" -> g += v
                "blueOff" -> b += v
            }
        }
        return argb(clamp(a), clamp(r), clamp(g), clamp(b))
    }

    fun argb(a: Double, r: Double, g: Double, b: Double): Int =
        ((a * 255).roundToInt() shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()

    private fun clamp(v: Double) = min(1.0, max(0.0, v))

    private fun linearToSrgb(c: Double): Double {
        val x = clamp(c)
        return if (x <= 0.0031308) 12.92 * x else 1.055 * Math.pow(x, 1 / 2.4) - 0.055
    }

    private fun rgbToHsl(r: Double, g: Double, b: Double): DoubleArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
            g -> ((b - r) / d + 2) / 6
            else -> ((r - g) / d + 4) / 6
        }
        return doubleArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): DoubleArray {
        if (s == 0.0) return doubleArrayOf(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return doubleArrayOf(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
    }

    private fun hslToArgb(h: Double, s: Double, l: Double, a: Double): Int {
        val rgb = hslToRgb(h, s, l)
        return argb(a, rgb[0], rgb[1], rgb[2])
    }
}
