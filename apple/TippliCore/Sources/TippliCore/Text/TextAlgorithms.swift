import Foundation

// SPEC §5.2–5.6. Mirrors shared/tools/reference_engine.py; verified against
// shared/tests/engine-cases.json. All indices are on Characters (grapheme
// clusters); every cut point is a whitespace or line boundary.

extension Unicode.Scalar {
    var tp_isLetter: Bool {
        switch properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
            return true
        default:
            return false
        }
    }

    var tp_isLetterOrDigit: Bool {
        switch properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .decimalNumber, .letterNumber, .otherNumber:
            return true
        default:
            return false
        }
    }

    var tp_isPunctuation: Bool {
        switch properties.generalCategory {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation:
            return true
        default:
            return false
        }
    }
}

extension Character {
    /// Letter or digit (Unicode general category L* or N*) of the base scalar.
    public var tp_isLetterOrDigit: Bool {
        guard let s = unicodeScalars.first else { return false }
        return s.tp_isLetterOrDigit
    }

    public var tp_isLetter: Bool {
        guard let s = unicodeScalars.first else { return false }
        return s.tp_isLetter
    }

    public var tp_isPunctuation: Bool {
        guard let s = unicodeScalars.first else { return false }
        return s.tp_isPunctuation
    }

    public var tp_isWhitespace: Bool { isWhitespace }

    public var tp_isLineBreak: Bool {
        self == "\n" || self == "\r\n" || self == "\r" || self == "\u{2028}" || self == "\u{2029}"
    }
}

public enum TextAlgorithms {
    /// `. ! ? … 。 ！ ？`
    public static let sentenceEnds: Set<Character> = [".", "!", "?", "…", "。", "！", "？"]
    static let quotePairs: [(Character, Character)] = [
        ("\"", "\""), ("„", "“"), ("“", "”"), ("«", "»"), ("'", "'"), ("‘", "’")
    ]
    static let wordExtra: Set<Character> = ["'", "’", "-", "_"]
    static let closers: Set<Character> = [")", "]", "}", "\"", "'", "»", "”", "’"]

    public static let maxSegment = 400
    public static let maxContext = 600

    public static func isSentenceEnd(_ c: Character) -> Bool {
        sentenceEnds.contains(c)
    }

    /// True if the text contains a sentence end or a line break (trigger rule §4.3/§5.3).
    public static func containsSentenceBoundary(_ text: String) -> Bool {
        text.contains { sentenceEnds.contains($0) || $0.tp_isLineBreak }
    }

    static func isWordChar(_ c: Character) -> Bool {
        c.tp_isLetterOrDigit || wordExtra.contains(c)
    }

    static func hasLetter(_ s: String) -> Bool {
        s.unicodeScalars.contains { $0.tp_isLetter }
    }

