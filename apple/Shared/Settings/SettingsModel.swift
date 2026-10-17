import SwiftUI
import TippliCore

/// Observable wrapper around the settings store for the SwiftUI settings UI
/// (iOS companion app and macOS settings window).
final class SettingsModel: ObservableObject {
    let store: SettingsStore
    let secrets: SecretStore
    let service: AIService
    let resources: SharedResources

    /// Bumped on every change so that views re-read the store.
    @Published private(set) var revision = 0

    init(store: SettingsStore, secrets: SecretStore, resources: SharedResources = .shared) {
        self.store = store
        self.secrets = secrets
        self.resources = resources
        self.service = AIService(settings: store, secrets: secrets, resources: resources)
    }

    /// The platform's default stores: iOS = App Group + shared keychain group,
    /// macOS = standard defaults + login keychain.
    static func makeDefault() -> SettingsModel {
        #if os(iOS)
        let defaults = SettingsStore.appGroupDefaults(groupId: SettingsStore.bundleAppGroup)
        let secrets = SecretStore(accessGroup: SecretStore.bundleAccessGroup)
        #else
        let defaults = UserDefaults.standard
        let secrets = SecretStore(accessGroup: nil)
        #endif
        return SettingsModel(store: SettingsStore(defaults: defaults), secrets: secrets)
    }

    func changed() {
        service.reload()
        revision += 1
    }

    // MARK: Bindings

    func bool(_ key: String) -> Binding<Bool> {
        Binding(get: { self.store.bool(key) },
                set: { self.store.set($0, forKey: key); self.changed() })
    }

    func string(_ key: String) -> Binding<String> {
        Binding(get: { self.store.string(key) },
                set: { self.store.set($0, forKey: key); self.changed() })
    }

    /// Integer setting as a Double binding for sliders (clamped by settings-defaults.json).
    func number(_ key: String) -> Binding<Double> {
        Binding(get: { Double(self.store.int(key)) },
                set: { self.store.set(Int($0.rounded()), forKey: key); self.changed() })
    }

    func range(_ key: String, fallback: ClosedRange<Double>) -> ClosedRange<Double> {
        guard let r = store.range(key) else { return fallback }
        return Double(r.lowerBound)...Double(r.upperBound)
    }

    /// Membership of `value` in a string-list setting.
    func listMember(_ key: String, _ value: String) -> Binding<Bool> {
        Binding(get: { self.store.stringArray(key).contains(value) },
                set: { on in
                    var list = self.store.stringArray(key)
                    list.removeAll { $0 == value }
                    if on { list.append(value) }
                    self.store.set(list, forKey: key)
                    self.changed()
                })
    }

    /// Typing languages: never allow an empty list.
    func languageEnabled(_ id: String) -> Binding<Bool> {
        Binding(get: { self.store.enabledLanguages.contains { $0.id == id } },
                set: { on in
                    var list = self.store.enabledLanguages.map { $0.id }
                    list.removeAll { $0 == id }
                    if on { list.append(id) }
                    guard !list.isEmpty else { return }
                    self.store.set(list, forKey: SettingKey.languages)
                    if !list.contains(self.store.string(SettingKey.activeLanguage)) {
                        self.store.set(list[0], forKey: SettingKey.activeLanguage)
                    }
                    self.changed()
                })
    }

    func keyboardLayout(for language: Language) -> Binding<String> {
        Binding(get: { self.store.keyboardId(for: language) },
                set: { id in
                    if id == language.keyboard {
                        self.store.reset(SettingKey.keyboard(language.id))
                    } else {
                        self.store.set(id, forKey: SettingKey.keyboard(language.id))
                    }
                    self.changed()
                })
    }

    // MARK: Provider

    var presets: [ProviderPreset] { resources.providers.providers }

    var preset: ProviderPreset { store.providerPreset }

    var providerBinding: Binding<String> {
        Binding(get: { self.store.providerId },
                set: { self.store.providerId = $0; self.changed() })
    }

    var model: String { store.model(for: preset.id) }

    func setModel(_ m: String?) {
        let trimmed = m?.trimmingCharacters(in: .whitespacesAndNewlines)
        store.setModel((trimmed?.isEmpty ?? true) ? nil : trimmed, for: preset.id)
        changed()
    }

    var baseURL: String { store.baseURL(for: preset) }

    func apiKey(for providerId: String) -> String {
        secrets.apiKey(for: providerId)
    }

    func setAPIKey(_ key: String, for providerId: String) {
        secrets.setAPIKey(key, for: providerId)
        changed()
    }

    var keyboardLayouts: [LayoutChoice] {
        resources.layouts.keyboards.map { LayoutChoice(id: $0.key, name: $0.value.name) }.sorted { $0.name < $1.name }
    }
}

struct LayoutChoice: Identifiable, Hashable {
    let id: String
    let name: String
}
