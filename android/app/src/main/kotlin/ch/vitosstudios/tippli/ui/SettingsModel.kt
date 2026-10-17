package ch.vitosstudios.tippli.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.vitosstudios.tippli.TippliApp
import ch.vitosstudios.tippli.core.data.SharedData
import ch.vitosstudios.tippli.core.data.Strings
import ch.vitosstudios.tippli.core.settings.SettingKeys
import ch.vitosstudios.tippli.core.settings.TippliSettings

/**
 * UI state of the companion app. Every change is written to the shared
 * settings immediately, so the keyboard picks it up the next time it is shown.
 */
class SettingsModel(val app: TippliApp) {

    val shared: SharedData get() = app.shared
    val strings: Strings get() = app.strings
    private val store get() = app.settingsStore
    private val appPrefs = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    var settings: TippliSettings by mutableStateOf(app.readSettings())
        private set

    var imeEnabled by mutableStateOf(false)
        private set
    var imeSelected by mutableStateOf(false)
        private set

    var onboardingDone by mutableStateOf(appPrefs.getBoolean(KEY_ONBOARDING_DONE, false))
        private set

    /** Bumped when an API key changes (so key fields reload). */
    var keyVersion by mutableIntStateOf(0)
        private set

    var onThemeChanged: (() -> Unit)? = null

    fun refresh() {
        settings = app.readSettings()
        refreshImeState()
    }

    fun refreshImeState() {
        val imm = app.getSystemService(InputMethodManager::class.java)
        imeEnabled = imm?.enabledInputMethodList?.any { it.packageName == app.packageName } == true
        val current = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        imeSelected = current != null && ComponentName.unflattenFromString(current)?.packageName == app.packageName
    }

    fun finishOnboarding() {
        appPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        onboardingDone = true
    }

    fun restartOnboarding() {
        appPrefs.edit().putBoolean(KEY_ONBOARDING_DONE, false).apply()
        onboardingDone = false
    }

    // ------------------------------------------------------------------ writing settings

    private fun reload() {
        val oldTheme = settings.theme
        settings = app.readSettings()
        if (settings.theme != oldTheme) onThemeChanged?.invoke()
    }

    fun setString(key: String, value: String) {
        store.putString(key, value)
        reload()
    }

    fun setBoolean(key: String, value: Boolean) {
        store.putBoolean(key, value)
        reload()
    }

    fun setInt(key: String, value: Int) {
        store.putInt(key, value)
        reload()
    }

    fun setList(key: String, value: List<String>) {
        store.putStringList(key, value)
        reload()
    }

    fun remove(key: String) {
        store.remove(key)
        reload()
    }

    /** Enables/disables a typing language; at least one stays enabled (SPEC §5.9). */
    fun setLanguageEnabled(id: String, enabled: Boolean) {
        val current = settings.languages
        val next = if (enabled) (current + id).distinct() else current - id
        if (next.isEmpty()) return
        store.putStringList(SettingKeys.LANGUAGES, next)
        if (settings.activeLanguage !in next) store.putString(SettingKeys.ACTIVE_LANGUAGE, next.first())
        reload()
    }

    fun moveLanguage(id: String, delta: Int) {
        val list = settings.languages.toMutableList()
        val index = list.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        list.removeAt(index)
        list.add(target, id)
        setList(SettingKeys.LANGUAGES, list)
    }

    fun apiKey(providerId: String): String = app.secrets.apiKey(providerId).orEmpty()

    fun setApiKey(providerId: String, value: String) {
        app.secrets.setApiKey(providerId, value)
        keyVersion++
    }

    private companion object {
        const val APP_PREFS = "tippli_app"
        const val KEY_ONBOARDING_DONE = "onboardingDone"
    }
}
