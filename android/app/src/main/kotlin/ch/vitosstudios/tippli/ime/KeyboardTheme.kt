package ch.vitosstudios.tippli.ime

import android.content.Context
import android.content.res.Configuration
import ch.vitosstudios.tippli.core.data.Metrics
import ch.vitosstudios.tippli.core.data.SharedData
import ch.vitosstudios.tippli.core.data.ThemePalette

/** Colours and metrics of theme.json for the current light/dark choice (SPEC §7.4). */
class KeyboardTheme(val palette: ThemePalette, val metrics: Metrics, val dark: Boolean) {

    companion object {
        fun resolve(context: Context, shared: SharedData, themeSetting: String): KeyboardTheme {
            val dark = when (themeSetting) {
                "dark" -> true
                "light" -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            val raw = if (dark) shared.theme.dark else shared.theme.light
            return KeyboardTheme(ThemePalette.from(raw), shared.theme.metrics, dark)
        }

        /** Mixes [color] towards [other] by [amount] (0..1), keeping the alpha of [color]. */
        fun blend(color: Int, other: Int, amount: Float): Int {
            fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
            fun mix(a: Int, b: Int) = (a + (b - a) * amount).toInt().coerceIn(0, 255)
            return (ch(color, 24) shl 24) or
                (mix(ch(color, 16), ch(other, 16)) shl 16) or
                (mix(ch(color, 8), ch(other, 8)) shl 8) or
                mix(ch(color, 0), ch(other, 0))
        }

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }
}
