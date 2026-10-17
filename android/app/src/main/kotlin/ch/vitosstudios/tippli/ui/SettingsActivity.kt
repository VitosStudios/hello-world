package ch.vitosstudios.tippli.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.vitosstudios.tippli.TippliApp

/** Companion app: onboarding and settings (SPEC §7.3), Jetpack Compose + Material 3. */
class SettingsActivity : ComponentActivity() {

    private lateinit var model: SettingsModel

    override fun onCreate(savedInstanceState: Bundle?) {
        model = SettingsModel(application as TippliApp)
        model.onThemeChanged = { applySystemBars() }
        applySystemBars()
        super.onCreate(savedInstanceState)
        setContent {
            TippliTheme(model.settings.theme) {
                TippliRoot(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input method picker is a dialog: re-check the selection when it closes.
        if (hasFocus) model.refreshImeState()
    }

    private fun applySystemBars() {
        val theme = model.settings.theme
        val dark: (Resources) -> Boolean = { res ->
            when (theme) {
                "dark" -> true
                "light" -> false
                else -> (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, dark),
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM, dark),
        )
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
