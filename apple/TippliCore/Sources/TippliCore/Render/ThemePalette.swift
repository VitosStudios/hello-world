import Foundation

/// An sRGB colour parsed from `#RRGGBB` / `#RRGGBBAA`.
public struct RGBA: Equatable {
    public var r: Double
    public var g: Double
    public var b: Double
    public var a: Double

    public init(r: Double, g: Double, b: Double, a: Double = 1) {
        self.r = r
        self.g = g
        self.b = b
        self.a = a
    }

    public init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6 || s.count == 8, let v = UInt64(s, radix: 16) else { return nil }
        if s.count == 6 {
            r = Double((v >> 16) & 0xFF) / 255
            g = Double((v >> 8) & 0xFF) / 255
            b = Double(v & 0xFF) / 255
            a = 1
        } else {
            r = Double((v >> 24) & 0xFF) / 255
            g = Double((v >> 16) & 0xFF) / 255
            b = Double((v >> 8) & 0xFF) / 255
            a = Double(v & 0xFF) / 255
        }
    }

    public func withAlpha(_ alpha: Double) -> RGBA {
        RGBA(r: r, g: g, b: b, a: alpha)
    }
}

/// The colour tokens of theme.json for one appearance.
public struct ThemePalette: Equatable {
    public var background: RGBA
    public var toolbar: RGBA
    public var key: RGBA
    public var keyPressed: RGBA
    public var specialKey: RGBA
    public var spaceKey: RGBA
    public var keyText: RGBA
    public var secondaryText: RGBA
    public var accent: RGBA
    public var accentText: RGBA
    public var popup: RGBA
    public var popupSelected: RGBA
    public var keyShadow: RGBA
    public var warning: RGBA
    public var danger: RGBA
    public var success: RGBA
    public var panel: RGBA
    public var card: RGBA
    public var divider: RGBA
    public var isDark: Bool

    public init(tokens: [String: String], isDark: Bool) {
        func c(_ name: String, _ fallback: String) -> RGBA {
            RGBA(hex: tokens[name] ?? fallback) ?? RGBA(hex: fallback) ?? RGBA(r: 0, g: 0, b: 0)
        }
        background = c("background", "#E7E9EF")
        toolbar = c("toolbar", "#E7E9EF")
        key = c("key", "#FFFFFF")
        keyPressed = c("keyPressed", "#D3D8E2")
        specialKey = c("specialKey", "#C8CEDA")
        spaceKey = c("spaceKey", "#FFFFFF")
        keyText = c("keyText", "#15171C")
        secondaryText = c("secondaryText", "#6B7280")
        accent = c("accent", "#4F46E5")
        accentText = c("accentText", "#FFFFFF")
        popup = c("popup", "#FFFFFF")
        popupSelected = c("popupSelected", "#4F46E5")
        keyShadow = c("keyShadow", "#0000002E")
        warning = c("warning", "#F59E0B")
        danger = c("danger", "#E5484D")
        success = c("success", "#12A594")
        panel = c("panel", "#F6F7FA")
        card = c("card", "#FFFFFF")
        divider = c("divider", "#D6DAE2")
        self.isDark = isDark
    }

    /// Light or dark palette from theme.json.
    public static func make(dark: Bool, resources: SharedResources = .shared) -> ThemePalette {
        let theme = resources.theme
        return ThemePalette(tokens: dark ? theme.dark : theme.light, isDark: dark)
    }

    /// Resolves the `theme` setting ("system" | "light" | "dark").
    public static func isDark(setting: String, systemIsDark: Bool) -> Bool {
        switch setting {
        case "light": return false
        case "dark": return true
        default: return systemIsDark
        }
    }
}
