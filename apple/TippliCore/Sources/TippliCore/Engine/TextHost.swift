import Foundation

/// The correction engine's view of a text field (SPEC §5).
public protocol TextHost: AnyObject {
    /// Up to `maxChars` Characters before the caret (as far as visible).
    func textBeforeCursor(_ maxChars: Int) -> String
    func textAfterCursor(_ maxChars: Int) -> String
    func selectedText() -> String
    /// Deletes exactly this text right before the caret (one platform delete
    /// unit per Character on Apple platforms).
    func delete(_ text: String)
    /// Inserts text at the caret.
    func insert(_ text: String)
    /// Groups edits to avoid flicker.
    func batch(_ block: () -> Void)
}

/// Additional operations the keyboard needs.
public protocol KeyboardTextHost: TextHost {
    /// Deletes one grapheme before the caret, or the selection if there is one.
    func deleteBackward()
    /// Moves the caret by `offset` Characters (negative = left).
    func moveCursor(by offset: Int)
    /// Return key: perform the editor action / insert a newline.
    func performReturn()
    /// Replaces the selection (if any) or the whole visible field text with `text`.
    func replaceScope(selection: Bool, with text: String)
}

/// A simple in-memory text field used by tests, previews and the desktop
/// on-screen keyboard preview.
public final class MemoryTextHost: KeyboardTextHost {
    /// Full text and caret position (in Characters).
    public private(set) var text: String = ""
    public private(set) var caret: Int = 0
    public private(set) var selectionLength: Int = 0
    public var returnCount = 0
    public var onEdit: (() -> Void)?

    public init(text: String = "") {
        self.text = text
        self.caret = text.count
    }

    public func setText(_ t: String, caret c: Int? = nil) {
        text = t
        caret = min(max(c ?? t.count, 0), t.count)
        selectionLength = 0
    }

    public func select(from start: Int, length: Int) {
        caret = min(max(start, 0), text.count)
        selectionLength = min(max(length, 0), text.count - caret)
    }

    private var chars: [Character] { Array(text) }

    public func textBeforeCursor(_ maxChars: Int) -> String {
        let c = chars
        let start = max(0, caret - maxChars)
        return String(c[start..<caret])
    }

    public func textAfterCursor(_ maxChars: Int) -> String {
        let c = chars
        let from = caret + selectionLength
        let end = min(c.count, from + maxChars)
        return String(c[from..<end])
    }

    public func selectedText() -> String {
        guard selectionLength > 0 else { return "" }
        let c = chars
        return String(c[caret..<(caret + selectionLength)])
    }

    private func removeSelection() {
        guard selectionLength > 0 else { return }
        var c = chars
        c.removeSubrange(caret..<(caret + selectionLength))
        text = String(c)
        selectionLength = 0
    }

    public func delete(_ t: String) {
        for _ in 0..<t.count { deleteBackward() }
    }

    public func insert(_ t: String) {
        removeSelection()
        var c = chars
        let new = Array(t)
        c.insert(contentsOf: new, at: caret)
        text = String(c)
        caret += new.count
        onEdit?()
    }

    public func batch(_ block: () -> Void) {
        block()
    }

    public func deleteBackward() {
        if selectionLength > 0 {
            removeSelection()
            onEdit?()
            return
        }
        guard caret > 0 else { return }
        var c = chars
        c.remove(at: caret - 1)
        text = String(c)
        caret -= 1
        onEdit?()
    }

    public func moveCursor(by offset: Int) {
        selectionLength = 0
        caret = min(max(caret + offset, 0), text.count)
    }

    public func performReturn() {
        returnCount += 1
        insert("\n")
    }

    public func replaceScope(selection: Bool, with t: String) {
        if selection && selectionLength > 0 {
            insert(t)
        } else {
            setText(t)
            onEdit?()
        }
    }
}
