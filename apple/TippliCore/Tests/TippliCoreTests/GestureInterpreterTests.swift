import XCTest
@testable import TippliCore

final class GestureInterpreterTests: XCTestCase {
    private var scheduler: ManualScheduler!
    private var layout: KeyboardLayout!
    private var interpreter: GestureInterpreter!
    private var events: [GestureEvent] = []

    override func setUp() {
        super.setUp()
        _ = testResources
        scheduler = ManualScheduler()
        let metrics = KeyboardMetrics(theme: testResources.theme.metrics, keyHeight: "medium")
        layout = KeyboardLayout.build(config: KeyboardLayoutConfig(width: 375, page: .letters, mode: .hex, keyboardId: "qwertz"),
                                      metrics: metrics, layouts: testResources.layouts)
        interpreter = GestureInterpreter(layout: layout, scheduler: scheduler)
        interpreter.alternativesProvider = { key in
            KeyAlternatives.alternatives(for: key, page: .letters, character: key.key, accents: testResources.accents,
                                         accentsEnabled: true, digitsEnabled: true, uppercase: false)
        }
        events = []
        interpreter.onEvent = { [weak self] e in self?.events.append(e) }
    }

    private func key(_ k: String, area: KeyArea = .letters) -> KeyItem {
        layout.keys.first { $0.key == k && $0.area == area }!
    }

    /// Events without the pressed-state bookkeeping.
    private var actions: [GestureEvent] {
        events.filter {
            switch $0 {
            case .pressBegan, .pressEnded: return false
            default: return true
            }
        }
    }

    func testTap() {
        let a = key("a")
        interpreter.pointerDown(id: 1, at: a.center)
        XCTAssertEqual(events.first, .pressBegan(a))
        interpreter.pointerUp(id: 1, at: CGPoint(x: a.center.x + 3, y: a.center.y + 2))
        XCTAssertEqual(actions, [.tap(a)])
        XCTAssertTrue(interpreter.pressedKeyIndices.isEmpty)
    }

    func testForgivingDriftTypesOriginalKey() {
        let a = key("a")
        interpreter.pointerDown(id: 1, at: a.center)
        // Drifts onto the neighbouring key but not far enough for a swipe.
        interpreter.pointerMoved(id: 1, to: CGPoint(x: a.center.x + 25, y: a.center.y + 5))
        interpreter.pointerUp(id: 1, at: CGPoint(x: a.center.x + 25, y: a.center.y + 5))
        XCTAssertEqual(actions, [.tap(a)])
    }

    func testSwipeUpTypesCapital() {
        let g = key("g")
        interpreter.pointerDown(id: 1, at: g.center)
        interpreter.pointerMoved(id: 1, to: CGPoint(x: g.center.x + 2, y: g.center.y - 20))
        interpreter.pointerUp(id: 1, at: CGPoint(x: g.center.x + 3, y: g.center.y - 35))
        XCTAssertEqual(actions, [.capital(g)])
    }

    func testSwipeLeftDeletesWordsStepwise() {
        let k = key("k")
        interpreter.pointerDown(id: 1, at: k.center)
        interpreter.pointerMoved(id: 1, to: CGPoint(x: k.center.x - 20, y: k.center.y))
        XCTAssertEqual(actions, [])
        interpreter.pointerMoved(id: 1, to: CGPoint(x: k.center.x - 41, y: k.center.y + 3))
        XCTAssertEqual(actions, [.swipeDeleteWord])
        interpreter.pointerMoved(id: 1, to: CGPoint(x: k.center.x - 79, y: k.center.y + 3))
        XCTAssertEqual(actions, [.swipeDeleteWord])
        interpreter.pointerMoved(id: 1, to: CGPoint(x: k.center.x - 81, y: k.center.y + 3))
        XCTAssertEqual(actions, [.swipeDeleteWord, .swipeDeleteWord])
        interpreter.pointerMoved(id: 1, to: CGPoint(x: k.center.x - 125, y: k.center.y + 3))
        interpreter.pointerUp(id: 1, at: CGPoint(x: k.center.x - 125, y: k.center.y + 3))
        XCTAssertEqual(actions, [.swipeDeleteWord, .swipeDeleteWord, .swipeDeleteWord])
    }

    func testQuickFlickLeftWithoutMoveEvents() {
        let k = key("k")
        interpreter.pointerDown(id: 1, at: k.center)
        interpreter.pointerUp(id: 1, at: CGPoint(x: k.center.x - 50, y: k.center.y))
        XCTAssertEqual(actions, [.swipeDeleteWord])
    }

    func testSwipeRightRestoresOncePerGesture() {
        let d = key("d")
        interpreter.pointerDown(id: 1, at: d.center)
        interpreter.pointerMoved(id: 1, to: CGPoint(x: d.center.x + 45, y: d.center.y))
        interpreter.pointerMoved(id: 1, to: CGPoint(x: d.center.x + 140, y: d.center.y))
        interpreter.pointerUp(id: 1, at: CGPoint(x: d.center.x + 140, y: d.center.y))
        XCTAssertEqual(actions, [.swipeRestore])
    }

    func testDiagonalMoveIsNotASwipe() {
        let d = key("d")
        interpreter.pointerDown(id: 1, at: d.center)
        interpreter.pointerUp(id: 1, at: CGPoint(x: d.center.x - 45, y: d.center.y + 40))
        XCTAssertEqual(actions, [.tap(d)])
    }

