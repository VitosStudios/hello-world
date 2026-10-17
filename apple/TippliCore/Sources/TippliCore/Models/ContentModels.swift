import Foundation

// MARK: - languages.json

public struct Language: Decodable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let short: String
    public let keyboard: String
    public let promptName: String
    public let rules: String
    public let pinyin: Bool

    private enum CodingKeys: String, CodingKey {
        case id, name, short, keyboard, promptName, rules, pinyin
    }

    public init(id: String, name: String, short: String, keyboard: String, promptName: String, rules: String, pinyin: Bool = false) {
        self.id = id
        self.name = name
        self.short = short
        self.keyboard = keyboard
        self.promptName = promptName
        self.rules = rules
        self.pinyin = pinyin
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        short = try c.decode(String.self, forKey: .short)
        keyboard = try c.decode(String.self, forKey: .keyboard)
        promptName = try c.decode(String.self, forKey: .promptName)
        rules = try c.decodeIfPresent(String.self, forKey: .rules) ?? ""
        pinyin = try c.decodeIfPresent(Bool.self, forKey: .pinyin) ?? false
    }
}

public struct TranslateTarget: Decodable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let promptName: String
}

public struct LanguagesFile: Decodable {
    public let languages: [Language]
    public let translateTargets: [TranslateTarget]

    public func language(_ id: String) -> Language? {
        languages.first { $0.id == id }
    }
}

// MARK: - providers.json

public enum APIProtocol: String, Decodable {
    case anthropic
    case openai
}

public struct ModelOption: Decodable, Equatable, Identifiable {
    public let id: String
    public let label: String
}

public struct ProviderPreset: Decodable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let apiProtocol: APIProtocol
    public let baseUrl: String
    public let baseUrlEditable: Bool
    public let defaultModel: String
    public let models: [ModelOption]
    public let keyUrl: String
    public let keyHint: String
    public let keyRequired: Bool
    public let jsonMode: Bool
    public let maxTokensField: String?
    public let sendTemperature: Bool
    public let reasoningEffort: String?
    public let extraHeaders: [String: String]

    private enum CodingKeys: String, CodingKey {
        case id, name
        case apiProtocol = "protocol"
        case baseUrl, baseUrlEditable, defaultModel, models, keyUrl, keyHint, keyRequired
        case jsonMode, maxTokensField, sendTemperature, reasoningEffort, extraHeaders
    }

    public init(id: String, name: String, apiProtocol: APIProtocol, baseUrl: String, baseUrlEditable: Bool = false,
                defaultModel: String, models: [ModelOption] = [], keyUrl: String = "", keyHint: String = "",
                keyRequired: Bool = true, jsonMode: Bool = false, maxTokensField: String? = nil,
                sendTemperature: Bool = false, reasoningEffort: String? = nil, extraHeaders: [String: String] = [:]) {
        self.id = id
        self.name = name
        self.apiProtocol = apiProtocol
        self.baseUrl = baseUrl
        self.baseUrlEditable = baseUrlEditable
        self.defaultModel = defaultModel
        self.models = models
        self.keyUrl = keyUrl
        self.keyHint = keyHint
        self.keyRequired = keyRequired
        self.jsonMode = jsonMode
        self.maxTokensField = maxTokensField
        self.sendTemperature = sendTemperature
        self.reasoningEffort = reasoningEffort
        self.extraHeaders = extraHeaders
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        apiProtocol = try c.decode(APIProtocol.self, forKey: .apiProtocol)
        baseUrl = try c.decode(String.self, forKey: .baseUrl)
        baseUrlEditable = try c.decodeIfPresent(Bool.self, forKey: .baseUrlEditable) ?? false
        defaultModel = try c.decode(String.self, forKey: .defaultModel)
        models = try c.decodeIfPresent([ModelOption].self, forKey: .models) ?? []
        keyUrl = try c.decodeIfPresent(String.self, forKey: .keyUrl) ?? ""
        keyHint = try c.decodeIfPresent(String.self, forKey: .keyHint) ?? ""
        keyRequired = try c.decodeIfPresent(Bool.self, forKey: .keyRequired) ?? true
        jsonMode = try c.decodeIfPresent(Bool.self, forKey: .jsonMode) ?? false
        maxTokensField = try c.decodeIfPresent(String.self, forKey: .maxTokensField)
        sendTemperature = try c.decodeIfPresent(Bool.self, forKey: .sendTemperature) ?? false
        reasoningEffort = try c.decodeIfPresent(String.self, forKey: .reasoningEffort)
        extraHeaders = try c.decodeIfPresent([String: String].self, forKey: .extraHeaders) ?? [:]
    }

    /// Short display name for the toolbar ("Claude", "ChatGPT", "Gemini", …):
    /// the preset name up to the first " (" or " /".
    public var shortName: String {
        var s = name
        for sep in [" (", " /"] {
            if let r = s.range(of: sep) {
                s = String(s[s.startIndex..<r.lowerBound])
            }
        }
        return s.trimmingCharacters(in: .whitespaces)
    }
}

public struct ProvidersFile: Decodable {
    public let providers: [ProviderPreset]

    public func provider(_ id: String) -> ProviderPreset? {
        providers.first { $0.id == id }
    }
}

// MARK: - prompts/prompt-parts.json

public struct RewriteVariant: Decodable, Equatable, Identifiable {
    public let id: String
    public let label: String
    public let instruction: String
}

public struct PromptParts: Decodable {
    public let uiLanguage: String
    public let strengthRules: [String: String]
    public let rewriteVariants: [RewriteVariant]
    public let sections: [String: String]
    public let testSegment: String
}

// MARK: - emoji.json

public struct EmojiCategory: Decodable, Identifiable {
    public let id: String
    public let name: String
    public let icon: String
    public let emoji: [String]
}

public struct EmojiFile: Decodable {
    public let categories: [EmojiCategory]
}

// MARK: - theme.json

public struct ThemeMetrics: Decodable {
    public let toolbarHeight: Double
    public let rowHeight: [String: Double]
    public let keyGap: Double
    public let hexGap: Double
    public let keyCornerRadius: Double
    public let hexCornerRadius: Double
    public let keyLabelSize: Double
    public let specialLabelSize: Double
    public let sidePadding: Double
    public let bottomPadding: Double
}

public struct ThemeFile: Decodable {
    public let light: [String: String]
    public let dark: [String: String]
    public let metrics: ThemeMetrics
}

// MARK: - settings-defaults.json

public struct SettingsDefaultsFile: Decodable {
    public let settings: [String: JSONValue]
    public let allowedValues: [String: JSONValue]
}
