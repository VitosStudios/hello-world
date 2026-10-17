import Foundation

/// Loads the shared files (the contents of the repository's `shared/` folder).
///
/// Paths are relative to `shared/`, e.g. `"layouts/layouts.json"`,
/// `"prompts/correction-system.txt"` or `"languages.json"`.
public protocol ResourceProvider: AnyObject {
    func data(at relativePath: String) -> Data?
}

/// Reads shared files from an app bundle. The Xcode project copies
/// `shared/layouts` and `shared/prompts` as folder references and the JSON
/// files individually into the bundle's resource root, so the relative paths
/// are identical to the repository layout below `shared/`.
public final class BundleResourceProvider: ResourceProvider {
    public let bundle: Bundle
    public let subdirectory: String?

    public init(bundle: Bundle = .main, subdirectory: String? = nil) {
        self.bundle = bundle
        self.subdirectory = subdirectory
    }

    public func data(at relativePath: String) -> Data? {
        guard var url = bundle.resourceURL else { return nil }
        if let sub = subdirectory, !sub.isEmpty {
            url = url.appendingPathComponent(sub, isDirectory: true)
        }
        url = url.appendingPathComponent(relativePath, isDirectory: false)
        if let data = try? Data(contentsOf: url) {
            return data
        }
        // Fallback: Xcode may flatten individual files into the resource root.
        let fileName = (relativePath as NSString).lastPathComponent
        if fileName != relativePath, let root = bundle.resourceURL {
            return try? Data(contentsOf: root.appendingPathComponent(fileName, isDirectory: false))
        }
        return nil
    }
}

/// Reads shared files from a directory (used by the unit tests, which point it
/// at the repository's `shared/` folder).
public final class DirectoryResourceProvider: ResourceProvider {
    public let root: URL

    public init(root: URL) {
        self.root = root
    }

    public func data(at relativePath: String) -> Data? {
        try? Data(contentsOf: root.appendingPathComponent(relativePath, isDirectory: false))
    }
}

/// In-memory provider, handy for tests that need to inject custom files.
public final class InMemoryResourceProvider: ResourceProvider {
    public var files: [String: Data]
    public let fallback: ResourceProvider?

    public init(files: [String: Data] = [:], fallback: ResourceProvider? = nil) {
        self.files = files
        self.fallback = fallback
    }

    public func data(at relativePath: String) -> Data? {
        if let d = files[relativePath] { return d }
        return fallback?.data(at: relativePath)
    }
}
