import SwiftUI
import TippliCore

/// All settings sections in the order of SPEC §7.3. The macOS app passes its
/// "Computer" section via `desktopSection`.
struct SettingsForm: View {
    @ObservedObject var model: SettingsModel
    var desktopSection: AnyView? = nil

    var body: some View {
        Form {
            ProviderSection(model: model)
            correctionSection
            languagesSection
            keyboardSection
            tipsSection
            privacySection
            if let desktop = desktopSection {
                desktop
            }
            aboutSection
        }
    }

    // MARK: Korrektur

    private var correctionSection: some View {
        let trigger = model.store.trigger
        return Section(header: Text(loc("settings.sectionCorrection"))) {
            Toggle(loc("settings.aiEnabled"), isOn: model.bool(SettingKey.aiEnabled))
            Picker(loc("settings.trigger"), selection: model.string(SettingKey.trigger)) {
                Text(loc("settings.triggerLive")).tag("live")
                Text(loc("settings.triggerPause")).tag("pause")
                Text(loc("settings.triggerWord")).tag("word")
                Text(loc("settings.triggerSentence")).tag("sentence")
                Text(loc("settings.triggerManual")).tag("manual")
            }
            if trigger == "live" || trigger == "pause" {
                SliderRow(title: loc("settings.pauseMs"), value: model.number(SettingKey.pauseMs),
                          range: model.range(SettingKey.pauseMs, fallback: 300...2000), step: 50, unit: "ms")
            }
            Picker(loc("settings.strength"), selection: model.string(SettingKey.strength)) {
                Text(loc("settings.strengthLight")).tag("light")
                Text(loc("settings.strengthNormal")).tag("normal")
                Text(loc("settings.strengthStrong")).tag("strong")
            }
            SliderRow(title: loc("settings.waitOnSend"), value: model.number(SettingKey.waitOnSendMs),
                      range: model.range(SettingKey.waitOnSendMs, fallback: 0...4000), step: 250, unit: "ms")
            Toggle(loc("settings.moodWarning"), isOn: model.bool(SettingKey.moodWarning))
        }
    }

    // MARK: Sprachen

    private var languagesSection: some View {
        let enabled = model.store.enabledLanguages
        return Section(header: Text(loc("settings.sectionLanguages")),
                       footer: Text(loc("settings.languagesHint"))) {
            ForEach(model.resources.languages.languages) { lang in
                Toggle(lang.name, isOn: model.languageEnabled(lang.id))
            }
            ForEach(enabled) { lang in
                Picker(loc("settings.layoutFor") + " · " + lang.short, selection: model.keyboardLayout(for: lang)) {
                    ForEach(model.keyboardLayouts) { choice in
                        Text(choice.name).tag(choice.id)
                    }
                }
            }
        }
    }

    // MARK: Tastatur

    private var keyboardSection: some View {
        Section(header: Text(loc("settings.sectionKeyboard"))) {
            Picker(loc("settings.keyboardMode"), selection: model.string(SettingKey.keyboardMode)) {
                Text(loc("settings.modeHex")).tag("hex")
                Text(loc("settings.modeNormal")).tag("normal")
            }
            Group {
                Toggle(loc("settings.longPressAccents"), isOn: model.bool(SettingKey.longPressAccents))
                Toggle(loc("settings.longPressDigits"), isOn: model.bool(SettingKey.longPressDigits))
                SliderRow(title: loc("settings.longPressMs"), value: model.number(SettingKey.longPressMs),
                          range: model.range(SettingKey.longPressMs, fallback: 200...800), step: 25, unit: "ms")
            }
            Picker(loc("settings.keyHeight"), selection: model.string(SettingKey.keyHeight)) {
                Text(loc("settings.keyHeightSmall")).tag("small")
                Text(loc("settings.keyHeightMedium")).tag("medium")
                Text(loc("settings.keyHeightLarge")).tag("large")
            }
            Group {
                Toggle(loc("settings.haptics"), isOn: model.bool(SettingKey.haptics))
                Toggle(loc("settings.sound"), isOn: model.bool(SettingKey.sound))
                Toggle(loc("settings.autoCapitalize"), isOn: model.bool(SettingKey.autoCapitalize))
                Toggle(loc("settings.doubleSpacePeriod"), isOn: model.bool(SettingKey.doubleSpacePeriod))
                Toggle(loc("settings.numberRow"), isOn: model.bool(SettingKey.numberRow))
            }
            Picker(loc("settings.theme"), selection: model.string(SettingKey.theme)) {
                Text(loc("settings.themeSystem")).tag("system")
                Text(loc("settings.themeLight")).tag("light")
                Text(loc("settings.themeDark")).tag("dark")
            }
        }
    }

