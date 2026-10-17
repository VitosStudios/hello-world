import SwiftUI
import UIKit
import TippliCore

/// 3-step onboarding (SPEC §7.3): enable keyboard → connect AI → try it.
struct OnboardingView: View {
    @ObservedObject var model: SettingsModel
    let openSettingsTab: () -> Void

    @State private var tryText = ""
    @FocusState private var tryFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                StepCard(number: 1, title: loc("onboarding.step1")) {
                    Text(AppleStrings.iosEnableKeyboard)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(loc("onboarding.iosFullAccess"))
                        .fixedSize(horizontal: false, vertical: true)
                        .foregroundColor(.secondary)
                    Button(action: openSystemSettings) {
                        Label(loc("onboarding.openSettings"), systemImage: "gear")
                    }
                    .buttonStyle(.borderedProminent)
                }
                StepCard(number: 2, title: loc("onboarding.step2")) {
                    Text(model.preset.name)
                        .font(.headline)
                    let hasKey = !model.apiKey(for: model.preset.id).isEmpty || !model.preset.keyRequired
                    Label(hasKey ? loc("settings.apiKeySaved") : loc("toolbar.aiNoKey"),
                          systemImage: hasKey ? "checkmark.seal.fill" : "key")
                        .foregroundColor(hasKey ? .green : .orange)
                    Button(action: openSettingsTab) {
                        Label(loc("settings.sectionProvider"), systemImage: "cpu")
                    }
                    .buttonStyle(.bordered)
                }
                StepCard(number: 3, title: loc("onboarding.step3")) {
                    TextField(loc("onboarding.tryHere"), text: $tryText, axis: .vertical)
                        .lineLimit(4...10)
                        .focused($tryFocused)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(UIColor.secondarySystemBackground)))
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(loc("settings.sectionPrivacy")).font(.headline)
                    Text(loc("settings.privacyText"))
                        .font(.callout)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(loc("app.name"))
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(systemName: "hexagon.fill")
                .font(.system(size: 44))
                .foregroundStyle(Color.accentColor)
                .overlay(Image(systemName: "sparkle").font(.system(size: 20, weight: .bold)).foregroundColor(.white))
            VStack(alignment: .leading, spacing: 4) {
                Text(loc("onboarding.welcomeTitle")).font(.title2.bold())
                Text(loc("onboarding.welcomeText"))
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

struct StepCard<Content: View>: View {
    let number: Int
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Text("\(number)")
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(Color.accentColor))
                Text(title).font(.headline)
            }
            content()
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(UIColor.systemBackground)))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(UIColor.separator), lineWidth: 0.5))
    }
}
