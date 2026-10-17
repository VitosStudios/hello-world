import Foundation

/// A cancellable scheduled block.
public protocol Cancellable: AnyObject {
    func cancel()
}

/// Clock + timer abstraction so that gestures and the correction engine can be
/// tested deterministically. All blocks run on the main thread in production.
public protocol Scheduler: AnyObject {
    /// Monotonic time in seconds.
    var now: TimeInterval { get }
    @discardableResult
    func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> Cancellable
}

/// Production scheduler on the main dispatch queue.
public final class MainScheduler: Scheduler {
    public static let shared = MainScheduler()

    public init() {}

    public var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    @discardableResult
    public func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> Cancellable {
        let token = WorkItemToken(block)
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, delay)) {
            token.fire()
        }
        return token
    }

    private final class WorkItemToken: Cancellable {
        private var block: (() -> Void)?

        init(_ block: @escaping () -> Void) {
            self.block = block
        }

        func fire() {
            let b = block
            block = nil
            b?()
        }

        func cancel() {
            block = nil
        }
    }
}

/// Deterministic scheduler for tests: time only moves with `advance(by:)`.
public final class ManualScheduler: Scheduler {
    private final class Item: Cancellable {
        let id: Int
        let time: TimeInterval
        var block: (() -> Void)?

        init(id: Int, time: TimeInterval, block: @escaping () -> Void) {
            self.id = id
            self.time = time
            self.block = block
        }

        func cancel() {
            block = nil
        }
    }

    public private(set) var now: TimeInterval = 0
    private var items: [Item] = []
    private var counter = 0

    public init(start: TimeInterval = 1000) {
        now = start
    }

    @discardableResult
    public func schedule(after delay: TimeInterval, _ block: @escaping () -> Void) -> Cancellable {
        counter += 1
        let item = Item(id: counter, time: now + max(0, delay), block: block)
        items.append(item)
        return item
    }

    /// Number of scheduled, not yet fired and not cancelled blocks.
    public var pendingCount: Int {
        items.filter { $0.block != nil }.count
    }

    /// Advances the clock, firing due blocks in time order (blocks scheduled
    /// while advancing fire too if they become due).
    public func advance(by delta: TimeInterval) {
        let target = now + delta
        while true {
            items.removeAll { $0.block == nil }
            let due = items.filter { $0.time <= target }
            guard let next = due.min(by: { ($0.time, $0.id) < ($1.time, $1.id) }) else { break }
            items.removeAll { $0 === next }
            now = max(now, next.time)
            let b = next.block
            next.block = nil
            b?()
        }
        now = target
    }
}
