import UIKit
import SwiftUI
import TippliCore

/// The Tippli keyboard extension (SPEC §9.2).
final class KeyboardViewController: UIInputViewController {
    private var store: SettingsStore!
    private var secrets: SecretStore!
    private var aiService: AIService!
    private var engine: CorrectionEngine!
    private var kb: KeyboardController!
    private var textHost: ProxyTextHost!

    private let keyboardView = KeyboardInputView(frame: .zero)
    private var heightConstraint: NSLayoutConstraint?
    private var panelController: UIViewController?
    private var animationTimer: Timer?

    private var lastDocumentId: UUID?
    private var expectedContext: String?
    private var lastTraits: FieldTraits?

    // MARK: Lifecycle

    override func loadView() {
        // A UIInputView adopting UIInputViewAudioFeedback enables input clicks.
        let root = TippliInputView(frame: CGRect(x: 0, y: 0, width: 320, height: 300), inputViewStyle: .keyboard)
        root.allowsSelfSizing = true
        self.inputView = root
        if viewIfLoaded !== root {
            self.view = root
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        let defaults: UserDefaults = hasFullAccess
            ? SettingsStore.appGroupDefaults(groupId: SettingsStore.bundleAppGroup)
            : .standard
        store = SettingsStore(defaults: defaults)
        secrets = SecretStore(accessGroup: SecretStore.bundleAccessGroup)
        aiService = AIService(settings: store, secrets: secrets)
        engine = CorrectionEngine(scheduler: MainScheduler.shared)
        textHost = ProxyTextHost(controller: self)
        engine.host = textHost
        engine.ai = aiService

        kb = KeyboardController(store: store, engine: engine, scheduler: MainScheduler.shared)
        kb.host = textHost
        kb.delegate = self

        keyboardView.keyboard = kb
        keyboardView.translatesAutoresizingMaskIntoConstraints = true
        keyboardView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        keyboardView.frame = view.bounds
        keyboardView.onNextKeyboardTouch = { [weak self] view, event in
            self?.handleInputModeList(from: view, with: event ?? UIEvent())
        }
        view.addSubview(keyboardView)
        updatePalette()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        aiService.reload()
        kb.fullAccessMissing = !hasFullAccess
        kb.needsNextKeyboard = needsInputModeSwitchKey
        kb.reloadSettings()
        kb.setWidth(view.bounds.width > 0 ? view.bounds.width : 320)
        startField()
        updatePalette()
        updateHeight()
        keyboardView.setNeedsDisplay()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Touches near the screen edges must not be delayed by system gestures.
        view.window?.gestureRecognizers?.forEach { $0.delaysTouchesBegan = false }
        kb.needsNextKeyboard = needsInputModeSwitchKey
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        keyboardView.resetTouches()
        setAnimating(false)
        closePanel()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = view.bounds.width
        if w > 0 {
            kb.setWidth(w)
        }
        keyboardView.frame = view.bounds
        layoutPanel()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updatePalette()
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        if kb.panel != .emoji {
            SharedResources.shared.releaseEmoji()
        }
    }

    // MARK: Field handling

    private func currentTraits() -> FieldTraits {
        FieldTraits(proxy: textDocumentProxy)
    }

    private func startField() {
        lastDocumentId = textDocumentProxy.documentIdentifier
        let traits = currentTraits()
        lastTraits = traits
        kb.returnKeyLabel = (textDocumentProxy.returnKeyType ?? .default).tippliLabel
        kb.startField(kind: traits.kind)
        rememberContext()
    }

    private func rememberContext() {
        expectedContext = textDocumentProxy.documentContextBeforeInput ?? ""
    }

    override func textWillChange(_ textInput: UITextInput?) {
        super.textWillChange(textInput)
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        guard kb != nil else { return }
        let docId = textDocumentProxy.documentIdentifier
        let traits = currentTraits()
        if docId != lastDocumentId || traits != lastTraits {
            startField()
            return
        }
        kb.returnKeyLabel = (textDocumentProxy.returnKeyType ?? .default).tippliLabel
        let ctx = textDocumentProxy.documentContextBeforeInput ?? ""
        if ctx != expectedContext {
            expectedContext = ctx
            kb.externalTextChange()
        }
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        guard kb != nil else { return }
        let ctx = textDocumentProxy.documentContextBeforeInput ?? ""
        if ctx != expectedContext {
            expectedContext = ctx
            kb.externalTextChange()
        }
    }

    // MARK: Appearance

    private func updatePalette() {
        let dark = ThemePalette.isDark(setting: store?.string(SettingKey.theme) ?? "system",
                                       systemIsDark: traitCollection.userInterfaceStyle == .dark)
        keyboardView.palette = ThemePalette.make(dark: dark)
        keyboardView.setNeedsDisplay()
    }

    private func updateHeight() {
        let h = kb.totalHeight
        if let c = heightConstraint {
            if abs(c.constant - h) > 0.5 { c.constant = h }
        } else {
            let c = view.heightAnchor.constraint(equalToConstant: h)
            c.priority = UILayoutPriority(rawValue: 999)
            c.isActive = true
            heightConstraint = c
        }
        layoutPanel()
    }

    private func setAnimating(_ on: Bool) {
        if on {
            guard animationTimer == nil else { return }
            let t = Timer(timeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
                self?.keyboardView.setNeedsDisplay()
            }
            RunLoop.main.add(t, forMode: .common)
            animationTimer = t
        } else {
            animationTimer?.invalidate()
            animationTimer = nil
            keyboardView.setNeedsDisplay()
        }
    }

    // MARK: Panels (emoji / tips)

    private func layoutPanel() {
        guard let pc = panelController, kb != nil else { return }
        let top = kb.layout.toolbarHeight
        pc.view.frame = CGRect(x: 0, y: top, width: view.bounds.width, height: max(0, kb.totalHeight - top))
    }

    private func closePanel() {
        guard let pc = panelController else { return }
        pc.willMove(toParent: nil)
        pc.view.removeFromSuperview()
        pc.removeFromParent()
        panelController = nil
        SharedResources.shared.releaseEmoji()
    }

    private func showPanelView<Content: View>(_ content: Content) {
        closePanel()
        let host = UIHostingController(rootView: content)
        host.view.backgroundColor = UIColor(cgColor: keyboardView.palette.panel.cgColor)
        addChild(host)
        view.addSubview(host.view)
        host.didMove(toParent: self)
        panelController = host
        layoutPanel()
    }

    private func showEmojiPanel() {
        let palette = keyboardView.palette
        let panel = EmojiPanelView(
            palette: palette,
            recents: store.emojiRecents,
            onEmoji: { [weak self] e in self?.kb.insertEmoji(e) },
            onLetters: { [weak self] in self?.kb.showPanel(.keys) },
            onDelete: { [weak self] in self?.kb.deleteBackward() })
        showPanelView(panel)
    }

    private func showTipsPanel(scrollToEmotion: Bool) {
        let proxy = textDocumentProxy
        let selection = proxy.selectedText ?? ""
        let isSelection = !selection.isEmpty
        let text: String
        if isSelection {
            text = selection
        } else {
            text = (proxy.documentContextBeforeInput ?? "") + (proxy.documentContextAfterInput ?? "")
        }
        let model = TipsModel(text: text, isSelection: isSelection, service: aiService,
                              history: engine.history, scrollToEmotion: scrollToEmotion)
        model.onClose = { [weak self] in self?.kb.showPanel(.keys) }
        model.onReplace = { [weak self] newText in
            guard let self = self else { return }
            self.kb.replaceScope(selection: isSelection, with: newText)
            self.kb.showPanel(.keys)
        }
        model.onInsert = { [weak self] newText in
            guard let self = self else { return }
            let insertion = isSelection ? selection + " " + newText : "\n" + newText
            self.kb.insertFromTips(insertion)
            self.kb.showPanel(.keys)
        }
        model.onCopy = { text in
            UIPasteboard.general.string = text
        }
        if scrollToEmotion {
            engine.clearMood()
        }
        showPanelView(TipsPanelView(model: model, palette: keyboardView.palette))
    }

    // MARK: Opening the container app

    private func openContainerApp() {
        guard let url = URL(string: "tippli://settings") else { return }
        // Keyboard extensions cannot use UIApplication.shared; walk the
        // responder chain to the application object instead.
        typealias OpenURLFunction = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let r = responder {
            if r.responds(to: selector), NSStringFromClass(type(of: r)).contains("Application") {
                if let imp = r.method(for: selector) {
                    let fn = unsafeBitCast(imp, to: OpenURLFunction.self)
                    fn(r, selector, url as NSURL, NSDictionary(), nil)
                    return
                }
            }
            responder = r.next
        }
        kb.showToast(loc("onboarding.openSettings") + ": Tippli")
    }
}

// MARK: - KeyboardControllerDelegate

extension KeyboardViewController: KeyboardControllerDelegate {
    func keyboardNeedsDisplay(_ controller: KeyboardController) {
        keyboardView.setNeedsDisplay()
    }

