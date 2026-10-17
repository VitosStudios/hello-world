import AppKit
import SwiftUI
import TippliCore

/// A floating panel that never becomes key, so the app the user types in
/// keeps the keyboard focus (on-screen keyboard and tips window).
final class NonActivatingPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }

    static func make(size: NSSize, resizable: Bool) -> NonActivatingPanel {
        var style: NSWindow.StyleMask = [.nonactivatingPanel, .titled, .closable, .utilityWindow, .fullSizeContentView]
        if resizable { style.insert(.resizable) }
        let panel = NonActivatingPanel(contentRect: NSRect(origin: .zero, size: size), styleMask: style,
                                       backing: .buffered, defer: false)
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.becomesKeyOnlyIfNeeded = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = false
        return panel
    }
}

/// NSView drawing the keyboard with the shared renderer; mouse/pen/touch
/// screen input goes through the same gesture interpreter as on iOS.
final class KeyboardNSView: NSView {
    weak var keyboard: KeyboardController?
    var palette: ThemePalette = ThemePalette.make(dark: false) {
        didSet { needsDisplay = true }
    }

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { false }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext, let kb = keyboard else { return }
        KeyboardRenderer.draw(kb.renderState(), palette: palette, in: ctx)
    }

    private func point(_ event: NSEvent) -> CGPoint {
        convert(event.locationInWindow, from: nil)
    }

    override func mouseDown(with event: NSEvent) {
        keyboard?.pointerDown(id: 1, at: point(event))
    }

    override func mouseDragged(with event: NSEvent) {
        keyboard?.pointerMoved(id: 1, to: point(event))
    }

    override func mouseUp(with event: NSEvent) {
        keyboard?.pointerUp(id: 1, at: point(event))
    }
}

/// The optional on-screen keyboard (SPEC §9.3, §10).
final class OnScreenKeyboardWindow: NSObject, NSWindowDelegate {
    let panel: NonActivatingPanel
    private let keyboardView = KeyboardNSView(frame: .zero)
    private var emojiHost: NSView?
    private weak var keyboard: KeyboardController?
    private var animationTimer: Timer?
    private var settingsTheme: () -> String

    init(keyboard: KeyboardController, theme: @escaping () -> String) {
        self.keyboard = keyboard
        self.settingsTheme = theme
        let width: CGFloat = 760
        keyboard.setWidth(width)
        panel = NonActivatingPanel.make(size: NSSize(width: width, height: keyboard.totalHeight), resizable: true)
        super.init()
        panel.title = loc("app.keyboardName")
        panel.delegate = self
        panel.contentMinSize = NSSize(width: 420, height: 200)
        keyboardView.keyboard = keyboard
        keyboardView.frame = NSRect(x: 0, y: 0, width: width, height: keyboard.totalHeight)
        keyboardView.autoresizingMask = [.width, .height]
        panel.contentView = keyboardView
        updatePalette()
        positionAtBottom()
    }

    var isVisible: Bool { panel.isVisible }

    func show() {
        updatePalette()
        panel.orderFrontRegardless()
    }

    func hide() {
        panel.orderOut(nil)
    }

    func redraw() {
        keyboardView.needsDisplay = true
    }

    func updatePalette() {
        let systemDark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        keyboardView.palette = ThemePalette.make(dark: ThemePalette.isDark(setting: settingsTheme(), systemIsDark: systemDark))
    }

    /// Height follows the layout (hex/normal, key height, number row).
    func updateHeight() {
        guard let kb = keyboard else { return }
        var frame = panel.frame
        let content = panel.contentRect(forFrameRect: frame)
        let newContent = NSRect(x: content.minX, y: content.maxY - kb.totalHeight, width: content.width, height: kb.totalHeight)
        frame = panel.frameRect(forContentRect: newContent)
        panel.setFrame(frame, display: true)
        layoutEmoji()
        redraw()
    }

    private func positionAtBottom() {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let size = panel.frame.size
        panel.setFrameOrigin(NSPoint(x: visible.midX - size.width / 2, y: visible.minY + 12))
    }

    func windowDidResize(_ notification: Notification) {
        guard let kb = keyboard else { return }
        let w = panel.contentRect(forFrameRect: panel.frame).width
        kb.setWidth(w)
        let h = panel.contentRect(forFrameRect: panel.frame).height
        if abs(h - kb.totalHeight) > 0.5 {
            updateHeight()
        }
        layoutEmoji()
    }

    func windowWillClose(_ notification: Notification) {
        DesktopController.shared.keyboardWindowClosed()
    }

    // MARK: Emoji panel

    func showEmoji(palette: ThemePalette, recents: [String], onEmoji: @escaping (String) -> Void,
                   onLetters: @escaping () -> Void, onDelete: @escaping () -> Void) {
        hideEmoji()
        let view = EmojiPanelView(palette: palette, recents: recents, onEmoji: onEmoji, onLetters: onLetters, onDelete: onDelete)
        let host = NSHostingView(rootView: view)
        keyboardView.addSubview(host)
        emojiHost = host
        layoutEmoji()
    }

    func hideEmoji() {
        emojiHost?.removeFromSuperview()
        emojiHost = nil
    }

    private func layoutEmoji() {
        guard let host = emojiHost, let kb = keyboard else { return }
        let top = kb.layout.toolbarHeight
        host.frame = NSRect(x: 0, y: top, width: keyboardView.bounds.width, height: max(0, keyboardView.bounds.height - top))
    }

    // MARK: Animation

    func setAnimating(_ on: Bool) {
        if on {
            guard animationTimer == nil else { return }
            let t = Timer(timeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
                self?.redraw()
            }
            RunLoop.main.add(t, forMode: .common)
            animationTimer = t
        } else {
            animationTimer?.invalidate()
            animationTimer = nil
            redraw()
        }
    }
}

/// The floating tips window for the desktop (SPEC §10).
final class TipsWindow {
    private var panel: NonActivatingPanel?

    var frame: NSRect? { panel?.isVisible == true ? panel?.frame : nil }

    func show(model: TipsModel, palette: ThemePalette) {
        let p = panel ?? NonActivatingPanel.make(size: NSSize(width: 440, height: 560), resizable: true)
        p.title = loc("tips.title")
        let host = NSHostingView(rootView: TipsPanelView(model: model, palette: palette))
        p.contentView = host
        if panel == nil {
            let mouse = NSEvent.mouseLocation
            let screen = NSScreen.screens.first { $0.frame.contains(mouse) } ?? NSScreen.main
            if let visible = screen?.visibleFrame {
                var origin = NSPoint(x: mouse.x - 220, y: mouse.y - 580)
                origin.x = min(max(origin.x, visible.minX + 8), visible.maxX - 448)
                origin.y = min(max(origin.y, visible.minY + 8), visible.maxY - 568)
                p.setFrameOrigin(origin)
            }
            panel = p
        }
        p.orderFrontRegardless()
    }

    func close() {
        panel?.orderOut(nil)
    }
}
