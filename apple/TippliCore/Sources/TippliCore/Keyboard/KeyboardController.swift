import Foundation

/// Platform callbacks of the keyboard controller.
public protocol KeyboardControllerDelegate: AnyObject {
    /// Redraw the keyboard view.
    func keyboardNeedsDisplay(_ controller: KeyboardController)
    /// The total height changed (layout mode, key height, number row).
    func keyboardHeightChanged(_ controller: KeyboardController)
    func keyboard(_ controller: KeyboardController, feedback: KeyboardFeedback)
    /// Show the emoji / tips panel or return to the keys.
    func keyboard(_ controller: KeyboardController, showPanel panel: KeyboardPanel, scrollToEmotion: Bool)
    func keyboardOpenSettings(_ controller: KeyboardController)
    /// `$nextkb` (iOS) when it was not handled by the platform directly.
    func keyboardNextInputMode(_ controller: KeyboardController)
    /// The keyboard or the engine edited the text (platforms remember the
    /// expected context to recognise external changes).
    func keyboardDidEditText(_ controller: KeyboardController)
    /// Something animates (AI working, enter spinner): keep redrawing until false.
    func keyboard(_ controller: KeyboardController, animating: Bool)
}

public extension KeyboardControllerDelegate {
    func keyboardHeightChanged(_ controller: KeyboardController) {}
    func keyboard(_ controller: KeyboardController, feedback: KeyboardFeedback) {}
    func keyboardOpenSettings(_ controller: KeyboardController) {}
    func keyboardNextInputMode(_ controller: KeyboardController) {}
    func keyboardDidEditText(_ controller: KeyboardController) {}
    func keyboard(_ controller: KeyboardController, animating: Bool) {}
}

/// Settings snapshot used by the keyboard.
public struct KeyboardSettings: Equatable {
    public var mode: KeyboardMode = .hex
    public var longPressAccents = true
    public var longPressDigits = true
    public var longPressMs = 350
    public var keyHeight = "medium"
    public var haptics = true
    public var sound = false
    public var autoCapitalize = true
    public var doubleSpacePeriod = true
    public var numberRow = false
    public var waitOnSendMs = 1500
    public var trigger: CorrectionTrigger = .live
    public var languages: [Language] = []
    public var activeLanguage: Language
    public var keyboardId = "qwertz"

    public init(store: SettingsStore) {
        mode = store.keyboardMode
        longPressAccents = store.bool(SettingKey.longPressAccents)
        longPressDigits = store.bool(SettingKey.longPressDigits)
        longPressMs = store.int(SettingKey.longPressMs)
        keyHeight = store.string(SettingKey.keyHeight)
        haptics = store.bool(SettingKey.haptics)
        sound = store.bool(SettingKey.sound)
        autoCapitalize = store.bool(SettingKey.autoCapitalize)
        doubleSpacePeriod = store.bool(SettingKey.doubleSpacePeriod)
        numberRow = store.bool(SettingKey.numberRow)
        waitOnSendMs = store.int(SettingKey.waitOnSendMs)
        trigger = CorrectionTrigger(rawValue: store.trigger) ?? .live
        languages = store.enabledLanguages
        activeLanguage = store.activeLanguage
        keyboardId = store.keyboardId(for: activeLanguage)
    }
}

/// The keyboard brain (SPEC §3, §4, §7.1): layout, gestures, shift, pages,
/// restore stack, toolbar and the bridge to the correction engine. Used by the
/// iOS keyboard extension and the macOS on-screen keyboard. Main thread only.
public final class KeyboardController {
    public let store: SettingsStore
    public let resources: SharedResources
    public let engine: CorrectionEngine
    public weak var host: KeyboardTextHost?
    public weak var delegate: KeyboardControllerDelegate?

    public private(set) var settings: KeyboardSettings
    public private(set) var layout: KeyboardLayout
    public private(set) var page: KeyboardPage = .letters
    public private(set) var shift: ShiftState = .off
    public private(set) var panel: KeyboardPanel = .keys
    public private(set) var fieldKind: FieldKind = .normal
    public private(set) var toast: Toast?
    public private(set) var enterPending = false

    /// iOS without "Allow Full Access": AI off, toolbar says so.
    public var fullAccessMissing = false {
        didSet { if oldValue != fullAccessMissing { applyEngineSettings(); redraw() } }
    }
    /// Label of the return key (Senden/Suchen/…); nil = ⏎.
    public var returnKeyLabel: String? {
        didSet { if oldValue != returnKeyLabel { redraw() } }
    }
    /// iOS `needsInputModeSwitchKey`.
    public var needsNextKeyboard = false {
        didSet { if oldValue != needsNextKeyboard { rebuildLayout() } }
    }

