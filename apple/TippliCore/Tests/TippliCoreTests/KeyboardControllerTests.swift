import XCTest
@testable import TippliCore

final class KeyboardControllerTests: XCTestCase {
    final class Delegate: KeyboardControllerDelegate {
        var feedback: [KeyboardFeedback] = []
        var panels: [KeyboardPanel] = []
        var heightChanges = 0

        func keyboardNeedsDisplay(_ controller: KeyboardController) {}

        func keyboard(_ controller: KeyboardController, feedback: KeyboardFeedback) {
            self.feedback.append(feedback)
        }

        func keyboard(_ controller: KeyboardController, showPanel panel: KeyboardPanel, scrollToEmotion: Bool) {
            panels.append(panel)
        }

        func keyboardHeightChanged(_ controller: KeyboardController) {
            heightChanges += 1
        }
    }

    private var scheduler: ManualScheduler!
    private var store: SettingsStore!
    private var engine: CorrectionEngine!
    private var ai: FakeAI!
    private var host: MemoryTextHost!
    private var delegate: Delegate!
    private var controller: KeyboardController!
    private var nextId = 1

    override func setUp() {
        super.setUp()
        _ = testResources
        scheduler = ManualScheduler()
        store = SettingsStore(defaults: makeTestDefaults(), resources: testResources)
        store.set("manual", forKey: SettingKey.trigger)
        engine = CorrectionEngine(scheduler: scheduler)
        ai = FakeAI()
        host = MemoryTextHost()
        engine.host = host
        engine.ai = ai
        delegate = Delegate()
        controller = KeyboardController(store: store, engine: engine, scheduler: scheduler, resources: testResources)
        controller.host = host
        controller.delegate = delegate
        controller.setWidth(375)
        controller.startField(kind: .normal)
    }

    private func item(_ key: String) -> KeyItem {
        if let k = controller.layout.keys.first(where: { $0.key == key && $0.area == .letters }) { return k }
        return controller.layout.keys.first(where: { $0.key == key })!
    }

    private func tap(_ key: String) {
        let k = item(key)
        let id = nextId
        nextId += 1
        controller.pointerDown(id: id, at: k.center)
        controller.pointerUp(id: id, at: k.center)
    }

    private func swipe(on key: String, dx: CGFloat) {
        let k = item(key)
        let id = nextId
        nextId += 1
        controller.pointerDown(id: id, at: k.center)
        let end = CGPoint(x: k.center.x + dx, y: k.center.y)
        controller.pointerMoved(id: id, to: end)
        controller.pointerUp(id: id, at: end)
    }

    private func type(_ letters: String) {
        for c in letters {
            tap(c == " " ? "$space" : String(c))
        }
    }

    func testAutoCapitalisationAtFieldStart() {
        XCTAssertEqual(controller.shift, .oneShot(auto: true))
        type("ha")
        XCTAssertEqual(host.text, "Ha")
        XCTAssertEqual(controller.shift, .off)
    }

    func testDoubleSpacePeriodAndAutoCapAfterIt() {
        type("ha")
        tap("$space")
        tap("$space")
        XCTAssertEqual(host.text, "Ha. ")
        XCTAssertEqual(controller.shift, .oneShot(auto: true))
        // A third quick space is a normal space.
        tap("$space")
        XCTAssertEqual(host.text, "Ha.  ")
    }

    func testDoubleSpaceTooSlowIsTwoSpaces() {
        type("ha")
        tap("$space")
        scheduler.advance(by: 0.5)
        tap("$space")
        XCTAssertEqual(host.text, "Ha  ")
    }

    func testSwipeDeleteAndRestore() {
        type("hallo welt ")
        XCTAssertEqual(host.text, "Hallo welt ")
        swipe(on: "k", dx: -50)
        XCTAssertEqual(host.text, "Hallo ")
        swipe(on: "k", dx: -50)
        XCTAssertEqual(host.text, "")
        swipe(on: "d", dx: 50)
        XCTAssertEqual(host.text, "Hallo ")
        swipe(on: "d", dx: 50)
        XCTAssertEqual(host.text, "Hallo welt ")
        // Nothing left to restore and no AI correction: no-op feedback.
        swipe(on: "d", dx: 50)
        XCTAssertEqual(host.text, "Hallo welt ")
        XCTAssertEqual(delegate.feedback.last, .noop)
    }

    func testTypingClearsRestoreStack() {
        type("hallo welt ")
        swipe(on: "k", dx: -50)
        type("x")
        swipe(on: "d", dx: 50)
        XCTAssertEqual(host.text, "Hallo x")
    }

    func testShiftTapDoubleTapAndCapsLock() {
        type("a")
        XCTAssertEqual(controller.shift, .off)
        tap("$shift")
        XCTAssertEqual(controller.shift, .oneShot(auto: false))
        type("b")
        XCTAssertEqual(host.text, "AB")
        XCTAssertEqual(controller.shift, .off)
        scheduler.advance(by: 1)
        tap("$shift")
        tap("$shift")
        XCTAssertEqual(controller.shift, .capsLock)
        type("cd")
        XCTAssertEqual(host.text, "ABCD")
        XCTAssertEqual(controller.shift, .capsLock)
        scheduler.advance(by: 1)
        tap("$shift")
        XCTAssertEqual(controller.shift, .off)
    }

