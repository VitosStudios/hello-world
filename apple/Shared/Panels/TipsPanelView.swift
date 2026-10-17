import SwiftUI
import TippliCore

/// Tips panel content (keyboard extension) and tips window (macOS), SPEC §7.2.
struct TipsPanelView: View {
    @ObservedObject var model: TipsModel
    let palette: ThemePalette

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(model.scopeTitle)
                    .font(.headline)
                    .foregroundColor(palette.keyText.color)
                    .lineLimit(1)
                Spacer()
                Button(action: { model.onClose() }) {
                    Text(loc("tips.close"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(palette.accent.color)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            Rectangle().fill(palette.divider.color).frame(height: 1)
            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 16) {
                        content
                        if model.sections.translate && !model.isEmptyText {
                            translateSection
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: model.loadedCount) { _ in
                    if model.scrollToEmotion {
                        withAnimation { proxy.scrollTo("emotion", anchor: .top) }
                    }
                }
            }
        }
        .background(palette.panel.color)
        .onAppear {
            if case .loading = model.phase { model.load() }
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .empty:
            Text(loc("tips.emptyText"))
                .foregroundColor(palette.secondaryText.color)
        case .loading:
            HStack(spacing: 8) {
                ProgressView()
                Text(loc("tips.loading")).foregroundColor(palette.secondaryText.color)
            }
        case .failed(let message):
            VStack(alignment: .leading, spacing: 8) {
                Text(message).foregroundColor(palette.danger.color)
                Button(loc("tips.retry")) { model.load() }
            }
        case .loaded(let result):
            loaded(result)
        }
    }

    @ViewBuilder
    private func loaded(_ result: TipsResult) -> some View {
        if model.sections.rewrites {
            SectionTitle(text: loc("tips.rewrites"), palette: palette)
            ForEach(Array(result.rewrites.enumerated()), id: \.offset) { index, rewrite in
                TextCard(title: rewrite.label, text: rewrite.text, palette: palette,
                         copied: model.copiedToken == "rw\(index)",
                         onReplace: { model.onReplace(rewrite.text) },
                         onInsert: nil,
                         onCopy: { model.copy(rewrite.text, token: "rw\(index)") })
            }
        }
        if model.sections.explanations {
            SectionTitle(text: loc("tips.explanations"), palette: palette)
            if result.explanations.isEmpty {
                Text(loc("tips.noCorrections")).foregroundColor(palette.secondaryText.color)
            } else {
                ForEach(Array(result.explanations.enumerated()), id: \.offset) { _, e in
                    (Text(e.from).strikethrough() + Text(" → ") + Text(e.to).bold() + Text(e.why.isEmpty ? "" : ": " + e.why))
                        .foregroundColor(palette.keyText.color)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        if model.sections.writing {
            SectionTitle(text: loc("tips.writing"), palette: palette)
            if result.tips.isEmpty {
                Text(loc("tips.noTips")).foregroundColor(palette.secondaryText.color)
            } else {
                ForEach(Array(result.tips.enumerated()), id: \.offset) { _, tip in
                    HStack(alignment: .top, spacing: 6) {
                        Text("•")
                        Text(tip).fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundColor(palette.keyText.color)
                }
            }
        }
        if model.sections.emotion {
            SectionTitle(text: loc("tips.emotion"), palette: palette)
                .id("emotion")
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(emotionColor(result.emotion.level))
                        .frame(width: 10, height: 10)
                    Text(model.emotionLabel(result.emotion.level))
                        .font(.subheadline.weight(.semibold))
                    if !result.emotion.label.isEmpty && result.emotion.level > 0 {
                        Text("· " + result.emotion.label).foregroundColor(palette.secondaryText.color)
                    }
                }
                .foregroundColor(palette.keyText.color)
                if !result.emotion.reason.isEmpty {
                    Text(result.emotion.reason)
                        .foregroundColor(palette.secondaryText.color)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if result.emotion.level > 0 && !result.emotion.calmer.isEmpty {
                    TextCard(title: loc("tips.calmer"), text: result.emotion.calmer, palette: palette,
                             copied: model.copiedToken == "calm",
                             onReplace: { model.onReplace(result.emotion.calmer) },
                             onInsert: nil,
                             onCopy: { model.copy(result.emotion.calmer, token: "calm") })
                }
            }
        }
    }

    private func emotionColor(_ level: Int) -> Color {
        switch level {
        case 0: return palette.success.color
        case 1: return palette.secondaryText.color
        case 2: return palette.warning.color
        default: return palette.danger.color
        }
    }

    // MARK: Translate

    private var translateSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: loc("tips.translate"), palette: palette)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(model.targets) { target in
                        Button(action: { model.translate(target) }) {
                            Text(target.name)
                                .font(.subheadline)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Capsule().fill(palette.card.color))
                                .foregroundColor(palette.keyText.color)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            ForEach(model.targets) { target in
                if let state = model.translations[target.id] {
                    switch state {
                    case .loading:
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(target.name).foregroundColor(palette.secondaryText.color)
                        }
                    case .failed(let message):
                        Text(message).foregroundColor(palette.danger.color)
                    case .done(let text):
                        TextCard(title: target.name, text: text, palette: palette,
                                 copied: model.copiedToken == "tr" + target.id,
                                 onReplace: { model.onReplace(text) },
                                 onInsert: { model.onInsert(text) },
                                 onCopy: { model.copy(text, token: "tr" + target.id) })
                    }
                }
            }
        }
    }
}

struct SectionTitle: View {
    let text: String
    let palette: ThemePalette

    var body: some View {
        Text(text.uppercased())
            .font(.caption.weight(.semibold))
            .foregroundColor(palette.secondaryText.color)
            .padding(.top, 2)
    }
}

/// A card with a text and Ersetzen / Einfügen / Kopieren actions.
struct TextCard: View {
    let title: String
    let text: String
    let palette: ThemePalette
    let copied: Bool
    let onReplace: () -> Void
    let onInsert: (() -> Void)?
    let onCopy: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !title.isEmpty {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(palette.accent.color)
            }
            Text(text)
                .foregroundColor(palette.keyText.color)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { onReplace() }
            HStack(spacing: 16) {
                actionButton(loc("tips.replace"), action: onReplace)
                if let insert = onInsert {
                    actionButton(loc("tips.insert"), action: insert)
                }
                actionButton(copied ? loc("tips.copied") : loc("tips.copy"), action: onCopy)
                Spacer()
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.card.color))
    }

    private func actionButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(palette.accent.color)
        }
        .buttonStyle(.plain)
    }
}
