package ch.vitosstudios.tippli.core.settings

import ch.vitosstudios.tippli.core.data.Language
import ch.vitosstudios.tippli.core.data.ProviderPreset
import ch.vitosstudios.tippli.core.data.SettingsDefaults
import ch.vitosstudios.tippli.core.data.SharedData

/** Setting keys – identical on every platform (SPEC §8, settings-defaults.json). */
object SettingKeys {
    const val PROVIDER = "provider"
    const val CUSTOM_BASE_URL = "customBaseUrl"
    const val AI_ENABLED = "aiEnabled"
    const val TRIGGER = "trigger"
    const val PAUSE_MS = "pauseMs"
    const val STRENGTH = "strength"
    const val WAIT_ON_SEND_MS = "waitOnSendMs"
    const val MOOD_WARNING = "moodWarning"
    const val LANGUAGES = "languages"
    const val ACTIVE_LANGUAGE = "activeLanguage"
    const val KEYBOARD_MODE = "keyboardMode"
    const val LONG_PRESS_ACCENTS = "longPressAccents"
    const val LONG_PRESS_DIGITS = "longPressDigits"
    const val LONG_PRESS_MS = "longPressMs"
    const val KEY_HEIGHT = "keyHeight"
    const val HAPTICS = "haptics"
    const val SOUND = "sound"
    const val AUTO_CAPITALIZE = "autoCapitalize"
    const val DOUBLE_SPACE_PERIOD = "doubleSpacePeriod"
    const val NUMBER_ROW = "numberRow"
    const val THEME = "theme"
    const val TIPS_REWRITES = "tipsRewrites"
    const val TIPS_EXPLANATIONS = "tipsExplanations"
    const val TIPS_WRITING = "tipsWriting"
    const val TIPS_TRANSLATE = "tipsTranslate"
    const val TIPS_EMOTION = "tipsEmotion"
    const val REWRITE_VARIANTS = "rewriteVariants"
    const val TRANSLATE_TARGETS = "translateTargets"
    const val INCOGNITO_NO_AI = "incognitoNoAI"
    const val EMOJI_RECENTS = "emojiRecents"

    fun model(providerId: String) = "model.$providerId"
    fun keyboard(languageId: String) = "keyboard.$languageId"
    fun apiKey(providerId: String) = "apiKey.$providerId"
}

/**
 * Minimal key/value storage the settings are read from and written to.
 * Android implements it with SharedPreferences, tests with a map.
 * Getters return null when the key has never been written.
 */
interface SettingsStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    fun getInt(key: String): Int?
    fun getStringList(key: String): List<String>?

    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putStringList(key: String, value: List<String>)
    fun remove(key: String)
}

/** In-memory store, used by tests and as a fallback. */
class MapSettingsStore(initial: Map<String, Any> = emptyMap()) : SettingsStore {
    private val map = HashMap<String, Any>(initial)
    override fun getString(key: String) = map[key] as? String
    override fun getBoolean(key: String) = map[key] as? Boolean
    override fun getInt(key: String) = (map[key] as? Number)?.toInt()
    @Suppress("UNCHECKED_CAST")
    override fun getStringList(key: String) = (map[key] as? List<String>)
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putStringList(key: String, value: List<String>) { map[key] = value.toList() }
    override fun remove(key: String) { map.remove(key) }
}

enum class Trigger(val id: String) {
    LIVE("live"), PAUSE("pause"), WORD("word"), SENTENCE("sentence"), MANUAL("manual");

    companion object {
        fun from(id: String?): Trigger? = entries.firstOrNull { it.id == id }
    }
}

enum class KeyboardMode(val id: String) {
    HEX("hex"), NORMAL("normal");

    fun toggled(): KeyboardMode = if (this == HEX) NORMAL else HEX