    // MARK: Tipps

    private var tipsSection: some View {
        Section(header: Text(loc("settings.sectionTips"))) {
            Text(loc("settings.tipsSections")).font(.subheadline).foregroundColor(.secondary)
            Toggle(loc("tips.rewrites"), isOn: model.bool(SettingKey.tipsRewrites))
            Toggle(loc("tips.explanations"), isOn: model.bool(SettingKey.tipsExplanations))
            Toggle(loc("tips.writing"), isOn: model.bool(SettingKey.tipsWriting))
            Toggle(loc("tips.emotion"), isOn: model.bool(SettingKey.tipsEmotion))
            Toggle(loc("tips.translate"), isOn: model.bool(SettingKey.tipsTranslate))
            DisclosureGroup(loc("settings.rewriteVariants")) {
                ForEach(model.resources.promptParts.rewriteVariants) { v in
                    Toggle(v.label, isOn: model.listMember(SettingKey.rewriteVariants, v.id))
                }
            }
            DisclosureGroup(loc("settings.translateTargets")) {
                ForEach(model.resources.languages.translateTargets) { t in
                    Toggle(t.name, isOn: model.listMember(SettingKey.translateTargets, t.id))
                }
            }
        }
    }

    // MARK: Datenschutz

    private var privacySection: some View {
        Section(header: Text(loc("settings.sectionPrivacy"))) {
            Text(loc("settings.privacyText"))
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Über Tippli

    private var aboutSection: some View {
        Section(header: Text(loc("settings.sectionAbout"))) {
            VStack(alignment: .leading, spacing: 4) {
                Text(loc("app.name")).font(.headline)
                Text(loc("app.tagline")).foregroundColor(.secondary)
                Text(AppleStrings.version + " " + appVersion).font(.caption).foregroundColor(.secondary)
            }
            DisclosureGroup(loc("gestures.title")) {
                GestureHelpList()
            }
        }
    }

    private var appVersion: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(v) (\(b))"
    }
}

/// A labelled slider for an integer setting.
struct SliderRow: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let unit: String

    init(title: String, value: Binding<Double>, range: ClosedRange<Double>, step: Double, unit: String) {
        self.title = title
        self._value = value
        self.range = range
        self.step = step
        self.unit = unit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text("\(Int(value)) \(unit)").foregroundColor(.secondary).monospacedDigit()
            }
            Slider(value: $value, in: range, step: step)
        }
    }
}

/// The gesture help (strings.gestures).
struct GestureHelpList: View {
    private struct Row: Identifiable {
        let icon: String
        let key: String
        var id: String { key }
    }

    private let rows: [Row] = [
        Row(icon: "arrow.up", key: "gestures.swipeUp"),
        Row(icon: "arrow.left", key: "gestures.swipeLeft"),
        Row(icon: "arrow.right", key: "gestures.swipeRight"),
        Row(icon: "hand.tap", key: "gestures.longPress"),
        Row(icon: "arrow.left.and.right", key: "gestures.spaceDrag"),
        Row(icon: "delete.left", key: "gestures.backspaceHold"),
        Row(icon: "shift", key: "gestures.shiftDouble")
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(rows) { row in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: row.icon)
                        .frame(width: 22)
                        .foregroundColor(.accentColor)
                    Text(loc(row.key))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