    func testSwipeUpCapitalDoesNotChangeShift() {
        type("a")
        let g = item("g")
        controller.pointerDown(id: 99, at: g.center)
        controller.pointerUp(id: 99, at: CGPoint(x: g.center.x, y: g.center.y - 40))
        XCTAssertEqual(host.text, "AG")
        XCTAssertEqual(controller.shift, .off)
    }

    func testSymbolsPageReturnsToLettersAfterSpace() {
        type("a")
        tap("$123")
        XCTAssertEqual(controller.page, .symbols1)
        XCTAssertFalse(controller.layout.isHexBoard)
        tap("1")
        tap("$space")
        XCTAssertEqual(controller.page, .letters)
        XCTAssertEqual(host.text, "A1 ")
    }

    func testEnterWithoutPendingCorrection() {
        type("ok")
        tap("$enter")
        XCTAssertEqual(host.returnCount, 1)
        XCTAssertEqual(host.text, "Ok\n")
        XCTAssertEqual(controller.shift, .oneShot(auto: true))
    }

    func testLayoutToggleFromToolbarPersists() {
        XCTAssertEqual(controller.settings.mode, .hex)
        let toggle = controller.toolbarItems().first { $0.kind == .layoutToggle }!
        controller.pointerDown(id: 50, at: CGPoint(x: toggle.frame.midX, y: toggle.frame.midY))
        controller.pointerUp(id: 50, at: CGPoint(x: toggle.frame.midX, y: toggle.frame.midY))
        XCTAssertEqual(controller.settings.mode, .normal)
        XCTAssertEqual(store.keyboardMode, .normal)
        XCTAssertGreaterThan(delegate.heightChanges, 0)
        XCTAssertTrue(controller.layout.keys.contains { $0.key == "$back" && $0.area == .letters })
    }

    func testToolbarFitsOnNarrowScreens() {
        for w: CGFloat in [320, 375, 430] {
            controller.setWidth(w)
            let items = controller.toolbarItems()
            XCTAssertTrue(items.allSatisfy { $0.frame.minX >= 0 && $0.frame.maxX <= w + 0.5 })
            let sorted = items.sorted { $0.frame.minX < $1.frame.minX }
            for (a, b) in zip(sorted, sorted.dropFirst()) {
                XCTAssertLessThanOrEqual(a.frame.maxX, b.frame.minX + 0.5, "overlap at width \(w)")
            }
        }
    }

    func testEmojiKeyOpensPanelAndEmojiInsertUpdatesRecents() {
        tap("$emoji")
        XCTAssertEqual(delegate.panels.last, .emoji)
        XCTAssertEqual(controller.panel, .emoji)
        controller.insertEmoji("🎉")
        XCTAssertEqual(host.text, "🎉")
        XCTAssertEqual(store.emojiRecents.first, "🎉")
        controller.showPanel(.keys)
        XCTAssertEqual(controller.panel, .keys)
    }

    func testLongPressAccentPopupTypesAlternative() {
        type("a")
        let e = item("e")
        controller.pointerDown(id: 77, at: e.center)
        scheduler.advance(by: 0.5)
        let state = controller.renderState()
        let popup = try! XCTUnwrap(state.popup)
        XCTAssertEqual(popup.selected, 0)
        let idx = try! XCTUnwrap(popup.labels.firstIndex(of: "é"))
        let cell = popup.cells[idx]
        controller.pointerMoved(id: 77, to: CGPoint(x: cell.midX, y: cell.midY))
        controller.pointerUp(id: 77, at: CGPoint(x: cell.midX, y: cell.midY))
        XCTAssertEqual(host.text, "Aé")
        XCTAssertTrue(delegate.feedback.contains(.popupOpened))
    }

    func testSecureFieldStartsWithoutAIAndAutoCap() {
        controller.startField(kind: .secure)
        XCTAssertEqual(engine.status, .locked)
        XCTAssertEqual(controller.indicator, .locked)
        XCTAssertEqual(controller.shift, .off)
        controller.startField(kind: .number)
        XCTAssertEqual(controller.page, .symbols1)
    }

    func testRenderStateLabels() {
        let state = controller.renderState()
        XCTAssertEqual(state.keys.count, controller.layout.keys.count)
        let q = state.keys.first { $0.item.key == "q" }!
        XCTAssertEqual(q.label, "Q", "auto-cap shows upper case")
        XCTAssertEqual(q.hint, "1")
        let spaces = state.keys.filter { $0.item.key == "$space" }
        XCTAssertEqual(spaces.first?.label, "DE-CH")
        XCTAssertTrue(spaces.dropFirst().allSatisfy { $0.label.isEmpty })
    }
}
