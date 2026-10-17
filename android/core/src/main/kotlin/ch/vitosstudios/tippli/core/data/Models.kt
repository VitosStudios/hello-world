package ch.vitosstudios.tippli.core.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// layouts/layouts.json
// ---------------------------------------------------------------------------

/** A key in a layout row: either a plain string (width 1) or `{"k": key, "w": width}`. */
@Serializable(with = KeySpecSerializer::class)
data class KeySpec(val key: String, val width: Double = 1.0) {
    val isSpecial: Boolean get() = key.startsWith("$")
}

object KeySpecSerializer : KSerializer<KeySpec> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("KeySpec")

    override fun deserialize(decoder: Decoder): KeySpec {
        val input = decoder as? JsonDecoder ?: throw SerializationException("KeySpec needs JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> KeySpec(element.content)
            is JsonObject -> KeySpec(
                key = element["k"]?.jsonPrimitive?.content
                    ?: throw SerializationException("key object without 'k'"),
                width = element["w"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
            )
            else -> throw SerializationException("Unexpected key element: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: KeySpec) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("KeySpec needs JSON")
        if (value.width == 1.0) {
            output.encodeJsonElement(JsonPrimitive(value.key))
        } else {
            output.encodeJsonElement(buildJsonObject {
                put("k", value.key)
                put("w", value.width)
            })
        }
    }
}

@Serializable
data class RowSpec(val indent: Double = 0.0, val keys: List<KeySpec> = emptyList())

@Serializable
data class KeyboardSpec(val width: Double = 10.0, val rows: List<RowSpec> = emptyList())

@Serializable
data class KeyboardDefinition(
    val name: String = "",
    val normal: KeyboardSpec,
    val hex: KeyboardSpec,
    val bottomPunctuation: Map<String, String> = emptyMap(),
)

@Serializable
data class LayoutsFile(
    val version: Int = 1,
    val bottomRows: Map<String, List<KeySpec>> = emptyMap(),
    val numberRow: List<KeySpec> = emptyList(),
    val keyboards: Map<String, KeyboardDefinition> = emptyMap(),
    val symbolPages: Map<String, KeyboardSpec> = emptyMap(),
)

// ---------------------------------------------------------------------------
// layouts/accents.json
// ---------------------------------------------------------------------------

@Serializable
data class AccentsFile(val version: Int = 1, val accents: Map<String, List<String>> = emptyMap())

// ---------------------------------------------------------------------------
// languages.json
// ---------------------------------------------------------------------------

@Serializable
data class Language(
    val id: String,
    val name: String = id,
    val short: String = id,
    val keyboard: String = "qwertz",
    val promptName: String = name,
    val rules: String = "",
    val pinyin: Boolean = false,
)

@Serializable
data class TranslateTarget(val id: String, val name: String = id, val promptName: String = name)

@Serializable
data class LanguagesFile(
    val version: Int = 1,
    val languages: List<Language> = emptyList(),
    val translateTargets: List<TranslateTarget> = emptyList(),
)

// ---------------------------------------------------------------------------
// providers.json
// ---------------------------------------------------------------------------

@Serializable
data class ProviderModel(val id: String, val label: String = id)

@Serializable
data class ProviderPreset(
    val id: String,
    val name: String = id,
    val protocol: String = PROTOCOL_OPENAI,
    val baseUrl: String = "",
    val defaultModel: String = "",
    val models: List<ProviderModel> = emptyList(),
    val keyUrl: String = "",
    val keyHint: String = "",
    val keyRequired: Boolean = true,
    val jsonMode: Boolean = false,
    val maxTokensField: String = "max_tokens",
    val sendTemperature: Boolean = false,
    val reasoningEffort: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val baseUrlEditable: Boolean = false,
) {
    /** Short name for the toolbar, e.g. "Claude (Anthropic)" → "Claude". */
    val shortName: String
        get() = name.substringBefore(" (").substringBefore(" /").trim().ifEmpty { name }

    companion object {
        const val PROTOCOL_ANTHROPIC = "anthropic"
        const val PROTOCOL_OPENAI = "openai"
    }
}

@Serializable
data class ProvidersFile(val version: Int = 1, val providers: List<ProviderPreset> = emptyList())

// ---------------------------------------------------------------------------
// emoji.json
// ---------------------------------------------------------------------------

@Serializable
data class EmojiCategory(
    val id: String,
    val name: String = id,
    val icon: String = "",
    val emoji: List<String> = emptyList(),
)

@Serializable
data class EmojiFile(val version: Int = 1, val categories: List<EmojiCategory> = emptyList())

// ---------------------------------------------------------------------------
// theme.json
// ---------------------------------------------------------------------------

@Serializable
data class Brand(val name: String = "Tippli", val tagline: String = "")

@Serializable
data class Metrics(
    val toolbarHeight: Float = 40f,
    val rowHeight: Map<String, Float> = mapOf("small" to 46f, "medium" to 52f, "large" to 58f),
    val keyGap: Float = 5f,
    val hexGap: Float = 3f,
    val keyCornerRadius: Float = 7f,
    val hexCornerRadius: Float = 5f,
    val keyLabelSize: Float = 22f,
    val specialLabelSize: Float = 15f,
    val sidePadding: Float = 3f,
    val bottomPadding: Float = 4f,
) {
    fun rowHeightFor(keyHeight: String): Float = rowHeight[keyHeight] ?: rowHeight["medium"] ?: 52f
}

@Serializable
data class ThemeFile(
    val version: Int = 1,
    val brand: Brand = Brand(),
    val light: Map<String, String> = emptyMap(),
    val dark: Map<String, String> = emptyMap(),
    val metrics: Metrics = Metrics(),
)

// ---------------------------------------------------------------------------
// prompts/prompt-parts.json
// ---------------------------------------------------------------------------

@Serializable
data class RewriteVariant(val id: String, val label: String = id, val instruction: String = "")

@Serializable
data class PromptParts(
    val version: Int = 1,
    val uiLanguage: String = "German",
    val strengthRules: Map<String, String> = emptyMap(),
    val rewriteVariants: List<RewriteVariant> = emptyList(),
    val sections: Map<String, String> = emptyMap(),
    val testSegment: String = "",
)
