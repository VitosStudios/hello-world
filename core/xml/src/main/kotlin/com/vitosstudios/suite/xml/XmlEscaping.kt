package com.vitosstudios.suite.xml

object XmlEscaping {
    fun escapeText(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '\r' }) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\r' -> sb.append("&#13;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun escapeAttribute(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\n' || it == '\r' || it == '\t' }) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                '\t' -> sb.append("&#9;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Expands the predefined entities and character references. Unknown entities stay as written. */
    fun decode(raw: String): String {
        val amp = raw.indexOf('&')
        if (amp < 0) return raw
        val sb = StringBuilder(raw.length)
        sb.append(raw, 0, amp)
        var i = amp
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                sb.append(c); i++; continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c); i++; continue
            }
            val name = raw.substring(i + 1, semi)
            val decoded: String? = when {
                name == "lt" -> "<"
                name == "gt" -> ">"
                name == "amp" -> "&"
                name == "quot" -> "\""
                name == "apos" -> "'"
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (decoded == null) {
                sb.append(c); i++
            } else {
                sb.append(decoded); i = semi + 1
            }
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else null
}
