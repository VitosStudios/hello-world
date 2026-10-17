import UIKit
import TippliCore

/// `TextHost` over `UITextDocumentProxy` (SPEC §9.2).
final class ProxyTextHost: KeyboardTextHost {
    weak var controller: UIInputViewController?

    init(controller: UIInputViewController) {
        self.controller = controller
    }

    private var proxy: UITextDocumentProxy? {
        controller?.textDocumentProxy
    }

    func textBeforeCursor(_ maxChars: Int) -> String {
        guard let before = proxy?.documentContextBeforeInput else { return "" }
        return String(before.suffix(maxChars))
    }

    func textAfterCursor(_ maxChars: Int) -> String {
        guard let after = proxy?.documentContextAfterInput else { return "" }
        return String(after.prefix(maxChars))
    }

    func selectedText() -> String {
        proxy?.selectedText ?? ""
    }

    /// One `deleteBackward()` per Character.
    func delete(_ text: String) {
        guard let p = proxy else { return }
        for _ in 0..<text.count {
            p.deleteBackward()
        }
    }

    func insert(_ text: String) {
        guard !text.isEmpty else { return }
        proxy?.insertText(text)
    }

    func batch(_ block: () -> Void) {
        block()
    }

    func deleteBackward() {
        proxy?.deleteBackward()
    }

    /// Moves by whole Characters (the proxy counts UTF-16 units).
    func moveCursor(by offset: Int) {
        guard let p = proxy, offset != 0 else { return }
        var units = 0
        if offset < 0 {
            let before = p.documentContextBeforeInput ?? ""
            for c in before.reversed().prefix(-offset) {
                units += c.utf16.count
            }
            if units == 0 { units = -offset }
            p.adjustTextPosition(byCharacterOffset: -units)
        } else {
            let after = p.documentContextAfterInput ?? ""
            for c in after.prefix(offset) {
                units += c.utf16.count
            }
            if units == 0 { units = offset }
            p.adjustTextPosition(byCharacterOffset: units)
        }
    }

    func performReturn() {
        proxy?.insertText("\n")
    }

    /// Selection: insertText replaces it. Whole field: move to the end of the
    /// visible text, delete everything visible and insert the new text.
    func replaceScope(selection: Bool, with text: String) {
        guard let p = proxy else { return }
        if selection, let sel = p.selectedText, !sel.isEmpty {
            p.insertText(text)
            return
        }
        let before = p.documentContextBeforeInput ?? ""
        let after = p.documentContextAfterInput ?? ""
        if !after.isEmpty {
            p.adjustTextPosition(byCharacterOffset: after.utf16.count)
        }
        for _ in 0..<(before.count + after.count) {
            p.deleteBackward()
        }
        p.insertText(text)
    }
}

extension FieldTraits {
    /// Classification input from the proxy's text input traits (SPEC §4.8).
    init(proxy: UITextDocumentProxy) {
        var traits = FieldTraits()
        traits.isSecure = proxy.isSecureTextEntry ?? false
        switch proxy.keyboardType ?? .default {
        case .numberPad, .decimalPad, .asciiCapableNumberPad, .numbersAndPunctuation:
            traits.keyboard = .number
        case .phonePad, .namePhonePad:
            traits.keyboard = .phone
        case .emailAddress:
            traits.keyboard = .email
        case .URL:
            traits.keyboard = .url
        case .webSearch:
            traits.keyboard = .webSearch
        default:
            traits.keyboard = .text
        }
        if let ct = proxy.textContentType ?? nil {
            switch ct {
            case .password, .newPassword:
                traits.content = .password
            case .oneTimeCode:
                traits.content = .oneTimeCode
            case .emailAddress:
                traits.content = .email
            case .URL:
                traits.content = .url
            case .username:
                traits.content = .username
            case .telephoneNumber:
                traits.content = .phone
            case .creditCardNumber:
                traits.content = .creditCard
            default:
                traits.content = .none
            }
        }
        self = traits
    }
}

extension UIReturnKeyType {
    /// Return key label from strings.keys (nil = ⏎).
    var tippliLabel: String? {
        switch self {
        case .send: return loc("keys.send")
        case .search, .google, .yahoo: return loc("keys.search")
        case .go, .route, .join: return loc("keys.go")
        case .next, .continue: return loc("keys.next")
        case .done: return loc("keys.done")
        default: return nil
        }
    }
}
