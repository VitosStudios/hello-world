import XCTest
@testable import TippliCore

final class CorrectionEngineTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = testResources
    }

    private struct Rig {
        let engine: CorrectionEngine
        let host: MemoryTextHost
        let ai: FakeAI
        let scheduler: ManualScheduler

        /// Types character by character like the keyboard does.
        func type(_ text: String) {
            for c in text {
                host.insert(String(c))
                engine.noteAppend(String(c))
            }
        }
    }

    private func makeRig(trigger: CorrectionTrigger, mood: Bool = false) -> Rig {
        let scheduler = ManualScheduler()
        let engine = CorrectionEngine(scheduler: scheduler)
        let host = MemoryTextHost()
        let ai = FakeAI()
        engine.host = host
        engine.ai = ai
        var s = EngineSettings()
        s.trigger = trigger
        s.pauseMs = 700
        s.moodWarning = mood
        s.tipsEmotion = mood
        engine.settings = s
        engine.fieldStarted(allowed: true)
        return Rig(engine: engine, host: host, ai: ai, scheduler: scheduler)
    }

    @MainActor
    func testPauseTriggerExcludesTrailingWordAndAppliesWithTypedSuffix() async {
        let rig = makeRig(trigger: .pause)
        rig.type("ich bin hir unterw")
        rig.scheduler.advance(by: 0.69)
        XCTAssertEqual(rig.ai.requests.count, 0)
        XCTAssertTrue(rig.engine.hasPendingWork)
        rig.scheduler.advance(by: 0.02)
        await waitUntil { rig.ai.requests.count == 1 }
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[0]), "ich bin hir ")
        XCTAssertEqual(rig.engine.status, .working)

        // The user keeps typing while the request is in flight.
        rig.type("egs")
        rig.ai.respond("Ich bin hier")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Ich bin hier unterwegs")
        XCTAssertEqual(rig.engine.history, [CorrectionRecord(raw: "ich bin hir ", corrected: "Ich bin hier ")])
        XCTAssertTrue(rig.engine.canUndo)
        XCTAssertEqual(rig.engine.status, .ready)
    }

    @MainActor
    func testSentenceEndTriggersImmediatelyInPauseMode() async {
        let rig = makeRig(trigger: .pause)
        rig.type("wie gehts dir?")
        await waitUntil { rig.ai.requests.count == 1 }
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[0]), "wie gehts dir?")
        rig.ai.respond("Wie geht's dir?")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Wie geht's dir?")
    }

    @MainActor
    func testLiveTriggerFiresOnEveryCompletedWord() async {
        let rig = makeRig(trigger: .live)
        rig.type("hallo")
        XCTAssertEqual(rig.ai.requests.count, 0)
        // A pause with only a word in progress has nothing to send.
        rig.scheduler.advance(by: 0.8)
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 0)
        rig.type(" ")
        await waitUntil { rig.ai.requests.count == 1 }
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[0]), "hallo ")
        // More words while in flight are coalesced into one rerun.
        rig.type("wi gehts ")
        XCTAssertEqual(rig.ai.requests.count, 1)
        rig.ai.respond("Hallo ")
        await waitUntil { rig.ai.requests.count == 2 }
        XCTAssertEqual(rig.host.text, "Hallo wi gehts ")
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[1]), "Hallo wi gehts ")
        rig.ai.respond("Hallo, wie geht's ")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Hallo, wie geht's ")
        XCTAssertEqual(rig.ai.requests.count, 2)
    }

    @MainActor
    func testWordInternalApostropheDoesNotTrigger() async {
        let rig = makeRig(trigger: .live)
        rig.type("geht'")
        XCTAssertEqual(rig.ai.requests.count, 0)
        XCTAssertEqual(rig.ai.pendingCount, 0)
    }

    @MainActor
    func testCursorMoveDropsResult() async {
        let rig = makeRig(trigger: .pause)
        rig.type("hallo wi gehts ")
        rig.scheduler.advance(by: 0.7)
        await waitUntil { rig.ai.requests.count == 1 }
        rig.host.moveCursor(by: -3)
        rig.engine.noteNonAppend()
        rig.ai.respond("Hallo, wie geht's ")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "hallo wi gehts ")
        XCTAssertTrue(rig.engine.history.isEmpty)
    }

    @MainActor
    func testTextChangedBehindTheEngineIsNotOverwritten() async {
        let rig = makeRig(trigger: .pause)
        rig.type("hallo wi gehts ")
        rig.scheduler.advance(by: 0.7)
        await waitUntil { rig.ai.requests.count == 1 }
        // The app replaced the text without telling the engine.
        rig.host.setText("ganz anders ")
        rig.ai.respond("Hallo, wie geht's ")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "ganz anders ")
    }

    @MainActor
    func testRerunAfterInFlightResult() async {
        let rig = makeRig(trigger: .word)
        rig.type("hallo ")
        await waitUntil { rig.ai.requests.count == 1 }
        rig.type("wie ")
        rig.ai.respond("Hallo ")
        await waitUntil { rig.ai.requests.count == 2 }
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[1]), "Hallo wie ")
        rig.ai.respond("Hallo, wie ")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Hallo, wie ")
    }

    @MainActor
    func testFlushIncludesWordInProgress() async {
        let rig = makeRig(trigger: .pause)
        rig.type("ich bin hir")
        XCTAssertTrue(rig.engine.hasPendingWork)
        let flush = Task { @MainActor in
            await rig.engine.flush(timeout: 3)
        }
        await waitUntil { rig.ai.requests.count == 1 }
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[0]), "ich bin hir")
        rig.ai.respond("Ich bin hier")
        await flush.value
        XCTAssertEqual(rig.host.text, "Ich bin hier")
        XCTAssertFalse(rig.engine.hasPendingWork)
    }

    @MainActor
    func testFlushGivesUpAfterTimeout() async {
        let rig = makeRig(trigger: .pause)
        rig.type("ich bin hir ")
        let start = Date()
        await rig.engine.flush(timeout: 0.2)
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
        XCTAssertEqual(rig.host.text, "ich bin hir ")
        await waitUntil { rig.ai.pendingCount == 1 }
        rig.ai.respond("Ich bin hier ")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Ich bin hier ")
    }

    @MainActor
    func testUndoAndSuppressUntilBoundary() async {
        let rig = makeRig(trigger: .pause)
        rig.type("ich bin hir ")
        rig.scheduler.advance(by: 0.7)
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.respond("Ich bin hier ")
        await rig.engine.waitUntilIdle()
        rig.type("und")
        XCTAssertTrue(rig.engine.canUndo)
        XCTAssertTrue(rig.engine.undo())
        XCTAssertEqual(rig.host.text, "ich bin hir und")
        XCTAssertFalse(rig.engine.canUndo)

        // No automatic correction until a sentence end.
        rig.type(" so ")
        rig.scheduler.advance(by: 1)
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 1)
        rig.type(".")
        await waitUntil { rig.ai.requests.count == 2 }
        XCTAssertEqual(FakeAI.segment(of: rig.ai.requests[1]), "ich bin hir und so .")
        rig.ai.respond("Ich bin hier und so.")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Ich bin hier und so.")
    }

    @MainActor
    func testUndoNotPossibleAfterDelete() async {
        let rig = makeRig(trigger: .pause)
        rig.type("ich bin hir ")
        rig.scheduler.advance(by: 0.7)
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.respond("Ich bin hier ")
        await rig.engine.waitUntilIdle()
        rig.host.deleteBackward()
        rig.engine.noteNonAppend()
        XCTAssertFalse(rig.engine.canUndo)
        XCTAssertFalse(rig.engine.undo())
    }

    @MainActor
    func testCacheAppliesWithoutRequest() async {
        let rig = makeRig(trigger: .pause)
        rig.type("hallo wi gehts ")
        rig.scheduler.advance(by: 0.7)
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.respond("Hallo, wie geht's ")
        await rig.engine.waitUntilIdle()
        XCTAssertTrue(rig.engine.undo())
        XCTAssertEqual(rig.host.text, "hallo wi gehts ")
        // ✨ forces a correction: same segment -> cached result, no new request.
        rig.engine.correctNow()
        XCTAssertEqual(rig.host.text, "Hallo, wie geht's ")
        XCTAssertEqual(rig.ai.requests.count, 1)
    }

    @MainActor
    func testUnchangedOutputIsNotResent() async {
        let rig = makeRig(trigger: .pause)
        rig.type("Hallo zusammen ")
        rig.scheduler.advance(by: 0.7)
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.respond("Hallo zusammen ")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "Hallo zusammen ")
        // Correcting again without new text: the segment equals the last output.
        rig.engine.correctNow()
        rig.scheduler.advance(by: 1)
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 1)
        XCTAssertFalse(rig.engine.isWorking)
    }

    @MainActor
    func testSkipsWithSelectionAndWithoutKey() async {
        let rig = makeRig(trigger: .pause)
        rig.type("hallo wi gehts ")
        rig.host.select(from: 0, length: 5)
        rig.scheduler.advance(by: 0.7)
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 0)

        let rig2 = makeRig(trigger: .pause)
        rig2.ai.configured = false
        rig2.type("hallo wi gehts ")
        rig2.scheduler.advance(by: 0.7)
        await Task.yield()
        XCTAssertEqual(rig2.ai.requests.count, 0)
        XCTAssertEqual(rig2.engine.status, .noKey)
    }

    @MainActor
    func testFieldNotAllowedAndSessionToggle() async {
        let rig = makeRig(trigger: .pause)
        rig.engine.fieldStarted(allowed: false)
        XCTAssertEqual(rig.engine.status, .locked)
        rig.type("hallo wi gehts ")
        rig.scheduler.advance(by: 0.7)
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 0)

        rig.engine.fieldStarted(allowed: true)
        rig.engine.sessionEnabled = false
        XCTAssertEqual(rig.engine.status, .off)
        rig.type("noch mehr ")
        rig.scheduler.advance(by: 0.7)
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 0)
    }

    @MainActor
    func testErrorsShowAndPauseAfterThree() async {
        let rig = makeRig(trigger: .sentence)
        for i in 0..<3 {
            rig.type("satz \(i).")
            await waitUntil { rig.ai.requests.count == i + 1 }
            rig.ai.fail(URLError(.notConnectedToInternet))
            await rig.engine.waitUntilIdle()
        }
        if case .error(let e) = rig.engine.status {
            XCTAssertEqual(e.kind, .network)
        } else {
            XCTFail("expected error status")
        }
        // Automatic triggers are paused for 30 s.
        rig.type(" noch einer.")
        await Task.yield()
        XCTAssertEqual(rig.ai.requests.count, 3)
        rig.scheduler.advance(by: 31)
        rig.type(" und noch einer.")
        await waitUntil { rig.ai.requests.count == 4 }
    }

    @MainActor
    func testRefusalKeepsRawTextSilently() async {
        let rig = makeRig(trigger: .sentence)
        rig.type("irgendwas.")
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.fail(AIError(.refused))
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "irgendwas.")
        XCTAssertEqual(rig.engine.status, .ready)
    }

    @MainActor
    func testModelAnsweringInsteadOfCorrectingIsDropped() async {
        let rig = makeRig(trigger: .sentence)
        rig.type("was ist die hauptstadt von frankreich?")
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.respond("Die Hauptstadt von Frankreich ist Paris. Paris ist eine wunderschöne Stadt mit vielen Sehenswürdigkeiten und Museen.")
        await rig.engine.waitUntilIdle()
        XCTAssertEqual(rig.host.text, "was ist die hauptstadt von frankreich?")
    }

    @MainActor
    func testMoodCheckShowsChipOnce() async {
        let rig = makeRig(trigger: .sentence, mood: true)
        var alerts = 0
        rig.engine.onMoodAlert = { alerts += 1 }
        rig.type("ich bin so wütend auf dich!")
        await waitUntil { rig.ai.requests.count == 1 }
        rig.ai.respond("Ich bin so wütend auf dich!")
        await waitUntil { rig.ai.requests.count == 2 }
        XCTAssertEqual(rig.ai.requests[1].task, .mood)
        XCTAssertEqual(rig.ai.requests[1].user, "Ich bin so wütend auf dich!")
        rig.ai.respond("{\"level\": 3, \"reason\": \"Wut\"}")
        await waitUntil { rig.engine.moodLevel == 3 }
        XCTAssertEqual(alerts, 1)
        XCTAssertEqual(rig.engine.moodReason, "Wut")
    }
}
