import Foundation

/// SPEC §5.3 trigger setting.
public enum CorrectionTrigger: String, CaseIterable {
    case live
    case pause
    case word
    case sentence
    case manual
}

/// Snapshot of the settings the engine needs.
public struct EngineSettings: Equatable {
    public var aiEnabled = true
    public var trigger: CorrectionTrigger = .live
    public var pauseMs = 700
    public var waitOnSendMs = 1500
    public var moodWarning = true
    public var tipsEmotion = true
    public var pinyin = false

    public init() {}
}

/// What the toolbar AI dot shows (SPEC §7.1).
public enum AIStatus: Equatable {
    case ready
    case working
    case error(AIError)
    case off
    case locked
    case noKey
}

/// Supplies clients and prompts to the engine (production: `AIService`).
public protocol CorrectionAIProvider: AnyObject {
    /// False when the selected provider needs a key and none is stored.
    var isConfigured: Bool { get }
    /// A client for the current provider/model/key, nil when not configured.
    func makeClient() -> AICompleting?
    func correctionRequest(context: String, segment: String) -> AIRequest
    func moodRequest(paragraph: String) -> AIRequest
}

/// The live correction engine (SPEC §5). Not thread-safe: use it from the
/// main thread only (all scheduled blocks and AI completions hop to main).
public final class CorrectionEngine {
    public weak var host: TextHost?
    public weak var ai: CorrectionAIProvider?
    public var settings = EngineSettings()

    /// Field allows AI (SPEC §4.8).
    public private(set) var fieldAllowed = true
    /// Toolbar dot toggle for the current field.
    public var sessionEnabled = true {
        didSet { onStatusChange?() }
    }

    /// Status / undo / mood changed (redraw the toolbar).
    public var onStatusChange: (() -> Void)?
    /// The engine edited the text (apply or undo of a correction).
    public var onTextEdited: (() -> Void)?
    /// The emotion chip appeared for the first time (double haptic tick).
    public var onMoodAlert: (() -> Void)?

    public private(set) var history: [CorrectionRecord] = []
    public private(set) var lastCorrection: LastCorrection?
    public private(set) var lastError: AIError?
    public private(set) var moodLevel = 0
    public private(set) var moodReason = ""

    public struct LastCorrection: Equatable {
        public let raw: String
        public let corrected: String
        /// Whatever followed the corrected segment (trailing + typed since).
        public var suffix: String
    }

    private final class InFlight {
        let id: Int
        let segment: String
        let trailing: String
        let explicit: Bool
        var typedSince = ""
        var invalid = false
        var task: Task<Void, Never>?

        init(id: Int, segment: String, trailing: String, explicit: Bool) {
            self.id = id
            self.segment = segment
            self.trailing = trailing
            self.explicit = explicit
        }
    }

    private let scheduler: Scheduler
    private var debounce: Cancellable?
    private var inFlight: InFlight?
    private var rerun = false
    private var rerunExplicit = false
    private var suppressUntilBoundary = false
    private var cache: [(segment: String, corrected: String)] = []
    private var lastOutput: String?
    private var consecutiveErrors = 0
    private var pausedUntil: TimeInterval = 0
    private var requestCounter = 0

    private var moodInFlight = false
    private var lastMoodCheck: TimeInterval = -1_000_000
    private var lastMoodParagraph = ""
    private var moodChipShown = false

    public static let historyLimit = 20
    public static let cacheLimit = 20
    public static let moodInterval: TimeInterval = 20
    public static let moodMinChange = 20
    public static let errorPause: TimeInterval = 30

    public init(scheduler: Scheduler) {
        self.scheduler = scheduler
    }

    // MARK: State

    public var isWorking: Bool { inFlight != nil }

    /// A correction is scheduled, in flight or queued (enter waits for it).
    public var hasPendingWork: Bool { debounce != nil || inFlight != nil || rerun }

    public var status: AIStatus {
        if !fieldAllowed { return .locked }
        if !settings.aiEnabled || !sessionEnabled { return .off }
        if !(ai?.isConfigured ?? false) { return .noKey }
        if inFlight != nil { return .working }
        if let e = lastError { return .error(e) }
        return .ready
    }

