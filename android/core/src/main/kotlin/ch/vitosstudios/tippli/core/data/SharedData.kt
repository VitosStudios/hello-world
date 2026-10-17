package ch.vitosstudios.tippli.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Reads a shared resource by its path relative to the `shared/` directory, e.g. `layouts/layouts.json`. */
fun interface ResourceLoader {
    fun readText(path: String): String
}

/** Loads shared resources from a directory on the file system (tests, desktop tools). */
class FileResourceLoader(private val baseDir: File) : ResourceLoader {
    override fun readText(path: String): String = File(baseDir, path).readText(Charsets.UTF_8)
}

/** The JSON configuration used to parse every shared file. */
val TippliJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** The raw prompt templates from `shared/prompts`. */
class PromptTemplates(
    val correctionSystem: String,
    val correctionUser: String,
    val tipsSystem: String,
    val tipsUser: String,
    val moodSystem: String,
    val translateSystem: String,
    val parts: PromptParts,
)

/**
 * All shared data of Tippli (SPEC §2). Every file is parsed lazily on first use,
 * so the keyboard only pays for what it needs (e.g. emoji are loaded when the
 * emoji panel is opened for the first time).
 */
class SharedData(private val loader: ResourceLoader) {

    val layouts: LayoutsFile by lazy { parse(LAYOUTS) }
    val accents: Map<String, List<String>> by lazy { parse<AccentsFile>(ACCENTS).accents }
    val languagesFile: LanguagesFile by lazy { parse(LANGUAGES) }
    val providers: List<ProviderPreset> by lazy { parse<ProvidersFile>(PROVIDERS).providers }
    val emoji: List<EmojiCategory> by lazy { parse<EmojiFile>(EMOJI).categories }
    val theme: ThemeFile by lazy { parse(THEME) }
    val settingsDefaults: SettingsDefaults by lazy {
        SettingsDefaults.parse(loader.readText(SETTINGS_DEFAULTS))
    }
    val strings: Strings by lazy { Strings.parse(loader.readText(STRINGS)) }
    val prompts: PromptTemplates by lazy {
        PromptTemplates(
            correctionSystem = loader.readText("prompts/correction-system.txt"),
            correctionUser = loader.readText("prompts/correction-user.txt"),
            tipsSystem = loader.readText("prompts/tips-system.txt"),
            tipsUser = loader.readText("prompts/tips-user.txt"),
            moodSystem = loader.readText("prompts/mood-system.txt"),
            translateSystem = loader.readText("prompts/translate-system.txt"),
            parts = parse(PROMPT_PARTS),
        )
    }

    val languages: List<Language> get() = languagesFile.languages
    val translateTargets: List<TranslateTarget> get() = languagesFile.translateTargets

    fun language(id: String): Language? = languages.firstOrNull { it.id == id }
    fun provider(id: String): ProviderPreset? = providers.firstOrNull { it.id == id }
    fun keyboard(id: String): KeyboardDefinition? = layouts.keyboards[id]

    private inline fun <reified T> parse(path: String): T =
        TippliJson.decodeFromString(loader.readText(path))

    companion object {
        const val LAYOUTS = "layouts/layouts.json"
        const val ACCENTS = "layouts/accents.json"
        const val LANGUAGES = "languages.json"
        const val PROVIDERS = "providers.json"
        const val EMOJI = "emoji.json"
        const val THEME = "theme.json"
        const val SETTINGS_DEFAULTS = "settings-defaults.json"
        const val STRINGS = "strings-de.json"
        const val PROMPT_PARTS = "prompts/prompt-parts.json"

        /** Every file an app bundle has to contain (relative to `shared/`). */
        val BUNDLED_FILES = listOf(
            LAYOUTS, ACCENTS, LANGUAGES, PROVIDERS, EMOJI, THEME, SETTINGS_DEFAULTS, STRINGS, PROMPT_PARTS,
            "prompts/correction-system.txt", "prompts/correction-user.txt", "prompts/tips-system.txt",
            "prompts/tips-user.txt", "prompts/mood-system.txt", "prompts/translate-system.txt",
        )
    }
}

/** `settings-defaults.json`: the default value of every setting plus the allowed values. */
class SettingsDefaults(val settings: JsonObject, val allowedValues: JsonObject) {

    fun string(key: String): String? = (settings[key] as? JsonPrimitive)?.contentOrNull

    fun boolean(key: String): Boolean? = (settings[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    fun int(key: String): Int? = (settings[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

    fun stringList(key: String): List<String>? =
        (settings[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /** Allowed values of an enum-like setting, e.g. `trigger` → [live, pause, …]. */
    fun allowed(key: String): List<String> =
        (allowedValues[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** Allowed numeric range of a setting, e.g. `pauseMs` → 300..2000. */
    fun range(key: String): IntRange? {
        val obj = allowedValues[key] as? JsonObject ?: return null
        val min = (obj["min"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return null
        val max = (obj["max"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return null
        return min..max
    }

    companion object {
        fun parse(text: String): SettingsDefaults {
            val root = TippliJson.parseToJsonElement(text) as JsonObject
            return SettingsDefaults(
                settings = root["settings"] as? JsonObject ?: JsonObject(emptyMap()),
                allowedValues = root["allowedValues"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }
}

/**
 * UI copy from `strings-de.json`, addressed by dotted paths such as
 * `"toolbar.aiOn"`. Missing keys return the key itself so a typo is visible
 * but never crashes the keyboard.
 */
class Strings(private val root: JsonObject) {

    operator fun get(path: String): String =
        (find(path) as? JsonPrimitive)?.contentOrNull ?: path

    fun list(path: String): List<String> =
        (find(path) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    fun has(path: String): Boolean = find(path) != null

    private fun find(path: String): JsonElement? {
        var node: JsonElement = root
        for (part in path.split('.')) {
            node = (node as? JsonObject)?.get(part) ?: return null
        }
        return node
    }

    companion object {
        fun parse(text: String): Strings = Strings(TippliJson.parseToJsonElement(text) as JsonObject)
    }
}
