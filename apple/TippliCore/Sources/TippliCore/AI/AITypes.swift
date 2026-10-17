import Foundation

/// The kind of AI task (decides max tokens, effort, timeout and JSON mode).
public enum AITask: String, Equatable, Sendable {
    case correction
    case mood
    case tips
    case translate
    case test

    /// SPEC §6.2: correction 2048, mood 1024, tips 8000, translate 4000.
    public var maxTokens: Int {
        switch self {
        case .correction, .test: return 2048
        case .mood: return 1024
        case .tips: return 8000
        case .translate: return 4000
        }
    }

    /// SPEC §6.2: low for correction and mood, medium for tips and translation.
    public var effort: String {
        switch self {
        case .correction, .mood, .test: return "low"
        case .tips, .translate: return "medium"
        }
    }

    /// SPEC §6.7: correction 10 s, mood 15 s, tips/translate 45 s.
    public var timeout: TimeInterval {
        switch self {
        case .correction, .test: return 10
        case .mood: return 15
        case .tips, .translate: return 45
        }
    }

    /// Tasks whose answer is a JSON object.
    public var expectsJSON: Bool {
        self == .tips || self == .mood
    }
}

public struct AIRequest: Equatable, Sendable {
    public var task: AITask
    public var system: String
    public var user: String

    public init(task: AITask, system: String, user: String) {
        self.task = task
        self.system = system
        self.user = user
    }
}

/// Error keys from `strings.errors` (SPEC §6.7).
public struct AIError: Error, Equatable {
    public enum Kind: String, Equatable, Sendable {
        case noKey
        case invalidKey
        case badModel
        case rateLimited
        case server
        case network
        case timeout
        case refused
        case badResponse
        case cancelled
    }

    public let kind: Kind
    /// Optional detail from the server (shown in settings / toasts).
    public let detail: String?
    public let status: Int?

    public init(_ kind: Kind, detail: String? = nil, status: Int? = nil) {
        self.kind = kind
        self.detail = detail
        self.status = status
    }

    /// The user-facing message from strings-de.json.
    public var message: String {
        if kind == .cancelled { return "" }
        return loc("errors." + kind.rawValue)
    }

    /// Message plus server detail.
    public var fullMessage: String {
        guard let d = detail, !d.isEmpty else { return message }
        return message + "\n" + d
    }

    /// Maps any error (URLError, CancellationError, AIError) to an AIError.
    public static func from(_ error: Error) -> AIError {
        if let e = error as? AIError { return e }
        if error is CancellationError { return AIError(.cancelled) }
        if let u = error as? URLError {
            switch u.code {
            case .timedOut: return AIError(.timeout)
            case .cancelled: return AIError(.cancelled)
            default: return AIError(.network, detail: u.localizedDescription)
            }
        }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            if ns.code == NSURLErrorTimedOut { return AIError(.timeout) }
            if ns.code == NSURLErrorCancelled { return AIError(.cancelled) }
            return AIError(.network, detail: ns.localizedDescription)
        }
        return AIError(.network, detail: error.localizedDescription)
    }

    /// SPEC §6.7 HTTP status mapping.
    public static func fromHTTP(status: Int, detail: String?) -> AIError {
        switch status {
        case 401, 403: return AIError(.invalidKey, detail: detail, status: status)
        case 404: return AIError(.badModel, detail: detail, status: status)
        case 429: return AIError(.rateLimited, detail: detail, status: status)
        case 529: return AIError(.server, detail: detail, status: status)
        default:
            if status >= 500 { return AIError(.server, detail: detail, status: status) }
            return AIError(.badResponse, detail: detail, status: status)
        }
    }
}

/// Anything that can answer an AI request (real clients and test fakes).
public protocol AICompleting: AnyObject {
    func complete(_ request: AIRequest) async throws -> String
}
