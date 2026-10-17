import Foundation

public struct TipsRewrite: Equatable, Identifiable {
    public var id: String
    public var label: String
    public var text: String

    public init(id: String, label: String, text: String) {
        self.id = id
        self.label = label
        self.text = text
    }
}

public struct TipsExplanation: Equatable {
    public var from: String
    public var to: String
    public var why: String

    public init(from: String, to: String, why: String) {
        self.from = from
        self.to = to
        self.why = why
    }
}

public struct TipsEmotion: Equatable {
    public var level: Int
    public var label: String
    public var reason: String
    public var calmer: String

    public init(level: Int = 0, label: String = "", reason: String = "", calmer: String = "") {
        self.level = level
        self.label = label
        self.reason = reason
        self.calmer = calmer
    }
}

public struct TipsResult: Equatable {
    public var rewrites: [TipsRewrite]
    public var explanations: [TipsExplanation]
    public var tips: [String]
    public var emotion: TipsEmotion

    public init(rewrites: [TipsRewrite] = [], explanations: [TipsExplanation] = [], tips: [String] = [], emotion: TipsEmotion = TipsEmotion()) {
        self.rewrites = rewrites
        self.explanations = explanations
        self.tips = tips
        self.emotion = emotion
    }
}

public struct MoodResult: Equatable {
    public var level: Int
    public var reason: String

    public init(level: Int, reason: String) {
        self.level = level
        self.reason = reason
    }
}

/// JSON parsing for tips and mood answers (SPEC §6.5).
public enum ResponseParsing {
    /// Removes code fences and returns the object between the first `{` and the last `}`.
    public static func extractJSONObject(_ text: String) throws -> [String: Any] {
        var t = text.replacingOccurrences(of: "```json", with: "")
        t = t.replacingOccurrences(of: "```JSON", with: "")
        t = t.replacingOccurrences(of: "```", with: "")
        guard let open = t.firstIndex(of: "{"), let close = t.lastIndex(of: "}"), open < close else {
            throw AIError(.badResponse)
        }
        let slice = String(t[open...close])
        guard let data = slice.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else {
            throw AIError(.badResponse)
        }
        return obj
    }

    static func string(_ v: Any?) -> String {
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return ""
    }

    static func level(_ v: Any?) -> Int {
        var l = 0
        if let n = v as? NSNumber {
            l = n.intValue
        } else if let s = v as? String {
            l = Int(s.trimmingCharacters(in: .whitespaces)) ?? Int(Double(s) ?? 0)
        }
        return min(max(l, 0), 3)
    }

    public static func parseTips(_ text: String) throws -> TipsResult {
        let obj = try extractJSONObject(text)
        var result = TipsResult()
        if let rewrites = obj["rewrites"] as? [[String: Any]] {
            result.rewrites = rewrites.compactMap { r in
                let t = string(r["text"])
                if t.isEmpty { return nil }
                return TipsRewrite(id: string(r["id"]), label: string(r["label"]), text: t)
            }
        }
        if let ex = obj["explanations"] as? [[String: Any]] {
            result.explanations = ex.map { TipsExplanation(from: string($0["from"]), to: string($0["to"]), why: string($0["why"])) }
        }
        if let tips = obj["tips"] as? [Any] {
            result.tips = tips.map { string($0) }.filter { !$0.isEmpty }
        }
        if let e = obj["emotion"] as? [String: Any] {
            result.emotion = TipsEmotion(level: level(e["level"]), label: string(e["label"]),
                                         reason: string(e["reason"]), calmer: string(e["calmer"]))
        }
        return result
    }

    public static func parseMood(_ text: String) throws -> MoodResult {
        let obj = try extractJSONObject(text)
        return MoodResult(level: level(obj["level"]), reason: string(obj["reason"]))
    }
}
