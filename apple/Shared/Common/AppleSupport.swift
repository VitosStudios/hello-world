import SwiftUI
import TippliCore

/// A few Apple-specific texts that are not (yet) in shared/strings-de.json.
/// Everything else comes from strings-de.json via `loc(_:)`.
enum AppleStrings {
    static let iosEnableKeyboard = "Öffne Einstellungen › Allgemein › Tastatur › Tastaturen › Neue Tastatur hinzufügen … und wähle „Tippli“."
    static let macAccessibility = "Bedienungshilfen"
    static let macInputMonitoring = "Eingabeüberwachung"
    static let macGranted = "Erlaubt ✓"
    static let macMissing = "Fehlt"
    static let macRequest = "Erlauben …"
    static let quit = "Tippli beenden"
    static let onScreenKeyboard = "Bildschirmtastatur"
    static let tipsForSelection = "Tipps für Auswahl"
    static let statusOn = "an"
    static let statusOff = "aus"
    static let add = "Hinzufügen"
    static let appNamePlaceholder = "Programmname"
    static let version = "Version"
    static let restartHint = "Falls die Berechtigung nicht erkannt wird: Tippli beenden und neu starten."
}

extension RGBA {
    var color: Color {
        Color(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

/// Palette for SwiftUI views following the `theme` setting.
struct TippliTheme {
    static func palette(setting: String, colorScheme: ColorScheme) -> ThemePalette {
        ThemePalette.make(dark: ThemePalette.isDark(setting: setting, systemIsDark: colorScheme == .dark))
    }
}
