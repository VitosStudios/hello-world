package ch.vitosstudios.tippli.core.ai

import ch.vitosstudios.tippli.core.data.TippliJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class HttpResult(val code: Int, val body: String)

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** JSON request body with the exact content type `application/json` (no charset suffix). */
internal fun jsonBody(element: JsonElement): RequestBody =
    TippliJson.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE)

/**
 * Executes [request] asynchronously with a hard call timeout. Network problems
 * become [AiException]s (`network` / `timeout`); cancelling the coroutine
 * cancels the HTTP call.
 */
internal suspend fun OkHttpClient.execute(request: Request, timeoutMs: Long): HttpResult {
    val result = withTimeoutOrNull(timeoutMs + 1_000) {
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(mapIoException(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = try {
                        response.use { Result.success(HttpResult(it.code, it.body?.string().orEmpty())) }
                    } catch (e: IOException) {
                        Result.failure(mapIoException(e))
                    }
                    outcome.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
    }
    return result ?: throw AiException(AiErrorKind.TIMEOUT)
}

internal fun mapIoException(e: IOException): AiException =
    if (e is InterruptedIOException) AiException(AiErrorKind.TIMEOUT, e.message, e)
    else AiException(AiErrorKind.NETWORK, e.message, e)

/** Maps a non-2xx HTTP status to an error (SPEC §6.7). */
internal fun httpError(code: Int, body: String): AiException {
    val message = providerMessage(body)
    val detail = listOfNotNull("HTTP $code", message).joinToString(": ")
    val kind = when {
        code == 401 || code == 403 -> AiErrorKind.INVALID_KEY
        code == 404 -> AiErrorKind.BAD_MODEL
        code == 429 -> AiErrorKind.RATE_LIMITED
        code >= 500 -> AiErrorKind.SERVER
        (code == 400 || code == 422) && message?.contains("model", ignoreCase = true) == true -> AiErrorKind.BAD_MODEL
        else -> AiErrorKind.SERVER
    }
    return AiException(kind, detail)
}

/** Extracts a human-readable error message from a provider error body, if any. */
internal fun providerMessage(body: String): String? {
    val root = try {
        TippliJson.parseToJsonElement(body)
    } catch (_: Exception) {
        return body.trim().take(200).ifEmpty { null }
    }
    val obj = when (root) {
        is JsonObject -> root
        is kotlinx.serialization.json.JsonArray -> root.firstOrNull() as? JsonObject
        else -> null
    } ?: return null
    val error = obj["error"]
    (error as? JsonObject)?.let { e -> (e["message"] as? JsonPrimitive)?.contentOrNull?.let { return it.take(300) } }
    (error as? JsonPrimitive)?.contentOrNull?.let { return it.take(300) }
    (obj["message"] as? JsonPrimitive)?.contentOrNull?.let { return it.take(300) }
    return null
}

internal fun parseJsonObject(body: String): JsonObject = try {
    TippliJson.parseToJsonElement(body) as? JsonObject
} catch (e: Exception) {
    null
} ?: throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))

/** Model ids from a `/models` response: `{"data": [{"id": …}]}` or a plain array. */
internal fun parseModelIds(body: String): List<String> {
    val root = try {
        TippliJson.parseToJsonElement(body)
    } catch (e: Exception) {
        throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
    }
    val items: List<JsonElement> = when (root) {
        is kotlinx.serialization.json.JsonArray -> root
        is JsonObject -> (root["data"] as? kotlinx.serialization.json.JsonArray)
            ?: (root["models"] as? kotlinx.serialization.json.JsonArray)
            ?: throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
        else -> throw AiException(AiErrorKind.BAD_RESPONSE, body.take(200))
    }
    return items.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> ((item["id"] ?: item["name"]) as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }.map { it.removePrefix("models/") }.filter { it.isNotBlank() }
}

/** Sorting for "Modelle laden" (SPEC §6.6): Claude/GPT/Gemini chat models first, then the rest, alphabetically. */
object ModelSorting {
    private val chatPrefixes = listOf("claude", "gpt", "gemini", "chatgpt", "o1", "o3", "o4")
    private val nonChatMarkers = listOf(
        "embed", "tts", "whisper", "dall-e", "image", "audio", "realtime", "transcribe",
        "moderation", "search", "computer-use", "davinci", "babbage", "veo", "imagen",
    )

    fun isPreferredChatModel(id: String): Boolean {
        val name = id.substringAfterLast('/').lowercase()
        return chatPrefixes.any { name.startsWith(it) } && nonChatMarkers.none { name.contains(it) }
    }

    fun sort(ids: List<String>): List<String> =
        ids.distinct().sortedWith(compareBy<String>({ if (isPreferredChatModel(it)) 0 else 1 }, { it.lowercase() }))
}
