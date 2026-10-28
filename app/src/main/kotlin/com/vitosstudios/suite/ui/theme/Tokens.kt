package com.vitosstudios.suite.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Spacing scale (4 dp grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Side gutter of screens. */
    val gutter = 16.dp
}

/** Sizes that guarantee accessible touch targets. */
object Sizes {
    /** Minimum touch target (WCAG / Material: 48 dp). */
    val touchTarget = 48.dp
    val iconSmall = 18.dp
    val icon = 24.dp
    val thumbnail = 56.dp
    val filmstripThumb = 96.dp
    val sheetCellHeight = 32.dp
    val sheetHeaderWidth = 48.dp
    val contextToolbarHeight = 56.dp
}

object Corners {
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 28.dp
}

/**
 * Accent colours that identify the five editors (badges, file icons). They are this app's own
 * palette; each pair keeps at least 4.5:1 contrast between [container] and [onContainer].
 */
data class AppAccent(val container: Color, val onContainer: Color, val strong: Color)

object Accents {
    val documents = AppAccent(Color(0xFFD6E8F7), Color(0xFF0D3A5C), Color(0xFF2F7FBF))
    val sheets = AppAccent(Color(0xFFD5F0E2), Color(0xFF0B4A2E), Color(0xFF2E9E6A))
    val slides = AppAccent(Color(0xFFFBE4CC), Color(0xFF5C3205), Color(0xFFE0892B))
    val photo = AppAccent(Color(0xFFF2DCEE), Color(0xFF55174B), Color(0xFFB0479E))
    val video = AppAccent(Color(0xFFF9DAD6), Color(0xFF5E1610), Color(0xFFD0503F))
    val pdf = AppAccent(Color(0xFFE3E3EC), Color(0xFF2A2A40), Color(0xFF5B5B8A))

    val documentsDark = AppAccent(Color(0xFF123B5B), Color(0xFFD6E8F7), Color(0xFF8CC3EE))
    val sheetsDark = AppAccent(Color(0xFF0F4630), Color(0xFFD5F0E2), Color(0xFF7FD6A9))
    val slidesDark = AppAccent(Color(0xFF55320C), Color(0xFFFBE4CC), Color(0xFFF2B26E))
    val photoDark = AppAccent(Color(0xFF4F1B47), Color(0xFFF2DCEE), Color(0xFFE39AD8))
    val videoDark = AppAccent(Color(0xFF581B15), Color(0xFFF9DAD6), Color(0xFFF29A8D))
    val pdfDark = AppAccent(Color(0xFF2E2E45), Color(0xFFE3E3EC), Color(0xFFB4B4D9))
}

/** Fallback brand colours when dynamic colour is unavailable (Android 8-11). */
internal object BrandColors {
    val primary = Color(0xFF1F5F8B)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFCDE5FF)
    val onPrimaryContainer = Color(0xFF001D32)
    val secondary = Color(0xFF51606F)
    val secondaryContainer = Color(0xFFD5E4F7)
    val onSecondaryContainer = Color(0xFF0E1D2A)
    val tertiary = Color(0xFF68587A)
    val tertiaryContainer = Color(0xFFEFDBFF)
    val onTertiaryContainer = Color(0xFF231533)
    val error = Color(0xFFBA1A1A)
    val background = Color(0xFFFBFDFF)
    val surface = Color(0xFFFBFDFF)
    val onSurface = Color(0xFF191C1E)
    val surfaceVariant = Color(0xFFDEE3EB)
    val onSurfaceVariant = Color(0xFF42474E)
    val outline = Color(0xFF72777F)

    val primaryDark = Color(0xFF94CCFF)
    val onPrimaryDark = Color(0xFF003352)
    val primaryContainerDark = Color(0xFF004A75)
    val onPrimaryContainerDark = Color(0xFFCDE5FF)
    val secondaryDark = Color(0xFFB9C8DA)
    val secondaryContainerDark = Color(0xFF3A4857)
    val onSecondaryContainerDark = Color(0xFFD5E4F7)
    val tertiaryDark = Color(0xFFD3BFE6)
    val tertiaryContainerDark = Color(0xFF4F4061)
    val onTertiaryContainerDark = Color(0xFFEFDBFF)
    val errorDark = Color(0xFFFFB4AB)
    val backgroundDark = Color(0xFF101418)
    val surfaceDark = Color(0xFF101418)
    val onSurfaceDark = Color(0xFFE1E2E5)
    val surfaceVariantDark = Color(0xFF42474E)
    val onSurfaceVariantDark = Color(0xFFC2C7CF)
    val outlineDark = Color(0xFF8C9199)
}