    private var automaticAllowed: Bool {
        settings.aiEnabled && sessionEnabled && fieldAllowed && settings.trigger != .manual
    }

    // MARK: Field lifecycle

    /// A new field (or a desktop buffer reset): clear everything.
    public func fieldStarted(allowed: Bool) {
        cancelDebounce()
        dropInFlight()
        fieldAllowed = allowed
        sessionEnabled = true
        history.removeAll()
        lastCorrection = nil
        suppressUntilBoundary = false
        lastOutput = nil
        consecutiveErrors = 0
        pausedUntil = 0
        lastError = nil
        moodLevel = 0
        moodReason = ""
        moodChipShown = false
        lastMoodParagraph = ""
        onStatusChange?()
    }

    /// Desktop buffer reset (click, focus change, navigation keys, Enter):
    /// invalidate requests and forget the context, but keep the session
    /// toggle, the error pause and the mood state.
    public func resetContext() {
        cancelDebounce()
        dropInFlight()
        history.removeAll()
        lastCorrection = nil
        suppressUntilBoundary = false
        lastOutput = nil
        onStatusChange?()
    }

    public func setFieldAllowed(_ allowed: Bool) {
        guard allowed != fieldAllowed else { return }
        fieldAllowed = allowed
        if !allowed {
            cancelDebounce()
            dropInFlight()
        }
        onStatusChange?()
    }

    private func dropInFlight() {
        if let r = inFlight {
            r.invalid = true
            r.task?.cancel()
        }
        inFlight = nil
        rerun = false
        rerunExplicit = false
    }

    // MARK: Text change notifications

    /// The keyboard (or the user on the desktop) appended `text` at the caret.
    /// Call after the text was inserted.
    public func noteAppend(_ text: String) {
        guard !text.isEmpty else { return }
        if let r = inFlight, !r.invalid {
            r.typedSince += text
        }
        if lastCorrection != nil {
            lastCorrection?.suffix += text
        }
        let boundary = TextAlgorithms.containsSentenceBoundary(text)
        if boundary {
            suppressUntilBoundary = false
        }
        guard automaticAllowed else { return }
        switch settings.trigger {
        case .live:
            if boundary || completesWord(text) {
                cancelDebounce()
                trigger(explicit: false, force: false)
            } else {
                restartDebounce()
            }
        case .pause:
            if boundary {
                cancelDebounce()
                trigger(explicit: false, force: false)
            } else {
                restartDebounce()
            }
        case .word:
            if boundary || completesWord(text) {
                trigger(explicit: false, force: false)
            }
        case .sentence:
            if boundary {
                trigger(explicit: false, force: false)
            }
        case .manual:
            break
        }
    }

    /// Any non-append change (delete, cursor move, selection change, swipe
    /// delete/restore, emoji-panel delete).
    public func noteNonAppend() {
        if let r = inFlight {
            r.invalid = true
            r.typedSince = ""
        }
        lastCorrection = nil
        onStatusChange?()
    }

    /// The text was replaced from the tips panel: clear history and undo.
    public func noteExternalReplace() {
        noteNonAppend()
        history.removeAll()
        lastOutput = nil
    }

    /// Whether `text` (just appended) completes a word: a whitespace or
    /// punctuation character typed after a letter or digit.
    private func completesWord(_ text: String) -> Bool {
        guard let host = host else { return false }
        let tail = Array(host.textBeforeCursor(text.count + 1))
        guard tail.count >= 2 else { return false }
        let firstAppended = max(1, tail.count - text.count)
        var i = firstAppended
        while i < tail.count {
            let c = tail[i]
            let separator = c.isWhitespace || (c.tp_isPunctuation && !TextAlgorithms.wordExtra.contains(c))
            if separator && tail[i - 1].tp_isLetterOrDigit {
                return true
            }
            i += 1
        }
        return false
    }

    // MARK: Triggers

    private func cancelDebounce() {
        debounce?.cancel()
        debounce = nil
    }

    private func restartDebounce() {
        cancelDebounce()
        let delay = Double(max(settings.pauseMs, 0)) / 1000
        debounce = scheduler.schedule(after: delay) { [weak self] in
            guard let self = self else { return }
            self.debounce = nil
            self.trigger(explicit: false, force: false)
        }
    }

