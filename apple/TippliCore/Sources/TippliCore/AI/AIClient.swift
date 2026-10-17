import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Anthropic per-model request profile (SPEC §6.2, prefix match on the model id).
public struct AnthropicProfile: Equatable {
    public var thinking: String?
    public var effort: Bool
    public var fallbacks: Bool
    public var betaHeader: String?

    public static let fallbackBeta = "server-side-fallback-2026-07-01"

    public static func profile(for model: String) -> AnthropicProfile {
        if model.hasPrefix("claude-opus-5") || model.hasPrefix("claude-fable-5") {
            return AnthropicProfile(thinking: nil, effort: true, fallbacks: true, betaHeader: fallbackBeta)
        }
        if model.hasPrefix("claude-sonnet-5-5") {
            return AnthropicProfile(thinking: "between_tools", effort: true, fallbacks: true, betaHeader: fallbackBeta)
        }
        if model.hasPrefix("claude-haiku-5-5") {
            return AnthropicProfile(thinking: "disabled", effort: true, fallbacks: false, betaHeader: nil)
        }
        return AnthropicProfile(thinking: nil, effort: false, fallbacks: false, betaHeader: nil)
    }
}

/// HTTP client for one provider + model + key (SPEC §6.2, §6.3, §6.6, §6.7).
public final class AIClient: AICompleting, @unchecked Sendable {
    public let preset: ProviderPreset
    public let model: String
    public let apiKey: String
    public let baseURL: String
    private let transport: HTTPTransport

    public static let anthropicVersion = "2023-06-01"

    // "Plain mode" memory for the OpenAI optional-parameter fallback (process lifetime).
    private static let plainLock = NSLock()
    private static var plainModes = Set<String>()

    public init(preset: ProviderPreset, model: String, apiKey: String, baseURL: String? = nil, session: URLSession = .shared) {
        self.preset = preset
        self.model = model.trimmingCharacters(in: .whitespacesAndNewlines)
        self.apiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        var base = (baseURL ?? preset.baseUrl).trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        self.baseURL = base
        self.transport = HTTPTransport(session: session)
    }

    // MARK: Plain mode

    private var plainKey: String { preset.id + "|" + model }

    public var isPlainMode: Bool {
        AIClient.plainLock.lock()
        defer { AIClient.plainLock.unlock() }
        return AIClient.plainModes.contains(plainKey)
    }

    private func markPlainMode() {
        AIClient.plainLock.lock()
        AIClient.plainModes.insert(plainKey)
        AIClient.plainLock.unlock()
    }

    /// Test hook: forget all plain-mode decisions.
    public static func resetPlainModes() {
        plainLock.lock()
        plainModes.removeAll()
        plainLock.unlock()
    }

    // MARK: Completion

    public func complete(_ request: AIRequest) async throws -> String {
        if preset.keyRequired && apiKey.isEmpty {
            throw AIError(.noKey)
        }
        do {
            return try await withAITimeout(request.task.timeout) { [self] in
                switch self.preset.apiProtocol {
                case .anthropic:
                    return try await self.completeAnthropic(request)
                case .openai:
                    return try await self.completeOpenAI(request)
                }
            }
        } catch {
            throw AIError.from(error)
        }
    }

    // MARK: Anthropic

    func anthropicRequest(_ r: AIRequest) throws -> URLRequest {
        let profile = AnthropicProfile.profile(for: model)
        var body: [String: Any] = [
            "model": model,
            "max_tokens": r.task.maxTokens,
            "system": r.system,
            "messages": [["role": "user", "content": r.user]]
        ]
        if let thinking = profile.thinking {
            body["thinking"] = ["type": thinking]
        }
        if profile.effort {
            body["output_config"] = ["effort": r.task.effort]
        }
        if profile.fallbacks {
            body["fallbacks"] = "default"
        }
        var req = try makeRequest(path: "/messages", method: "POST", body: body, timeout: r.task.timeout)
        req.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        req.setValue(AIClient.anthropicVersion, forHTTPHeaderField: "anthropic-version")
        if let beta = profile.betaHeader {
            req.setValue(beta, forHTTPHeaderField: "anthropic-beta")
        }
        return req
    }

    private func completeAnthropic(_ r: AIRequest) async throws -> String {
        let req = try anthropicRequest(r)
        let (data, response) = try await transport.send(req)
        try checkStatus(response, data: data)
        return try AIClient.parseAnthropic(data)
    }

    static func parseAnthropic(_ data: Data) throws -> String {
        guard let obj = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else {
            throw AIError(.badResponse)
        }
        if let stop = obj["stop_reason"] as? String, stop == "refusal" {
            throw AIError(.refused)
        }
        let blocks = obj["content"] as? [[String: Any]] ?? []
        var text = ""
        for b in blocks where (b["type"] as? String) == "text" {
            text += (b["text"] as? String) ?? ""
        }
        return text
    }

    // MARK: OpenAI-compatible

