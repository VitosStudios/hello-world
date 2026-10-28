package com.vitosstudios.suite.feature.docs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.vitosstudios.suite.docx.InlineChars
import com.vitosstudios.suite.docx.RunFormat
import com.vitosstudios.suite.docx.TextSpan

/** Maps document formatting to Compose text styles for the reflowed editing view. */
object DocsText {
    /** Points to scaled pixels at zoom 1 (11 pt body text shows at about 14 sp). */
    const val PT_TO_SP = 1.27f

    val paperColor = Color.White
    val autoTextColor = Color(0xFF1B1B1B)
    val linkColor = Color(0xFF0B57A8)

    fun color(hex: String?): Color? {
        if (hex == null || hex.length != 6) return null
        return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }

    /** ST_HighlightColor names (and "#RRGGBB" shading) to colours. */
    fun highlight(name: String?): Color? = when (name) {
        null -> null
        "yellow" -> Color(0xFFFFFF00)
        "green" -> Color(0xFF00FF00)
        "cyan" -> Color(0xFF00FFFF)
        "magenta" -> Color(0xFFFF00FF)
        "blue" -> Color(0xFF0000FF)
        "red" -> Color(0xFFFF0000)
        "darkBlue" -> Color(0xFF000080)
        "darkCyan" -> Color(0xFF008080)
        "darkGreen" -> Color(0xFF008000)
        "darkMagenta" -> Color(0xFF800080)
        "darkRed" -> Color(0xFF800000)
        "darkYellow" -> Color(0xFF808000)
        "darkGray" -> Color(0xFF808080)
        "lightGray" -> Color(0xFFC0C0C0)
        "black" -> Color(0xFF000000)
        "white" -> Color(0xFFFFFFFF)
        else -> if (name.startsWith("#")) color(name.substring(1)) else null
    }

    /** Fonts are not bundled; families map to the closest generic family. */
    fun family(name: String?): FontFamily? {
        val n = name?.lowercase() ?: return null
        return when {
            listOf("courier", "mono", "consolas", "menlo", "code").any { it in n } -> FontFamily.Monospace
            listOf("times", "georgia", "serif", "cambria", "garamond", "book", "palatino", "minion").any { it in n } && "sans" !in n -> FontFamily.Serif
            else -> FontFamily.SansSerif
        }
    }

    fun spanStyle(f: RunFormat, zoom: Float, link: Boolean): SpanStyle {
        val decorations = buildList {
            if (f.underline || link) add(TextDecoration.Underline)
            if (f.strike) add(TextDecoration.LineThrough)
        }
        val size = f.sizePt * PT_TO_SP * zoom * if (f.superscript || f.subscript) 0.7f else 1f
        val base = color(f.color) ?: if (link) linkColor else autoTextColor
        return SpanStyle(
            color = if (f.hidden) base.copy(alpha = 0.4f) else base,
            fontSize = size.sp,
            fontWeight = if (f.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (f.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = family(f.fontFamily),
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            background = highlight(f.highlight) ?: Color.Unspecified,
            baselineShift = when {
                f.superscript -> BaselineShift.Superscript
                f.subscript -> BaselineShift.Subscript
                else -> null
            },
        )
    }

    /**
     * Styled text for [text]. Inline objects are kept as one character each (so offsets match the
     * model); [objectGlyph] replaces them for display inside a text field.
     */
    fun annotate(
        text: String,
        spans: List<TextSpan>,
        zoom: Float,
        matches: List<IntRange> = emptyList(),
        current: IntRange? = null,
        objectGlyph: Char? = null,
    ): AnnotatedString = buildAnnotatedString {
        val shown = if (objectGlyph != null) text.replace(InlineChars.OBJECT, objectGlyph) else text
        append(shown)
        for (s in spans) {
            val a = s.start.coerceIn(0, text.length)
            val b = s.end.coerceIn(a, text.length)
            if (a < b) addStyle(spanStyle(s.format, zoom, s.link != null), a, b)
        }
        for (m in matches) {
            val a = m.first.coerceIn(0, text.length)
            val b = (m.last + 1).coerceIn(a, text.length)
            val bg = if (m == current) Color(0xFFFFB74D) else Color(0x66FFD54F)
            if (a < b) addStyle(SpanStyle(background = bg), a, b)
        }
    }
}

/** Styles the text field content with the paragraph's runs; offsets are unchanged. */
class ParagraphVisualTransformation(
    private val spans: List<TextSpan>,
    private val zoom: Float,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(DocsText.annotate(text.text, spans, zoom, objectGlyph = '▣'), OffsetMapping.Identity)

    override fun equals(other: Any?): Boolean = other is ParagraphVisualTransformation && other.spans == spans && other.zoom == zoom
    override fun hashCode(): Int = spans.hashCode() * 31 + zoom.hashCode()
}
