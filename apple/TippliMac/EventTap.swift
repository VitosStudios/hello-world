import AppKit
import Carbon
import CoreGraphics
import TippliCore

/// Session-level keyboard/mouse event tap (SPEC §10). Runs on the main run loop.
final class EventTap {
    /// Return true to swallow the event.
    var handler: ((CGEventType, CGEvent) -> Bool)?

    private var port: CFMachPort?
    private var source: CFRunLoopSource?

    var isRunning: Bool { port != nil }

    private static func bit(_ type: CGEventType) -> CGEventMask {
        CGEventMask(1) << CGEventMask(type.rawValue)
    }

    @discardableResult
    func start() -> Bool {
        stop()
        let mask: CGEventMask = EventTap.bit(.keyDown) | EventTap.bit(.flagsChanged)
            | EventTap.bit(.leftMouseDown) | EventTap.bit(.rightMouseDown) | EventTap.bit(.otherMouseDown)
        let callback: CGEventTapCallBack = { _, type, event, refcon in
            guard let refcon = refcon else { return Unmanaged.passUnretained(event) }
            let tap = Unmanaged<EventTap>.fromOpaque(refcon).takeUnretainedValue()
            return tap.handle(type: type, event: event)
        }
        guard let port = CGEvent.tapCreate(tap: .cgSessionEventTap,
                                           place: .headInsertEventTap,
                                           options: .defaultTap,
                                           eventsOfInterest: mask,
                                           callback: callback,
                                           userInfo: Unmanaged.passUnretained(self).toOpaque()) else {
            return false
        }
        let src = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, port, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), src, .commonModes)
        CGEvent.tapEnable(tap: port, enable: true)
        self.port = port
        self.source = src
        return true
    }

    func stop() {
        if let p = port {
            CGEvent.tapEnable(tap: p, enable: false)
            CFMachPortInvalidate(p)
        }
        if let s = source {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), s, .commonModes)
        }
        port = nil
        source = nil
    }

    fileprivate func handle(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let p = port { CGEvent.tapEnable(tap: p, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        // Ignore our own injected events.
        if event.getIntegerValueField(.eventSourceUserData) == EventInjector.tag {
            return Unmanaged.passUnretained(event)
        }
        if let h = handler, h(type, event) {
            return nil
        }
        return Unmanaged.passUnretained(event)
    }
}

/// Key code → characters with the current keyboard layout (`UCKeyTranslate`)
/// and our own dead-key state.
final class KeyTranslator {
    private var inputSource: TISInputSource?
    private var layoutData: CFData?
    private var deadKeyState: UInt32 = 0
    private var observer: NSObjectProtocol?

    init() {
        reloadLayout()
        observer = DistributedNotificationCenter.default().addObserver(
            forName: Notification.Name(kTISNotifySelectedKeyboardInputSourceChanged as String),
            object: nil, queue: .main) { [weak self] _ in
                self?.reloadLayout()
            }
    }

    deinit {
        if let o = observer {
            DistributedNotificationCenter.default().removeObserver(o)
        }
    }

    func reloadLayout() {
        deadKeyState = 0
        var src: TISInputSource? = TISCopyCurrentKeyboardLayoutInputSource()?.takeRetainedValue()
        var ptr: UnsafeMutableRawPointer? = nil
        if let s = src {
            ptr = TISGetInputSourceProperty(s, kTISPropertyUnicodeKeyLayoutData)
        }
        if ptr == nil {
            src = TISCopyCurrentASCIICapableKeyboardLayoutInputSource()?.takeRetainedValue()
            if let s = src {
                ptr = TISGetInputSourceProperty(s, kTISPropertyUnicodeKeyLayoutData)
            }
        }
        inputSource = src
        if let p = ptr {
            layoutData = Unmanaged<CFData>.fromOpaque(UnsafeRawPointer(p)).takeUnretainedValue()
        } else {
            layoutData = nil
        }
    }

    func resetDeadKeys() {
        deadKeyState = 0
    }

    /// The characters a key down produces; "" while a dead key is pending,
    /// nil when the layout cannot translate.
    func translate(keyCode: UInt16, flags: CGEventFlags) -> String? {
        guard let data = layoutData, let bytes = CFDataGetBytePtr(data) else { return nil }
        var modifiers: UInt32 = 0
        if flags.contains(.maskShift) { modifiers |= UInt32(shiftKey) }
        if flags.contains(.maskAlphaShift) { modifiers |= UInt32(alphaLock) }
        if flags.contains(.maskAlternate) { modifiers |= UInt32(optionKey) }
        let modifierKeyState = (modifiers >> 8) & 0xFF
        let maxLength = 8
        var chars = [UniChar](repeating: 0, count: maxLength)
        var length = 0
        var dead = deadKeyState
        let keyboardType = UInt32(LMGetKbdType())
        let status: OSStatus = bytes.withMemoryRebound(to: UCKeyboardLayout.self, capacity: 1) { layout in
            UCKeyTranslate(layout, keyCode, UInt16(kUCKeyActionDown), modifierKeyState,
                           keyboardType, OptionBits(0), &dead, maxLength, &length, &chars)
        }
        guard status == 0 else {
            deadKeyState = 0
            return nil
        }
        deadKeyState = dead
        if length == 0 { return "" }
        return String(utf16CodeUnits: chars, count: length)
    }
}

/// Synthetic key events tagged with `eventSourceUserData = "TIPP"` (SPEC §9.3).
final class EventInjector: KeyInjecting {
    static let tag: Int64 = 0x54495050

    static let keyReturn: CGKeyCode = 36
    static let keyBackspace: CGKeyCode = 51
    static let keyLeft: CGKeyCode = 123
    static let keyRight: CGKeyCode = 124
    static let keyC: CGKeyCode = 8
    static let keyV: CGKeyCode = 9

    private let source: CGEventSource?

    init() {
        source = CGEventSource(stateID: .privateState)
        source?.userData = EventInjector.tag
    }

    private func tagAndPost(_ events: [CGEvent]) {
        for e in events {
            e.setIntegerValueField(.eventSourceUserData, value: EventInjector.tag)
            e.post(tap: .cghidEventTap)
        }
    }

    func postKey(_ keyCode: CGKeyCode, flags: CGEventFlags = []) {
        guard let down = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false) else { return }
        down.flags = flags
        up.flags = flags
        tagAndPost([down, up])
    }

    private func postUnicode(_ text: String) {
        let units = Array(text.utf16)
        guard !units.isEmpty,
              let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else { return }
        units.withUnsafeBufferPointer { buf in
            down.keyboardSetUnicodeString(stringLength: buf.count, unicodeString: buf.baseAddress)
            up.keyboardSetUnicodeString(stringLength: buf.count, unicodeString: buf.baseAddress)
        }
        down.flags = []
        up.flags = []
        tagAndPost([down, up])
    }

    // MARK: KeyInjecting

    func injectBackspaces(_ count: Int) {
        guard count > 0 else { return }
        for _ in 0..<count {
            postKey(EventInjector.keyBackspace)
        }
    }

    /// Unicode text in chunks of at most 20 UTF-16 units (never splitting a Character).
    func injectText(_ text: String) {
        var chunk = ""
        var units = 0
        for ch in text {
            let n = ch.utf16.count
            if units + n > 20 && !chunk.isEmpty {
                postUnicode(chunk)
                chunk = ""
                units = 0
            }
            chunk.append(ch)
            units += n
        }
        if !chunk.isEmpty {
            postUnicode(chunk)
        }
    }

    func injectReturn(shift: Bool) {
        postKey(EventInjector.keyReturn, flags: shift ? .maskShift : [])
    }

    func injectArrows(_ offset: Int) {
        let key = offset < 0 ? EventInjector.keyLeft : EventInjector.keyRight
        for _ in 0..<abs(offset) {
            postKey(key)
        }
    }
}

/// Clipboard save/restore around simulated ⌘C / ⌘V.
enum Clipboard {
    typealias Snapshot = [[NSPasteboard.PasteboardType: Data]]

    static func snapshot() -> Snapshot {
        let items = NSPasteboard.general.pasteboardItems ?? []
        return items.map { item in
            var d: [NSPasteboard.PasteboardType: Data] = [:]
            for t in item.types {
                if let data = item.data(forType: t) { d[t] = data }
            }
            return d
        }
    }

    static func restore(_ snapshot: Snapshot) {
        let pb = NSPasteboard.general
        _ = pb.clearContents()
        guard !snapshot.isEmpty else { return }
        let items: [NSPasteboardItem] = snapshot.map { dict in
            let item = NSPasteboardItem()
            for (t, data) in dict {
                _ = item.setData(data, forType: t)
            }
            return item
        }
        _ = pb.writeObjects(items)
    }
}