    /// ✨ button / desktop hotkey: correct now including the word in progress
    /// (also after an undo).
    public func correctNow() {
        cancelDebounce()
        trigger(explicit: true, force: true)
    }

    private func trigger(explicit: Bool, force: Bool) {
        cancelDebounce()
        guard let host = host else { return }
        guard settings.aiEnabled, sessionEnabled, fieldAllowed else { return }
        if !explicit && scheduler.now < pausedUntil { return }
        guard let ai = ai, ai.isConfigured, let client = ai.makeClient() else {
            onStatusChange?()
            return
        }
        if inFlight != nil {
            rerun = true
            rerunExplicit = rerunExplicit || explicit
            return
        }
        if !host.selectedText().isEmpty { return }
        if suppressUntilBoundary && !force { return }
        let before = host.textBeforeCursor(1000)
        guard let sel = TextAlgorithms.selectSegment(before, explicit: explicit, pinyin: settings.pinyin) else { return }
        if let last = lastOutput,
           TextAlgorithms.dropTrailingWhitespace(sel.segment) == TextAlgorithms.dropTrailingWhitespace(last) {
            return
        }
        if let hit = cache.first(where: { $0.segment == sel.segment }) {
            apply(segment: sel.segment, trailing: sel.trailing, typedSince: "", corrected: hit.corrected)
            return
        }

        requestCounter += 1
        let req = InFlight(id: requestCounter, segment: sel.segment, trailing: sel.trailing, explicit: explicit)
        inFlight = req
        onStatusChange?()
        let request = ai.correctionRequest(context: sel.context, segment: sel.segment)
        req.task = Task { @MainActor [weak self] in
            var output: String? = nil
            var failure: Error? = nil
            do {
                output = try await client.complete(request)
            } catch {
                failure = error
            }
            self?.finish(req, output: output, error: failure)
        }
    }

    private func finish(_ req: InFlight, output: String?, error: Error?) {
        guard inFlight === req else { return }
        inFlight = nil
        if let error = error {
            let e = AIError.from(error)
            switch e.kind {
            case .cancelled:
                break
            case .refused:
                // Keep the raw text silently.
                consecutiveErrors = 0
            default:
                lastError = e
                consecutiveErrors += 1
                if consecutiveErrors >= 3 {
                    pausedUntil = scheduler.now + CorrectionEngine.errorPause
                    consecutiveErrors = 0
                }
            }
        } else if let output = output {
            lastError = nil
            consecutiveErrors = 0
            if let cleaned = TextAlgorithms.cleanOutput(segment: req.segment, output: output, pinyin: settings.pinyin) {
                remember(segment: req.segment, corrected: cleaned)
                if !req.invalid {
                    lastOutput = cleaned
                    apply(segment: req.segment, trailing: req.trailing, typedSince: req.typedSince, corrected: cleaned)
                }
            }
        }
        onStatusChange?()
        if rerun {
            rerun = false
            let ex = rerunExplicit
            rerunExplicit = false
            trigger(explicit: ex, force: false)
        }
    }

    private func remember(segment: String, corrected: String) {
        cache.removeAll { $0.segment == segment }
        cache.append((segment: segment, corrected: corrected))
        if cache.count > CorrectionEngine.cacheLimit {
            cache.removeFirst(cache.count - CorrectionEngine.cacheLimit)
        }
    }

    private func apply(segment: String, trailing: String, typedSince: String, corrected: String) {
        guard let host = host else { return }
        lastOutput = corrected
        let suffix = trailing + typedSince
        let needed = segment.count + suffix.count + 2
        guard host.selectedText().isEmpty,
              TextAlgorithms.canApply(beforeNow: host.textBeforeCursor(needed), segment: segment,
                                      trailing: trailing, typedSince: typedSince) else { return }
        guard let edit = TextAlgorithms.computeEdit(segment: segment, corrected: corrected, suffix: suffix) else { return }
        host.batch {
            host.delete(edit.delete)
            host.insert(edit.insert)
        }
        history.append(CorrectionRecord(raw: segment, corrected: corrected))
        if history.count > CorrectionEngine.historyLimit {
            history.removeFirst(history.count - CorrectionEngine.historyLimit)
        }
        lastCorrection = LastCorrection(raw: segment, corrected: corrected, suffix: suffix)
        onTextEdited?()
        onStatusChange?()
        maybeCheckMood(segment: segment)
    }

