import Foundation

/// Sends synthetic key events to the focused application (macOS: CGEvent).
public protocol KeyInjecting: AnyObject {
    func injectBackspaces(_ count: Int)
    func injectText(_ text: String)
    func injectReturn(shift: Bool)
    /// Arrow keys (negative = left).
    func injectArrows(_ offset: Int)
}

/// SPEC §10: desktop apps cannot read other programs' text, so the engine
/// works on our own buffer of what was typed before the caret (max 2000).
public final class DesktopTextBuffer: KeyboardTextHost {
    public static let maxLength = 2000

    public private(set) var buffer = ""
    public weak var injector: KeyInjecting?
    /// Called when the buffer is reset (the engine must invalidate requests).
    public var onReset: (() -> Void)?
    /// Replaces the current selection in the focused app (clipboard paste).
    public var replaceSelectionHandler: ((String) -> Void)?

    public init() {}

    // MARK: Physical keyboard

    /// Printable characters typed by the user (after dead-key composition).
    public func typed(_ text: String) {
        guard !text.isEmpty else { return }
        buffer += text
        trim()
    }

    /// The user pressed Backspace: remove the last grapheme (nothing is known
    /// any more when the buffer is already empty).
    public func backspaceTyped() {
        if !buffer.isEmpty { buffer.removeLast() }
    }

    /// Clear the buffer (mouse click, focus change, navigation keys, shortcuts, Enter).
    public func reset() {
        buffer = ""
        onReset?()
    }

    private func trim() {
        if buffer.count > DesktopTextBuffer.maxLength {
            buffer = String(buffer.suffix(DesktopTextBuffer.maxLength))
        }
    }

    // MARK: TextHost

    public func textBeforeCursor(_ maxChars: Int) -> String {
        String(buffer.suffix(max(0, maxChars)))
    }

    public func textAfterCursor(_ maxChars: Int) -> String {
        ""
    }

    public func selectedText() -> String {
        ""
    }

    public func delete(_ text: String) {
        let n = text.count
        guard n > 0 else { return }
        injector?.injectBackspaces(n)
        if buffer.count >= n {
            buffer.removeLast(n)
        } else {
            buffer = ""
        }
    }

    public func insert(_ text: String) {
        guard !text.isEmpty else { return }
        injector?.injectText(text)
        buffer += text
        trim()
    }

    public func batch(_ block: () -> Void) {
        block()
    }

    // MARK: KeyboardTextHost (on-screen keyboard)

    public func deleteBackward() {
        injector?.injectBackspaces(1)
        backspaceTyped()
    }

    public func moveCursor(by offset: Int) {
        guard offset != 0 else { return }
        injector?.injectArrows(offset)
        reset()
    }

    public func performReturn() {
        injector?.injectReturn(shift: false)
        reset()
    }

    public func replaceScope(selection: Bool, with text: String) {
        if selection, let handler = replaceSelectionHandler {
            handler(text)
            reset()
            return
        }
        delete(buffer)
        insert(text)
    }
}
