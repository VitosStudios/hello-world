import Foundation

/// A pair recorded by the correction engine for the tips explanations (§5.8).
public struct CorrectionRecord: Equatable {
    public let raw: String
    public let corrected: String

    public init(raw: String, corrected: String) {
        self.raw = raw
        self.corrected = corrected
    }
}

/// Which tips sections are enabled (settings tipsRewrites, tipsExplanations, …).
public struct TipsSections: Equatable {
    public var rewrites: Bool
    public var explanations: Bool
    public var writing: Bool
    public var emotion: Bool
    public var translate: Bool

    public init(rewrites: Bool = true, explanations: Bool = true, writing: Bool = true, emotion: Bool = true, translate: Bool = true) {
        self.rewrites = rewrites
        self.explanations = explanations
        self.writing = writing
        self.emotion = emotion
        self.translate = translate
    }
}

/// Builds the prompts of SPEC §6.4 from the shared templates.
public struct PromptBuilder {
    public let resources: SharedResources

    public init(resources: SharedResources) {
        self.resources = resources
    }

    /// Replaces `{{name}}` placeholders in a single pass (values are inserted
    /// literally and never re-scanned). Unknown placeholders stay as they are.
    public static func fill(_ template: String, _ values: [String: String]) -> String {
        var out = ""
        var i = template.startIndex
        while i < template.endIndex, let open = template.range(of: "{{", range: i..<template.endIndex) {
            out.append(contentsOf: template[i..<open.lowerBound])
            guard let close = template.range(of: "}}", range: open.upperBound..<template.endIndex) else {
                out.append(contentsOf: template[open.lowerBound..<template.endIndex])
                i = template.endIndex
                break
            }
            let name = String(template[open.upperBound..<close.lowerBound])
            if let v = values[name] {
                out.append(contentsOf: v)
            } else {
                out.append(contentsOf: template[open.lowerBound..<close.upperBound])
            }
            i = close.upperBound
        }
        if i < template.endIndex {
            out.append(contentsOf: template[i..<template.endIndex])
        }
        return out
    }

    /// The `keyboardLayout` placeholder for a language (its keyboard id may be overridden).
    public func keyboardLayoutDescription(keyboardId: String) -> String {
        resources.layouts.promptDescription(keyboardId: keyboardId)
    }

    public func correctionSystem(language: Language, keyboardId: String, strength: String) -> String {
        let parts = resources.promptParts
        let rule = parts.strengthRules[strength] ?? parts.strengthRules["normal"] ?? ""
        return PromptBuilder.fill(resources.prompt("correction-system.txt"), [
            "language": language.promptName,
            "languageRules": language.rules,
            "keyboardLayout": keyboardLayoutDescription(keyboardId: keyboardId),
            "strengthRule": rule
        ])
    }

    public func correction(language: Language, keyboardId: String, strength: String, context: String, segment: String) -> AIRequest {
        let user = PromptBuilder.fill(resources.prompt("correction-user.txt"), [
            "context": context,
            "segment": segment
        ])
        return AIRequest(task: .correction,
                         system: correctionSystem(language: language, keyboardId: keyboardId, strength: strength),
                         user: user)
    }

    /// Connection test: correction prompts with an empty context and the test segment.
    public func connectionTest(language: Language, keyboardId: String, strength: String) -> AIRequest {
        var r = correction(language: language, keyboardId: keyboardId, strength: strength,
                           context: "", segment: resources.promptParts.testSegment)
        r.task = .test
        return r
    }

    public func tips(text: String, language: Language, enabledVariants: [String], sections: TipsSections,
                     history: [CorrectionRecord]) -> AIRequest {
        let parts = resources.promptParts
        var variantTexts: [String] = []
        if sections.rewrites {
            for v in parts.rewriteVariants where enabledVariants.contains(v.id) {
                variantTexts.append("\(v.id) (\"\(v.label)\": \(v.instruction))")
            }
        }
        var disabled: [String] = []
        if !sections.rewrites, let n = parts.sections["rewrites"] { disabled.append(n) }
        if !sections.explanations, let n = parts.sections["explanations"] { disabled.append(n) }
        if !sections.writing, let n = parts.sections["writing"] { disabled.append(n) }
        if !sections.emotion, let n = parts.sections["emotion"] { disabled.append(n) }
        let system = PromptBuilder.fill(resources.prompt("tips-system.txt"), [
            "uiLanguage": parts.uiLanguage,
            "language": language.promptName,
            "variants": variantTexts.isEmpty ? "none" : variantTexts.joined(separator: ", "),
            "disabledSections": disabled.isEmpty ? "none" : disabled.joined(separator: ", ")
        ])
        let recent = history.suffix(20)
        let corrections = recent.isEmpty
            ? "(none)"
            : recent.map { "\($0.raw) -> \($0.corrected)" }.joined(separator: "\n")
        let user = PromptBuilder.fill(resources.prompt("tips-user.txt"), [
            "text": text,
            "corrections": corrections
        ])
        return AIRequest(task: .tips, system: system, user: user)
    }

    public func mood(paragraph: String) -> AIRequest {
        let system = PromptBuilder.fill(resources.prompt("mood-system.txt"), [
            "uiLanguage": resources.promptParts.uiLanguage
        ])
        return AIRequest(task: .mood, system: system, user: paragraph)
    }

    public func translate(text: String, target: TranslateTarget) -> AIRequest {
        let system = PromptBuilder.fill(resources.prompt("translate-system.txt"), [
            "targetLanguage": target.promptName
        ])
        return AIRequest(task: .translate, system: system, user: text)
    }
}