    private let scheduler: Scheduler
    private let interpreter: GestureInterpreter
    private var width: CGFloat = 320

    private struct RestoreEntry {
        let text: String
        let context: String
    }
    private var restoreStack: [RestoreEntry] = []
    public static let restoreLimit = 20

    private var lastWasSpace = false
    private var lastSpaceTime: TimeInterval = -1000
    private var lastShiftTap: TimeInterval = -1000
    private var shiftUsedWhileHeld = false
    private var toastCounter = 0
    private var animating = false

    // Toolbar pointer + language popup
    private struct ToolbarPress {
        let id: Int
        let kind: ToolbarItemKind
        var longFired: Bool
        var timer: Cancellable?
    }
    private var toolbarPress: ToolbarPress?
    public private(set) var toolbarPressed: ToolbarItemKind?
    private struct LanguagePopup {
        var layout: PopupLayout
        var languages: [Language]
        var selected: Int?
        var ownerId: Int?
    }
    private var languagePopup: LanguagePopup?
    private var ignoredPointers = Set<Int>()

    public static let doubleTapInterval: TimeInterval = 0.3
    public static let doubleSpaceInterval: TimeInterval = 0.4

    public init(store: SettingsStore, engine: CorrectionEngine, scheduler: Scheduler, resources: SharedResources = .shared) {
        self.store = store
        self.resources = resources
        self.engine = engine
        self.scheduler = scheduler
        let s = KeyboardSettings(store: store)
        self.settings = s
        let metrics = KeyboardMetrics(theme: resources.theme.metrics, keyHeight: s.keyHeight)
        let cfg = KeyboardLayoutConfig(width: 320, page: .letters, mode: s.mode, keyboardId: s.keyboardId,
                                       numberRow: s.numberRow, needsNextKeyboard: false)
        let lay = KeyboardLayout.build(config: cfg, metrics: metrics, layouts: resources.layouts)
        self.layout = lay
        self.interpreter = GestureInterpreter(layout: lay, scheduler: scheduler)
        interpreter.thresholds.longPress = Double(s.longPressMs) / 1000
        interpreter.alternativesProvider = { [weak self] key in
            self?.alternatives(for: key) ?? []
        }
        interpreter.onEvent = { [weak self] event in
            self?.handle(event)
        }
        engine.onStatusChange = { [weak self] in
            self?.engineStatusChanged()
        }
        engine.onTextEdited = { [weak self] in
            guard let self = self else { return }
            self.delegate?.keyboardDidEditText(self)
            self.updateAutoCapitalization()
            self.redraw()
        }
        engine.onMoodAlert = { [weak self] in
            guard let self = self else { return }
            self.delegate?.keyboard(self, feedback: .emotionAlert)
        }
        applyEngineSettings()
    }

    // MARK: Configuration

    public var totalHeight: CGFloat { layout.totalHeight }
    public var metrics: KeyboardMetrics { layout.metrics }
    public var activeLanguage: Language { settings.activeLanguage }

    /// Re-reads all settings (iOS: viewWillAppear; macOS: on every change).
    public func reloadSettings() {
        settings = KeyboardSettings(store: store)
        interpreter.thresholds.longPress = Double(settings.longPressMs) / 1000
        applyEngineSettings()
        rebuildLayout()
    }

    private func applyEngineSettings() {
        var es = store.engineSettings(fullAccess: !fullAccessMissing)
        es.pinyin = settings.activeLanguage.pinyin
        engine.settings = es
    }

    public func setWidth(_ w: CGFloat) {
        guard w > 0, abs(w - width) > 0.5 else { return }
        width = w
        rebuildLayout()
    }

    private func rebuildLayout() {
        let oldHeight = layout.totalHeight
        let metrics = KeyboardMetrics(theme: resources.theme.metrics, keyHeight: settings.keyHeight)
        let cfg = KeyboardLayoutConfig(width: width, page: page, mode: settings.mode, keyboardId: settings.keyboardId,
                                       numberRow: settings.numberRow, needsNextKeyboard: needsNextKeyboard)
        layout = KeyboardLayout.build(config: cfg, metrics: metrics, layouts: resources.layouts)
        interpreter.cancelAll()
        interpreter.layout = layout
        languagePopup = nil
        if abs(oldHeight - layout.totalHeight) > 0.5 {
            delegate?.keyboardHeightChanged(self)
        }
        redraw()
    }

