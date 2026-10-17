import Foundation

/// Setting keys (SPEC §8, identical on every platform).
public enum SettingKey {
    public static let provider = "provider"
    public static let customBaseUrl = "customBaseUrl"
    public static let aiEnabled = "aiEnabled"
    public static let trigger = "trigger"
    public static let pauseMs = "pauseMs"
    public static let strength = "strength"
    public static let waitOnSendMs = "waitOnSendMs"
    public static let moodWarning = "moodWarning"
    public static let languages = "languages"
    public static let activeLanguage = "activeLanguage"
    public static let keyboardMode = "keyboardMode"
    public static let longPressAccents = "longPressAccents"
    public static let longPressDigits = "longPressDigits"
    public static let longPressMs = "longPressMs"
    public static let keyHeight = "keyHeight"
    public static let haptics = "haptics"
    public static let sound = "sound"
    public static let autoCapitalize = "autoCapitalize"
    public static let doubleSpacePeriod = "doubleSpacePeriod"
    public static let numberRow = "numberRow"
    public static let theme = "theme"
    public static let tipsRewrites = "tipsRewrites"
    public static let tipsExplanations = "tipsExplanations"
    public static let tipsWriting = "tipsWriting"
    public static let tipsTranslate = "tipsTranslate"
    public static let tipsEmotion = "tipsEmotion"
    public static let rewriteVariants = "rewriteVariants"
    public static let translateTargets = "translateTargets"
    public static let incognitoNoAI = "incognitoNoAI"
    public static let emojiRecents = "emojiRecents"
    public static let desktopLiveCorrection = "desktopLiveCorrection"
    public static let desktopLaunchAtLogin = "desktopLaunchAtLogin"
    public static let desktopShowKeyboardOnStart = "desktopShowKeyboardOnStart"
    public static let desktopExcludedApps = "desktopExcludedApps"

    public static func model(_ providerId: String) -> String { "model." + providerId }
    public static func keyboard(_ languageId: String) -> String { "keyboard." + languageId }
    public static func apiKey(_ providerId: String) -> String { "apiKey." + providerId }
}

/// Typed access to the settings with defaults from settings-defaults.json.
public final class SettingsStore {
    public let defaults: UserDefaults
    public let resources: SharedResources

    public init(defaults: UserDefaults, resources: SharedResources = .shared) {
        self.defaults = defaults
        self.resources = resources
    }

    /// iOS: the App Group suite shared by the app and the keyboard extension.
    public static func appGroupDefaults(groupId: String?) -> UserDefaults {
        if let g = groupId, !g.isEmpty, !g.hasPrefix("$("), let d = UserDefaults(suiteName: g) {
            return d
        }
        return .standard
    }

    /// The App Group id from the bundle's Info.plist (`TippliAppGroup`).
    public static var bundleAppGroup: String? {
        Bundle.main.object(forInfoDictionaryKey: "TippliAppGroup") as? String
    }

    private var defaultValues: [String: JSONValue] { resources.settingsDefaults.settings }

    public func defaultValue(_ key: String) -> JSONValue? {
        defaultValues[key]
    }

    /// Allowed values for an enum-like setting (from settings-defaults.json).
    public func allowedValues(_ key: String) -> [String] {
        resources.settingsDefaults.allowedValues[key]?.stringArrayValue ?? []
    }

    /// Min/max for numeric settings.
    public func range(_ key: String) -> ClosedRange<Int>? {
        guard let o = resources.settingsDefaults.allowedValues[key]?.objectValue,
              let lo = o["min"]?.intValue, let hi = o["max"]?.intValue, lo <= hi else { return nil }
        return lo...hi
    }

    // MARK: Generic accessors

    public func bool(_ key: String) -> Bool {
        if let v = defaults.object(forKey: key) {
            if let b = v as? Bool { return b }
            if let n = v as? NSNumber { return n.boolValue }
        }
        return defaultValue(key)?.boolValue ?? false
    }

    public func int(_ key: String) -> Int {
        var value: Int
        if let v = defaults.object(forKey: key) as? NSNumber {
            value = v.intValue
        } else if let s = defaults.object(forKey: key) as? String, let i = Int(s) {
            value = i
        } else {
            value = defaultValue(key)?.intValue ?? 0
        }
        if let r = range(key) {
            value = min(max(value, r.lowerBound), r.upperBound)
        }
        return value
    }

    public func string(_ key: String) -> String {
        if let s = defaults.string(forKey: key) {
            let allowed = allowedValues(key)
            if allowed.isEmpty || allowed.contains(s) { return s }
        }
        return defaultValue(key)?.stringValue ?? ""
    }

