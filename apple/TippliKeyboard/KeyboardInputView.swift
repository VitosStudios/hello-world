import UIKit
import TippliCore

/// The root input view. Adopting `UIInputViewAudioFeedback` enables
/// `UIDevice.current.playInputClick()` (respects the system click setting).
final class TippliInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

/// Draws the keyboard with the shared CoreGraphics renderer and feeds raw
/// multi-touch input into the platform-agnostic gesture interpreter
/// (SwiftUI gestures lose touches during fast two-thumb typing).
final class KeyboardInputView: UIView {
    weak var keyboard: KeyboardController?
    var palette: ThemePalette = ThemePalette.make(dark: false)
    /// Called for touches on `$nextkb` (wired to `handleInputModeList(from:with:)`).
    var onNextKeyboardTouch: ((UIView, UIEvent?) -> Void)?

    private var touchIds: [ObjectIdentifier: Int] = [:]
    private var nextKeyboardTouches = Set<ObjectIdentifier>()
    private var counter = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isExclusiveTouch = false
        isOpaque = true
        contentMode = .redraw
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let kb = keyboard else { return }
        KeyboardRenderer.draw(kb.renderState(), palette: palette, in: ctx)
    }

    // MARK: Touches

    private func isNextKeyboardKey(at p: CGPoint) -> Bool {
        guard let kb = keyboard, kb.panel == .keys, p.y >= kb.layout.toolbarHeight else { return false }
        return kb.layout.hitTest(p)?.key == "$nextkb"
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let kb = keyboard else { return }
        for t in touches {
            let p = t.location(in: self)
            let oid = ObjectIdentifier(t)
            if isNextKeyboardKey(at: p) {
                nextKeyboardTouches.insert(oid)
                onNextKeyboardTouch?(self, event)
                continue
            }
            counter += 1
            touchIds[oid] = counter
            kb.pointerDown(id: counter, at: p)
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let kb = keyboard else { return }
        for t in touches {
            let oid = ObjectIdentifier(t)
            if nextKeyboardTouches.contains(oid) {
                onNextKeyboardTouch?(self, event)
                continue
            }
            if let id = touchIds[oid] {
                kb.pointerMoved(id: id, to: t.location(in: self))
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let kb = keyboard else { return }
        for t in touches {
            let oid = ObjectIdentifier(t)
            if nextKeyboardTouches.remove(oid) != nil {
                onNextKeyboardTouch?(self, event)
                continue
            }
            if let id = touchIds.removeValue(forKey: oid) {
                kb.pointerUp(id: id, at: t.location(in: self))
            }
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let kb = keyboard else { return }
        for t in touches {
            let oid = ObjectIdentifier(t)
            if nextKeyboardTouches.remove(oid) != nil {
                onNextKeyboardTouch?(self, event)
                continue
            }
            if let id = touchIds.removeValue(forKey: oid) {
                kb.pointerCancelled(id: id)
            }
        }
    }

    func resetTouches() {
        touchIds.removeAll()
        nextKeyboardTouches.removeAll()
        keyboard?.cancelAllPointers()
    }
}
