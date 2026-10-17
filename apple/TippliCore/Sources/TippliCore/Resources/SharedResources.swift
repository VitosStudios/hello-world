import Foundation

/// Lazily loads and caches the shared files. Everything except the emoji list
/// is tiny; the emoji list is only loaded when the emoji panel opens and can
/// be released again with `releaseEmoji()` (keyboard extension memory limit).
public final class SharedResources {
    /// The resources used by the app. Defaults to the main bundle; tests replace it.
    public static var shared: SharedResources = SharedResources(provider: BundleResourceProvider())

    public let provider: ResourceProvider
    private let lock = NSLock()

    private var _layouts: LayoutsFile?
    private var _accents: AccentsFile?
    private var _languages: LanguagesFile?
    private var _providers: ProvidersFile?
    private var _promptParts: PromptParts?
    private var _theme: ThemeFile?
    private var _settingsDefaults: SettingsDefaultsFile?
    private var _strings: Strings?
    private var _emoji: EmojiFile?
    private var _prompts: [String: String] = [:]

    public init(provider: ResourceProvider) {
        self.provider = provider
    }

    // MARK: Loading

    public func rawData(_ path: String) -> Data? {
        provider.data(at: path)
    }

    private func load<T: Decodable>(_ type: T.Type, _ path: String) -> T {
        guard let data = provider.data(at: path) else {
            fatalError("Tippli: shared resource '\(path)' is missing from the bundle. Check the XcodeGen resources.")
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            fatalError("Tippli: shared resource '\(path)' could not be decoded: \(error)")
        }
    }

    private func cached<T>(_ storage: ReferenceWritableKeyPath<SharedResources, T?>, _ make: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        if let v = self[keyPath: storage] { return v }
        let v = make()
        self[keyPath: storage] = v
        return v
    }

    // MARK: Files

    public var layouts: LayoutsFile {
        cached(\._layouts) { load(LayoutsFile.self, "layouts/layouts.json") }
    }

    public var accents: [String: [String]] {
        cached(\._accents) { load(AccentsFile.self, "layouts/accents.json") }.accents
    }

    public var languages: LanguagesFile {
        cached(\._languages) { load(LanguagesFile.self, "languages.json") }
    }

    public var providers: ProvidersFile {
        cached(\._providers) { load(ProvidersFile.self, "providers.json") }
    }

    public var promptParts: PromptParts {
        cached(\._promptParts) { load(PromptParts.self, "prompts/prompt-parts.json") }
    }

    public var theme: ThemeFile {
        cached(\._theme) { load(ThemeFile.self, "theme.json") }
    }

    public var settingsDefaults: SettingsDefaultsFile {
        cached(\._settingsDefaults) { load(SettingsDefaultsFile.self, "settings-defaults.json") }
    }

    public var strings: Strings {
        cached(\._strings) {
            guard let data = provider.data(at: "strings-de.json") else {
                fatalError("Tippli: shared resource 'strings-de.json' is missing from the bundle.")
            }
            return Strings(data: data)
        }
    }

    /// Emoji categories (loaded on first use).
    public var emoji: EmojiFile {
        cached(\._emoji) { load(EmojiFile.self, "emoji.json") }
    }

    /// Frees the emoji list (call when the emoji panel closes in the keyboard extension).
    public func releaseEmoji() {
        lock.lock()
        _emoji = nil
        lock.unlock()
    }

    /// A prompt template from `prompts/<name>` with trailing newlines removed.
    public func prompt(_ name: String) -> String {
        lock.lock()
        if let p = _prompts[name] {
            lock.unlock()
            return p
        }
        lock.unlock()
        guard let data = provider.data(at: "prompts/" + name), let text = String(data: data, encoding: .utf8) else {
            fatalError("Tippli: prompt template '\(name)' is missing from the bundle.")
        }
        var t = text
        while let last = t.last, last == "\n" || last == "\r" || last == "\r\n" {
            t.removeLast()
        }
        lock.lock()
        _prompts[name] = t
        lock.unlock()
        return t
    }

    // MARK: Convenience

    public func language(_ id: String) -> Language? {
        languages.language(id)
    }

    public func provider(_ id: String) -> ProviderPreset? {
        providers.provider(id)
    }
}