    public func optionalString(_ key: String) -> String? {
        defaults.string(forKey: key)
    }

    public func stringArray(_ key: String) -> [String] {
        if let a = defaults.array(forKey: key) as? [String] { return a }
        return defaultValue(key)?.stringArrayValue ?? []
    }

    public func set(_ value: Any?, forKey key: String) {
        if let v = value {
            defaults.set(v, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    public func reset(_ key: String) {
        defaults.removeObject(forKey: key)
    }

    // MARK: Typed accessors

    public var providerId: String {
        get {
            let id = string(SettingKey.provider)
            return resources.provider(id) != nil ? id : (resources.providers.providers.first?.id ?? id)
        }
        set { self.set(newValue, forKey: SettingKey.provider) }
    }

    public var providerPreset: ProviderPreset {
        resources.provider(providerId) ?? resources.providers.providers[0]
    }

    /// `model.<providerId>`, default = the preset's defaultModel (from providers.json).
    public func model(for providerId: String) -> String {
        if let m = defaults.string(forKey: SettingKey.model(providerId)),
           !m.trimmingCharacters(in: .whitespaces).isEmpty {
            return m
        }
        return resources.provider(providerId)?.defaultModel ?? ""
    }

    public func setModel(_ model: String?, for providerId: String) {
        set(model, forKey: SettingKey.model(providerId))
    }

    /// The base URL for a provider (custom: `customBaseUrl`).
    public func baseURL(for preset: ProviderPreset) -> String {
        if preset.baseUrlEditable {
            let s = string(SettingKey.customBaseUrl).trimmingCharacters(in: .whitespacesAndNewlines)
            return s.isEmpty ? preset.baseUrl : s
        }
        return preset.baseUrl
    }

    public var trigger: String { string(SettingKey.trigger) }

    /// Enabled typing languages (unknown ids dropped; never empty).
    public var enabledLanguages: [Language] {
        let all = resources.languages
        var list = stringArray(SettingKey.languages).compactMap { all.language($0) }
        if list.isEmpty {
            list = (defaultValue(SettingKey.languages)?.stringArrayValue ?? []).compactMap { all.language($0) }
        }
        if list.isEmpty, let first = all.languages.first { list = [first] }
        return list
    }

    public var activeLanguage: Language {
        let langs = enabledLanguages
        let id = string(SettingKey.activeLanguage)
        return langs.first { $0.id == id } ?? langs[0]
    }

    /// Layout id for a language (`keyboard.<languageId>` override or the language default).
    public func keyboardId(for language: Language) -> String {
        if let k = defaults.string(forKey: SettingKey.keyboard(language.id)),
           resources.layouts.keyboards[k] != nil {
            return k
        }
        return language.keyboard
    }

    public var keyboardMode: KeyboardMode {
        get { KeyboardMode(rawValue: string(SettingKey.keyboardMode)) ?? .hex }
        set { self.set(newValue.rawValue, forKey: SettingKey.keyboardMode) }
    }

    public var tipsSections: TipsSections {
        TipsSections(rewrites: bool(SettingKey.tipsRewrites),
                     explanations: bool(SettingKey.tipsExplanations),
                     writing: bool(SettingKey.tipsWriting),
                     emotion: bool(SettingKey.tipsEmotion),
                     translate: bool(SettingKey.tipsTranslate))
    }

    public var translateTargets: [TranslateTarget] {
        let all = resources.languages.translateTargets
        return stringArray(SettingKey.translateTargets).compactMap { id in all.first { $0.id == id } }
    }

    public var emojiRecents: [String] {
        get { stringArray(SettingKey.emojiRecents) }
        set { self.set(Array(newValue.prefix(32)), forKey: SettingKey.emojiRecents) }
    }

    /// Moves an emoji to the front of the recents (max 32).
    public func noteEmojiUsed(_ emoji: String) {
        var r = emojiRecents
        r.removeAll { $0 == emoji }
        r.insert(emoji, at: 0)
        emojiRecents = r
    }

    /// Engine settings for the active language.
    public func engineSettings(fullAccess: Bool = true) -> EngineSettings {
        var s = EngineSettings()
        s.aiEnabled = bool(SettingKey.aiEnabled) && fullAccess
        s.trigger = CorrectionTrigger(rawValue: trigger) ?? .live
        s.pauseMs = int(SettingKey.pauseMs)
        s.waitOnSendMs = int(SettingKey.waitOnSendMs)
        s.moodWarning = bool(SettingKey.moodWarning)
        s.tipsEmotion = bool(SettingKey.tipsEmotion)
        s.pinyin = activeLanguage.pinyin
        return s
    }
}
