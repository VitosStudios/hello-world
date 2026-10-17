import Foundation

/// Long-press alternatives (SPEC §4.4) and character helpers.
public enum KeyAlternatives {
    /// Uppercases a key character (`ß` → `ẞ`, everything else via `uppercased()`
    /// as long as it stays a single character).
    public static func uppercase(_ s: String) -> String {
        if s == "ß" { return "ẞ" }
        let u = s.uppercased()
        return u.count == s.count ? u : s
    }

    /// The character a key types (bottom-row punctuation replaced for pinyin).
    public static func character(for key: KeyItem, bottomPunctuation: [String: String]) -> String {
        if key.area == .bottom, let mapped = bottomPunctuation[key.key] {
            return mapped
        }
        return key.key
    }

    /// Alternatives = optional top-row digit + accents[lowercase key], upper-cased
    /// when shift/caps is active.
    public static func alternatives(for key: KeyItem, page: KeyboardPage, character: String,
                                    accents: [String: [String]], accentsEnabled: Bool,
                                    digitsEnabled: Bool, uppercase upper: Bool) -> [String] {
        guard key.isCharacter else { return [] }
        var result: [String] = []
        if digitsEnabled && page == .letters && key.area == .letters && key.row == 0 && key.column < 10 {
            result.append(String((key.column + 1) % 10))
        }
        if accentsEnabled {
            let lower = character.lowercased()
            if let list = accents[lower] ?? accents[character] {
                for alt in list where !result.contains(alt) {
                    result.append(alt)
                }
            }
        }
        if upper {
            result = result.map { uppercase($0) }
        }
        return result
    }
}

// MARK: - Field classification (SPEC §4.8)

public enum FieldKind: Equatable {
    case normal
    case secure
    case number
    case emailOrURL
    case incognito

    /// Whether the AI may see this field.
    public func aiAllowed(incognitoNoAI: Bool) -> Bool {
        switch self {
        case .normal: return true
        case .secure, .number, .emailOrURL: return false
        case .incognito: return !incognitoNoAI
        }
    }

    public var autoCapitalizeAllowed: Bool {
        switch self {
        case .normal, .incognito: return true
        case .secure, .number, .emailOrURL: return false
        }
    }

    public var startPage: KeyboardPage {
        self == .number ? .symbols1 : .letters
    }
}

/// Platform-neutral description of an editor field.
public struct FieldTraits: Equatable {
    public enum KeyboardKind: Equatable {
        case text, number, phone, decimal, email, url, webSearch, date
    }

    public enum ContentKind: Equatable {
        case none, password, oneTimeCode, email, url, username, phone, creditCard, date
    }

    public var isSecure: Bool
    public var keyboard: KeyboardKind
    public var content: ContentKind
    public var incognito: Bool

    public init(isSecure: Bool = false, keyboard: KeyboardKind = .text, content: ContentKind = .none, incognito: Bool = false) {
        self.isSecure = isSecure
        self.keyboard = keyboard
        self.content = content
        self.incognito = incognito
    }

    public var kind: FieldKind {
        if isSecure { return .secure }
        switch content {
        case .password, .oneTimeCode, .creditCard: return .secure
        case .phone, .date: return .number
        case .email, .url, .username: return .emailOrURL
        case .none: break
        }
        switch keyboard {
        case .number, .phone, .decimal, .date: return .number
        case .email, .url, .webSearch: return .emailOrURL
        case .text: break
        }
        return incognito ? .incognito : .normal
    }
}
