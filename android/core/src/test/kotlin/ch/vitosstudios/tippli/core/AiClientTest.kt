package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.ai.AiClientFactory
import ch.vitosstudios.tippli.core.ai.AiEndpoint
import ch.vitosstudios.tippli.core.ai.AiErrorKind
import ch.vitosstudios.tippli.core.ai.AiException
import ch.vitosstudios.tippli.core.ai.AiTask
import ch.vitosstudios.tippli.core.ai.AnthropicProfile
import ch.vitosstudios.tippli.core.ai.ModelSorting
import ch.vitosstudios.tippli.core.ai.PlainModeRegistry
import ch.vitosstudios.tippli.core.data.ProviderPreset
import ch.vitosstudios.tippli.core.data.TippliJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiClientTest {
    private val shared = TestShared.data
    private lateinit var server: MockWebServer
    private val plainModes = PlainModeRegistry()
    private val factory = AiClientFactory(OkHttpClient(), plainModes)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun preset(id: String): ProviderPreset = shared.provider(id)!!

    private fun endpoint(id: String, model: String, key: String = "sk-test") =
        AiEndpoint(preset(id), apiKey = key, model = model, baseUrl = server.url("/v1/").toString())

    private fun RecordedRequest.json(): JsonObject = TippliJson.parseToJsonElement(body.readUtf8()).jsonObject

    private fun anthropicOk(text: String = "Hallo, wie geht's dir heute?") = MockResponse().setBody(
        """{"id":"msg_1","type":"message","role":"assistant","content":[
           {"type":"thinking","thinking":"hmm"},{"type":"text","text":"$text"}],"stop_reason":"end_turn"}""",
    )

    private fun openAiOk(text: String = "Hallo") =
        MockResponse().setBody("""{"choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}""")

    private fun assertError(kind: AiErrorKind, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected $kind")
        } catch (e: AiException) {
            assertEquals(kind, e.kind)
        }
    }

    // ------------------------------------------------------------------ Anthropic

    @Test
    fun anthropicHaikuProfileAndHeaders() = runBlocking {
        server.enqueue(anthropicOk())
        val client = factory.create(endpoint("anthropic", "claude-haiku-5-5"))
        val text = client.complete(AiTask.CORRECTION, "SYS", "USER")
        assertEquals("Hallo, wie geht's dir heute?", text) // thinking blocks ignored
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertNull(req.getHeader("anthropic-beta"))
        assertNull(req.getHeader("Authorization"))
        val body = req.json()
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(2048, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("SYS", body["system"]!!.jsonPrimitive.content)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("USER", message["content"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("fallbacks"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun anthropicSonnetAndOpusProfiles() = runBlocking {
        server.enqueue(anthropicOk("{}"))
        factory.create(endpoint("anthropic", "claude-sonnet-5-5")).complete(AiTask.TIPS, "S", "U")
        val sonnet = server.takeRequest()
        assertEquals(AnthropicProfile.FALLBACK_BETA, sonnet.getHeader("anthropic-beta"))
        val sBody = sonnet.json()
        assertEquals("between_tools", sBody["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", sBody["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", sBody["fallbacks"]!!.jsonPrimitive.content)
        assertEquals(8000, sBody["max_tokens"]!!.jsonPrimitive.int)

        for (model in listOf("claude-opus-5-5", "claude-fable-5-1")) {
            server.enqueue(anthropicOk())
            factory.create(endpoint("anthropic", model)).complete(AiTask.MOOD, "S", "U")
            val opus = server.takeRequest()
            assertEquals(AnthropicProfile.FALLBACK_BETA, opus.getHeader("anthropic-beta"))
            val oBody = opus.json()
            assertFalse(oBody.containsKey("thinking"))
            assertEquals("low", oBody["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals("default", oBody["fallbacks"]!!.jsonPrimitive.content)
            assertEquals(1024, oBody["max_tokens"]!!.jsonPrimitive.int)
        }

        server.enqueue(anthropicOk())
        factory.create(endpoint("anthropic", "claude-3-5-haiku-latest")).complete(AiTask.TRANSLATE, "S", "U")
        val other = server.takeRequest()
        assertNull(other.getHeader("anthropic-beta"))
        val otherBody = other.json()
        assertEquals(setOf("model", "max_tokens", "system", "messages"), otherBody.keys)
        assertEquals(4000, otherBody["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun anthropicRefusalAndErrors() {
        server.enqueue(MockResponse().setBody("""{"content":[],"stop_reason":"refusal"}"""))
        assertError(AiErrorKind.REFUSED) { factory.create(endpoint("anthropic", "claude-haiku-5-5")).complete(AiTask.CORRECTION, "S", "U") }
        val cases = mapOf(
            401 to AiErrorKind.INVALID_KEY, 403 to AiErrorKind.INVALID_KEY, 404 to AiErrorKind.BAD_MODEL,
            429 to AiErrorKind.RATE_LIMITED, 500 to AiErrorKind.SERVER, 529 to AiErrorKind.SERVER,
        )
        for ((code, kind) in cases) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"type":"error","error":{"type":"x","message":"nope"}}"""))
            try {
                runBlocking { factory.create(endpoint("anthropic", "claude-haiku-5-5")).complete(AiTask.CORRECTION, "S", "U") }
                fail("expected $kind for $code")
            } catch (e: AiException) {
                assertEquals("HTTP $code", kind, e.kind)
                assertTrue(e.detail!!.contains("nope"))
            }
        }
        server.enqueue(MockResponse().setBody("not json"))
        assertError(AiErrorKind.BAD_RESPONSE) { factory.create(endpoint("anthropic", "claude-haiku-5-5")).complete(AiTask.CORRECTION, "S", "U") }
        assertError(AiErrorKind.NO_KEY) { factory.create(endpoint("anthropic", "claude-haiku-5-5", key = "")).complete(AiTask.CORRECTION, "S", "U") }
    }

    @Test
    fun networkAndTimeoutErrors() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertError(AiErrorKind.NETWORK) { factory.create(endpoint("anthropic", "claude-haiku-5-5")).complete(AiTask.CORRECTION, "S", "U") }
        server.enqueue(anthropicOk().setHeadersDelay(2, TimeUnit.SECONDS))
        val impatient = AiClientFactory(OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build(), plainModes)
        assertError(AiErrorKind.TIMEOUT) { impatient.create(endpoint("anthropic", "claude-haiku-5-5")).complete(AiTask.CORRECTION, "S", "U") }
    }

    @Test
    fun anthropicModelList() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-sonnet-5-5"},{"id":"claude-haiku-5-5"},{"id":"zz-other"}],"has_more":false}"""))
        val models = factory.create(endpoint("anthropic", "claude-haiku-5-5")).listModels()
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "zz-other"), models)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!.startsWith("/v1/models"))
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
    }

    // ------------------------------------------------------------------ OpenAI-compatible

    @Test
    fun openAiPresetFields() = runBlocking {
        server.enqueue(openAiOk())
        val client = factory.create(endpoint("openai", "gpt-5-mini"))
        assertEquals("Hallo", client.complete(AiTask.CORRECTION, "SYS", "USER"))
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Content-Type"))
        val body = req.json()
        assertEquals("gpt-5-mini", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("SYS", "USER"), messages.map { it["content"]!!.jsonPrimitive.content })
        assertEquals(4000, body["max_completion_tokens"]!!.jsonPrimitive.int) // reasoning provider: at least 4000
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("temperature"))
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("response_format")) // correction is not a JSON task

        server.enqueue(openAiOk("{}"))
        client.complete(AiTask.TIPS, "S", "U")
        val tips = server.takeRequest().json()
        assertEquals(8000, tips["max_completion_tokens"]!!.jsonPrimitive.int)
        assertEquals("json_object", tips["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // reasoning_effort only for gpt-5* / o* models.
        server.enqueue(openAiOk())
        factory.create(endpoint("openai", "chatgpt-4o-latest")).complete(AiTask.CORRECTION, "S", "U")
        assertFalse(server.takeRequest().json().containsKey("reasoning_effort"))
        server.enqueue(openAiOk())
        factory.create(endpoint("openai", "o4-mini")).complete(AiTask.CORRECTION, "S", "U")
        assertTrue(server.takeRequest().json().containsKey("reasoning_effort"))
        // ^o[0-9] only: other ids starting with "o" are not reasoning models.
        for (model in listOf("omni-moderation-latest", "openai/gpt-5-mini", "olmo-2")) {
            server.enqueue(openAiOk())
            factory.create(endpoint("openai", model)).complete(AiTask.CORRECTION, "S", "U")
            assertFalse(model, server.takeRequest().json().containsKey("reasoning_effort"))
        }
    }

    @Test
    fun geminiGithubAndCustomPresets() = runBlocking {
        server.enqueue(openAiOk())
        factory.create(endpoint("gemini", "gemini-flash-lite-latest")).complete(AiTask.MOOD, "S", "U")
        val gemini = server.takeRequest().json()
        assertEquals(1024, gemini["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.2, gemini["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("json_object", gemini["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(gemini.containsKey("reasoning_effort"))

        server.enqueue(openAiOk())
        factory.create(endpoint("github", "openai/gpt-4.1-mini")).complete(AiTask.CORRECTION, "S", "U")
        val github = server.takeRequest()
        assertEquals("application/vnd.github+json", github.getHeader("Accept"))
        assertEquals("2022-11-28", github.getHeader("X-GitHub-Api-Version"))
        assertEquals("Bearer sk-test", github.getHeader("Authorization"))

        server.enqueue(openAiOk())
        factory.create(endpoint("custom", "llama3.2", key = "")).complete(AiTask.TIPS, "S", "U")
        val custom = server.takeRequest()
        assertNull(custom.getHeader("Authorization")) // omitted when the key is empty
        val customBody = custom.json()
        assertFalse(customBody.containsKey("response_format")) // jsonMode false
        assertEquals(0.2, customBody["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun optionalParameterFallbackOn400And422() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter: temperature"}}"""))
        server.enqueue(openAiOk("ok"))
        val client = factory.create(endpoint("mistral", "mistral-small-latest"))
        assertEquals("ok", client.complete(AiTask.TIPS, "S", "U"))
        val first = server.takeRequest().json()
        assertTrue(first.containsKey("temperature"))
        assertTrue(first.containsKey("response_format"))
        val retry = server.takeRequest().json()
        assertFalse(retry.containsKey("temperature"))
        assertFalse(retry.containsKey("response_format"))
        assertFalse(retry.containsKey("reasoning_effort"))
        assertEquals(8000, retry["max_tokens"]!!.jsonPrimitive.int)
        // Plain mode is remembered for this provider + model.
        assertTrue(plainModes.isPlain("mistral", "mistral-small-latest"))
        server.enqueue(openAiOk("again"))
        assertEquals("again", client.complete(AiTask.CORRECTION, "S", "U"))
        assertFalse(server.takeRequest().json().containsKey("temperature"))

        server.enqueue(MockResponse().setResponseCode(422).setBody("{}"))
        server.enqueue(openAiOk("ok2"))
        assertEquals("ok2", factory.create(endpoint("openai", "gpt-5-nano")).complete(AiTask.CORRECTION, "S", "U"))
        assertTrue(server.takeRequest().json().containsKey("reasoning_effort"))
        assertFalse(server.takeRequest().json().containsKey("reasoning_effort"))
    }

    @Test
    fun noRetryWithoutOptionalParameters() {
        // custom preset sends temperature; a preset copy without it has nothing to drop.
        val bare = preset("custom").copy(id = "bare", sendTemperature = false, jsonMode = false)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad request"}}"""))
        val client = factory.create(AiEndpoint(bare, "", "m", server.url("/v1").toString()))
        assertError(AiErrorKind.SERVER) { client.complete(AiTask.CORRECTION, "S", "U") }
        assertEquals(1, server.requestCount)
        // A 400 that complains about the model maps to badModel.
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"model 'x' not found"}}"""))
        assertError(AiErrorKind.BAD_MODEL) { client.complete(AiTask.CORRECTION, "S", "U") }
    }

    @Test
    fun openAiResponses() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":null,"refusal":"I can't help"}}]}"""))
        assertError(AiErrorKind.REFUSED) { factory.create(endpoint("openai", "gpt-5-mini")).complete(AiTask.CORRECTION, "S", "U") }
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""))
        assertEquals("ab", runBlocking { factory.create(endpoint("openai", "gpt-5-mini")).complete(AiTask.CORRECTION, "S", "U") })
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        assertError(AiErrorKind.BAD_RESPONSE) { factory.create(endpoint("openai", "gpt-5-mini")).complete(AiTask.CORRECTION, "S", "U") }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key"}}"""))
        assertError(AiErrorKind.INVALID_KEY) { factory.create(endpoint("openai", "gpt-5-mini")).complete(AiTask.CORRECTION, "S", "U") }
        assertError(AiErrorKind.NO_KEY) { factory.create(endpoint("openai", "gpt-5-mini", key = "")).complete(AiTask.CORRECTION, "S", "U") }
    }

    @Test
    fun openAiModelList() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"id":"text-embedding-3-small"},{"id":"gpt-5-mini"},{"id":"babbage-002"},{"id":"gpt-5"},{"id":"models/gemini-flash-latest"}]}""",
        ))
        val models = factory.create(endpoint("openai", "gpt-5-mini")).listModels()
        assertEquals(listOf("gemini-flash-latest", "gpt-5", "gpt-5-mini", "babbage-002", "text-embedding-3-small"), models)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(ModelSorting.isPreferredChatModel("anthropic/claude-x"))
        assertFalse(ModelSorting.isPreferredChatModel("gpt-4o-transcribe"))
    }
}