    companion object {
        fun from(id: String?): KeyboardMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * An immutable snapshot of all settings. Created with [SettingsReader.read];
 * values that are missing or invalid in the store fall back to
 * settings-defaults.json.
 */
data class TippliSettings(
    val provider: String,
    val customBaseUrl: String,
    val aiEnabled: Boolean,
    val trigger: Trigger,
    val pauseMs: Int,
    val strength: String,
    val waitOnSendMs: Int,
    val moodWarning: Boolean,
    val languages: List<String>,
    val activeLanguage: String,
    val keyboardMode: KeyboardMode,
    val longPressAccents: Boolean,
    val longPressDigits: Boolean,
    val longPressMs: Int,
    val keyHeight: String,
    val haptics: Boolean,
    val sound: Boolean,
    val autoCapitalize: Boolean,
    val doubleSpacePeriod: Boolean,
    val numberRow: Boolean,
    val theme: String,
    val tipsRewrites: Boolean,
    val tipsExplanations: Boolean,
    val tipsWriting: Boolean,
    val tipsTranslate: Boolean,
    val tipsEmotion: Boolean,
    val rewriteVariants: List<String>,
    val translateTargets: List<String>,
    val incognitoNoAI: Boolean,
    val emojiRecents: List<String>,
    /** `model.<providerId>` overrides. */
    val models: Map<String, String> = emptyMap(),
    /** `keyboard.<languageId>` overrides. */
    val keyboards: Map<String, String> = emptyMap(),
) {
    fun modelFor(preset: ProviderPreset): String =
        models[preset.id]?.takeIf { it.isNotBlank() } ?: preset.defaultModel

    /** Layout id for a language: the user override or the language default (SPEC §5.9). */
    fun keyboardFor(language: Language): String =
        keyboards[language.id]?.takeIf { it.isNotBlank() } ?: language.keyboard

    fun baseUrlFor(preset: ProviderPreset): String =
        if (preset.baseUrlEditable) customBaseUrl.ifBlank { preset.baseUrl } else preset.baseUrl
}

/** Reads and validates settings from a [SettingsStore] with defaults from shared data. */
class SettingsReader(private val shared: SharedData) {

    private val defaults: SettingsDefaults get() = shared.settingsDefaults

    fun read(store: SettingsStore): TippliSettings {
        val d = defaults
        fun str(key: String, fallback: String) = store.getString(key) ?: d.string(key) ?: fallback
        fun bool(key: String, fallback: Boolean) = store.getBoolean(key) ?: d.boolean(key) ?: fallback
        fun list(key: String) = store.getStringList(key) ?: d.stringList(key) ?: emptyList()
        fun enumValue(key: String, fallback: String): String {
            val allowed = d.allowed(key)
            val value = store.getString(key)
            return if (value != null && (allowed.isEmpty() || value in allowed)) value else d.string(key) ?: fallback
        }
        fun int(key: String, fallback: Int): Int {
            val value = store.getInt(key) ?: d.int(key) ?: fallback
            val range = d.range(key) ?: return value
            return value.coerceIn(range)
        }

        val knownLanguages = shared.languages.map { it.id }
        val languages = list(SettingKeys.LANGUAGES).filter { it in knownLanguages }.distinct()
            .ifEmpty { d.stringList(SettingKeys.LANGUAGES)?.filter { it in knownLanguages } ?: emptyList() }
            .ifEmpty { listOfNotNull(knownLanguages.firstOrNull()) }
        val active = str(SettingKeys.ACTIVE_LANGUAGE, languages.first()).takeIf { it in languages } ?: languages.first()

        val providerIds = shared.providers.map { it.id }
        val provider = str(SettingKeys.PROVIDER, providerIds.firstOrNull() ?: "anthropic")
            .takeIf { it in providerIds } ?: d.string(SettingKeys.PROVIDER) ?: providerIds.first()

        val models = shared.providers.mapNotNull { p ->
            store.getString(SettingKeys.model(p.id))?.takeIf { it.isNotBlank() }?.let { p.id to it }
        }.toMap()
        val keyboards = shared.languages.mapNotNull { l ->
            store.getString(SettingKeys.keyboard(l.id))
                ?.takeIf { it.isNotBlank() && shared.layouts.keyboards.containsKey(it) }
                ?.let { l.id to it }
        }.toMap()

        return TippliSettings(
            provider = provider,
            customBaseUrl = str(SettingKeys.CUSTOM_BASE_URL, ""),
            aiEnabled = bool(SettingKeys.AI_ENABLED, true),
            trigger = Trigger.from(enumValue(SettingKeys.TRIGGER, "live")) ?: Trigger.LIVE,
            pauseMs = int(SettingKeys.PAUSE_MS, 700),
            strength = enumValue(SettingKeys.STRENGTH, "normal"),
            waitOnSendMs = int(SettingKeys.WAIT_ON_SEND_MS, 1500),
            moodWarning = bool(SettingKeys.MOOD_WARNING, true),
            languages = languages,
            activeLanguage = active,
            keyboardMode = KeyboardMode.from(enumValue(SettingKeys.KEYBOARD_MODE, "hex")) ?: KeyboardMode.HEX,
            longPressAccents = bool(SettingKeys.LONG_PRESS_ACCENTS, true),
            longPressDigits = bool(SettingKeys.LONG_PRESS_DIGITS, true),
            longPressMs = int(SettingKeys.LONG_PRESS_MS, 350),
            keyHeight = enumValue(SettingKeys.KEY_HEIGHT, "medium"),
            haptics = bool(SettingKeys.HAPTICS, true),
            sound = bool(SettingKeys.SOUND, false),
            autoCapitalize = bool(SettingKeys.AUTO_CAPITALIZE, true),
            doubleSpacePeriod = bool(SettingKeys.DOUBLE_SPACE_PERIOD, true),
            numberRow = bool(SettingKeys.NUMBER_ROW, false),
            theme = enumValue(SettingKeys.THEME, "system"),
            tipsRewrites = bool(SettingKeys.TIPS_REWRITES, true),
            tipsExplanations = bool(SettingKeys.TIPS_EXPLANATIONS, true),
            tipsWriting = bool(SettingKeys.TIPS_WRITING, true),
            tipsTranslate = bool(SettingKeys.TIPS_TRANSLATE, true),
            tipsEmotion = bool(SettingKeys.TIPS_EMOTION, true),
            rewriteVariants = list(SettingKeys.REWRITE_VARIANTS),
            translateTargets = list(SettingKeys.TRANSLATE_TARGETS),
            incognitoNoAI = bool(SettingKeys.INCOGNITO_NO_AI, true),
            emojiRecents = list(SettingKeys.EMOJI_RECENTS),
            models = models,
            keyboards = keyboards,
        )
    }
}
