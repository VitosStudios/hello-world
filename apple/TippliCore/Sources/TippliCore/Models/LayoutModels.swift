import Foundation

/// A key in layouts.json: either a plain string (width 1) or `{"k": key, "w": width}`.
public struct KeySpec: Decodable, Equatable {
    public let key: String
    public let width: Double

    public init(key: String, width: Double = 1) {
        self.key = key
        self.width = width
    }

    private enum CodingKeys: String, CodingKey {
        case k
        case w
    }

    public init(from decoder: Decoder) throws {
        if let single = try? decoder.singleValueContainer(), let s = try? single.decode(String.self) {
            key = s
            width = 1
            return
        }
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .k)
        width = try c.decodeIfPresent(Double.self, forKey: .w) ?? 1
    }

    /// Special keys start with `$` followed by a name (`"$"` alone is a symbol key).
    public var isSpecial: Bool { KeySpec.isSpecialKey(key) }

    public static func isSpecialKey(_ key: String) -> Bool {
        key.count > 1 && key.hasPrefix("$")
    }
}

public struct RowSpec: Decodable, Equatable {
    public let indent: Double
    public let keys: [KeySpec]

    private enum CodingKeys: String, CodingKey {
        case indent
        case keys
    }

    public init(indent: Double = 0, keys: [KeySpec]) {
        self.indent = indent
        self.keys = keys
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        indent = try c.decodeIfPresent(Double.self, forKey: .indent) ?? 0
        keys = try c.decode([KeySpec].self, forKey: .keys)
    }
}

public struct BoardSpec: Decodable, Equatable {
    public let width: Double
    public let rows: [RowSpec]
}

public struct KeyboardSpec: Decodable, Equatable {
    public let name: String
    public let bottomPunctuation: [String: String]?
    public let normal: BoardSpec
    public let hex: BoardSpec
}

public struct LayoutsFile: Decodable {
    public let bottomRows: [String: [KeySpec]]
    public let numberRow: [String]
    public let keyboards: [String: KeyboardSpec]
    public let symbolPages: [String: BoardSpec]

    /// The keyboard for an id, falling back to qwertz (or any keyboard).
    public func keyboard(_ id: String) -> KeyboardSpec {
        if let k = keyboards[id] { return k }
        if let k = keyboards["qwertz"] { return k }
        return keyboards.values.first!
    }

    /// The `keyboardLayout` prompt placeholder (SPEC §6.4): the keyboard name,
    /// ": " and its three letter rows (normal variant, character keys only)
    /// joined with " / ".
    public func promptDescription(keyboardId: String) -> String {
        let kb = keyboard(keyboardId)
        let rows = kb.normal.rows.map { row in
            row.keys.filter { !$0.isSpecial }.map { $0.key }.joined()
        }
        return kb.name + ": " + rows.joined(separator: " / ")
    }
}

public struct AccentsFile: Decodable {
    public let accents: [String: [String]]
}
