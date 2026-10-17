import AppKit
import SwiftUI
import TippliCore

@main
struct TippliMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var desktop = DesktopController.shared

    var body: some Scene {
        MenuBarExtra {
            MenuContent(desktop: desktop)
        } label: {
            Image(systemName: desktop.liveCorrection ? "sparkles" : "hexagon")
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        DesktopController.shared.start()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }
}

/// Menu-bar menu (SPEC §10): status, live correction, keyboard, tips, settings, quit.
struct MenuContent: View {
    @ObservedObject var desktop: DesktopController

    var body: some View {
        Text(desktop.statusText)
        Divider()
        Group {
            Toggle(loc("settings.desktopLive") + "   ⌃⌥K", isOn: Binding(
                get: { desktop.liveCorrection },
                set: { desktop.setLiveCorrection($0) }))
            Toggle(AppleStrings.onScreenKeyboard + "   ⌃⌥B", isOn: Binding(
                get: { desktop.keyboardVisible },
                set: { _ in desktop.toggleKeyboard() }))
            Button(AppleStrings.tipsForSelection + "   ⌃⌥H") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { desktop.tipsForSelection() }
            }
            Button(AppleStrings.correctNow + "   ⌃⌥J") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { desktop.correctNow() }
            }
        }
        Divider()
        if !desktop.permissionsOK {
            Button(loc("onboarding.openSettings") + " (" + AppleStrings.macAccessibility + ")") {
                desktop.showPermissions()
            }
        }
        Button(loc("settings.title") + " …") {
            desktop.showSettings()
        }
        .keyboardShortcut(",")
        Divider()
        Button(AppleStrings.quit) {
            NSApp.terminate(nil)
        }
        .keyboardShortcut("q")
    }
}

/// Settings window: the shared settings form plus the desktop section.
struct MacSettingsView: View {
    @ObservedObject var model: SettingsModel
    @ObservedObject var desktop: DesktopController

    var body: some View {
        SettingsForm(model: model, desktopSection: AnyView(DesktopSection(model: model, desktop: desktop)))
            .formStyle(.grouped)
            .frame(minWidth: 520, minHeight: 560)
    }
}

/// "Computer (Mac/PC)" settings section.
struct DesktopSection: View {
    @ObservedObject var model: SettingsModel
    @ObservedObject var desktop: DesktopController
    @State private var newApp = ""

    var body: some View {
        Section(header: Text(loc("settings.sectionDesktop"))) {
            PermissionsView(desktop: desktop)
            Toggle(loc("settings.desktopLive"), isOn: model.bool(SettingKey.desktopLiveCorrection))
            Toggle(loc("settings.desktopLogin"), isOn: model.bool(SettingKey.desktopLaunchAtLogin))
            Toggle(loc("settings.desktopKeyboardOnStart"), isOn: model.bool(SettingKey.desktopShowKeyboardOnStart))
            DisclosureGroup(loc("settings.excludedApps")) {
                ForEach(model.store.stringArray(SettingKey.desktopExcludedApps), id: \.self) { app in
                    HStack {
                        Text(app)
                        Spacer()
                        Button(action: { remove(app) }) {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.borderless)
                    }
                }
                HStack {
                    TextField(AppleStrings.appNamePlaceholder, text: $newApp)
                        .onSubmit { add() }
                    Button(AppleStrings.add) { add() }
                        .disabled(newApp.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(loc("settings.hotkeys")).font(.headline)
                hotkeyRow("⌃⌥K", loc("settings.hotkeyToggle"))
                hotkeyRow("⌃⌥H", loc("settings.hotkeyTips"))
                hotkeyRow("⌃⌥B", loc("settings.hotkeyKeyboard"))
                hotkeyRow("⌃⌥J", loc("settings.hotkeyCorrectNow"))
            }
        }
    }

    private func hotkeyRow(_ keys: String, _ title: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(keys)
                .font(.system(.body, design: .monospaced))
                .frame(width: 56, alignment: .leading)
            Text(title).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func add() {
        let name = newApp.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        var list = model.store.stringArray(SettingKey.desktopExcludedApps)
        if !list.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) {
            list.append(name)
            model.store.set(list, forKey: SettingKey.desktopExcludedApps)
            model.changed()
        }
        newApp = ""
    }

    private func remove(_ app: String) {
        var list = model.store.stringArray(SettingKey.desktopExcludedApps)
        list.removeAll { $0 == app }
        model.store.set(list, forKey: SettingKey.desktopExcludedApps)
        model.changed()
    }
}

/// Accessibility + Input Monitoring status with request buttons.
struct PermissionsView: View {
    @ObservedObject var desktop: DesktopController

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(loc("onboarding.macPermissions"))
                .fixedSize(horizontal: false, vertical: true)
            row(AppleStrings.macAccessibility, granted: desktop.accessibilityGranted) {
                desktop.requestAccessibility()
            }
            row(AppleStrings.macInputMonitoring, granted: desktop.inputMonitoringGranted) {
                desktop.requestInputMonitoring()
            }
            if !desktop.permissionsOK {
                Text(AppleStrings.restartHint)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .onAppear { desktop.refreshPermissions() }
    }

    private func row(_ title: String, granted: Bool, request: @escaping () -> Void) -> some View {
        HStack {
            Image(systemName: granted ? "checkmark.circle.fill" : "exclamationmark.circle")
                .foregroundColor(granted ? .green : .orange)
            Text(title)
            Spacer()
            if granted {
                Text(AppleStrings.macGranted).foregroundColor(.secondary)
            } else {
                Button(AppleStrings.macRequest, action: request)
            }
        }
    }
}

/// First-start window when permissions are missing.
struct PermissionsWindowView: View {
    @ObservedObject var desktop: DesktopController

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(loc("onboarding.welcomeTitle")).font(.title2.bold())
                    Text(loc("app.tagline")).foregroundColor(.secondary)
                }
            }
            PermissionsView(desktop: desktop)
            HStack {
                Spacer()
                Button(loc("settings.title") + " …") { desktop.showSettings() }
            }
        }
        .padding(24)
        .frame(width: 460)
    }
}
