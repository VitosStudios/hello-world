import AppKit
import ApplicationServices
import Carbon
import ServiceManagement
import SwiftUI
import TippliCore

/// The macOS background app (SPEC §9.3, §10): event tap + buffer + engine,
/// hotkeys, on-screen keyboard, tips window, settings and permissions.
final class DesktopController: ObservableObject {
    static let shared = DesktopController()

    let store: SettingsStore
    let secrets: SecretStore
    let ai: AIService
    let engine: CorrectionEngine
    let buffer = DesktopTextBuffer()
    let injector = EventInjector()
    let translator = KeyTranslator()
    let tap = EventTap()
    let settingsModel: SettingsModel
    private(set) var keyboardController: KeyboardController!

    @Published private(set) var liveCorrection = true
    @Published private(set) var accessibilityGranted = false
    @Published private(set) var inputMonitoringGranted = false
    @Published private(set) var tapRunning = false
    @Published private(set) var keyboardVisible = false
    @Published private(set) var statusText = ""

    private var keyboardWindow: OnScreenKeyboardWindow?
    private let tipsWindow = TipsWindow()
    private var settingsWindow: NSWindow?
    private var permissionsWindow: NSWindow?
    private var permissionTimer: Timer?
    private var observers: [NSObjectProtocol] = []

    private var needsFocusCheck = true
    private var skipping = false
    private var enterInProgress = false
    private var enterStopRequested = false
    private var started = false

    var permissionsOK: Bool { accessibilityGranted && inputMonitoringGranted }

    private init() {
        store = SettingsStore(defaults: .standard)
        secrets = SecretStore(accessGroup: nil)
        settingsModel = SettingsModel(store: store, secrets: secrets)
        ai = settingsModel.service
        engine = CorrectionEngine(scheduler: MainScheduler.shared)
        engine.host = buffer
        engine.ai = ai
        buffer.injector = injector
        buffer.onReset = { [weak self] in
            guard let self = self else { return }
            self.engine.resetContext()
            self.translator.resetDeadKeys()
            self.needsFocusCheck = true
            self.keyboardController?.externalTextChange()
        }
        buffer.replaceSelectionHandler = { [weak self] text in
            guard let self = self else { return }
            Task { @MainActor in await self.paste(text) }
        }

        let kb = KeyboardController(store: store, engine: engine, scheduler: MainScheduler.shared)
        kb.host = buffer
        kb.delegate = self
        keyboardController = kb
        // Keep the menu status in sync as well.
        let keyboardStatus = engine.onStatusChange
        engine.onStatusChange = { [weak self] in
            keyboardStatus?()
            self?.updateStatus()
        }
        tap.handler = { [weak self] type, event in
            self?.handle(type: type, event: event) ?? false
        }
    }

    // MARK: Startup

