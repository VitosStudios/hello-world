package ch.vitosstudios.tippli.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** OpenAI-compatible Chat Completions client (protocol `openai`, SPEC §6.3). */
class OpenAiClient(
    override val endpoint: AiEndpoint,
    private val http: OkHttpClient,
    private val plainModes: PlainModeRegistry = PlainModeRegistry.GLOBAL,
) : AiClient {

    private val preset get() = endpoint.preset

    /** `max_tokens` value: at least 4000 for reasoning providers (reasoning tokens count against it). */
    fun maxTokensFor(task: AiTask): Int =
        if (preset.reasoningEffort != null) maxOf(task.maxTokens, 4000) else task.maxTokens

    private fun sendsReasoningEffort(): Boolean =
        preset.reasoningEffort != null && (endpoint.model.startsWith("gpt-5") || endpoint.model.startsWith("o"))

    /** True when the full request for [task] contains any optional parameter. */
    fun hasOptionalParams(task: AiTask): Boolean =
        preset.sendTemperature || sendsReasoningEffort() || (task.json && preset.jsonMode)

    fun buildRequestBody(task: AiTask, system: String, user: String, plain: Boolean): JsonObject = buildJsonObject {
        put("model", endpoint.model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                put("content", user)
            }
        }
        put(preset.maxTokensField.ifBlank { "max_tokens" }, maxTokensFor(task))
        if (!plain) {
            if (preset.sendTemperature) put("temperature", 0.2)
            if (sendsReasoningEffort()) put("reasoning_effort", preset.reasoningEffort)
            if (task.json && preset.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }
    }

    private fun Request.Builder.authHeaders(): Request.Builder = apply {
        if (endpoint.apiKey.isNotBlank()) header("Authorization", "Bearer ${endpoint.apiKey}")
        preset.extraHeaders.forEach { (name, value) -> header(name, value) }
    }

    private fun request(body: JsonObject): Request = Request.Builder()
        .url(endpoint.normalizedBaseUrl + "/chat/completions")
        .authHeaders()
        .post(jsonBody(body))
        .build()

    override suspend fun complete(task: AiTask, system: String, user: String): String {
        if (preset.keyRequired && endpoint.apiKey.isBlank()) throw AiException(AiErrorKind.NO_KEY)
        val plain = plainModes.isPlain(preset.id, endpoint.model)
        var result = http.execute(request(buildRequestBody(task, system, user, plain)), task.timeoutMs)
        if (!plain && (result.code == 400 || result.code == 422) && hasOptionalParams(task)) {
            // Optional-parameter fallback (SPEC §6.3): retry once without them and remember it.
            plainModes.markPlain(preset.id, endpoint.model)
            result = http.execute(request(buildRequestBody(task, system, user, plain = true)), task.timeoutMs)
        }
        if (result.code !in 200..299) throw httpError(result.code, result.body)
        return parseResponse(result.body)
    }

    override suspend fun listModels(): List<String> {
        if (preset.keyRequired && endpoint.apiKey.isBlank()) throw AiException(AiErrorKind.NO_KEY)
        val request = Request.Builder()
            .url(endpoint.normalizedBaseUrl + "/models")
            .authHeaders()
            .get()
            .build()
        val result = http.execute(request, AnthropicClient.MODEL_LIST_TIMEOUT_MS)
        if (result.code !in 200..299) throw httpError(result.code, result.body)
        return ModelSorting.sort(parseModelIds(result.body))
    }

    companion object {
        /** `choices[0].message.content` (string or content parts); refusals → [AiErrorKind.REFUSED]. */
        fun parseResponse(body: String): String {
            val root = parseJsonObject(body)
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
            val message = choice["message"] as? JsonObject
                ?: throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
            val refusal = (message["refusal"] as? JsonPrimitive)?.contentOrNull
            if (!refusal.isNullOrBlank()) throw AiException(AiErrorKind.REFUSED, refusal)
            val text = when (val content = message["content"]) {
                is JsonPrimitive -> content.contentOrNull.orEmpty()
                is JsonArray -> content.joinToString("") { part ->
                    ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
                null -> ""
                else -> throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
            }
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (text.isEmpty() && finish == "content_filter") throw AiException(AiErrorKind.REFUSED)
            return text
        }
    }
}