    private func setPage(_ p: KeyboardPage) {
        guard p != page else { return }
        page = p
        rebuildLayout()
    }

    // MARK: Field lifecycle

    /// A field became active (iOS viewWillAppear / document change, desktop reset).
    public func startField(kind: FieldKind) {
        fieldKind = kind
        restoreStack.removeAll()
        lastWasSpace = false
        enterPending = false
        shift = .off
        panel = .keys
        page = kind.startPage
        engine.fieldStarted(allowed: kind.aiAllowed(incognitoNoAI: store.bool(SettingKey.incognitoNoAI)))
        rebuildLayout()
        updateAutoCapitalization()
        redraw()
    }

    /// The text changed outside the keyboard (user tapped elsewhere, the app edited it).
    public func externalTextChange() {
        restoreStack.removeAll()
        lastWasSpace = false
        engine.noteNonAppend()
        updateAutoCapitalization()
        redraw()
    }

    // MARK: Pointer input (key area + toolbar)

    public func pointerDown(id: Int, at p: CGPoint) {
        if languagePopup != nil {
            if let lp = languagePopup, lp.layout.frame.insetBy(dx: -6, dy: -6).contains(p) {
                languagePopup?.ownerId = id
                languagePopup?.selected = lp.layout.selection(at: p)
                redraw()
            } else {
                languagePopup = nil
                ignoredPointers.insert(id)
                redraw()
            }
            return
        }
        if toast != nil && p.y < layout.toolbarHeight {
            toolbarPress = ToolbarPress(id: id, kind: .aiStatus, longFired: false, timer: nil)
            return
        }
        if p.y < layout.toolbarHeight {
            guard let item = toolbarItem(at: p) else { return }
            var press = ToolbarPress(id: id, kind: item.kind, longFired: false, timer: nil)
            if item.kind == .language || item.kind == .aiStatus {
                press.timer = scheduler.schedule(after: 0.5) { [weak self] in
                    self?.toolbarLongPress(id: id)
                }
            }
            toolbarPress = press
            toolbarPressed = item.kind
            redraw()
            return
        }
        guard panel == .keys else { return }
        interpreter.pointerDown(id: id, at: p)
    }

    public func pointerMoved(id: Int, to p: CGPoint) {
        if ignoredPointers.contains(id) { return }
        if let lp = languagePopup, lp.ownerId == id {
            let sel = lp.layout.frame.insetBy(dx: -20, dy: -20).contains(p) ? lp.layout.selection(at: p) : nil
            if sel != lp.selected {
                languagePopup?.selected = sel
                redraw()
            }
            return
        }
        if toolbarPress?.id == id { return }
        interpreter.pointerMoved(id: id, to: p)
    }

    public func pointerUp(id: Int, at p: CGPoint) {
        if ignoredPointers.remove(id) != nil { return }
        if let lp = languagePopup, lp.ownerId == id {
            let sel = lp.layout.frame.insetBy(dx: -20, dy: -20).contains(p) ? lp.layout.selection(at: p) : nil
            if let s = sel, s < lp.languages.count {
                languagePopup = nil
                selectLanguage(lp.languages[s])
            } else {
                // Sticky: keep the list open for a tap.
                languagePopup?.ownerId = nil
                languagePopup?.selected = nil
                redraw()
            }
            if toolbarPress?.id == id { endToolbarPress() }
            return
        }
        if let press = toolbarPress, press.id == id {
            endToolbarPress()
            if toast != nil {
                handleToastTap()
                return
            }
            if !press.longFired, let item = toolbarItem(at: p), item.kind == press.kind {
                handleToolbarTap(item.kind)
            }
            return
        }
        interpreter.pointerUp(id: id, at: p)
    }

    public func pointerCancelled(id: Int) {
        ignoredPointers.remove(id)
        if toolbarPress?.id == id {
            endToolbarPress()
            return
        }
        if languagePopup?.ownerId == id {
            languagePopup?.ownerId = nil
        }
        interpreter.pointerCancelled(id: id)
    }

    /// Cancel all touches (view disappeared).
    public func cancelAllPointers() {
        interpreter.cancelAll()
        endToolbarPress()
        ignoredPointers.removeAll()
        languagePopup = nil
        redraw()
    }

    private func endToolbarPress() {
        toolbarPress?.timer?.cancel()
        toolbarPress = nil
        if toolbarPressed != nil {
            toolbarPressed = nil
            redraw()
        }
    }

    // MARK: Gesture events

