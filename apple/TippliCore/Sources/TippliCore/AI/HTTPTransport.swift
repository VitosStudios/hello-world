import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Minimal async wrapper around `URLSession.dataTask` with cooperative
/// cancellation (works with `URLProtocol` stubs in tests).
final class HTTPTransport: @unchecked Sendable {
    let session: URLSession

    init(session: URLSession) {
        self.session = session
    }

    private final class TaskBox: @unchecked Sendable {
        private let lock = NSLock()
        private var task: URLSessionDataTask?
        private var cancelled = false

        func set(_ t: URLSessionDataTask) {
            lock.lock()
            task = t
            let c = cancelled
            lock.unlock()
            if c { t.cancel() }
        }

        func cancel() {
            lock.lock()
            cancelled = true
            let t = task
            lock.unlock()
            t?.cancel()
        }
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let box = TaskBox()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<(Data, HTTPURLResponse), Error>) in
                let task = self.session.dataTask(with: request) { data, response, error in
                    if let error = error {
                        cont.resume(throwing: error)
                        return
                    }
                    guard let http = response as? HTTPURLResponse else {
                        cont.resume(throwing: URLError(.badServerResponse))
                        return
                    }
                    cont.resume(returning: (data ?? Data(), http))
                }
                box.set(task)
                task.resume()
            }
        }, onCancel: {
            box.cancel()
        })
    }
}

/// Runs `operation` with a wall-clock timeout; throws `AIError(.timeout)`.
func withAITimeout(_ seconds: TimeInterval, _ operation: @escaping @Sendable () async throws -> String) async throws -> String {
    try await withThrowingTaskGroup(of: String.self) { group in
        group.addTask {
            try await operation()
        }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
            throw AIError(.timeout)
        }
        defer { group.cancelAll() }
        guard let first = try await group.next() else {
            throw AIError(.timeout)
        }
        return first
    }
}