    func start() {
        guard !started else { return }
        started = true
        applySettings()
        keyboardController.startField(kind: .normal)
        refreshPermissions()
        if permissionsOK {
            startTap()
        } else {
            showPermissions()
        }
        startPermissionPolling()

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.applySettings()
        })
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] _ in
                self?.buffer.reset()
            })
        observers.append(DistributedNotificationCenter.default().addObserver(
            forName: Notification.Name("AppleInterfaceThemeChangedNotification"), object: nil, queue: .main) { [weak self] _ in
                self?.keyboardWindow?.updatePalette()
            })

        syncLaunchAtLogin()
        if store.bool(SettingKey.desktopShowKeyboardOnStart) {
            showKeyboard()
        }
    }

    private var lastAppliedSignature = ""

    /// Settings changes apply immediately on the desktop (SPEC §8).
    private func applySettings() {
        ai.reload()
        let live = store.bool(SettingKey.desktopLiveCorrection)
        if live != liveCorrection { liveCorrection = live }
        var es = store.engineSettings()
        es.aiEnabled = es.aiEnabled && live
        if es != engine.settings { engine.settings = es }
        // Only rebuild the keyboard when something it uses changed.
        let sig = [store.string(SettingKey.keyboardMode), store.string(SettingKey.keyHeight),
                   store.string(SettingKey.activeLanguage), store.stringArray(SettingKey.languages).joined(separator: ","),
                   String(store.bool(SettingKey.numberRow)), String(store.bool(SettingKey.longPressAccents)),
                   String(store.bool(SettingKey.longPressDigits)), String(store.int(SettingKey.longPressMs)),
                   store.string(SettingKey.trigger), store.string(SettingKey.theme),
                   String(store.bool(SettingKey.autoCapitalize)), String(store.bool(SettingKey.doubleSpacePeriod))]
            .joined(separator: "|")
        if sig != lastAppliedSignature {
            lastAppliedSignature = sig
            keyboardController.reloadSettings()
            keyboardWindow?.updatePalette()
            keyboardWindow?.updateHeight()
        }
        syncLaunchAtLogin()
        updateStatus()
    }

    private func updateStatus() {
        let name = store.providerPreset.shortName
        let state = liveCorrection ? AppleStrings.statusOn : AppleStrings.statusOff
        var text = "\(name) · \(loc("settings.desktopLive")): \(state)"
        if !permissionsOK {
            text = loc("onboarding.macPermissions")
        } else if case .error(let e) = engine.status {
            text = "\(name) · \(e.message)"
        } else if engine.status == .noKey {
            text = "\(name) · \(loc("toolbar.aiNoKey"))"
        }
        if text != statusText { statusText = text }
        keyboardWindow?.redraw()
    }

    private func syncLaunchAtLogin() {
        let want = store.bool(SettingKey.desktopLaunchAtLogin)
        let service = SMAppService.mainApp
        do {
            if want && service.status != .enabled {
                try service.register()
            } else if !want && service.status == .enabled {
                try service.unregister()
            }
        } catch {
            NSLog("Tippli: launch at login could not be changed: \(error.localizedDescription)")
        }
    }

    // MARK: Permissions

    func refreshPermissions() {
        let ax = AXIsProcessTrusted()
        let im = CGPreflightListenEventAccess()
        if ax != accessibilityGranted { accessibilityGranted = ax }
        if im != inputMonitoringGranted { inputMonitoringGranted = im }
    }

    private func startPermissionPolling() {
        permissionTimer?.invalidate()
        let t = Timer(timeInterval: 2, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.refreshPermissions()
            if self.permissionsOK && !self.tap.isRunning {
                self.startTap()
                self.permissionsWindow?.close()
            }
            self.updateStatus()
        }
        RunLoop.main.add(t, forMode: .common)
        permissionTimer = t
    }

    func requestAccessibility() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        openPrivacyPane("Privacy_Accessibility")
    }

    func requestInputMonitoring() {
        _ = CGRequestListenEventAccess()
        openPrivacyPane("Privacy_ListenEvent")
    }

    private func openPrivacyPane(_ anchor: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?" + anchor) {
            NSWorkspace.shared.open(url)
        }
    }

    private func startTap() {
        let ok = tap.start()
        if ok != tapRunning { tapRunning = ok }
        updateStatus()
    }

    func showPermissions() {
        if permissionsWindow == nil {
            let host = NSHostingController(rootView: PermissionsWindowView(desktop: self))
            let w = NSWindow(contentViewController: host)
            w.title = loc("app.name")
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            w.center()
            permissionsWindow = w
        }
        NSApp.activate(ignoringOtherApps: true)
        permissionsWindow?.makeKeyAndOrderFront(nil)
    }

    // MARK: Event tap handling (SPEC §10)

    private func handle(type: CGEventType, event: CGEvent) -> Bool {
        switch type {
        case .leftMouseDown, .rightMouseDown, .otherMouseDown:
            // Clicks on our own floating panels do not move the caret.
            let mouse = NSEvent.mouseLocation
            if let kw = keyboardWindow, kw.isVisible, kw.panel.frame.contains(mouse) { return false }
            if let tf = tipsWindow.frame, tf.contains(mouse) { return false }
            buffer.reset()
            return false
        case .keyDown:
            return handleKeyDown(event)
        default:
            return false
        }
    }

    private func handleKeyDown(_ event: CGEvent) -> Bool {
        let keyCode = UInt16(truncatingIfNeeded: event.getIntegerValueField(.keyboardEventKeycode))
        let flags = event.flags
        let cmd = flags.contains(.maskCommand)
        let ctrl = flags.contains(.maskControl)
        let alt = flags.contains(.maskAlternate)

        // Hotkeys ⌃⌥K / H / B / J.
        if ctrl && alt && !cmd {
            switch keyCode {
            case 40:
                DispatchQueue.main.async { self.setLiveCorrection(!self.liveCorrection) }
                return true
            case 4:
                DispatchQueue.main.async { self.tipsForSelection() }
                return true
            case 11:
                DispatchQueue.main.async { self.toggleKeyboard() }
                return true
            case 38:
                DispatchQueue.main.async { self.correctNow() }
                return true
            default:
                break
            }
        }

        guard liveCorrection else { return false }

        if IsSecureEventInputEnabled() {
            buffer.reset()
            return false
        }
        if needsFocusCheck {
            needsFocusCheck = false
            skipping = computeSkip()
        }
        if skipping { return false }

        // Any Ctrl/Cmd shortcut (paste, undo, Ctrl+Backspace, …) resets the buffer.
        if cmd || ctrl {
            buffer.reset()
            return false
        }

        switch keyCode {
        case 36, 76: // Return, keypad Enter
            return handleEnter(event, keyCode: keyCode)
        case 51: // Backspace
            if alt {
                buffer.reset()
            } else {
                buffer.backspaceTyped()
                engine.noteNonAppend()
                keyboardController.externalTextChange()
            }
            return false
        case 48, 53, 115, 116, 119, 121, 123, 124, 125, 126:
            // Tab, Escape, Home, PageUp, End, PageDown, arrows.
            buffer.reset()
            return false
        default:
            break
        }

        guard let text = translator.translate(keyCode: keyCode, flags: flags) else { return false }
        if text.isEmpty { return false } // dead key pending
        guard DesktopController.isPrintable(text) else { return false }
        buffer.typed(text)
        engine.noteAppend(text)
        return false
    }

    static func isPrintable(_ text: String) -> Bool {
        for scalar in text.unicodeScalars {
            let v = scalar.value
            if v < 0x20 || v == 0x7F || (v >= 0xF700 && v <= 0xF8FF) { return false }
        }
        return true
    }

    /// Enter / Shift+Enter: wait for a pending correction, then re-inject the key.
    private func handleEnter(_ event: CGEvent, keyCode: UInt16) -> Bool {
        if enterInProgress {
            // Second Enter during the wait: continue at once.
            enterStopRequested = true
            return true
        }
        let wait = Double(store.int(SettingKey.waitOnSendMs)) / 1000
        guard wait > 0, engine.hasPendingWork else {
            buffer.reset()
            return false
        }
        let flags = event.flags.intersection([.maskShift, .maskAlternate])
        enterInProgress = true
        enterStopRequested = false
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            await self.engine.flush(timeout: wait, stop: { [weak self] in self?.enterStopRequested ?? true })
            self.injector.postKey(CGKeyCode(keyCode), flags: flags)
            self.enterInProgress = false
            self.buffer.reset()
        }
        return true
    }

    private func computeSkip() -> Bool {
        guard let app = NSWorkspace.shared.frontmostApplication else { return false }
        if app.processIdentifier == ProcessInfo.processInfo.processIdentifier { return true }
        if isExcluded(app) { return true }
        return focusedElementIsSecure()
    }

    private func isExcluded(_ app: NSRunningApplication) -> Bool {
        let list = Set(store.stringArray(SettingKey.desktopExcludedApps).map { $0.lowercased() })
        var names: [String] = []
        if let n = app.localizedName { names.append(n) }
        if let u = app.bundleURL { names.append(u.deletingPathExtension().lastPathComponent) }
        if let e = app.executableURL { names.append(e.lastPathComponent) }
        if let b = app.bundleIdentifier { names.append(b) }
        return names.contains { list.contains($0.lowercased()) }
    }

    private func focusedElementIsSecure() -> Bool {
        let system = AXUIElementCreateSystemWide()
        var focused: CFTypeRef?
        guard AXUIElementCopyAttributeValue(system, kAXFocusedUIElementAttribute as CFString, &focused) == .success,
              let value = focused, CFGetTypeID(value) == AXUIElementGetTypeID() else { return false }
        let element = unsafeBitCast(value, to: AXUIElement.self)
        var subrole: CFTypeRef?
        if AXUIElementCopyAttributeValue(element, kAXSubroleAttribute as CFString, &subrole) == .success,
           let s = subrole as? String, s == (kAXSecureTextFieldSubrole as String) {
            return true
        }
        return false
    }

    // MARK: Actions (menu + hotkeys)

    func setLiveCorrection(_ on: Bool) {
        store.set(on, forKey: SettingKey.desktopLiveCorrection)
        buffer.reset()
        applySettings()
    }

    func toggleKeyboard() {
        if keyboardWindow?.isVisible == true {
            keyboardWindow?.hide()
            keyboardVisible = false
        } else {
            showKeyboard()
        }
    }

    func showKeyboard() {
        if keyboardWindow == nil {
            keyboardWindow = OnScreenKeyboardWindow(keyboard: keyboardController, theme: { [weak self] in
                self?.store.string(SettingKey.theme) ?? "system"
            })
        }
        keyboardWindow?.show()
        keyboardVisible = true
    }

    func keyboardWindowClosed() {
        keyboardVisible = false
    }

    func showSettings() {
        if settingsWindow == nil {
            let host = NSHostingController(rootView: MacSettingsView(model: settingsModel, desktop: self))
            let w = NSWindow(contentViewController: host)
            w.title = loc("settings.title")
            w.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            w.setContentSize(NSSize(width: 580, height: 720))
            w.isReleasedWhenClosed = false
            w.center()
            settingsWindow = w
        }
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    private var palette: ThemePalette {
        let systemDark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        return ThemePalette.make(dark: ThemePalette.isDark(setting: store.string(SettingKey.theme), systemIsDark: systemDark))
    }

    /// ⌃⌥H / menu: tips for the selected text (falls back to the buffer).
    func tipsForSelection(scrollToEmotion: Bool = false) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let selection = await self.copySelection()
            let isSelection = !(selection ?? "").isEmpty
            let text = isSelection ? (selection ?? "") : self.buffer.buffer
            let model = TipsModel(text: text, isSelection: isSelection, service: self.ai,
                                  history: self.engine.history, scrollToEmotion: scrollToEmotion)
            model.onClose = { [weak self] in self?.tipsWindow.close() }
            model.onCopy = { text in
                _ = NSPasteboard.general.clearContents()
                _ = NSPasteboard.general.setString(text, forType: .string)
            }
            model.onReplace = { [weak self] newText in
                guard let self = self else { return }
                if isSelection {
                    Task { @MainActor in await self.paste(newText) }
                } else {
                    self.buffer.replaceScope(selection: false, with: newText)
                }
                self.engine.noteExternalReplace()
                self.tipsWindow.close()
            }
            model.onInsert = { [weak self] newText in
                guard let self = self else { return }
                if isSelection {
                    // Collapse the selection to its end, then type.
                    self.injector.postKey(EventInjector.keyRight)
                }
                self.buffer.insert(" " + newText)
                self.engine.noteExternalReplace()
                self.tipsWindow.close()
            }
            if scrollToEmotion { self.engine.clearMood() }
            self.tipsWindow.show(model: model, palette: self.palette)
        }
    }

    /// ⌃⌥J / menu: correct the selection (replace via paste) or flush the buffer.
    func correctNow() {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            let selection = await self.copySelection() ?? ""
            if selection.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                self.engine.correctNow()
                return
            }
            guard let client = self.ai.client() else {
                self.engine.correctNow()
                return
            }
            let lang = self.store.activeLanguage
            let request = self.ai.prompts.correction(language: lang, keyboardId: self.store.keyboardId(for: lang),
                                                     strength: self.store.string(SettingKey.strength),
                                                     context: "", segment: selection)
            do {
                let out = try await client.complete(request)
                if let cleaned = TextAlgorithms.cleanOutput(segment: selection, output: out, pinyin: lang.pinyin),
                   cleaned != selection {
                    await self.paste(cleaned)
                }
            } catch {
                NSLog("Tippli: correction failed: \(AIError.from(error).message)")
            }
        }
    }

    // MARK: Clipboard helpers

    /// Waits (max 0.6 s) until the hotkey modifiers are released.
    @MainActor
    private func waitForModifierRelease() async {
        for _ in 0..<30 {
            let flags = CGEventSource.flagsState(.hidSystemState)
            if !flags.contains(.maskControl) && !flags.contains(.maskAlternate) && !flags.contains(.maskCommand) { return }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    /// Simulated ⌘C with clipboard save/restore. nil = nothing selected.
    @MainActor
    func copySelection() async -> String? {
        await waitForModifierRelease()
        let pb = NSPasteboard.general
        let snapshot = Clipboard.snapshot()
        _ = pb.clearContents()
        let count = pb.changeCount
        injector.postKey(EventInjector.keyC, flags: .maskCommand)
        var result: String? = nil
        for _ in 0..<16 {
            try? await Task.sleep(nanoseconds: 25_000_000)
            if pb.changeCount != count {
                result = pb.string(forType: .string)
                break
            }
        }
        Clipboard.restore(snapshot)
        return result
    }

    /// Replaces the selection by pasting, then restores the clipboard.
    @MainActor
    func paste(_ text: String) async {
        await waitForModifierRelease()
        let pb = NSPasteboard.general
        let snapshot = Clipboard.snapshot()
        _ = pb.clearContents()
        _ = pb.setString(text, forType: .string)
        injector.postKey(EventInjector.keyV, flags: .maskCommand)
        try? await Task.sleep(nanoseconds: 350_000_000)
        Clipboard.restore(snapshot)
        buffer.reset()
    }
}

// MARK: - On-screen keyboard delegate

extension DesktopController: KeyboardControllerDelegate {
    func keyboardNeedsDisplay(_ controller: KeyboardController) {
        keyboardWindow?.redraw()
    }

    func keyboardHeightChanged(_ controller: KeyboardController) {
        keyboardWindow?.updateHeight()
    }

    func keyboard(_ controller: KeyboardController, showPanel panel: KeyboardPanel, scrollToEmotion: Bool) {
        switch panel {
        case .keys:
            keyboardWindow?.hideEmoji()
        case .emoji:
            keyboardWindow?.showEmoji(palette: palette, recents: store.emojiRecents,
                                      onEmoji: { [weak self] e in self?.keyboardController.insertEmoji(e) },
                                      onLetters: { [weak self] in self?.keyboardController.showPanel(.keys) },
                                      onDelete: { [weak self] in self?.keyboardController.deleteBackward() })
        case .tips:
            // The desktop shows tips in their own window.
            controller.showPanel(.keys)
            tipsForSelection(scrollToEmotion: scrollToEmotion)
        }
        keyboardWindow?.redraw()
    }

    func keyboardOpenSettings(_ controller: KeyboardController) {
        showSettings()
    }

    func keyboard(_ controller: KeyboardController, animating: Bool) {
        keyboardWindow?.setAnimating(animating)
    }
}
