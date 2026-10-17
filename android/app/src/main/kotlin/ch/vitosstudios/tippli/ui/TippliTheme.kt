package ch.vitosstudios.tippli.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ch.vitosstudios.tippli.TippliApp
import ch.vitosstudios.tippli.core.data.ThemePalette

/** Material 3 theme from theme.json (accent colour, panels); dynamic colour is off on purpose. */
@Composable
fun TippliTheme(themeSetting: String, content: @Composable () -> Unit) {
    val dark = when (themeSetting) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val shared = (context.applicationContext as TippliApp).shared
    val palette = remember(dark) { ThemePalette.from(if (dark) shared.theme.dark else shared.theme.light) }
    val accent = Color(palette.accent)
    val onAccent = Color(palette.accentText)
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            tertiary = Color(palette.success),
            background = Color(palette.panel),
            onBackground = Color(palette.keyText),
            surface = Color(palette.panel),
            onSurface = Color(palette.keyText),
            surfaceVariant = Color(palette.card),
            onSurfaceVariant = Color(palette.secondaryText),
            surfaceContainer = Color(palette.card),
            surfaceContainerHigh = Color(palette.card),
            surfaceContainerHighest = Color(palette.specialKey),
            outlineVariant = Color(palette.divider),
            error = Color(palette.danger),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            tertiary = Color(palette.success),
            background = Color(palette.panel),
            onBackground = Color(palette.keyText),
            surface = Color(palette.panel),
            onSurface = Color(palette.keyText),
            surfaceVariant = Color(palette.background),
            onSurfaceVariant = Color(palette.secondaryText),
            surfaceContainer = Color(palette.card),
            surfaceContainerHigh = Color(palette.card),
            surfaceContainerHighest = Color(palette.background),
            outlineVariant = Color(palette.divider),
            error = Color(palette.danger),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
