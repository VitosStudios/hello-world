import Foundation
import XCTest
@testable import TippliCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Captures requests and answers them with canned responses.
final class StubURLProtocol: URLProtocol {
    struct Reply {
        var status: Int
        var body: String
        var error: URLError?

        init(status: Int = 200, body: String = "", error: URLError? = nil) {
            self.status = status
            self.body = body
            self.error = error
        }
    }

    struct Captured {
        let request: URLRequest
        let body: Data

        var json: [String: Any] {
            (try? JSONSerialization.jsonObject(with: body, options: [])) as? [String: Any] ?? [:]
        }

        func header(_ name: String) -> String? {
            request.value(forHTTPHeaderField: name)
        }
    }

    private static let lock = NSLock()
    private static var _replies: [Reply] = []
    private static var _captured: [Captured] = []

    static func reset(_ replies: [Reply]) {
        lock.lock()
        _replies = replies
        _captured = []
        lock.unlock()
    }

    static var captured: [Captured] {
        lock.lock()
        defer { lock.unlock() }
        return _captured
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = StubURLProtocol.bodyData(of: request)
        StubURLProtocol.lock.lock()
        StubURLProtocol._captured.append(Captured(request: request, body: body))
        let reply = StubURLProtocol._replies.isEmpty ? Reply(status: 500, body: "{}") : StubURLProtocol._replies.removeFirst()
        StubURLProtocol.lock.unlock()

        if let error = reply.error {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    static func bodyData(of request: URLRequest) -> Data {
        if let b = request.httpBody { return b }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}

final class AIClientTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = testResources
        AIClient.resetPlainModes()
    }

    private func preset(_ id: String) -> ProviderPreset {
        testResources.provider(id)!
    }

    private func client(_ presetId: String, model: String, key: String = "test-key") -> AIClient {
        AIClient(preset: preset(presetId), model: model, apiKey: key, session: StubURLProtocol.session())
    }

    private let anthropicOK = """
    {"id":"msg_1","type":"message","role":"assistant","stop_reason":"end_turn",
     "content":[{"type":"thinking","thinking":""},{"type":"text","text":"Hallo "},{"type":"text","text":"Welt"}]}
    """

    private func openAIOK(_ text: String) -> String {
        let escaped = text.replacingOccurrences(of: "\"", with: "\\\"")
        return "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"\(escaped)\"}}]}"
    }

    private let request = AIRequest(task: .correction, system: "SYS", user: "USER")

    // MARK: Anthropic

    func testAnthropicOpusProfile() async throws {
        StubURLProtocol.reset([.init(body: anthropicOK)])
        let out = try await client("anthropic", model: "claude-opus-5-5").complete(request)
        XCTAssertEqual(out, "Hallo Welt")
        let c = try XCTUnwrap(StubURLProtocol.captured.first)
        XCTAssertEqual(c.request.url?.absoluteString, "https://api.anthropic.com/v1/messages")
        XCTAssertEqual(c.request.httpMethod, "POST")
        XCTAssertEqual(c.header("x-api-key"), "test-key")
        XCTAssertEqual(c.header("anthropic-version"), "2023-06-01")
        XCTAssertEqual(c.header("anthropic-beta"), "server-side-fallback-2026-07-01")
        XCTAssertNil(c.header("Authorization"))
        let j = c.json
        XCTAssertEqual(j["model"] as? String, "claude-opus-5-5")
        XCTAssertEqual(j["max_tokens"] as? Int, 2048)
        XCTAssertEqual(j["system"] as? String, "SYS")
        let messages = try XCTUnwrap(j["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.count, 1)
        XCTAssertEqual(messages[0]["role"] as? String, "user")
        XCTAssertEqual(messages[0]["content"] as? String, "USER")
        XCTAssertEqual((j["output_config"] as? [String: Any])?["effort"] as? String, "low")
        XCTAssertEqual(j["fallbacks"] as? String, "default")
        XCTAssertNil(j["thinking"])
        XCTAssertNil(j["temperature"])
        XCTAssertNil(j["top_p"])
    }

    func testAnthropicFableProfileMatchesOpus() {
        XCTAssertEqual(AnthropicProfile.profile(for: "claude-fable-5-1"), AnthropicProfile.profile(for: "claude-opus-5"))
    }

    func testAnthropicSonnetProfileForTips() async throws {
        StubURLProtocol.reset([.init(body: anthropicOK)])
        _ = try await client("anthropic", model: "claude-sonnet-5-5")
            .complete(AIRequest(task: .tips, system: "S", user: "U"))
        let c = try XCTUnwrap(StubURLProtocol.captured.first)
        let j = c.json
        XCTAssertEqual((j["thinking"] as? [String: Any])?["type"] as? String, "between_tools")
        XCTAssertEqual((j["output_config"] as? [String: Any])?["effort"] as? String, "medium")
        XCTAssertEqual(j["fallbacks"] as? String, "default")
        XCTAssertEqual(j["max_tokens"] as? Int, 8000)
        XCTAssertEqual(c.header("anthropic-beta"), "server-side-fallback-2026-07-01")
    }

    func testAnthropicHaikuProfile() async throws {
        StubURLProtocol.reset([.init(body: anthropicOK)])
        _ = try await client("anthropic", model: "claude-haiku-5-5")
            .complete(AIRequest(task: .mood, system: "S", user: "U"))
        let c = try XCTUnwrap(StubURLProtocol.captured.first)
        let j = c.json
        XCTAssertEqual((j["thinking"] as? [String: Any])?["type"] as? String, "disabled")
        XCTAssertEqual((j["output_config"] as? [String: Any])?["effort"] as? String, "low")
        XCTAssertNil(j["fallbacks"])
        XCTAssertEqual(j["max_tokens"] as? Int, 1024)
        XCTAssertNil(c.header("anthropic-beta"))
    }

    func testAnthropicOtherModelHasNoExtras() async throws {
        StubURLProtocol.reset([.init(body: anthropicOK)])
        _ = try await client("anthropic", model: "claude-haiku-4-5")
            .complete(AIRequest(task: .translate, system: "S", user: "U"))
        let j = try XCTUnwrap(StubURLProtocol.captured.first).json
        XCTAssertNil(j["thinking"])
        XCTAssertNil(j["output_config"])
        XCTAssertNil(j["fallbacks"])
        XCTAssertEqual(j["max_tokens"] as? Int, 4000)
        XCTAssertNil(try XCTUnwrap(StubURLProtocol.captured.first).header("anthropic-beta"))
    }

    func testDefaultModelComesFromProvidersJSON() {
        let store = SettingsStore(defaults: makeTestDefaults(), resources: testResources)
        XCTAssertEqual(store.model(for: "anthropic"), preset("anthropic").defaultModel)
        XCTAssertFalse(preset("anthropic").defaultModel.isEmpty)
        store.setModel("claude-sonnet-5-5", for: "anthropic")
        XCTAssertEqual(store.model(for: "anthropic"), "claude-sonnet-5-5")
    }

    func testAnthropicRefusal() async {
        StubURLProtocol.reset([.init(body: "{\"stop_reason\":\"refusal\",\"content\":[]}")])
        do {
            _ = try await client("anthropic", model: "claude-opus-5-5").complete(request)
            XCTFail("expected refusal")
        } catch {
            XCTAssertEqual(AIError.from(error).kind, .refused)
        }
    }

    func testHTTPErrorMapping() async {
        let cases: [(Int, AIError.Kind)] = [
            (401, .invalidKey), (403, .invalidKey), (404, .badModel), (429, .rateLimited),
            (500, .server), (503, .server), (529, .server), (400, .badResponse)
        ]
        for (status, kind) in cases {
            StubURLProtocol.reset([.init(status: status, body: "{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"nope\"}}")])
            do {
                _ = try await client("anthropic", model: "claude-opus-5-5").complete(request)
                XCTFail("expected error for \(status)")
            } catch {
                let e = AIError.from(error)
                XCTAssertEqual(e.kind, kind, "status \(status)")
                XCTAssertEqual(e.detail, "nope")
            }
        }
    }

    func testNetworkFailure() async {
        StubURLProtocol.reset([.init(error: URLError(.notConnectedToInternet))])
        do {
            _ = try await client("anthropic", model: "claude-opus-5-5").complete(request)
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(AIError.from(error).kind, .network)
        }
    }

    func testMissingKey() async {
        StubURLProtocol.reset([])
        do {
            _ = try await client("anthropic", model: "claude-opus-5-5", key: "").complete(request)
            XCTFail("expected noKey")
        } catch {
            XCTAssertEqual(AIError.from(error).kind, .noKey)
        }
        XCTAssertTrue(StubURLProtocol.captured.isEmpty)
    }

    func testTimeoutHelper() async {
        do {
            _ = try await withAITimeout(0.05) {
                try await Task.sleep(nanoseconds: 2_000_000_000)
                return "late"
            }
            XCTFail("expected timeout")
        } catch {
            XCTAssertEqual(AIError.from(error).kind, .timeout)
        }
    }

    // MARK: OpenAI-compatible

    func testOpenAIPresetFields() async throws {
        StubURLProtocol.reset([.init(body: openAIOK("ok")), .init(body: openAIOK("Hallo"))])
        let c = client("openai", model: "gpt-5-mini")
        _ = try await c.complete(AIRequest(task: .tips, system: "S", user: "U"))
        let first = try XCTUnwrap(StubURLProtocol.captured.first)
        XCTAssertEqual(first.request.url?.absoluteString, "https://api.openai.com/v1/chat/completions")
        XCTAssertEqual(first.header("Authorization"), "Bearer test-key")
        let j = first.json
        XCTAssertEqual(j["max_completion_tokens"] as? Int, 8000)
        XCTAssertNil(j["max_tokens"])
        XCTAssertEqual(j["reasoning_effort"] as? String, "low")
        XCTAssertEqual((j["response_format"] as? [String: Any])?["type"] as? String, "json_object")
        XCTAssertNil(j["temperature"])
        let messages = try XCTUnwrap(j["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.map { $0["role"] as? String }, ["system", "user"])

        // Correction: at least 4000 tokens for reasoning providers, no JSON mode.
        let out = try await c.complete(request)
        XCTAssertEqual(out, "Hallo")
        let second = StubURLProtocol.captured[1].json
        XCTAssertEqual(second["max_completion_tokens"] as? Int, 4000)
        XCTAssertNil(second["response_format"])
    }

    func testGitHubExtraHeadersAndTemperature() async throws {
        StubURLProtocol.reset([.init(body: openAIOK("ok"))])
        _ = try await client("github", model: "openai/gpt-4.1-mini").complete(request)
        let c = try XCTUnwrap(StubURLProtocol.captured.first)
        XCTAssertEqual(c.request.url?.absoluteString, "https://models.github.ai/inference/chat/completions")
        XCTAssertEqual(c.header("X-GitHub-Api-Version"), "2022-11-28")
        XCTAssertEqual(c.header("Accept"), "application/vnd.github+json")
        let j = c.json
        XCTAssertEqual((j["temperature"] as? Double) ?? -1, 0.2, accuracy: 0.0001)
        XCTAssertEqual(j["max_tokens"] as? Int, 2048)
        XCTAssertNil(j["reasoning_effort"])
    }

    func testCustomServerWithoutKeyOmitsAuthorization() async throws {
        StubURLProtocol.reset([.init(body: openAIOK("ok"))])
        let c = AIClient(preset: preset("custom"), model: "llama3.2", apiKey: "", baseURL: "http://localhost:11434/v1/",
                         session: StubURLProtocol.session())
        _ = try await c.complete(request)
        let cap = try XCTUnwrap(StubURLProtocol.captured.first)
        XCTAssertEqual(cap.request.url?.absoluteString, "http://localhost:11434/v1/chat/completions")
        XCTAssertNil(cap.header("Authorization"))
    }

    func testOptionalParameterFallbackOn400() async throws {
        StubURLProtocol.reset([
            .init(status: 400, body: "{\"error\":{\"message\":\"Unknown parameter: temperature\"}}"),
            .init(body: openAIOK("{}")),
            .init(body: openAIOK("{}"))
        ])
        let c = client("gemini", model: "gemini-flash-lite-latest")
        _ = try await c.complete(AIRequest(task: .mood, system: "S", user: "U"))
        XCTAssertEqual(StubURLProtocol.captured.count, 2)
        let first = StubURLProtocol.captured[0].json
        XCTAssertNotNil(first["temperature"])
        XCTAssertNotNil(first["response_format"])
        let retry = StubURLProtocol.captured[1].json
        XCTAssertNil(retry["temperature"])
        XCTAssertNil(retry["response_format"])
        XCTAssertNil(retry["reasoning_effort"])
        XCTAssertTrue(c.isPlainMode)

        // Plain mode is remembered for this provider + model.
        _ = try await c.complete(AIRequest(task: .mood, system: "S", user: "U"))
        XCTAssertEqual(StubURLProtocol.captured.count, 3)
        XCTAssertNil(StubURLProtocol.captured[2].json["temperature"])
    }

    func testFailingPlainRetryIsReported() async {
        let c = client("mistral", model: "mistral-small-latest")
        // Mistral sends temperature: 422 -> plain retry -> 400 is reported.
        StubURLProtocol.reset([
            .init(status: 422, body: "{\"error\":{\"message\":\"bad\"}}"),
            .init(status: 400, body: "{\"error\":{\"message\":\"still bad\"}}")
        ])
        do {
            _ = try await c.complete(request)
            XCTFail("expected error")
        } catch {
            let e = AIError.from(error)
            XCTAssertEqual(e.kind, .badResponse)
            XCTAssertEqual(e.detail, "still bad")
        }
        XCTAssertEqual(StubURLProtocol.captured.count, 2)
    }

    func testOpenAIRefusal() async {
        StubURLProtocol.reset([.init(body: "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":\"no\"}}]}")])
        do {
            _ = try await client("openai", model: "gpt-5-mini").complete(request)
            XCTFail("expected refusal")
        } catch {
            XCTAssertEqual(AIError.from(error).kind, .refused)
        }
    }

    // MARK: Model list

    func testAnthropicModelList() async throws {
        StubURLProtocol.reset([.init(body: "{\"data\":[{\"id\":\"claude-sonnet-5-5\"},{\"id\":\"claude-haiku-5-5\"}],\"has_more\":false}")])
        let ids = try await client("anthropic", model: "claude-haiku-5-5").listModels()
        XCTAssertEqual(ids, ["claude-haiku-5-5", "claude-sonnet-5-5"])
        let c = try XCTUnwrap(StubURLProtocol.captured.first)
        XCTAssertEqual(c.request.httpMethod, "GET")
        XCTAssertEqual(c.header("x-api-key"), "test-key")
        XCTAssertEqual(c.header("anthropic-version"), "2023-06-01")
        XCTAssertTrue(c.request.url?.absoluteString.hasPrefix("https://api.anthropic.com/v1/models") ?? false)
    }

    func testOpenAIModelListSortsChatModelsFirst() async throws {
        StubURLProtocol.reset([.init(body: "{\"data\":[{\"id\":\"whisper-1\"},{\"id\":\"llama-3\"},{\"id\":\"gpt-5-mini\"},{\"id\":\"models/gemini-flash-latest\"},{\"id\":\"text-embedding-3\"}]}")])
        let ids = try await client("openai", model: "gpt-5-mini").listModels()
        XCTAssertEqual(ids, ["gemini-flash-latest", "gpt-5-mini", "llama-3", "text-embedding-3", "whisper-1"])
        XCTAssertEqual(StubURLProtocol.captured.first?.header("Authorization"), "Bearer test-key")
    }
}