    func openAIRequest(_ r: AIRequest, plain: Bool) throws -> (URLRequest, Bool) {
        var body: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "system", "content": r.system],
                ["role": "user", "content": r.user]
            ]
        ]
        var n = r.task.maxTokens
        if preset.reasoningEffort != nil { n = max(n, 4000) }
        body[preset.maxTokensField ?? "max_tokens"] = n
        var usedOptional = false
        if !plain {
            if preset.sendTemperature {
                body["temperature"] = 0.2
                usedOptional = true
            }
            if let effort = preset.reasoningEffort, model.hasPrefix("gpt-5") || model.hasPrefix("o") {
                body["reasoning_effort"] = effort
                usedOptional = true
            }
            if r.task.expectsJSON && preset.jsonMode {
                body["response_format"] = ["type": "json_object"]
                usedOptional = true
            }
        }
        var req = try makeRequest(path: "/chat/completions", method: "POST", body: body, timeout: r.task.timeout)
        applyOpenAIHeaders(&req)
        return (req, usedOptional)
    }

    private func applyOpenAIHeaders(_ req: inout URLRequest) {
        if !apiKey.isEmpty {
            req.setValue("Bearer " + apiKey, forHTTPHeaderField: "Authorization")
        }
        for (k, v) in preset.extraHeaders {
            req.setValue(v, forHTTPHeaderField: k)
        }
    }

    private func completeOpenAI(_ r: AIRequest) async throws -> String {
        let (req, usedOptional) = try openAIRequest(r, plain: isPlainMode)
        let (data, response) = try await transport.send(req)
        if usedOptional && (response.statusCode == 400 || response.statusCode == 422) {
            markPlainMode()
            let (retry, _) = try openAIRequest(r, plain: true)
            let (data2, response2) = try await transport.send(retry)
            try checkStatus(response2, data: data2)
            return try AIClient.parseOpenAI(data2)
        }
        try checkStatus(response, data: data)
        return try AIClient.parseOpenAI(data)
    }

    static func parseOpenAI(_ data: Data) throws -> String {
        guard let obj = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any],
              let choices = obj["choices"] as? [[String: Any]], let first = choices.first else {
            throw AIError(.badResponse)
        }
        let message = first["message"] as? [String: Any] ?? [:]
        if let refusal = message["refusal"] as? String, !refusal.isEmpty {
            throw AIError(.refused)
        }
        if let finish = first["finish_reason"] as? String, finish == "content_filter" {
            throw AIError(.refused)
        }
        if let s = message["content"] as? String {
            return s
        }
        if let parts = message["content"] as? [[String: Any]] {
            return parts.compactMap { $0["text"] as? String }.joined()
        }
        return ""
    }

    // MARK: Model list (SPEC §6.6)

    public func listModels() async throws -> [String] {
        if preset.keyRequired && apiKey.isEmpty {
            throw AIError(.noKey)
        }
        do {
            return try await listModelsUnmapped()
        } catch {
            throw AIError.from(error)
        }
    }

    private func listModelsUnmapped() async throws -> [String] {
        var req: URLRequest
        switch preset.apiProtocol {
        case .anthropic:
            req = try makeRequest(path: "/models?limit=1000", method: "GET", body: nil, timeout: 20)
            req.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            req.setValue(AIClient.anthropicVersion, forHTTPHeaderField: "anthropic-version")
        case .openai:
            req = try makeRequest(path: "/models", method: "GET", body: nil, timeout: 20)
            applyOpenAIHeaders(&req)
        }
        let (data, response) = try await transport.send(req)
        try checkStatus(response, data: data)
        return AIClient.parseModelList(data)
    }

    static func parseModelList(_ data: Data) -> [String] {
        guard let obj = try? JSONSerialization.jsonObject(with: data, options: []) else { return [] }
        var items: [[String: Any]] = []
        if let dict = obj as? [String: Any] {
            items = (dict["data"] as? [[String: Any]]) ?? (dict["models"] as? [[String: Any]]) ?? []
        } else if let arr = obj as? [[String: Any]] {
            items = arr
        }
        var ids: [String] = []
        for item in items {
            guard var id = (item["id"] as? String) ?? (item["name"] as? String) else { continue }
            if id.hasPrefix("models/") { id = String(id.dropFirst("models/".count)) }
            if !ids.contains(id) { ids.append(id) }
        }
        return sortModels(ids)
    }

    /// Claude / GPT / Gemini chat models first, then the rest; alphabetical within a group.
    public static func sortModels(_ ids: [String]) -> [String] {
        let nonChat = ["embed", "tts", "whisper", "dall-e", "image", "audio", "realtime", "moderation",
                       "transcribe", "search", "vision-preview", "babbage", "davinci"]
        func rank(_ id: String) -> Int {
            let l = id.lowercased()
            if nonChat.contains(where: { l.contains($0) }) { return 2 }
            if l.contains("claude") || l.contains("gpt") || l.contains("gemini") { return 0 }
            return 1
        }
        return ids.sorted { a, b in
            let ra = rank(a)
            let rb = rank(b)
            if ra != rb { return ra < rb }
            return a.localizedStandardCompare(b) == .orderedAscending
        }
    }

    // MARK: Helpers

    private func makeRequest(path: String, method: String, body: [String: Any]?, timeout: TimeInterval) throws -> URLRequest {
        guard let url = URL(string: baseURL + path) else {
            throw AIError(.network, detail: "Ungültige Server-Adresse: \(baseURL)")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeout
        if let body = body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
            req.setValue("application/json", forHTTPHeaderField: "content-type")
        }
        return req
    }

    private func checkStatus(_ response: HTTPURLResponse, data: Data) throws {
        let status = response.statusCode
        if (200..<300).contains(status) { return }
        throw AIError.fromHTTP(status: status, detail: AIClient.errorMessage(from: data))
    }

    static func errorMessage(from data: Data) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: data, options: []) else {
            let s = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let s = s, !s.isEmpty { return String(s.prefix(300)) }
            return nil
        }
        var dict: [String: Any]? = obj as? [String: Any]
        if dict == nil, let arr = obj as? [[String: Any]] { dict = arr.first }
        guard let d = dict else { return nil }
        if let err = d["error"] as? [String: Any], let m = err["message"] as? String { return m }
        if let m = d["error"] as? String { return m }
        if let m = d["message"] as? String { return m }
        return nil
    }
}
