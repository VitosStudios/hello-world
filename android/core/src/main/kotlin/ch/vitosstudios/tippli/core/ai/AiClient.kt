package ch.vitosstudios.tippli.core.ai

import ch.vitosstudios.tippli.core.data.ProviderPreset
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The four kinds of AI requests with their limits (SPEC §6.2, §6.7). */
enum class AiTask(val maxTokens: Int, val effort: String, val timeoutMs: Long, val json: Boolean) {
    CORRECTION(maxTokens = 2048, effort = "low", timeoutMs = 10_000, json = false),
    MOOD(maxTokens = 1024, effort = "low", timeoutMs = 15_000, json = true),
    TIPS(maxTokens = 8000, effort = "medium", timeoutMs = 45_000, json = true),
    TRANSLATE(maxTokens = 4000, effort = "medium", timeoutMs = 45_000, json = false),
}

/** Error keys of `strings.errors` (SPEC §6.7). */
enum class AiErrorKind(val key: String) {
    NO_KEY("noKey"),
    INVALID_KEY("invalidKey"),
    RATE_LIMITED("rateLimited"),
    NETWORK("network"),
    TIMEOUT("timeout"),
    REFUSED("refused"),
    SERVER("server"),
    BAD_MODEL("badModel"),
    BAD_RESPONSE("badResponse");

    /** Path in strings-de.json, e.g. `errors.invalidKey`. */
    val stringPath: String get() = "errors.$key"
}

/** Every failure of an AI request is reported as an [AiException] with a [kind]. */
class AiException(
    val kind: AiErrorKind,
    /** Provider message or HTTP status, for display in settings ("Verbindung testen"). */
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail?.let { "${kind.key}: $it" } ?: kind.key, cause)

/** Where and how to talk to a provider. */
data class AiEndpoint(
    val preset: ProviderPreset,
    val apiKey: String,
    val model: String,
    val baseUrl: String = preset.baseUrl,
) {
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
}

/** A chat-style AI backend. All calls are main-safe and cancellable. */
interface AiClient {
    val endpoint: AiEndpoint

    /** Sends one system + user prompt and returns the model's text. Throws [AiException]. */
    suspend fun complete(task: AiTask, system: String, user: String): String

    /** `GET {baseUrl}/models` (SPEC §6.6), sorted with chat models first. Throws [AiException]. */
    suspend fun listModels(): List<String>
}

/**
 * Remembers provider+model combinations that rejected the optional
 * parameters (SPEC §6.3 "plain mode") for the rest of the process lifetime.
 */
class PlainModeRegistry {
    private val plain = ConcurrentHashMap.newKeySet<String>()

    fun isPlain(providerId: String, model: String) = plain.contains(key(providerId, model))
    fun markPlain(providerId: String, model: String) { plain.add(key(providerId, model)) }
    fun clear() = plain.clear()

    private fun key(providerId: String, model: String) = "$providerId|$model"

    companion object {
        val GLOBAL = PlainModeRegistry()
    }
}

/** Creates the right client for a provider preset's protocol. */
class AiClientFactory(
    private val http: OkHttpClient = defaultHttpClient(),
    private val plainModes: PlainModeRegistry = PlainModeRegistry.GLOBAL,
) {
    fun create(endpoint: AiEndpoint): AiClient = when (endpoint.preset.protocol) {
        ProviderPreset.PROTOCOL_ANTHROPIC -> AnthropicClient(endpoint, http)
        else -> OpenAiClient(endpoint, http, plainModes)
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
