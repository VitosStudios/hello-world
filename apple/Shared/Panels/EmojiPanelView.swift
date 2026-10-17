import SwiftUI
import TippliCore

/// Emoji panel (SPEC §4.9): lazy grid, category bar with recents, ABC and ⌫.
struct EmojiPanelView: View {
    let palette: ThemePalette
    let recents: [String]
    let onEmoji: (String) -> Void
    let onLetters: () -> Void
    let onDelete: () -> Void

    private let categories: [EmojiCategory] = SharedResources.shared.emoji.categories
    @State private var selected = "recent"

    private static let recentId = "recent"
    private let columns = [GridItem(.adaptive(minimum: 42, maximum: 56), spacing: 2)]

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 2, pinnedViews: []) {
                        if !recents.isEmpty {
                            Section(header: header("⏱").id(EmojiPanelView.recentId)) {
                                ForEach(Array(recents.enumerated()), id: \.offset) { _, e in
                                    cell(e)
                                }
                            }
                        }
                        ForEach(categories) { cat in
                            Section(header: header(cat.name).id(cat.id)) {
                                ForEach(Array(cat.emoji.enumerated()), id: \.offset) { _, e in
                                    cell(e)
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 4)
                }
                .onChange(of: selected) { id in
                    proxy.scrollTo(id, anchor: .top)
                }
            }
            Rectangle().fill(palette.divider.color).frame(height: 1)
            categoryBar
        }
        .background(palette.panel.color)
    }

    private func header(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundColor(palette.secondaryText.color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
            .padding(.top, 8)
    }

    private func cell(_ emoji: String) -> some View {
        Button(action: { onEmoji(emoji) }) {
            Text(emoji)
                .font(.system(size: 30))
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var categoryBar: some View {
        HStack(spacing: 0) {
            Button(action: onLetters) {
                Text(loc("keys.letters"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(palette.keyText.color)
                    .frame(width: 52, height: 38)
                    .background(RoundedRectangle(cornerRadius: 7).fill(palette.specialKey.color))
            }
            .buttonStyle(.plain)
            .padding(.leading, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    categoryButton(id: EmojiPanelView.recentId, icon: "⏱")
                    ForEach(categories) { cat in
                        categoryButton(id: cat.id, icon: cat.icon)
                    }
                }
                .padding(.horizontal, 4)
            }

            Button(action: onDelete) {
                Image(systemName: "delete.left")
                    .font(.system(size: 18))
                    .foregroundColor(palette.keyText.color)
                    .frame(width: 52, height: 38)
                    .background(RoundedRectangle(cornerRadius: 7).fill(palette.specialKey.color))
            }
            .buttonStyle(.plain)
            .padding(.trailing, 4)
        }
        .frame(height: 46)
    }

    private func categoryButton(id: String, icon: String) -> some View {
        Button(action: { selected = id }) {
            Text(icon)
                .font(.system(size: 20))
                .frame(width: 36, height: 36)
                .background(Circle().fill(selected == id ? palette.keyPressed.color : Color.clear))
        }
        .buttonStyle(.plain)
    }
}
