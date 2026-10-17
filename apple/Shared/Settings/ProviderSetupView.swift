import SwiftUI
import TippliCore

extension View {
    /// Plain text entry (no auto-capitalisation / autocorrect) on every platform.
    @ViewBuilder
    func plainTextEntry(url: Bool = false) -> some View {
        #if os(iOS)
        self.textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .keyboardType(url ? .URL : .asciiCapable)
        #else
        self.autocorrectionDisabled(true)
        #endif
    }
}

/// KI-Anbieter section: provider, API key, model, base URL, model list, test (SPEC §6.1).
struct ProviderSection: View {
    @ObservedObject var model: SettingsModel

    @State private var keyDraft = ""
    @State private var loadedKeyFor = ""
    @State private var useCustomModel = false
    @State private var customModel = ""
    @State private var loadedModels: [String] = []
    @State private var modelsError: String?
    @State private var loadingModels = false
    @State private var testState: TestState = .idle

    private enum TestState: Equatable {
        case idle
        case running
        case ok(String)
        case failed(String)
    }

    private static let customTag = "__custom__"

    var body: some View {
        let preset = model.preset
        Section(header: Text(loc("settings.sectionProvider"))) {
            Picker(loc("settings.provider"), selection: model.providerBinding) {
                ForEach(model.presets) { p in
                    Text(p.name).tag(p.id)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(loc("settings.apiKey")).font(.subheadline)
                SecureField(preset.keyHint, text: $keyDraft)
                    .plainTextEntry()
                    .onChange(of: keyDraft) { newValue in
                        guard loadedKeyFor == preset.id else { return }
                        if newValue != model.apiKey(for: preset.id) {
                            model.setAPIKey(newValue, for: preset.id)
                            testState = .idle
                        }
                    }
                if !model.apiKey(for: preset.id).isEmpty {
                    Text(loc("settings.apiKeySaved"))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                if !preset.keyUrl.isEmpty, let url = URL(string: preset.keyUrl) {
                    Link(loc("settings.getKey"), destination: url)
                        .font(.caption)
                }
            }

            if preset.baseUrlEditable {
                VStack(alignment: .leading, spacing: 6) {
                    Text(loc("settings.baseUrl")).font(.subheadline)
                    TextField(preset.baseUrl, text: model.string(SettingKey.customBaseUrl))
                        .plainTextEntry(url: true)
                }
            }

            Picker(loc("settings.model"), selection: modelSelection) {
                ForEach(modelOptions(preset), id: \.self) { id in
                    Text(label(for: id, preset: preset)).tag(id)
                }
                Text(loc("settings.customModel")).tag(ProviderSection.customTag)
            }
            if useCustomModel {
                TextField(loc("settings.customModel"), text: $customModel)
                    .plainTextEntry()
                    .onSubmit { model.setModel(customModel) }
                    .onChange(of: customModel) { newValue in
                        model.setModel(newValue)
                    }
            }

            HStack {
                Button(loc("settings.loadModels")) { loadModels() }
                    .disabled(loadingModels)
                if loadingModels {
                    ProgressView()
                }
            }
            if let err = modelsError {
                Text(err).font(.caption).foregroundColor(.red)
            }

            HStack {
                Button(loc("settings.test")) { runTest() }
                    .disabled(testState == .running)
                Spacer()
                switch testState {
                case .idle:
                    EmptyView()
                case .running:
                    Text(loc("settings.testRunning")).foregroundColor(.secondary)
                case .ok:
                    Text(loc("settings.testOk")).foregroundColor(.green)
                case .failed:
                    Image(systemName: "exclamationmark.triangle").foregroundColor(.red)
                }
            }
            switch testState {
            case .ok(let text):
                Text(text).font(.caption).foregroundColor(.secondary)
            case .failed(let message):
                Text(message).font(.caption).foregroundColor(.red)
            default:
                EmptyView()
            }
        }
        .onAppear { syncFromStore() }
        .onChange(of: model.preset.id) { _ in syncFromStore() }
    }

    // MARK: Model selection

    private func modelOptions(_ preset: ProviderPreset) -> [String] {
        var ids = preset.models.map { $0.id }
        for id in loadedModels where !ids.contains(id) {
            ids.append(id)
        }
        let current = model.model
        if !current.isEmpty && !ids.contains(current) && !useCustomModel {
            ids.append(current)
        }
        return ids
    }

    private func label(for id: String, preset: ProviderPreset) -> String {
        preset.models.first { $0.id == id }?.label ?? id
    }

    private var modelSelection: Binding<String> {
        Binding(get: {
            useCustomModel ? ProviderSection.customTag : model.model
        }, set: { newValue in
            if newValue == ProviderSection.customTag {
                useCustomModel = true
                customModel = model.model
            } else {
                useCustomModel = false
                model.setModel(newValue)
            }
            testState = .idle
        })
    }

    private func syncFromStore() {
        let preset = model.preset
        loadedKeyFor = ""
        keyDraft = model.apiKey(for: preset.id)
        loadedKeyFor = preset.id
        loadedModels = []
        modelsError = nil
        testState = .idle
        useCustomModel = false
        customModel = model.model
    }

    // MARK: Actions

    private func loadModels() {
        let preset = model.preset
        let key = keyDraft
        let base = model.baseURL
        loadingModels = true
        modelsError = nil
        Task { @MainActor in
            do {
                let ids = try await model.service.listModels(preset: preset, apiKey: key, baseURL: base)
                loadedModels = ids
                if ids.isEmpty { modelsError = loc("errors.badResponse") }
            } catch {
                modelsError = AIError.from(error).fullMessage
            }
            loadingModels = false
        }
    }

    private func runTest() {
        let preset = model.preset
        let key = keyDraft
        if key != model.apiKey(for: preset.id) {
            model.setAPIKey(key, for: preset.id)
        }
        let m = useCustomModel ? customModel : model.model
        let base = model.baseURL
        testState = .running
        Task { @MainActor in
            do {
                let out = try await model.service.testConnection(preset: preset, model: m, apiKey: key, baseURL: base)
                testState = .ok(out)
            } catch {
                testState = .failed(AIError.from(error).fullMessage)
            }
        }
    }
}