    // MARK: Flush (SPEC §5.4.7)

    /// Runs the correction now (explicit, word in progress included) and waits
    /// until it is applied, at most `timeout` seconds or until `stop` returns true.
    @MainActor
    public func flush(timeout: TimeInterval, stop: (() -> Bool)? = nil) async {
        cancelDebounce()
        if inFlight != nil {
            rerun = true
            rerunExplicit = true
        } else {
            trigger(explicit: true, force: false)
        }
        let deadline = Date().addingTimeInterval(timeout)
        while (inFlight != nil || rerun) && Date() < deadline {
            if let stop = stop, stop() { return }
            try? await Task.sleep(nanoseconds: 15_000_000)
        }
    }

    /// Waits until no request is in flight (tests).
    @MainActor
    public func waitUntilIdle(timeout: TimeInterval = 5) async {
        let deadline = Date().addingTimeInterval(timeout)
        while (inFlight != nil || rerun) && Date() < deadline {
            await Task.yield()
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
    }

    // MARK: Undo (SPEC §5.5)

    public var canUndo: Bool {
        guard let lc = lastCorrection, let host = host else { return false }
        let tail = lc.corrected + lc.suffix
        return host.textBeforeCursor(tail.count + 2).hasSuffix(tail)
    }

    @discardableResult
    public func undo() -> Bool {
        guard canUndo, let lc = lastCorrection, let host = host else { return false }
        let p = TextAlgorithms.commonPrefixLength(lc.raw, lc.corrected)
        let del = String(lc.corrected.dropFirst(p)) + lc.suffix
        let ins = String(lc.raw.dropFirst(p)) + lc.suffix
        cancelDebounce()
        if let r = inFlight {
            r.invalid = true
            r.typedSince = ""
        }
        host.batch {
            host.delete(del)
            host.insert(ins)
        }
        suppressUntilBoundary = true
        lastCorrection = nil
        onTextEdited?()
        onStatusChange?()
        return true
    }

    // MARK: Mood check (SPEC §5.7)

    private func maybeCheckMood(segment: String) {
        guard settings.moodWarning, settings.tipsEmotion, fieldAllowed, !moodInFlight else { return }
        guard segment.contains(where: { TextAlgorithms.sentenceEnds.contains($0) }) else { return }
        let now = scheduler.now
        guard now - lastMoodCheck >= CorrectionEngine.moodInterval else { return }
        guard let host = host, let ai = ai, let client = ai.makeClient() else { return }
        let before = host.textBeforeCursor(4000)
        var paragraph = before
        if let idx = before.lastIndex(where: { $0.tp_isLineBreak }) {
            paragraph = String(before[before.index(after: idx)...])
        }
        paragraph = String(paragraph.suffix(1000))
        guard !paragraph.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard CorrectionEngine.changeAmount(lastMoodParagraph, paragraph) >= CorrectionEngine.moodMinChange else { return }
        lastMoodCheck = now
        lastMoodParagraph = paragraph
        moodInFlight = true
        let request = ai.moodRequest(paragraph: paragraph)
        Task { @MainActor [weak self] in
            var mood: MoodResult? = nil
            do {
                let out = try await client.complete(request)
                mood = try ResponseParsing.parseMood(out)
            } catch {
                mood = nil
            }
            guard let self = self else { return }
            self.moodInFlight = false
            if let m = mood {
                self.applyMood(m)
            }
        }
    }

    /// Characters that differ after the common prefix (rough edit size).
    static func changeAmount(_ a: String, _ b: String) -> Int {
        let p = TextAlgorithms.commonPrefixLength(a, b)
        return max(a.count, b.count) - p
    }

    func applyMood(_ m: MoodResult) {
        moodLevel = m.level
        moodReason = m.reason
        if m.level >= 2 {
            if !moodChipShown {
                moodChipShown = true
                onMoodAlert?()
            }
        } else {
            moodChipShown = false
        }
        onStatusChange?()
    }

    /// Hides the emotion chip (e.g. after the tips panel was opened from it).
    public func clearMood() {
        moodLevel = 0
        moodReason = ""
        moodChipShown = false
        onStatusChange?()
    }
}