    static func trim(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func leadingWhitespace(_ s: String) -> String {
        String(s.prefix(while: { $0.isWhitespace }))
    }

    static func trailingWhitespace(_ s: String) -> String {
        var chars: [Character] = []
        for c in s.reversed() {
            if c.isWhitespace { chars.append(c) } else { break }
        }
        return String(chars.reversed())
    }

    static func dropTrailingWhitespace(_ s: String) -> String {
        var t = s
        while let last = t.last, last.isWhitespace { t.removeLast() }
        return t
    }

    // MARK: 5.2 selectSegment

    public struct Segment: Equatable {
        public var context: String
        public var segment: String
        public var trailing: String

        public init(context: String, segment: String, trailing: String) {
            self.context = context
            self.segment = segment
            self.trailing = trailing
        }
    }

    public static func selectSegment(_ before: String, explicit: Bool, pinyin: Bool,
                                     maxSegment: Int = TextAlgorithms.maxSegment,
                                     maxContext: Int = TextAlgorithms.maxContext) -> Segment? {
        let chars = Array(before)
        let n = chars.count
        var p = 0
        if let lastBreak = chars.lastIndex(where: { $0.tp_isLineBreak }) {
            p = lastBreak + 1
        }
        var end = n
        var trailing = ""
        let inProgress = n > 0 && chars[n - 1].tp_isLetterOrDigit
        if !explicit && !pinyin && inProgress {
            var k = -1
            var i = n - 1
            while i >= 0 {
                if chars[i].isWhitespace { k = i; break }
                i -= 1
            }
            if k < p { return nil }
            end = k + 1
            trailing = String(chars[end..<n])
        }
        var segStart = p
        if end - p > maxSegment {
            let window = end - maxSegment
            var found: Int? = nil
            var i = window
            while i < end {
                if chars[i].isWhitespace && i - 1 >= 0 && sentenceEnds.contains(chars[i - 1]) {
                    found = i + 1
                    break
                }
                i += 1
            }
            if found == nil {
                i = window
                while i < end {
                    if chars[i].isWhitespace {
                        found = i + 1
                        break
                    }
                    i += 1
                }
            }
            segStart = found ?? window
        }
        let segment = String(chars[segStart..<end])
        if !hasLetter(segment) { return nil }
        var ctxStart = max(0, segStart - maxContext)
        if ctxStart > 0 {
            var i = ctxStart
            while i < segStart {
                if chars[i].isWhitespace {
                    ctxStart = i + 1
                    break
                }
                i += 1
            }
        }
        let context = String(chars[ctxStart..<segStart])
        return Segment(context: context, segment: segment, trailing: trailing)
    }

    // MARK: 5.4 cleanOutput

    public static func cleanOutput(segment: String, output: String, pinyin: Bool) -> String? {
        var t = output
        if let r = t.range(of: "<segment>") {
            t = String(t[r.upperBound...])
        }
        if let r = t.range(of: "</segment>") {
            t = String(t[..<r.lowerBound])
        }
        t = trim(t)
        if t.hasPrefix("```") {
            if let nl = t.firstIndex(where: { $0.tp_isLineBreak }) {
                t = String(t[t.index(after: nl)...])
            } else {
                t = String(t.dropFirst(3))
            }
            if t.hasSuffix("```") {
                t = String(t.dropLast(3))
            }
            t = trim(t)
        }
        let sTrim = trim(segment)
        for (open, close) in quotePairs {
            if t.count >= 2, t.first == open, t.last == close, sTrim.first != open {
                t = trim(String(t.dropFirst().dropLast()))
                break
            }
        }
        if t.isEmpty { return nil }
        if t.range(of: "<context>") != nil || t.range(of: "</context>") != nil { return nil }
        let segLen = sTrim.unicodeScalars.count
        if !pinyin && segLen >= 12 {
            let ls = Double(segLen)
            let lt = Double(t.unicodeScalars.count)
            if lt > ls * 1.5 + 10 || lt < ls * 0.5 - 5 { return nil }
        }
        return leadingWhitespace(segment) + t + trailingWhitespace(segment)
    }

    // MARK: 5.4 computeEdit

    public struct Edit: Equatable {
        public var delete: String
        public var insert: String

        public init(delete: String, insert: String) {
            self.delete = delete
            self.insert = insert
        }
    }

    /// Length (in Characters) of the common prefix of two strings.
    public static func commonPrefixLength(_ a: String, _ b: String) -> Int {
        var p = 0
        var ia = a.startIndex
        var ib = b.startIndex
        while ia < a.endIndex && ib < b.endIndex && a[ia] == b[ib] {
            p += 1
            ia = a.index(after: ia)
            ib = b.index(after: ib)
        }
        return p
    }

    public static func computeEdit(segment: String, corrected: String, suffix: String) -> Edit? {
        if segment == corrected { return nil }
        let p = commonPrefixLength(segment, corrected)
        return Edit(delete: String(segment.dropFirst(p)) + suffix,
                    insert: String(corrected.dropFirst(p)) + suffix)
    }

    // MARK: 5.6 wordDelete

    public static func wordDelete(_ before: String) -> String {
        let chars = Array(before)
        let n = chars.count
        if n == 0 { return "" }
        if chars[n - 1].tp_isLineBreak { return String(chars[n - 1]) }
        var i = n
        while i > 0 && chars[i - 1].isWhitespace && !chars[i - 1].tp_isLineBreak { i -= 1 }
        if i == 0 || chars[i - 1].tp_isLineBreak { return String(chars[i..<n]) }
        var j = i
        if isWordChar(chars[i - 1]) {
            while j > 0 && isWordChar(chars[j - 1]) { j -= 1 }
        } else {
            while j > 0 && !chars[j - 1].isWhitespace && !chars[j - 1].tp_isLetterOrDigit { j -= 1 }
        }
        return String(chars[j..<n])
    }

    // MARK: 5.6 autoCapitalize

    public static func autoCapitalize(_ before: String) -> Bool {
        guard let last = before.last else { return true }
        if last.tp_isLineBreak { return true }
        if !last.isWhitespace { return false }
        let t = dropTrailingWhitespace(before)
        guard let tl = t.last else { return true }
        if tl.tp_isLineBreak { return true }
        return sentenceEnds.contains(tl)
    }

    // MARK: 5.6 doubleSpacePeriod

    public static func doubleSpacePeriod(_ before: String) -> Bool {
        let chars = Array(before.suffix(2))
        guard chars.count == 2, chars[1] == " " else { return false }
        let c = chars[0]
        return c.tp_isLetterOrDigit || closers.contains(c)
    }

    // MARK: 5.6 canApply

    public static func canApply(beforeNow: String, segment: String, trailing: String, typedSince: String) -> Bool {
        beforeNow.hasSuffix(segment + trailing + typedSince)
    }
}
