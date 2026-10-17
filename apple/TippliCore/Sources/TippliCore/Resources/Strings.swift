import Foundation

/// UI copy from `shared/strings-de.json`, addressed by dotted paths such as
/// `"toolbar.aiOn"` or `"settings.sectionProvider"`.
public final class Strings {
    private let root: [String: Any]

    public init(data: Data) {
        let obj = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any]
        root = obj ?? [:]
    }

    public static var shared: Strings { SharedResources.shared.strings }

    private func value(_ path: String) -> Any? {
        var node: Any? = root
        for part in path.split(separator: ".") {
            guard let dict = node as? [String: Any] else { return nil }
            node = dict[String(part)]
        }
        return node
    }

    /// The string for a path; returns the path itself when it is missing so
    /// that missing copy is visible during development.
    public subscript(_ path: String) -> String {
        if let s = value(path) as? String { return s }
        return path
    }

    /// An optional lookup.
    public func string(_ path: String) -> String? {
        value(path) as? String
    }

    /// A string list (e.g. `"tips.emotionLevels"`).
    public func list(_ path: String) -> [String] {
        (value(path) as? [Any])?.compactMap { $0 as? String } ?? []
    }
}

/// Shorthand for `Strings.shared[path]`.
public func loc(_ path: String) -> String {
    Strings.shared[path]
}
