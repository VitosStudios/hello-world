package ch.vitosstudios.tippli.core.data

/**
 * Resolved colours of one theme variant (`light` or `dark` in theme.json) as
 * ARGB integers, ready for Android's `Paint.setColor` or any other platform.
 */
class ThemePalette(private val colors: Map<String, Int>) {
    val background get() = color("background")
    val toolbar get() = color("toolbar")
    val key get() = color("key")
    val keyPressed get() = color("keyPressed")
    val specialKey get() = color("specialKey")
    val spaceKey get() = color("spaceKey")
    val keyText get() = color("keyText")
    val secondaryText get() = color("secondaryText")
    val accent get() = color("accent")
    val accentText get() = color("accentText")
    val popup get() = color("popup")
    val popupSelected get() = color("popupSelected")
    val keyShadow get() = color("keyShadow")
    val warning get() = color("warning")
    val danger get() = color("danger")
    val success get() = color("success")
    val panel get() = color("panel")
    val card get() = color("card")
    val divider get() = color("divider")

    fun color(name: String): Int = colors[name] ?: FALLBACK

    companion object {
        private const val FALLBACK = 0xFFFF00FF.toInt() // loud magenta: a missing token is easy to spot

        fun from(raw: Map<String, String>): ThemePalette =
            ThemePalette(raw.mapNotNull { (k, v) -> parseHexColor(v)?.let { k to it } }.toMap())
    }
}

/**
 * Parses `#RRGGBB` or `#RRGGBBAA` (alpha last, as in theme.json) into an ARGB int.
 * Returns null for anything else.
 */
fun parseHexColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.any { it.digitToIntOrNull(16) == null }) return null
    return when (hex.length) {
        6 -> (0xFF000000L or hex.toLong(16)).toInt()
        8 -> {
            val rgb = hex.substring(0, 6).toLong(16)
            val alpha = hex.substring(6, 8).toLong(16)
            ((alpha shl 24) or rgb).toInt()
        }
        else -> null
    }
}
