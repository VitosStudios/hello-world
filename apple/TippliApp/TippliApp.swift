import SwiftUI
import TippliCore

@main
struct TippliApp: App {
    @StateObject private var model = SettingsModel.makeDefault()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}

enum AppTab: Hashable {
    case start
    case settings
    case gestures
}

struct RootView: View {
    @ObservedObject var model: SettingsModel
    @State private var tab: AppTab = .start

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack {
                OnboardingView(model: model, openSettingsTab: { tab = .settings })
            }
            .tabItem { Label(loc("app.name"), systemImage: "sparkles") }
            .tag(AppTab.start)

            NavigationStack {
                SettingsForm(model: model)
                    .navigationTitle(loc("settings.title"))
            }
            .tabItem { Label(loc("settings.title"), systemImage: "gearshape") }
            .tag(AppTab.settings)

            NavigationStack {
                ScrollView {
                    GestureHelpList()
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .navigationTitle(loc("gestures.title"))
            }
            .tabItem { Label(loc("gestures.title"), systemImage: "hand.draw") }
            .tag(AppTab.gestures)
        }
        .onOpenURL { _ in
            // tippli://settings from the keyboard's ⚙ button.
            tab = .settings
        }
    }
}
