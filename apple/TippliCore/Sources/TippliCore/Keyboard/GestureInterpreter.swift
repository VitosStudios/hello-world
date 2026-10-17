import Foundation

/// Thresholds from SPEC §4.1 (points; already density independent on Apple platforms).
public struct GestureThresholds: Equatable {
    public var tapSlop: CGFloat = 12
    public var swipeUpMin: CGFloat = 28
    public var swipeHMin: CGFloat = 40
    public var swipeHStep: CGFloat = 40
    public var cursorStep: CGFloat = 10
    public var longPress: TimeInterval = 0.35
    public var repeatDelay: TimeInterval = 0.4
    public var repeatInterval: TimeInterval = 0.055
    public var wordRepeatAfter: Int = 12
    public var wordRepeatInterval: TimeInterval = 0.22

    public init() {}
}

/// What the interpreter recognised. The keyboard controller turns these into
/// text operations.
public enum GestureEvent: Equatable {
    /// Pointer went down on a key (pressed state + haptic/sound).
    case pressBegan(KeyItem)
    /// Pointer released / cancelled / committed (pressed state off).
    case pressEnded(KeyItem)
    /// A tap on a key (also emitted for keys committed by the fast typing rule).
    case tap(KeyItem)
    /// Swipe up on a character key: type it upper-cased.
    case capital(KeyItem)
    /// Delete one grapheme (backspace down or repeat).
    case backspace
    /// Delete one word (backspace repeat in word mode).
    case backspaceWord
    /// Swipe left step: delete one word onto the restore stack.
    case swipeDeleteWord
    /// Swipe right: restore / undo.
    case swipeRestore
    /// Space-bar trackpad: move the cursor by n characters.
    case moveCursor(Int)
    /// Long press on $shift: caps lock.
    case shiftLongPress(KeyItem)
    /// Long-press popup opened with these alternatives (first one selected).
    case popupOpened(KeyItem, [String])
    /// Popup highlight changed.
    case popupSelectionChanged(Int?)
    /// Popup closed: type the alternative at this index (nil = cancelled).
    case popupCommit(KeyItem, String?)
}

/// The long-press popup currently open.
public struct PopupState: Equatable {
    public let ownerId: Int
    public let key: KeyItem
    public let alternatives: [String]
    public let layout: PopupLayout
    public var selected: Int?
}

/// Platform-agnostic multi-touch interpreter (SPEC §4.1, §4.4, §4.5).
public final class GestureInterpreter {
    public var layout: KeyboardLayout
    public var thresholds: GestureThresholds
    /// Alternatives for a key (empty = no popup).
    public var alternativesProvider: (KeyItem) -> [String] = { _ in [] }
    public var onEvent: (GestureEvent) -> Void = { _ in }
    public private(set) var popup: PopupState?

    private let scheduler: Scheduler
    private var pointers: [Int: Pointer] = [:]

    private enum Mode {
        case idle
        case cursor
        case swipeLeft
        case swipeRight
        case consumed
    }

    private final class Pointer {
        let id: Int
        let key: KeyItem
        let start: CGPoint
        var last: CGPoint
        var beyondSlop = false
        var committed = false
        var mode: Mode = .idle
        var cursorSteps = 0
        var swipeSteps = 0
        var deleted = 0
        var alternatives: [String] = []
        var longPressTimer: Cancellable?
        var repeatTimer: Cancellable?

        init(id: Int, key: KeyItem, start: CGPoint) {
            self.id = id
            self.key = key
            self.start = start
            self.last = start
        }

        func cancelTimers() {
            longPressTimer?.cancel()
            longPressTimer = nil
            repeatTimer?.cancel()
            repeatTimer = nil
        }
    }

    public init(layout: KeyboardLayout, scheduler: Scheduler, thresholds: GestureThresholds = GestureThresholds()) {
        self.layout = layout
        self.scheduler = scheduler
        self.thresholds = thresholds
    }

