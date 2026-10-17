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

/** Model-family specific request extras for Claude (SPEC §6.2). */
data class AnthropicProfile(
    /** `thinking.type`, or null to omit `thinking`. */
    val thinking: String?,
    /** Send `output_config.effort`. */
    val effort: Boolean,
    /** Send `"fallbacks": "default"`. */
    val fallbacks: Boolean,
    /** Value of the `anthropic-beta` header, or null. */
    val betaHeader: String?,
) {
    companion object {
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        fun forModel(model: String): AnthropicProfile = when {
            model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5") ->
                AnthropicProfile(thinking = null, effort = true, fallbacks = true, betaHeader = FALLBACK_BETA)
            model.startsWith("claude-sonnet-5-5") ->
                AnthropicProfile(thinking = "between_tools", effort = true, fallbacks = true, betaHeader = FALLBACK_BETA)
            model.startsWith("claude-haiku-5-5") ->
                AnthropicProfile(thinking = "disabled", effort = true, fallbacks = false, betaHeader = null)
            else -> AnthropicProfile(thinking = null, effort = false, fallbacks = false, betaHeader = null)
        }
    }
}

/** Claude Messages API client (protocol `anthropic`, SPEC §6.2). */
class AnthropicClient(
    override val endpoint: AiEndpoint,
    private val http: OkHttpClient,
) : AiClient {

    fun buildRequestBody(task: AiTask, system: String, user: String): JsonObject {
        val profile = AnthropicProfile.forModel(endpoint.model)
        return buildJsonObject {
            put("model", endpoint.model)
            put("max_tokens", task.maxTokens)
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            profile.thinking?.let { type -> putJsonObject("thinking") { put("type", type) } }
            if (profile.effort) putJsonObject("output_config") { put("effort", task.effort) }
            if (profile.fallbacks) put("fallbacks", "default")
        }
    }

    private fun Request.Builder.authHeaders(): Request.Builder = apply {
        header("x-api-key", endpoint.apiKey)
        header("anthropic-version", ANTHROPIC_VERSION)
    }

    override suspend fun complete(task: AiTask, system: String, user: String): String {
        if (endpoint.preset.keyRequired && endpoint.apiKey.isBlank()) throw AiException(AiErrorKind.NO_KEY)
        val profile = AnthropicProfile.forModel(endpoint.model)
        val request = Request.Builder()
            .url(endpoint.normalizedBaseUrl + "/messages")
            .authHeaders()
            .apply { profile.betaHeader?.let { header("anthropic-beta", it) } }
            .post(jsonBody(buildRequestBody(task, system, user)))
            .build()
        val result = http.execute(request, task.timeoutMs)
        if (result.code !in 200..299) throw httpError(result.code, result.body)
        return parseResponse(result.body)
    }

    override suspend fun listModels(): List<String> {
        if (endpoint.preset.keyRequired && endpoint.apiKey.isBlank()) throw AiException(AiErrorKind.NO_KEY)
        val request = Request.Builder()
            .url(endpoint.normalizedBaseUrl + "/models?limit=1000")
            .authHeaders()
            .get()
            .build()
        val result = http.execute(request, MODEL_LIST_TIMEOUT_MS)
        if (result.code !in 200..299) throw httpError(result.code, result.body)
        return ModelSorting.sort(parseModelIds(result.body))
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MODEL_LIST_TIMEOUT_MS = 20_000L

        /** Text of all `text` blocks; `stop_reason == "refusal"` → [AiErrorKind.REFUSED]. */
        fun parseResponse(body: String): String {
            val root = parseJsonObject(body)
            val stopReason = (root["stop_reason"] as? JsonPrimitive)?.contentOrNull
            if (stopReason == "refusal") throw AiException(AiErrorKind.REFUSED)
            val content = root["content"] as? JsonArray ?: throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
            return content.joinToString("") { block ->
                val obj = block as? JsonObject
                if (obj != null && (obj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                } else {
                    ""
                }
            }
        }
    }
}
