import XCTest
@testable import TippliCore

final class GeometryTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = testResources
    }

    private func layout(width: CGFloat, keyboard: String, mode: KeyboardMode, page: KeyboardPage = .letters,
                        numberRow: Bool = false, nextKb: Bool = false, keyHeight: String = "medium") -> KeyboardLayout {
        let metrics = KeyboardMetrics(theme: testResources.theme.metrics, keyHeight: keyHeight)
        let cfg = KeyboardLayoutConfig(width: width, page: page, mode: mode, keyboardId: keyboard,
                                       numberRow: numberRow, needsNextKeyboard: nextKb)
        return KeyboardLayout.build(config: cfg, metrics: metrics, layouts: testResources.layouts)
    }

    func testEveryHexKeyCentreHitsItself() {
        for id in testResources.layouts.keyboards.keys.sorted() {
            for width: CGFloat in [320, 375, 430, 820] {
                for height in ["small", "medium", "large"] {
                    let l = layout(width: width, keyboard: id, mode: .hex, keyHeight: height)
                    XCTAssertTrue(l.isHexBoard)
                    let hexKeys = l.keys.filter { $0.isHex }
                    XCTAssertEqual(hexKeys.count, testResources.layouts.keyboards[id]!.hex.rows.reduce(0) { $0 + $1.keys.count })
                    for key in hexKeys {
                        let hit = l.hitTest(key.center)
                        XCTAssertEqual(hit?.index, key.index, "\(id) w=\(width) key \(key.key) r\(key.row)c\(key.column)")
                    }
                }
            }
        }
    }

    func testHexPointsInsideDrawnHexagonHitThatKey() {
        let l = layout(width: 390, keyboard: "qwertz", mode: .hex)
        for key in l.keys where key.isHex {
            // Points well inside the hexagon (60% towards each vertex).
            for v in key.hexVertices {
                let p = CGPoint(x: key.center.x + (v.x - key.center.x) * 0.6, y: key.center.y + (v.y - key.center.y) * 0.6)
                XCTAssertEqual(l.hitTest(p)?.index, key.index, "key \(key.key)")
            }
        }
    }

    func testEveryNormalKeyCentreHitsItself() {
        for id in testResources.layouts.keyboards.keys.sorted() {
            for numberRow in [false, true] {
                let l = layout(width: 375, keyboard: id, mode: .normal, numberRow: numberRow)
                XCTAssertFalse(l.isHexBoard)
                for key in l.keys {
                    XCTAssertEqual(l.hitTest(key.center)?.index, key.index, "\(id) key \(key.key)")
                }
            }
        }
    }

    func testSymbolPagesUseNormalGeometryInHexMode() {
        for page in [KeyboardPage.symbols1, .symbols2] {
            let l = layout(width: 375, keyboard: "qwertz", mode: .hex, page: page)
            XCTAssertFalse(l.isHexBoard)
            XCTAssertTrue(l.keys.allSatisfy { !$0.isHex })
            XCTAssertTrue(l.keys.contains { $0.key == "$abc" })
            for key in l.keys {
                XCTAssertEqual(l.hitTest(key.center)?.index, key.index)
            }
            // Same total height as the letters page.
            XCTAssertEqual(l.totalHeight, layout(width: 375, keyboard: "qwertz", mode: .hex).totalHeight, accuracy: 0.001)
        }
    }

    func testTotalHeight() {
        let m = KeyboardMetrics(theme: testResources.theme.metrics, keyHeight: "medium")
        let H = m.rowHeight
        let hex = layout(width: 375, keyboard: "qwertz", mode: .hex)
        XCTAssertEqual(hex.totalHeight, m.toolbarHeight + 4 * H + H / 3 + m.bottomPadding, accuracy: 0.001)
        let normal = layout(width: 375, keyboard: "qwertz", mode: .normal)
        XCTAssertEqual(normal.totalHeight, m.toolbarHeight + 4 * H + m.bottomPadding, accuracy: 0.001)
        let numbers = layout(width: 375, keyboard: "qwertz", mode: .normal, numberRow: true)
        XCTAssertEqual(numbers.totalHeight, m.toolbarHeight + 5 * H + m.bottomPadding, accuracy: 0.001)
        // Hex padding H/6 above the first row.
        XCTAssertEqual(hex.rowsTop, m.toolbarHeight + H / 6, accuracy: 0.001)
    }

    func testUnitsAndBottomRowFillWidth() {
        let W: CGFloat = 375
        let l = layout(width: W, keyboard: "qwertz", mode: .normal)
        let sp = l.metrics.sidePadding
        XCTAssertEqual(l.unit, (W - 2 * sp) / 11, accuracy: 0.001)
        let bottom = l.keys.filter { $0.area == .bottom }
        XCTAssertEqual(bottom.first!.frame.minX, sp, accuracy: 0.001)
        XCTAssertEqual(bottom.last!.frame.maxX, W - sp, accuracy: 0.001)
        // Indented third row: $shift starts at sp + 0.5u.
        let shift = l.keys.first { $0.key == "$shift" }!
        XCTAssertEqual(shift.frame.minX, sp + 0.5 * l.unit, accuracy: 0.001)
        XCTAssertEqual(shift.frame.width, 1.5 * l.unit, accuracy: 0.001)
    }

    func testNextKeyboardKeyInsertedAfter123AndSpaceShrinks() {
        let l = layout(width: 375, keyboard: "qwertz", mode: .normal, nextKb: true)
        let bottom = l.keys.filter { $0.area == .bottom }.map { $0.key }
        XCTAssertEqual(bottom, ["$123", "$nextkb", "$emoji", ",", "$space", ".", "$enter"])
        let plain = layout(width: 375, keyboard: "qwertz", mode: .normal)
        let spaceWith = l.keys.first { $0.key == "$space" && $0.area == .bottom }!.frame.width
        let spaceWithout = plain.keys.first { $0.key == "$space" && $0.area == .bottom }!.frame.width
        XCTAssertLessThan(spaceWith, spaceWithout)
    }

    func testToolbarAndBottomBandHitTesting() {
        let l = layout(width: 375, keyboard: "qwertz", mode: .hex)
        XCTAssertNil(l.hitTest(CGPoint(x: 100, y: 10)))
        let belowEverything = l.hitTest(CGPoint(x: 1, y: l.totalHeight - 1))
        XCTAssertEqual(belowEverything?.key, "$123")
        let rightEdge = l.hitTest(CGPoint(x: 374.9, y: l.bottomRowTop + 5))
        XCTAssertEqual(rightEdge?.key, "$enter")
    }

    func testHexMetricFactor() {
        let l = layout(width: 375, keyboard: "qwertz", mode: .hex)
        XCTAssertEqual(l.hexK, (CGFloat(3).squareRoot() / 2) * l.unit / l.rowHeight, accuracy: 0.0001)
    }

    func testPopupLayoutWrapsAndClamps() {
        let anchor = CGRect(x: 2, y: 100, width: 30, height: 50)
        let p = PopupLayout.make(anchor: anchor, count: 14, unit: 33, rowHeight: 52, width: 320, sidePadding: 3)
        XCTAssertEqual(p.rows.count, 2)
        XCTAssertGreaterThanOrEqual(p.frame.minX, 3)
        XCTAssertLessThanOrEqual(p.frame.maxX, 317 + 0.001)
        XCTAssertEqual(p.frame.maxY, anchor.minY, accuracy: 0.001)
        // Index 0 is in the bottom row and selected by a point there.
        XCTAssertEqual(p.selection(at: CGPoint(x: p.cells[0].midX, y: p.cells[0].midY)), 0)
        XCTAssertEqual(p.selection(at: CGPoint(x: -100, y: anchor.midY)), 0)
        let last = p.cells.count - 1
        XCTAssertEqual(p.selection(at: CGPoint(x: p.cells[last].midX, y: p.cells[last].midY)), last)

        let single = PopupLayout.make(anchor: CGRect(x: 150, y: 100, width: 30, height: 50), count: 3, unit: 33,
                                      rowHeight: 52, width: 375, sidePadding: 3)
        XCTAssertEqual(single.rows.count, 1)
        XCTAssertEqual(single.frame.midX, 165, accuracy: 0.001)
        XCTAssertEqual(single.cellSize.height, 0.9 * 52, accuracy: 0.001)
    }

    func testAlternatives() {
        let l = layout(width: 375, keyboard: "qwertz", mode: .hex)
        let e = l.keys.first { $0.key == "e" }!
        let alts = KeyAlternatives.alternatives(for: e, page: .letters, character: "e", accents: testResources.accents,
                                                accentsEnabled: true, digitsEnabled: true, uppercase: false)
        XCTAssertEqual(alts.first, "3")
        XCTAssertTrue(alts.contains("é"))
        let s = l.keys.first { $0.key == "s" }!
        let upper = KeyAlternatives.alternatives(for: s, page: .letters, character: "s", accents: testResources.accents,
                                                 accentsEnabled: true, digitsEnabled: true, uppercase: true)
        XCTAssertEqual(upper.first, "ẞ")
        let none = KeyAlternatives.alternatives(for: s, page: .letters, character: "s", accents: testResources.accents,
                                                accentsEnabled: false, digitsEnabled: true, uppercase: false)
        XCTAssertTrue(none.isEmpty, "s is not in the first row: no digit")
        let p = l.keys.first { $0.key == "p" }!
        XCTAssertEqual(KeyAlternatives.alternatives(for: p, page: .letters, character: "p", accents: [:],
                                                    accentsEnabled: true, digitsEnabled: true, uppercase: false), ["0"])
    }

    func testKeyboardLayoutPromptDescription() {
        XCTAssertEqual(testResources.layouts.promptDescription(keyboardId: "qwertz"),
                       "QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm")
        XCTAssertEqual(testResources.layouts.promptDescription(keyboardId: "azerty"),
                       "AZERTY (Français): azertyuiop / qsdfghjklm / wxcvbn'")
    }

    func testFieldClassification() {
        XCTAssertEqual(FieldTraits(isSecure: true).kind, .secure)
        XCTAssertEqual(FieldTraits(content: .oneTimeCode).kind, .secure)
        XCTAssertEqual(FieldTraits(keyboard: .phone).kind, .number)
        XCTAssertEqual(FieldTraits(keyboard: .email).kind, .emailOrURL)
        XCTAssertEqual(FieldTraits(content: .username).kind, .emailOrURL)
        XCTAssertEqual(FieldTraits().kind, .normal)
        XCTAssertEqual(FieldTraits(incognito: true).kind, .incognito)
        XCTAssertFalse(FieldKind.number.aiAllowed(incognitoNoAI: false))
        XCTAssertEqual(FieldKind.number.startPage, .symbols1)
        XCTAssertFalse(FieldKind.incognito.aiAllowed(incognitoNoAI: true))
        XCTAssertTrue(FieldKind.incognito.aiAllowed(incognitoNoAI: false))
    }
}
