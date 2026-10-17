import SwiftUI
import TippliCore

/// State of the tips panel / tips window (SPEC §7.2).
final class TipsModel: ObservableObject {
    enum Phase: Equatable {
        case empty
        case loading
        case loaded(TipsResult)
        case failed(String)
    }

    enum TranslationState: Equatable {
        case loading
        case done(String)
        case failed(String)
    }

    @Published var phase: Phase = .loading
    @Published var translations: [String: TranslationState] = [:]
    @Published var copiedToken: String?
    /// Incremented when results arrive (used to scroll to the emotion section).
    @Published var loadedCount = 0

    let text: String
    let isSelection: Bool
    let sections: TipsSections
    let targets: [TranslateTarget]
    let scrollToEmotion: Bool
    let emotionLevels: [String]

    private let service: AIService
    private let history: [CorrectionRecord]

    var onReplace: (String) -> Void = { _ in }
    var onInsert: (String) -> Void = { _ in }
    var onCopy: (String) -> Void = { _ in }
    var onClose: () -> Void = {}

    init(text: String, isSelection: Bool, service: AIService, history: [CorrectionRecord], scrollToEmotion: Bool = false) {
        self.text = String(text.suffix(4000))
        self.isSelection = isSelection
        self.service = service
        self.history = history
        self.scrollToEmotion = scrollToEmotion
        self.sections = service.settings.tipsSections
        self.targets = service.settings.translateTargets
        self.emotionLevels = Strings.shared.list("tips.emotionLevels")
    }

    var scopeTitle: String {
        loc("tips.title") + " · " + (isSelection ? loc("tips.scopeSelection") : loc("tips.scopeAll"))
    }

    var isEmptyText: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func load() {
        if isEmptyText {
            phase = .empty
            return
        }
        let needsRequest = sections.rewrites || sections.explanations || sections.writing || sections.emotion
        guard needsRequest else {
            phase = .loaded(TipsResult())
            return
        }
        phase = .loading
        let text = self.text
        let history = self.history
        let service = self.service
        Task { @MainActor [weak self] in
            do {
                let result = try await service.fetchTips(text: text, history: history)
                self?.phase = .loaded(result)
                self?.loadedCount += 1
            } catch {
                self?.phase = .failed(AIError.from(error).fullMessage)
            }
        }
    }

    func translate(_ target: TranslateTarget) {
        guard !isEmptyText else { return }
        translations[target.id] = .loading
        let text = self.text
        let service = self.service
        Task { @MainActor [weak self] in
            do {
                let out = try await service.translate(text: text, target: target)
                self?.translations[target.id] = .done(out)
            } catch {
                self?.translations[target.id] = .failed(AIError.from(error).fullMessage)
            }
        }
    }

    func copy(_ text: String, token: String) {
        onCopy(text)
        copiedToken = token
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            if self?.copiedToken == token { self?.copiedToken = nil }
        }
    }

    func emotionLabel(_ level: Int) -> String {
        guard level >= 0 && level < emotionLevels.count else { return "" }
        return emotionLevels[level]
    }
}
