import Foundation

/// A loosely typed JSON value (used for settings defaults).
public enum JSONValue: Codable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case array([JSONValue])
    case object([String: JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else if let n = try? container.decode(Double.self) {
            self = .number(n)
        } else if let s = try? container.decode(String.self) {
            self = .string(s)
        } else if let a = try? container.decode([JSONValue].self) {
            self = .array(a)
        } else if let o = try? container.decode([String: JSONValue].self) {
            self = .object(o)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported JSON value")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let s): try container.encode(s)
        case .number(let n): try container.encode(n)
        case .bool(let b): try container.encode(b)
        case .array(let a): try container.encode(a)
        case .object(let o): try container.encode(o)
        case .null: try container.encodeNil()
        }
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var doubleValue: Double? {
        switch self {
        case .number(let n): return n
        case .string(let s): return Double(s)
        default: return nil
        }
    }

    public var intValue: Int? {
        guard let d = doubleValue else { return nil }
        return Int(d)
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var stringArrayValue: [String]? {
        guard let a = arrayValue else { return nil }
        return a.compactMap { $0.stringValue }
    }

    public var objectValue: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    /// The value as a property-list compatible object (for UserDefaults).
    public var plistValue: Any? {
        switch self {
        case .string(let s): return s
        case .number(let n):
            if n == n.rounded(), abs(n) < 1e15 { return Int(n) }
            return n
        case .bool(let b): return b
        case .array(let a): return a.compactMap { $0.plistValue }
        case .object(let o):
            var out: [String: Any] = [:]
            for (k, v) in o { if let pv = v.plistValue { out[k] = pv } }
            return out
        case .null: return nil
        }
    }
}
