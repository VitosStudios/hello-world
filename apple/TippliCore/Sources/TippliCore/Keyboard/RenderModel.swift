import Foundation

public enum ShiftState: Equatable {
    case off
    case oneShot(auto: Bool)
    case capsLock

    public var isActive: Bool { self != .off }
}

public enum KeyboardPanel: Equatable {
    case keys
    case emoji
    case tips
}

public enum KeyboardFeedback: Equatable {
    case keyDown
    case popupOpened
    case emotionAlert
    case noop
}

/// What the AI dot in the toolbar shows.
public enum AIIndicator: Equatable {
    case ready
    case working
    case error
    case off
    case locked
    case noKey
    case fullAccessNeeded
}

public enum ToolbarItemKind: Equatable {
    case aiStatus
    case undo
    case emotion
    case correctNow
    case tips
    case language
    case layoutToggle
    case settings
}

public enum ToolbarItemStyle: Equatable {
    case plain
    case chip
    case warningChip
    case dangerChip
}

public struct ToolbarItem: Equatable {
    public var kind: ToolbarItemKind
    public var frame: CGRect
    public var icon: String
    public var title: String
    public var style: ToolbarItemStyle
}

public enum ToastAction: Equatable {
    case openSettings
}

public struct Toast: Equatable {
    public var message: String
    public var actionTitle: String?
    public var action: ToastAction?
    public var id: Int
}

public enum KeyVisualKind: Equatable {
    case character
    case special
    case space
    case enter
    case shift
}

public struct RenderKey: Equatable {
    public var item: KeyItem
    public var label: String
    /// Tiny hint in the top-right corner (long-press digit in hex mode).
    public var hint: String?
    public var kind: KeyVisualKind
    public var pressed: Bool
    public var shift: ShiftState
    public var showsSpinner: Bool
    /// Labels that are words (space language, enter action, 123/ABC) use the small font.
    public var smallLabel: Bool
}

public struct RenderPopup: Equatable {
    public var frame: CGRect
    public var cells: [CGRect]
    public var labels: [String]
    public var selected: Int?
}

/// Everything the renderer needs for one frame.
public struct KeyboardRenderState {
    public var size: CGSize
    public var metrics: KeyboardMetrics
    public var showKeys: Bool
    public var keys: [RenderKey]
    public var toolbar: [ToolbarItem]
    public var toolbarPressed: ToolbarItemKind?
    public var indicator: AIIndicator
    /// 0…1, advances over time while something animates (pulsing dot, spinner).
    public var animationPhase: CGFloat
    public var popup: RenderPopup?
    public var toast: Toast?
}