    private func handle(_ event: GestureEvent) {
        switch event {
        case .pressBegan:
            delegate?.keyboard(self, feedback: .keyDown)
            redraw()
        case .pressEnded:
            redraw()
        case .tap(let key):
            handleTap(key)
        case .capital(let key):
            let c = KeyAlternatives.character(for: key, bottomPunctuation: layout.bottomPunctuation)
            typeText(page == .letters ? KeyAlternatives.uppercase(c) : c, consumeShift: false)
        case .backspace:
            deleteBackward()
        case .backspaceWord:
            deleteWordBackward()
        case .swipeDeleteWord:
            swipeDeleteWord()
        case .swipeRestore:
            swipeRestore()
        case .moveCursor(let n):
            moveCursor(n)
        case .shiftLongPress:
            shift = .capsLock
            lastShiftTap = scheduler.now
            redraw()
        case .popupOpened:
            delegate?.keyboard(self, feedback: .popupOpened)
            redraw()
        case .popupSelectionChanged:
            redraw()
        case .popupCommit(_, let choice):
            if let c = choice {
                typeText(c, consumeShift: true)
            } else {
                redraw()
            }
        }
    }

    private func handleTap(_ key: KeyItem) {
        switch key.key {
        case "$shift":
            tapShift()
        case "$space":
            tapSpace()
        case "$enter":
            tapEnter()
        case "$back":
            break
        case "$123", "$sym1":
            lastWasSpace = false
            setPage(.symbols1)
        case "$sym2":
            lastWasSpace = false
            setPage(.symbols2)
        case "$abc":
            lastWasSpace = false
            setPage(.letters)
        case "$emoji":
            lastWasSpace = false
            showPanel(.emoji)
        case "$nextkb":
            delegate?.keyboardNextInputMode(self)
        default:
            if key.isSpecial { return }
            var c = KeyAlternatives.character(for: key, bottomPunctuation: layout.bottomPunctuation)
            if page == .letters && (shift.isActive || interpreter.isShiftHeld) {
                c = KeyAlternatives.uppercase(c)
                if interpreter.isShiftHeld { shiftUsedWhileHeld = true }
            }
            typeText(c, consumeShift: true)
        }
    }

    private func tapShift() {
        if shiftUsedWhileHeld {
            shiftUsedWhileHeld = false
            if case .oneShot = shift { shift = .off }
            redraw()
            return
        }
        let now = scheduler.now
        let quick = now - lastShiftTap < KeyboardController.doubleTapInterval
        switch shift {
        case .capsLock:
            shift = .off
        case .oneShot:
            shift = quick ? .capsLock : .off
        case .off:
            shift = quick ? .capsLock : .oneShot(auto: false)
        }
        lastShiftTap = now
        lastWasSpace = false
        redraw()
    }

    // MARK: Text operations

    /// Types text at the caret (characters, popup alternatives, emoji).
    public func typeText(_ text: String, consumeShift: Bool) {
        guard let host = host, !text.isEmpty else { return }
        host.insert(text)
        lastWasSpace = false
        didAppend(text)
        if consumeShift, case .oneShot = shift {
            shift = .off
        }
        updateAutoCapitalization()
        redraw()
    }

    private func didAppend(_ text: String) {
        restoreStack.removeAll()
        engine.noteAppend(text)
        delegate?.keyboardDidEditText(self)
    }

    private func didNonAppend() {
        lastWasSpace = false
        engine.noteNonAppend()
        delegate?.keyboardDidEditText(self)
        updateAutoCapitalization()
        redraw()
    }

    private func tapSpace() {
        guard let host = host else { return }
        let now = scheduler.now
        if settings.doubleSpacePeriod && lastWasSpace && now - lastSpaceTime < KeyboardController.doubleSpaceInterval
            && TextAlgorithms.doubleSpacePeriod(host.textBeforeCursor(8)) {
            host.batch {
                host.delete(" ")
                host.insert(". ")
            }
            lastWasSpace = false
            restoreStack.removeAll()
            engine.noteNonAppend()
            engine.noteAppend(". ")
            delegate?.keyboardDidEditText(self)
        } else {
            host.insert(" ")
            didAppend(" ")
            lastWasSpace = true
            lastSpaceTime = now
        }
        if page != .letters { setPage(.letters) }
        updateAutoCapitalization()
        redraw()
    }

