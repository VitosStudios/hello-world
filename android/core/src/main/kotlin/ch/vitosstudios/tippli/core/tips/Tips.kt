package ch.vitosstudios.tippli.core.tips

import ch.vitosstudios.tippli.core.ai.AiClient
import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.ai.AiTask
import ch.vitosstudios.tippli.core.data.Language
import ch.vitosstudios.tippli.core.data.TippliJson
import ch.vitosstudios.tippli.core.data.TranslateTarget
import ch.vitosstudios.tippli.core.prompts.HistoryEntry
import ch.vitosstudios.tippli.core.prompts.PromptBuilder
import ch.vitosstudios.tippli.core.prompts.TipsOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

data class Rewrite(val id: String, val label: String, val text: String)
data class Explanation(val from: String, val to: String, val why: String)
data class Emotion(val level: Int = 0, val label: String = "", val reason: String = "", val calmer: String = "")

/** Parsed tips response (SPEC §7.2). */
data class TipsResult(
    val rewrites: List<Rewrite> = emptyList(),
    val explanations: List<Explanation> = emptyList(),
    val tips: List<String> = emptyList(),
    val emotion: Emotion = Emotion(),
)

/** Parsed mood response (SPEC §5.7). */
data class MoodResult(val level: Int = 0, val reason: String = "")

/** JSON parsing of tips and mood answers (SPEC §6.5). Missing fields default to empty values. */
object TipsParser {

    /** Substring from the first `{` to the last `}` after removing code fences, parsed. */
    fun extractJson(text: String): JsonObject {
        val withoutFences = text.replace(Regex("```[A-Za-z]*"), "")
        val start = withoutFences.indexOf('{')
        val end = withoutFences.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException(AiErrorKind.BAD_RESPONSE, text.take(200))
        return try {
            TippliJson.parseToJsonElement(withoutFences.substring(start, end + 1)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw AiException(AiErrorKind.BAD_RESPONSE, text.take(200))
    }

    fun parseTips(text: String): TipsResult {
        val root = extractJson(text)
        val rewrites = root.array("rewrites").mapNotNull { (it as? JsonObject) }.map {
            Rewrite(id = it.string("id"), label = it.string("label"), text = it.string("text"))
        }.filter { it.text.isNotBlank() }
        val explanations = root.array("explanations").mapNotNull { (it as? JsonObject) }.map {
            Explanation(from = it.string("from"), to = it.string("to"), why = it.string("why"))
        }.filter { it.from.isNotBlank() || it.to.isNotBlank() || it.why.isNotBlank() }
        val tips = root.array("tips").mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.string("text").ifBlank { element.string("tip") }
                else -> null
            }
        }.filter { it.isNotBlank() }
        val emotionObj = root["emotion"] as? JsonObject
        val emotion = if (emotionObj == null) Emotion() else Emotion(
            level = emotionObj.level(),
            label = emotionObj.string("label"),
            reason = emotionObj.string("reason"),
            calmer = emotionObj.string("calmer"),
        )
        return TipsResult(rewrites, explanations, tips, emotion)
    }

    fun parseMood(text: String): MoodResult {
        val root = extractJson(text)
        return MoodResult(level = root.level(), reason = root.string("reason"))
    }

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.level(): Int {
        val p = this["level"] as? JsonPrimitive ?: return 0
        val value = p.doubleOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull() ?: return 0
        return value.toInt().coerceIn(0, 3)
    }
}

/** Runs tips and translation requests (SPEC §7.2). */
class TipsService(private val prompts: PromptBuilder) {

    suspend fun tips(
        client: AiClient,
        language: Language,
        options: TipsOptions,
        text: String,
        history: List<HistoryEntry>,
    ): TipsResult {
        val prompt = prompts.tips(language, options, text, history)
        return TipsParser.parseTips(client.complete(AiTask.TIPS, prompt.system, prompt.user))
    }

    suspend fun translate(client: AiClient, target: TranslateTarget, text: String): String {
        val prompt = prompts.translate(target, text)
        val output = cleanFreeText(client.complete(AiTask.TRANSLATE, prompt.system, prompt.user))
        if (output.isBlank()) throw AiException(AiErrorKind.BAD_RESPONSE)
        return output
    }

    companion object {
        /** Removes surrounding code fences and whitespace from a free-text answer. */
        fun cleanFreeText(output: String): String {
            var t = output.trim()
            if (t.startsWith("```")) {
                val nl = t.indexOf('\n')
                t = if (nl >= 0) t.substring(nl + 1) else t.substring(3)
                if (t.endsWith("```")) t = t.substring(0, t.length - 3)
                t = t.trim()
            }
            return t
        }
    }
}