    func testLongPressPopupSelection() {
        let e = key("e")
        interpreter.pointerDown(id: 1, at: e.center)
        scheduler.advance(by: 0.2)
        XCTAssertNil(interpreter.popup)
        scheduler.advance(by: 0.2)
        let popup = try! XCTUnwrap(interpreter.popup)
        XCTAssertEqual(popup.selected, 0)
        XCTAssertEqual(popup.alternatives.first, "3")
        guard case .popupOpened(let k, let alts)? = actions.last else {
            return XCTFail("popup not opened")
        }
        XCTAssertEqual(k, e)
        XCTAssertEqual(alts, popup.alternatives)
        // Move onto the third cell and release.
        let cell = popup.layout.cells[2]
        interpreter.pointerMoved(id: 1, to: CGPoint(x: cell.midX, y: cell.midY))
        XCTAssertEqual(interpreter.popup?.selected, 2)
        interpreter.pointerUp(id: 1, at: CGPoint(x: cell.midX, y: cell.midY))
        XCTAssertNil(interpreter.popup)
        XCTAssertEqual(actions.last, .popupCommit(e, popup.alternatives[2]))
    }

    func testLongPressPopupCancelBelowKey() {
        let e = key("e")
        interpreter.pointerDown(id: 1, at: e.center)
        scheduler.advance(by: 0.5)
        XCTAssertNotNil(interpreter.popup)
        let below = CGPoint(x: e.center.x, y: e.frame.maxY + 1.6 * layout.rowHeight)
        interpreter.pointerMoved(id: 1, to: below)
        interpreter.pointerUp(id: 1, at: below)
        XCTAssertEqual(actions.last, .popupCommit(e, nil))
    }

    func testLongPressWithoutAlternativesIsASlowTap() {
        interpreter.alternativesProvider = { _ in [] }
        let x = key("x")
        interpreter.pointerDown(id: 1, at: x.center)
        scheduler.advance(by: 1.0)
        XCTAssertNil(interpreter.popup)
        interpreter.pointerUp(id: 1, at: x.center)
        XCTAssertEqual(actions, [.tap(x)])
    }

    func testShiftLongPressIsCapsLock() {
        let shift = key("$shift")
        interpreter.pointerDown(id: 1, at: shift.center)
        scheduler.advance(by: 0.4)
        interpreter.pointerUp(id: 1, at: shift.center)
        XCTAssertEqual(actions, [.shiftLongPress(shift)])
    }

    func testBackspaceRepeatSwitchesToWordMode() {
        let back = key("$back", area: .bottom)
        interpreter.pointerDown(id: 1, at: back.center)
        XCTAssertEqual(actions, [.backspace])
        scheduler.advance(by: 0.39)
        XCTAssertEqual(actions.count, 1)
        scheduler.advance(by: 0.011)      // REPEAT_DELAY reached: 2nd char
        XCTAssertEqual(actions.count, 2)
        // 10 more characters at 55 ms -> 12 deleted characters.
        scheduler.advance(by: 0.055 * 10 + 0.001)
        XCTAssertEqual(actions.count, 12)
        XCTAssertTrue(actions.allSatisfy { $0 == .backspace })
        // Now whole words every 220 ms.
        scheduler.advance(by: 0.056)
        XCTAssertEqual(actions.last, .backspaceWord)
        let count = actions.count
        scheduler.advance(by: 0.2)
        XCTAssertEqual(actions.count, count)
        scheduler.advance(by: 0.021)
        XCTAssertEqual(actions.count, count + 1)
        XCTAssertEqual(actions.last, .backspaceWord)
        interpreter.pointerUp(id: 1, at: back.center)
        scheduler.advance(by: 2)
        XCTAssertEqual(actions.count, count + 1, "repeat stops on release")
    }

    func testFastTypingRuleCommitsOlderPointer() {
        let a = key("a")
        let s = key("s")
        interpreter.pointerDown(id: 1, at: a.center)
        interpreter.pointerDown(id: 2, at: s.center)
        XCTAssertEqual(actions, [.tap(a)])
        // The old pointer is ignored afterwards.
        interpreter.pointerMoved(id: 1, to: CGPoint(x: a.center.x - 100, y: a.center.y))
        interpreter.pointerUp(id: 1, at: CGPoint(x: a.center.x - 100, y: a.center.y))
        XCTAssertEqual(actions, [.tap(a)])
        interpreter.pointerUp(id: 2, at: s.center)
        XCTAssertEqual(actions, [.tap(a), .tap(s)])
    }

    func testFastTypingRuleIgnoresMovedPointer() {
        let k = key("k")
        let s = key("s")
        interpreter.pointerDown(id: 1, at: k.center)
        interpreter.pointerMoved(id: 1, to: CGPoint(x: k.center.x - 45, y: k.center.y))
        interpreter.pointerDown(id: 2, at: s.center)
        XCTAssertEqual(actions, [.swipeDeleteWord])
    }

    func testSpaceCursorMode() {
        let space = key("$space")
        interpreter.pointerDown(id: 1, at: space.center)
        interpreter.pointerMoved(id: 1, to: CGPoint(x: space.center.x + 13, y: space.center.y))
        XCTAssertEqual(actions, [.moveCursor(1)])
        interpreter.pointerMoved(id: 1, to: CGPoint(x: space.center.x + 35, y: space.center.y + 4))
        XCTAssertEqual(actions, [.moveCursor(1), .moveCursor(2)])
        interpreter.pointerMoved(id: 1, to: CGPoint(x: space.center.x - 21, y: space.center.y))
        XCTAssertEqual(actions.last, .moveCursor(-5))
        interpreter.pointerUp(id: 1, at: CGPoint(x: space.center.x - 21, y: space.center.y))
        // Never types a space.
        XCTAssertFalse(actions.contains(.tap(space)))
    }

    func testSpaceTap() {
        let space = key("$space", area: .bottom)
        interpreter.pointerDown(id: 1, at: space.center)
        interpreter.pointerUp(id: 1, at: space.center)
        XCTAssertEqual(actions, [.tap(space)])
    }
}
