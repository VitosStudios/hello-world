import Foundation
import XCTest
@testable import TippliCore

/// The repository's `shared/` folder, resolved relative to this file.
let sharedDirectory: URL = {
    URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // TippliCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // TippliCore
        .deletingLastPathComponent() // apple
        .deletingLastPathComponent() // repository root
        .appendingPathComponent("shared", isDirectory: true)
}()

/// Shared resources read straight from the repository checkout.
let testResources: SharedResources = {
    let r = SharedResources(provider: DirectoryResourceProvider(root: sharedDirectory))
    SharedResources.shared = r
    return r
}()

func loadSharedJSON(_ relativePath: String) throws -> Any {
    let data = try Data(contentsOf: sharedDirectory.appendingPathComponent(relativePath))
    return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
}

/// A fresh, empty UserDefaults suite for a test.
func makeTestDefaults() -> UserDefaults {
    let suite = "tippli.tests." + UUID().uuidString
    let d = UserDefaults(suiteName: suite)!
    d.removePersistentDomain(forName: suite)
    return d
}

/// A fake AI whose answers are controlled by the test.
final class FakeAI: AICompleting, CorrectionAIProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [AIRequest] = []
    private var pending: [CheckedContinuation<String, Error>] = []
    /// When set, answers immediately with this closure instead of waiting.
    var autoAnswer: ((AIRequest) -> String)?
    var configured = true

    var requests: [AIRequest] {
        lock.lock()
        defer { lock.unlock() }
        return _requests
    }

    var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return pending.count
    }

    func complete(_ request: AIRequest) async throws -> String {
        if let auto = autoAnswer {
            lock.lock()
            _requests.append(request)
            lock.unlock()
            return auto(request)
        }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<String, Error>) in
            lock.lock()
            _requests.append(request)
            pending.append(cont)
            lock.unlock()
        }
    }

    /// Answers the oldest pending request.
    func respond(_ text: String) {
        lock.lock()
        let c = pending.isEmpty ? nil : pending.removeFirst()
        lock.unlock()
        c?.resume(returning: text)
    }

    func fail(_ error: Error) {
        lock.lock()
        let c = pending.isEmpty ? nil : pending.removeFirst()
        lock.unlock()
        c?.resume(throwing: error)
    }

    // CorrectionAIProvider
    var isConfigured: Bool { configured }

    func makeClient() -> AICompleting? { configured ? self : nil }

    func correctionRequest(context: String, segment: String) -> AIRequest {
        AIRequest(task: .correction, system: "SYSTEM", user: "<context>\(context)</context>\n<segment>\(segment)</segment>")
    }

    func moodRequest(paragraph: String) -> AIRequest {
        AIRequest(task: .mood, system: "MOOD", user: paragraph)
    }

    /// The segment of a correction request built by `correctionRequest`.
    static func segment(of request: AIRequest) -> String {
        guard let s = request.user.range(of: "<segment>"), let e = request.user.range(of: "</segment>") else { return "" }
        return String(request.user[s.upperBound..<e.lowerBound])
    }
}

extension XCTestCase {
    /// Polls (on the main actor) until the condition holds or the timeout expires.
    @MainActor
    func waitUntil(timeout: TimeInterval = 3, file: StaticString = #filePath, line: UInt = #line,
                   _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline {
                XCTFail("Condition not met within \(timeout) s", file: file, line: line)
                return
            }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
    }
}