    /// Indices of keys currently held down (for the pressed state).
    public var pressedKeyIndices: Set<Int> {
        var s = Set<Int>()
        for p in pointers.values where !p.committed && p.mode != .swipeLeft && p.mode != .swipeRight {
            s.insert(p.key.index)
        }
        return s
    }

    public var hasActivePointers: Bool { !pointers.isEmpty }

    /// True while a pointer is down on a `$shift` key.
    public var isShiftHeld: Bool {
        pointers.values.contains { $0.key.key == "$shift" && !$0.committed }
    }

    // MARK: Pointer input

    public func pointerDown(id: Int, at p: CGPoint) {
        guard let key = layout.hitTest(p) else { return }
        if pointers[id] != nil { pointerCancelled(id: id) }

        // Fast typing rule: commit older, still-resting character pointers.
        let older = pointers.values.sorted { $0.id < $1.id }
        for o in older where !o.committed && o.mode == .idle && !o.beyondSlop
            && (o.key.isCharacter || o.key.key == "$space") && popup?.ownerId != o.id {
            o.committed = true
            o.cancelTimers()
            onEvent(.tap(o.key))
            onEvent(.pressEnded(o.key))
        }

        let ptr = Pointer(id: id, key: key, start: p)
        pointers[id] = ptr
        onEvent(.pressBegan(key))

        switch key.key {
        case "$back":
            ptr.deleted = 1
            onEvent(.backspace)
            scheduleRepeat(ptr, after: thresholds.repeatDelay)
        case "$shift":
            ptr.longPressTimer = scheduler.schedule(after: thresholds.longPress) { [weak self] in
                self?.longPressFired(id: id)
            }
        default:
            if key.isCharacter {
                ptr.alternatives = alternativesProvider(key)
                if !ptr.alternatives.isEmpty {
                    ptr.longPressTimer = scheduler.schedule(after: thresholds.longPress) { [weak self] in
                        self?.longPressFired(id: id)
                    }
                }
            }
        }
    }

    public func pointerMoved(id: Int, to p: CGPoint) {
        guard let ptr = pointers[id], !ptr.committed else { return }
        process(ptr, at: p)
    }

    public func pointerUp(id: Int, at p: CGPoint) {
        guard let ptr = pointers[id] else { return }
        if ptr.committed {
            pointers[id] = nil
            return
        }
        process(ptr, at: p)
        pointers[id] = nil
        ptr.cancelTimers()

        if let pop = popup, pop.ownerId == id {
            popup = nil
            let choice: String? = pop.selected.flatMap { $0 < pop.alternatives.count ? pop.alternatives[$0] : nil }
            onEvent(.popupCommit(pop.key, choice))
            onEvent(.pressEnded(ptr.key))
            return
        }
        onEvent(.pressEnded(ptr.key))
        if ptr.mode != .idle { return }
        if ptr.key.key == "$back" { return }

        let dx = p.x - ptr.start.x
        let dy = p.y - ptr.start.y
        if ptr.key.isCharacter && dy <= -thresholds.swipeUpMin && abs(dy) > abs(dx) {
            onEvent(.capital(ptr.key))
            return
        }
        onEvent(.tap(ptr.key))
    }

    public func pointerCancelled(id: Int) {
        guard let ptr = pointers.removeValue(forKey: id) else { return }
        ptr.cancelTimers()
        if let pop = popup, pop.ownerId == id {
            popup = nil
            onEvent(.popupCommit(pop.key, nil))
        }
        if !ptr.committed {
            onEvent(.pressEnded(ptr.key))
        }
    }

    /// Cancels everything (layout change, panel switch, keyboard hidden).
    public func cancelAll() {
        for id in Array(pointers.keys) {
            pointerCancelled(id: id)
        }
        popup = nil
    }

    // MARK: Internals

