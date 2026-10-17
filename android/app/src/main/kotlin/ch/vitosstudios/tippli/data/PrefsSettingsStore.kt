package ch.vitosstudios.tippli.data

import android.content.Context
import android.content.SharedPreferences
import ch.vitosstudios.tippli.core.data.TippliJson
import ch.vitosstudios.tippli.core.settings.SettingsStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Settings in SharedPreferences `tippli_settings` with the key names of
 * settings-defaults.json (SPEC §8). Lists are stored as JSON arrays because
 * their order matters (languages, emoji recents).
 */
class PrefsSettingsStore(context: Context) : SettingsStore {

    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val listSerializer = ListSerializer(String.serializer())

    override fun getString(key: String): String? =
        if (prefs.contains(key)) runCatching { prefs.getString(key, null) }.getOrNull() else null

    override fun getBoolean(key: String): Boolean? =
        if (prefs.contains(key)) runCatching { prefs.getBoolean(key, false) }.getOrNull() else null

    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null

    override fun getStringList(key: String): List<String>? {
        val raw = getString(key) ?: return null
        return runCatching { TippliJson.decodeFromString(listSerializer, raw) }.getOrNull()
    }

    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun putStringList(key: String, value: List<String>) =
        prefs.edit().putString(key, TippliJson.encodeToString(listSerializer, value)).apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()

    companion object {
        const val PREFS_NAME = "tippli_settings"
    }
}