    private func tapEnter() {
        if enterPending {
            // Second tap during the wait: continue at once.
            enterPending = false
            finishEnter()
            return
        }
        let wait = Double(settings.waitOnSendMs) / 1000
        if engine.hasPendingWork && wait > 0 && !fullAccessMissing {
            enterPending = true
            updateAnimation()
            redraw()
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                await self.engine.flush(timeout: wait, stop: { [weak self] in
                    !(self?.enterPending ?? false)
                })
                if self.enterPending {
                    self.enterPending = false
                    self.finishEnter()
                }
            }
        } else {
            finishEnter()
        }
    }

    private func finishEnter() {
        guard let host = host else { return }
        host.performReturn()
        lastWasSpace = false
        didAppend("\n")
        if page != .letters { setPage(.letters) }
        updateAnimation()
        updateAutoCapitalization()
        redraw()
    }

    public func deleteBackward() {
        guard let host = host else { return }
        host.deleteBackward()
        restoreStack.removeAll()
        didNonAppend()
    }

    private func deleteWordBackward() {
        guard let host = host else { return }
        let w = TextAlgorithms.wordDelete(host.textBeforeCursor(1000))
        if w.isEmpty {
            host.deleteBackward()
        } else {
            host.delete(w)
        }
        restoreStack.removeAll()
        didNonAppend()
    }

    private func swipeDeleteWord() {
        guard let host = host else { return }
        if !host.selectedText().isEmpty {
            host.deleteBackward()
            didNonAppend()
            return
        }
        let before = host.textBeforeCursor(1000)
        let w = TextAlgorithms.wordDelete(before)
        guard !w.isEmpty else {
            delegate?.keyboard(self, feedback: .noop)
            return
        }
        host.delete(w)
        let context = String(String(before.dropLast(w.count)).suffix(24))
        restoreStack.append(RestoreEntry(text: w, context: context))
        if restoreStack.count > KeyboardController.restoreLimit {
            restoreStack.removeFirst(restoreStack.count - KeyboardController.restoreLimit)
        }
        didNonAppend()
    }

    private func swipeRestore() {
        guard let host = host else { return }
        if let top = restoreStack.last {
            let before = host.textBeforeCursor(top.context.count + 2)
            let matches = top.context.isEmpty || before.hasSuffix(top.context)
                || (before.count < top.context.count && top.context.hasSuffix(before))
            if matches {
                restoreStack.removeLast()
                host.insert(top.text)
                lastWasSpace = false
                engine.noteNonAppend()
                delegate?.keyboardDidEditText(self)
                updateAutoCapitalization()
                redraw()
                return
            }
            restoreStack.removeAll()
        }
        if engine.undo() {
            updateAutoCapitalization()
            redraw()
            return
        }
        delegate?.keyboard(self, feedback: .noop)
    }

    private func moveCursor(_ n: Int) {
        guard let host = host, n != 0 else { return }
        host.moveCursor(by: n)
        restoreStack.removeAll()
        didNonAppend()
    }

    /// Inserts an emoji from the emoji panel.
    public func insertEmoji(_ emoji: String) {
        typeText(emoji, consumeShift: false)
        store.noteEmojiUsed(emoji)
    }

    /// The tips panel replaced text (engine change: clear history and undo).
    public func replaceScope(selection: Bool, with text: String) {
        guard let host = host else { return }
        host.replaceScope(selection: selection, with: text)
        restoreStack.removeAll()
        engine.noteExternalReplace()
        delegate?.keyboardDidEditText(self)
        updateAutoCapitalization()
        redraw()
    }

    /// Inserts text at the caret without replacing anything (tips "Einfügen").
    public func insertFromTips(_ text: String) {
        guard let host = host else { return }
        host.insert(text)
        restoreStack.removeAll()
        engine.noteExternalReplace()
        delegate?.keyboardDidEditText(self)
        updateAutoCapitalization()
        redraw()
    }

    // MARK: Auto-capitalisation (SPEC §4.2)

    public func updateAutoCapitalization() {
        let allowed = settings.autoCapitalize && fieldKind.autoCapitalizeAllowed && !settings.activeLanguage.pinyin
        guard allowed, let host = host else {
            if shift == .oneShot(auto: true) { shift = .off }
            return
        }
        if shift == .capsLock { return }
        let want = TextAlgorithms.autoCapitalize(host.textBeforeCursor(64))
        if want {
            if shift == .off { shift = .oneShot(auto: true) }
        } else if shift == .oneShot(auto: true) {
            shift = .off
        }
    }

    // MARK: Long-press alternatives

    private func alternatives(for key: KeyItem) -> [String] {
        let c = KeyAlternatives.character(for: key, bottomPunctuation: layout.bottomPunctuation)
        return KeyAlternatives.alternatives(for: key, page: page, character: c, accents: resources.accents,
                                            accentsEnabled: settings.longPressAccents,
                                            digitsEnabled: settings.longPressDigits,
                                            uppercase: page == .letters && shift.isActive)
    }

    // MARK: Panels

    public func showPanel(_ p: KeyboardPanel, scrollToEmotion: Bool = false) {
        interpreter.cancelAll()
        languagePopup = nil
        panel = p
        delegate?.keyboard(self, showPanel: p, scrollToEmotion: scrollToEmotion)
        redraw()
    }

    // MARK: Languages and layout

    public func cycleLanguage() {
        let langs = settings.languages
        guard langs.count > 1 else {
            showToast(settings.activeLanguage.name)
            return
        }
        let idx = langs.firstIndex(where: { $0.id == settings.activeLanguage.id }) ?? 0
        selectLanguage(langs[(idx + 1) % langs.count])
    }

    public func selectLanguage(_ lang: Language) {
        store.set(lang.id, forKey: SettingKey.activeLanguage)
        settings = KeyboardSettings(store: store)
        applyEngineSettings()
        rebuildLayout()
        updateAutoCapitalization()
        redraw()
    }

    public func toggleLayoutMode() {
        store.keyboardMode = settings.mode == .hex ? .normal : .hex
        settings = KeyboardSettings(store: store)
        rebuildLayout()
    }

    // MARK: Toolbar

    public var indicator: AIIndicator {
        if fullAccessMissing { return .fullAccessNeeded }
        switch engine.status {
        case .locked: return .locked
        case .off: return .off
        case .noKey: return .noKey
        case .working: return .working
        case .error: return .error
        case .ready: return .ready
        }
    }

    private func engineStatusChanged() {
        updateAnimation()
        redraw()
    }

    private func updateAnimation() {
        let should = engine.isWorking || enterPending
        if should != animating {
            animating = should
            delegate?.keyboard(self, animating: should)
        }
    }

    static func approxTextWidth(_ s: String, size: CGFloat) -> CGFloat {
        var w: CGFloat = 0
        for c in s {
            if let sc = c.unicodeScalars.first, sc.value >= 0x2E80 {
                w += size * 1.05
            } else if c.isUppercase {
                w += size * 0.68
            } else if c == " " {
                w += size * 0.3
            } else {
                w += size * 0.56
            }
        }
        return w
    }

    /// Toolbar items with frames (SPEC §7.1), compressed on narrow widths.
    public func toolbarItems() -> [ToolbarItem] {
        let H = layout.toolbarHeight
        let chipH: CGFloat = min(30, H - 8)
        let y = (H - chipH) / 2
        let sp = max(layout.metrics.sidePadding, 4)
        let font: CGFloat = 13
        let preset = store.providerPreset
        let ind = indicator

        var aiTitle = preset.shortName
        if ind == .fullAccessNeeded { aiTitle = loc("toolbar.fullAccessNeeded") }
        let aiIcon = ind == .locked ? "🔒" : ""
        let showUndo = engine.canUndo
        let showEmotion = engine.moodLevel >= 2
        let showCorrect = settings.trigger == .manual
        let langTitle = settings.activeLanguage.short

        struct Spec {
            var kind: ToolbarItemKind
            var icon: String
            var title: String
            var style: ToolbarItemStyle
            var minWidth: CGFloat
        }

        func build(compact level: Int) -> (left: [Spec], right: [Spec]) {
            var left: [Spec] = []
            var right: [Spec] = []
            let dropAITitle = level >= 4 || (level >= 3 && ind != .fullAccessNeeded)
            let aiText = dropAITitle ? "" : aiTitle
            left.append(Spec(kind: .aiStatus, icon: aiIcon, title: aiText, style: .plain, minWidth: 30))
            if showUndo {
                left.append(Spec(kind: .undo, icon: "↶", title: level >= 2 ? "" : loc("toolbar.undo"), style: .chip, minWidth: 34))
            }
            if showEmotion {
                let style: ToolbarItemStyle = engine.moodLevel >= 3 ? .dangerChip : .warningChip
                left.append(Spec(kind: .emotion, icon: level >= 4 ? "⚠︎" : "", title: level >= 4 ? "" : loc("toolbar.emotionChip"), style: style, minWidth: 34))
            }
            right.append(Spec(kind: .settings, icon: "⚙︎", title: "", style: .plain, minWidth: 34))
            right.append(Spec(kind: .layoutToggle, icon: settings.mode == .hex ? "▭" : "⬡", title: "", style: .plain, minWidth: 34))
            right.append(Spec(kind: .language, icon: "", title: langTitle, style: .chip, minWidth: 40))
            right.append(Spec(kind: .tips, icon: "💡", title: level >= 1 ? "" : loc("toolbar.tips"), style: .chip, minWidth: 36))
            if showCorrect {
                right.append(Spec(kind: .correctNow, icon: "✨", title: "", style: .plain, minWidth: 34))
            }
            return (left, right)
        }

        func itemWidth(_ s: Spec) -> CGFloat {
            var w: CGFloat = 0
            if s.kind == .aiStatus { w += 16 }
            if !s.icon.isEmpty { w += KeyboardController.approxTextWidth(s.icon, size: font + 2) }
            if !s.title.isEmpty {
                if !s.icon.isEmpty || s.kind == .aiStatus { w += 4 }
                w += KeyboardController.approxTextWidth(s.title, size: font)
            }
            return max(s.minWidth, w + 18)
        }

        let available = layout.width - 2 * sp
        var chosen = build(compact: 0)
        for level in 0...4 {
            chosen = build(compact: level)
            let total = (chosen.left + chosen.right).reduce(CGFloat(0)) { $0 + itemWidth($1) + 4 }
            if total <= available { break }
        }

        var items: [ToolbarItem] = []
        var x = sp
        for s in chosen.left {
            let w = itemWidth(s)
            items.append(ToolbarItem(kind: s.kind, frame: CGRect(x: x, y: y, width: w, height: chipH),
                                     icon: s.icon, title: s.title, style: s.style))
            x += w + 4
        }
        var rx = layout.width - sp
        for s in chosen.right {
            let w = itemWidth(s)
            rx -= w
            items.append(ToolbarItem(kind: s.kind, frame: CGRect(x: rx, y: y, width: w, height: chipH),
                                     icon: s.icon, title: s.title, style: s.style))
            rx -= 4
        }
        return items
    }

    private func toolbarItem(at p: CGPoint) -> ToolbarItem? {
        let items = toolbarItems()
        if let hit = items.first(where: { $0.frame.insetBy(dx: -2, dy: -6).contains(p) }) {
            return hit
        }
        // Nearest item within 16 pt.
        var best: ToolbarItem? = nil
        var bestD: CGFloat = 16
        for item in items {
            let d = p.x < item.frame.minX ? item.frame.minX - p.x : (p.x > item.frame.maxX ? p.x - item.frame.maxX : 0)
            if d < bestD {
                bestD = d
                best = item
            }
        }
        return best
    }

    private func handleToolbarTap(_ kind: ToolbarItemKind) {
        switch kind {
        case .aiStatus:
            tapAIStatus()
        case .undo:
            if engine.undo() {
                updateAutoCapitalization()
            }
            redraw()
        case .emotion:
            showPanel(.tips, scrollToEmotion: true)
        case .correctNow:
            engine.correctNow()
        case .tips:
            showPanel(panel == .tips ? .keys : .tips)
        case .language:
            cycleLanguage()
        case .layoutToggle:
            toggleLayoutMode()
        case .settings:
            delegate?.keyboardOpenSettings(self)
        }
    }

    private func tapAIStatus() {
        switch indicator {
        case .fullAccessNeeded:
            showToast(loc("onboarding.iosFullAccess"), action: .openSettings)
        case .locked:
            showToast(loc("toolbar.aiLocked"))
        case .noKey:
            showToast(loc("errors.noKey"), action: .openSettings)
        case .error:
            if case .error(let e) = engine.status {
                showToast(e.message, action: .openSettings)
            }
        case .off:
            if !engine.settings.aiEnabled {
                showToast(loc("toolbar.aiOff"), action: .openSettings)
            } else {
                engine.sessionEnabled = true
                showToast(loc("toolbar.aiOn"))
            }
        case .ready, .working:
            engine.sessionEnabled = false
            showToast(loc("toolbar.aiOff"))
        }
        redraw()
    }

    private func toolbarLongPress(id: Int) {
        guard var press = toolbarPress, press.id == id, !press.longFired else { return }
        press.longFired = true
        toolbarPress = press
        switch press.kind {
        case .aiStatus:
            delegate?.keyboard(self, feedback: .popupOpened)
            engine.correctNow()
        case .language:
            openLanguagePopup(ownerId: id)
        default:
            break
        }
    }

    private func openLanguagePopup(ownerId: Int) {
        let langs = settings.languages
        guard !langs.isEmpty, let anchor = toolbarItems().first(where: { $0.kind == .language })?.frame else { return }
        let widest = langs.map { KeyboardController.approxTextWidth($0.short, size: 15) }.max() ?? 30
        let pl = PopupLayout.make(anchor: anchor, count: langs.count, unit: max(48, widest + 20),
                                  rowHeight: layout.rowHeight, width: layout.width,
                                  sidePadding: layout.metrics.sidePadding, below: true)
        let current = langs.firstIndex(where: { $0.id == settings.activeLanguage.id })
        languagePopup = LanguagePopup(layout: pl, languages: langs, selected: current, ownerId: ownerId)
        delegate?.keyboard(self, feedback: .popupOpened)
        redraw()
    }

    // MARK: Toasts

    public func showToast(_ message: String, action: ToastAction? = nil) {
        toastCounter += 1
        let id = toastCounter
        toast = Toast(message: message, actionTitle: action == nil ? nil : loc("onboarding.openSettings"), action: action, id: id)
        scheduler.schedule(after: action == nil ? 2.5 : 4.5) { [weak self] in
            guard let self = self, self.toast?.id == id else { return }
            self.toast = nil
            self.redraw()
        }
        redraw()
    }

    private func handleToastTap() {
        let action = toast?.action
        toast = nil
        if action == .openSettings {
            delegate?.keyboardOpenSettings(self)
        }
        redraw()
    }

    // MARK: Rendering

    private func redraw() {
        delegate?.keyboardNeedsDisplay(self)
    }

    public func renderState() -> KeyboardRenderState {
        let pressed = interpreter.pressedKeyIndices
        let firstSpace = layout.keys.first(where: { $0.key == "$space" })?.index
        var keys: [RenderKey] = []
        if panel == .keys {
            keys.reserveCapacity(layout.keys.count)
            for k in layout.keys {
                keys.append(renderKey(k, pressed: pressed.contains(k.index), firstSpace: firstSpace))
            }
        }
        var popupRender: RenderPopup? = nil
        if let pop = interpreter.popup {
            popupRender = RenderPopup(frame: pop.layout.frame, cells: pop.layout.cells, labels: pop.alternatives, selected: pop.selected)
        } else if let lp = languagePopup {
            popupRender = RenderPopup(frame: lp.layout.frame, cells: lp.layout.cells, labels: lp.languages.map { $0.short }, selected: lp.selected)
        }
        var phase: CGFloat = 0
        if animating {
            let period = 1.2
            phase = CGFloat(scheduler.now.truncatingRemainder(dividingBy: period) / period)
        }
        return KeyboardRenderState(size: CGSize(width: layout.width, height: layout.totalHeight),
                                   metrics: layout.metrics, showKeys: panel == .keys, keys: keys,
                                   toolbar: toolbarItems(), toolbarPressed: toolbarPressed,
                                   indicator: indicator, animationPhase: phase, popup: popupRender, toast: toast)
    }

    private func renderKey(_ k: KeyItem, pressed: Bool, firstSpace: Int?) -> RenderKey {
        var label = ""
        var hint: String? = nil
        var kind = KeyVisualKind.special
        var small = true
        switch k.key {
        case "$shift":
            kind = .shift
        case "$back":
            label = "⌫"
            small = false
        case "$space":
            kind = .space
            if !layout.isHexBoard || k.index == firstSpace {
                label = settings.activeLanguage.short
            }
        case "$enter":
            kind = .enter
            if let r = returnKeyLabel, !r.isEmpty {
                label = r
            } else {
                label = "⏎"
                small = false
            }
        case "$123", "$sym1":
            label = loc("keys.symbols")
        case "$sym2":
            label = loc("keys.symbols2")
        case "$abc":
            label = loc("keys.letters")
        case "$emoji":
            label = "😊"
            small = false
        case "$nextkb":
            label = "🌐"
            small = false
        default:
            kind = .character
            small = false
            var c = KeyAlternatives.character(for: k, bottomPunctuation: layout.bottomPunctuation)
            if page == .letters && shift.isActive {
                c = KeyAlternatives.uppercase(c)
            }
            label = c
            if layout.isHexBoard && settings.longPressDigits && page == .letters && k.area == .letters
                && k.row == 0 && k.column < 10 {
                hint = String((k.column + 1) % 10)
            }
        }
        return RenderKey(item: k, label: label, hint: hint, kind: kind, pressed: pressed, shift: shift,
                         showsSpinner: k.key == "$enter" && enterPending, smallLabel: small)
    }
}
