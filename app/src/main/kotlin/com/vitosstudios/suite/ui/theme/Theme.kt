package com.vitosstudios.suite.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightFallback: ColorScheme = lightColorScheme(
    primary = BrandColors.primary,
    onPrimary = BrandColors.onPrimary,
    primaryContainer = BrandColors.primaryContainer,
    onPrimaryContainer = BrandColors.onPrimaryContainer,
    secondary = BrandColors.secondary,
    secondaryContainer = BrandColors.secondaryContainer,
    onSecondaryContainer = BrandColors.onSecondaryContainer,
    tertiary = BrandColors.tertiary,
    tertiaryContainer = BrandColors.tertiaryContainer,
    onTertiaryContainer = BrandColors.onTertiaryContainer,
    error = BrandColors.error,
    background = BrandColors.background,
    surface = BrandColors.surface,
    onSurface = BrandColors.onSurface,
    onBackground = BrandColors.onSurface,
    surfaceVariant = BrandColors.surfaceVariant,
    onSurfaceVariant = BrandColors.onSurfaceVariant,
    outline = BrandColors.outline,
)

private val DarkFallback: ColorScheme = darkColorScheme(
    primary = BrandColors.primaryDark,
    onPrimary = BrandColors.onPrimaryDark,
    primaryContainer = BrandColors.primaryContainerDark,
    onPrimaryContainer = BrandColors.onPrimaryContainerDark,
    secondary = BrandColors.secondaryDark,
    secondaryContainer = BrandColors.secondaryContainerDark,
    onSecondaryContainer = BrandColors.onSecondaryContainerDark,
    tertiary = BrandColors.tertiaryDark,
    tertiaryContainer = BrandColors.tertiaryContainerDark,
    onTertiaryContainer = BrandColors.onTertiaryContainerDark,
    error = BrandColors.errorDark,
    background = BrandColors.backgroundDark,
    surface = BrandColors.surfaceDark,
    onSurface = BrandColors.onSurfaceDark,
    onBackground = BrandColors.onSurfaceDark,
    surfaceVariant = BrandColors.surfaceVariantDark,
    onSurfaceVariant = BrandColors.onSurfaceVariantDark,
    outline = BrandColors.outlineDark,
)

private val SuiteTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Monospaced style for formula bars and cell references. */
val FormulaTextStyle = TextStyle(fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

data class SuiteAccents(
    val documents: AppAccent,
    val sheets: AppAccent,
    val slides: AppAccent,
    val photo: AppAccent,
    val video: AppAccent,
    val pdf: AppAccent,
)

val LocalAccents = staticCompositionLocalOf {
    SuiteAccents(Accents.documents, Accents.sheets, Accents.slides, Accents.photo, Accents.video, Accents.pdf)
}

/**
 * App theme: Material 3 with dynamic colour on Android 12+ and an original fallback palette on
 * older versions.
 */
@Composable
fun SuiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    val accents = if (darkTheme) {
        SuiteAccents(Accents.documentsDark, Accents.sheetsDark, Accents.slidesDark, Accents.photoDark, Accents.videoDark, Accents.pdfDark)
    } else {
        SuiteAccents(Accents.documents, Accents.sheets, Accents.slides, Accents.photo, Accents.video, Accents.pdf)
    }
    CompositionLocalProvider(LocalAccents provides accents) {
        MaterialTheme(colorScheme = scheme, typography = SuiteTypography, content = content)
    }
}
