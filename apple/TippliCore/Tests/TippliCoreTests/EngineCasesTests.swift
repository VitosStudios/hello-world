import XCTest
@testable import TippliCore

/// Runs every case of shared/tests/engine-cases.json (SPEC §11).
final class EngineCasesTests: XCTestCase {
    private func cases(_ name: String) throws -> [[String: Any]] {
        let root = try XCTUnwrap(loadSharedJSON("tests/engine-cases.json") as? [String: Any])
        return try XCTUnwrap(root[name] as? [[String: Any]], "missing section \(name)")
    }

    func testSelectSegment() throws {
        let list = try cases("selectSegment")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let before = try XCTUnwrap(c["before"] as? String)
            let explicit = try XCTUnwrap(c["explicit"] as? Bool)
            let pinyin = try XCTUnwrap(c["pinyin"] as? Bool)
            let result = TextAlgorithms.selectSegment(before, explicit: explicit, pinyin: pinyin)
            if let exp = c["expected"] as? [String: Any] {
                let r = try XCTUnwrap(result, "case \(i): expected a segment")
                XCTAssertEqual(r.context, exp["context"] as? String, "case \(i) context")
                XCTAssertEqual(r.segment, exp["segment"] as? String, "case \(i) segment")
                XCTAssertEqual(r.trailing, exp["trailing"] as? String, "case \(i) trailing")
            } else {
                XCTAssertNil(result, "case \(i): expected null")
            }
        }
    }

    func testCleanOutput() throws {
        let list = try cases("cleanOutput")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let segment = try XCTUnwrap(c["segment"] as? String)
            let output = try XCTUnwrap(c["output"] as? String)
            let pinyin = try XCTUnwrap(c["pinyin"] as? Bool)
            let result = TextAlgorithms.cleanOutput(segment: segment, output: output, pinyin: pinyin)
            XCTAssertEqual(result, c["expected"] as? String, "case \(i)")
        }
    }

    func testComputeEdit() throws {
        let list = try cases("computeEdit")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let segment = try XCTUnwrap(c["segment"] as? String)
            let corrected = try XCTUnwrap(c["corrected"] as? String)
            let suffix = try XCTUnwrap(c["suffix"] as? String)
            let result = TextAlgorithms.computeEdit(segment: segment, corrected: corrected, suffix: suffix)
            if let exp = c["expected"] as? [String: Any] {
                let r = try XCTUnwrap(result, "case \(i)")
                XCTAssertEqual(r.delete, exp["delete"] as? String, "case \(i) delete")
                XCTAssertEqual(r.insert, exp["insert"] as? String, "case \(i) insert")
            } else {
                XCTAssertNil(result, "case \(i)")
            }
        }
    }

    func testWordDelete() throws {
        let list = try cases("wordDelete")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let before = try XCTUnwrap(c["before"] as? String)
            XCTAssertEqual(TextAlgorithms.wordDelete(before), c["expected"] as? String, "case \(i): \(before)")
        }
    }

    func testAutoCapitalize() throws {
        let list = try cases("autoCapitalize")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let before = try XCTUnwrap(c["before"] as? String)
            let expected = try XCTUnwrap(c["expected"] as? Bool)
            XCTAssertEqual(TextAlgorithms.autoCapitalize(before), expected, "case \(i): '\(before)'")
        }
    }

    func testDoubleSpacePeriod() throws {
        let list = try cases("doubleSpacePeriod")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let before = try XCTUnwrap(c["before"] as? String)
            let expected = try XCTUnwrap(c["expected"] as? Bool)
            XCTAssertEqual(TextAlgorithms.doubleSpacePeriod(before), expected, "case \(i): '\(before)'")
        }
    }

    func testCanApply() throws {
        let list = try cases("canApply")
        XCTAssertFalse(list.isEmpty)
        for (i, c) in list.enumerated() {
            let now = try XCTUnwrap(c["beforeNow"] as? String)
            let segment = try XCTUnwrap(c["segment"] as? String)
            let trailing = try XCTUnwrap(c["trailing"] as? String)
            let typed = try XCTUnwrap(c["typedSince"] as? String)
            let expected = try XCTUnwrap(c["expected"] as? Bool)
            XCTAssertEqual(TextAlgorithms.canApply(beforeNow: now, segment: segment, trailing: trailing, typedSince: typed),
                           expected, "case \(i)")
        }
    }
}