    func keyboardHeightChanged(_ controller: KeyboardController) {
        updateHeight()
    }

    func keyboard(_ controller: KeyboardController, feedback: KeyboardFeedback) {
        let settings = controller.settings
        if feedback == .keyDown && settings.sound {
            UIDevice.current.playInputClick()
        }
        // Haptics need full access in keyboard extensions.
        guard settings.haptics, hasFullAccess else { return }
        switch feedback {
        case .keyDown:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .popupOpened:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .noop:
            UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.5)
        case .emotionAlert:
            let gen = UIImpactFeedbackGenerator(style: .rigid)
            gen.impactOccurred()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                gen.impactOccurred()
            }
        }
    }

    func keyboard(_ controller: KeyboardController, showPanel panel: KeyboardPanel, scrollToEmotion: Bool) {
        switch panel {
        case .keys:
            closePanel()
        case .emoji:
            showEmojiPanel()
        case .tips:
            showTipsPanel(scrollToEmotion: scrollToEmotion)
        }
        keyboardView.setNeedsDisplay()
    }

    func keyboardOpenSettings(_ controller: KeyboardController) {
        openContainerApp()
    }

    func keyboardNextInputMode(_ controller: KeyboardController) {
        advanceToNextInputMode()
    }

    func keyboardDidEditText(_ controller: KeyboardController) {
        rememberContext()
    }

    func keyboard(_ controller: KeyboardController, animating: Bool) {
        setAnimating(animating)
    }
}
