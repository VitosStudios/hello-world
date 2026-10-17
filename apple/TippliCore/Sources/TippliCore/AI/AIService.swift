import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Production glue between settings, keychain, prompts and the HTTP client.
public final class AIService: CorrectionAIProvider {
    public let settings: SettingsStore
    public let secrets: SecretStore
    public let resources: SharedResources
    public let prompts: PromptBuilder
    private let session: URLSession

    private var cachedClient: AIClient?
    private var cachedSignature = ""
    private var cachedConfigured: Bool?

    public init(settings: SettingsStore, secrets: SecretStore, resources: SharedResources = .shared,
                session: URLSession = .shared) {
        self.settings = settings
        self.secrets = secrets
        self.resources = resources
        self.prompts = PromptBuilder(resources: resources)
        self.session = session
    }

    /// Forget cached keys/clients (call after settings changed).
    public func reload() {
        cachedClient = nil
        cachedSignature = ""
        cachedConfigured = nil
    }

    public var preset: ProviderPreset { settings.providerPreset }
    public var model: String { settings.model(for: preset.id) }

    // MARK: CorrectionAIProvider

    public var isConfigured: Bool {
        if let c = cachedConfigured { return c }
        let p = preset
        let ok = !p.keyRequired || secrets.hasAPIKey(for: p.id)
        cachedConfigured = ok
        return ok
    }

    public func makeClient() -> AICompleting? {
        client()
    }

    /// The HTTP client for the current settings (nil when a key is required but missing).
    public func client() -> AIClient? {
        let p = preset
        let m = model
        let base = settings.baseURL(for: p)
        let signature = p.id + "|" + m + "|" + base
        if let c = cachedClient, signature == cachedSignature {
            return c
        }
        let key = secrets.apiKey(for: p.id)
        cachedConfigured = !p.keyRequired || !key.isEmpty
        if p.keyRequired && key.isEmpty { return nil }
        let c = AIClient(preset: p, model: m, apiKey: key, baseURL: base, session: session)
        cachedClient = c
        cachedSignature = signature
        return c
    }

    public func correctionRequest(context: String, segment: String) -> AIRequest {
        let lang = settings.activeLanguage
        return prompts.correction(language: lang, keyboardId: settings.keyboardId(for: lang),
                                  strength: settings.string(SettingKey.strength), context: context, segment: segment)
    }

    public func moodRequest(paragraph: String) -> AIRequest {
        prompts.mood(paragraph: paragraph)
    }

    // MARK: Tips, translation, connection test

    public func tipsRequest(text: String, history: [CorrectionRecord]) -> AIRequest {
        let lang = settings.activeLanguage
        return prompts.tips(text: text, language: lang,
                            enabledVariants: settings.stringArray(SettingKey.rewriteVariants),
                            sections: settings.tipsSections, history: history)
    }

    public func fetchTips(text: String, history: [CorrectionRecord]) async throws -> TipsResult {
        guard let c = client() else { throw AIError(.noKey) }
        let out = try await c.complete(tipsRequest(text: text, history: history))
        return try ResponseParsing.parseTips(out)
    }

    public func translate(text: String, target: TranslateTarget) async throws -> String {
        guard let c = client() else { throw AIError(.noKey) }
        let out = try await c.complete(prompts.translate(text: text, target: target))
        let t = out.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { throw AIError(.badResponse) }
        return t
    }

    /// "Verbindung testen": the correction prompt with the test segment.
    /// Returns the model's corrected test sentence.
    public func testConnection(preset p: ProviderPreset, model m: String, apiKey: String, baseURL: String) async throws -> String {
        let c = AIClient(preset: p, model: m, apiKey: apiKey, baseURL: baseURL, session: session)
        let lang = settings.activeLanguage
        let req = prompts.connectionTest(language: lang, keyboardId: settings.keyboardId(for: lang),
                                         strength: settings.string(SettingKey.strength))
        let out = try await c.complete(req)
        let t = out.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { throw AIError(.badResponse) }
        return t
    }

    public func listModels(preset p: ProviderPreset, apiKey: String, baseURL: String) async throws -> [String] {
        let c = AIClient(preset: p, model: settings.model(for: p.id), apiKey: apiKey, baseURL: baseURL, session: session)
        return try await c.listModels()
    }
}