    private func process(_ ptr: Pointer, at p: CGPoint) {
        ptr.last = p
        let dx = p.x - ptr.start.x
        let dy = p.y - ptr.start.y
        if !ptr.beyondSlop && (dx * dx + dy * dy).squareRoot() > thresholds.tapSlop {
            ptr.beyondSlop = true
            ptr.longPressTimer?.cancel()
            ptr.longPressTimer = nil
        }

        if var pop = popup, pop.ownerId == ptr.id {
            let keyBottom = pop.key.visualFrame(keyGap: layout.metrics.keyGap).maxY
            let newSel: Int?
            if p.y > keyBottom + 1.5 * layout.metrics.rowHeight {
                newSel = nil
            } else {
                newSel = pop.layout.selection(at: p)
            }
            if newSel != pop.selected {
                pop.selected = newSel
                popup = pop
                onEvent(.popupSelectionChanged(newSel))
            }
            return
        }

        switch ptr.mode {
        case .cursor:
            updateCursor(ptr, dx: dx)
            return
        case .swipeLeft:
            updateSwipeLeft(ptr, dx: dx)
            return
        case .swipeRight, .consumed:
            return
        case .idle:
            break
        }

        guard ptr.beyondSlop else { return }

        if ptr.key.key == "$space" {
            if abs(dx) > thresholds.tapSlop {
                ptr.mode = .cursor
                ptr.cancelTimers()
                updateCursor(ptr, dx: dx)
            }
            return
        }

        if abs(dx) >= thresholds.swipeHMin && abs(dx) > 1.5 * abs(dy) {
            ptr.cancelTimers()
            if dx < 0 {
                ptr.mode = .swipeLeft
                updateSwipeLeft(ptr, dx: dx)
            } else {
                ptr.mode = .swipeRight
                onEvent(.swipeRestore)
            }
        }
    }

    private func updateCursor(_ ptr: Pointer, dx: CGFloat) {
        let steps = Int(dx / thresholds.cursorStep)
        let delta = steps - ptr.cursorSteps
        if delta != 0 {
            ptr.cursorSteps = steps
            onEvent(.moveCursor(delta))
        }
    }

    private func updateSwipeLeft(_ ptr: Pointer, dx: CGFloat) {
        let travel = -dx
        guard travel >= thresholds.swipeHMin else { return }
        let steps = 1 + Int(((travel - thresholds.swipeHMin) / thresholds.swipeHStep).rounded(.down))
        while ptr.swipeSteps < steps {
            ptr.swipeSteps += 1
            onEvent(.swipeDeleteWord)
        }
    }

    private func longPressFired(id: Int) {
        guard let ptr = pointers[id], !ptr.committed, !ptr.beyondSlop, ptr.mode == .idle else { return }
        ptr.longPressTimer = nil
        if ptr.key.key == "$shift" {
            ptr.mode = .consumed
            onEvent(.shiftLongPress(ptr.key))
            return
        }
        guard !ptr.alternatives.isEmpty, popup == nil else { return }
        let anchor = ptr.key.visualFrame(keyGap: layout.metrics.keyGap)
        let pl = PopupLayout.make(anchor: anchor, count: ptr.alternatives.count, unit: layout.unit,
                                  rowHeight: layout.metrics.rowHeight, width: layout.width,
                                  sidePadding: layout.metrics.sidePadding)
        popup = PopupState(ownerId: id, key: ptr.key, alternatives: ptr.alternatives, layout: pl, selected: 0)
        onEvent(.popupOpened(ptr.key, ptr.alternatives))
    }

    private func scheduleRepeat(_ ptr: Pointer, after delay: TimeInterval) {
        let id = ptr.id
        ptr.repeatTimer = scheduler.schedule(after: delay) { [weak self] in
            self?.repeatFired(id: id)
        }
    }

    private func repeatFired(id: Int) {
        guard let ptr = pointers[id], !ptr.committed, ptr.mode == .idle else { return }
        if ptr.deleted >= thresholds.wordRepeatAfter {
            onEvent(.backspaceWord)
            ptr.deleted += 1
            scheduleRepeat(ptr, after: thresholds.wordRepeatInterval)
        } else {
            onEvent(.backspace)
            ptr.deleted += 1
            scheduleRepeat(ptr, after: thresholds.repeatInterval)
        }
    }
}
