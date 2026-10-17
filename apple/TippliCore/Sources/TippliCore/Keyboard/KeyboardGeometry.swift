import Foundation

public enum KeyboardPage: String, Equatable {
    case letters
    case symbols1
    case symbols2
}

public enum KeyboardMode: String, Equatable {
    case hex
    case normal
}

public enum KeyArea: Equatable {
    case number
    case letters
    case bottom
}

/// One key of a computed layout (SPEC §3).
public struct KeyItem: Equatable {
    public let index: Int
    /// Raw key id from layouts.json (`"q"`, `"$shift"`, `"$space"`, …).
    public let key: String
    public let area: KeyArea
    /// Row within the area (letters: 0…2).
    public let row: Int
    public let column: Int
    /// Normal keys: the full row cell (drawn inset by keyGap/2).
    /// Hex keys: the bounding box of the hexagon.
    public let frame: CGRect
    public let isHex: Bool
    public let center: CGPoint
    /// Hex half width `a` and full height `h` (SPEC §3.4).
    public let hexHalfWidth: CGFloat
    public let hexHeight: CGFloat

    public var isSpecial: Bool { KeySpec.isSpecialKey(key) }
    /// A key that types its own character(s).
    public var isCharacter: Bool { !isSpecial }

    /// Hexagon vertices (pointy top), clockwise starting at the top.
    public var hexVertices: [CGPoint] {
        let cx = center.x
        let cy = center.y
        let a = hexHalfWidth
        let h = hexHeight
        return [
            CGPoint(x: cx, y: cy - h / 2),
            CGPoint(x: cx + a, y: cy - h / 4),
            CGPoint(x: cx + a, y: cy + h / 4),
            CGPoint(x: cx, y: cy + h / 2),
            CGPoint(x: cx - a, y: cy + h / 4),
            CGPoint(x: cx - a, y: cy - h / 4)
        ]
    }

    /// The visible shape's bounding box (normal keys inset by keyGap/2).
    public func visualFrame(keyGap: CGFloat) -> CGRect {
        if isHex { return frame }
        return frame.insetBy(dx: keyGap / 2, dy: keyGap / 2)
    }
}

/// Metrics from theme.json for the chosen key height.
public struct KeyboardMetrics: Equatable {
    public var toolbarHeight: CGFloat
    public var rowHeight: CGFloat
    public var keyGap: CGFloat
    public var hexGap: CGFloat
    public var keyCornerRadius: CGFloat
    public var hexCornerRadius: CGFloat
    public var keyLabelSize: CGFloat
    public var specialLabelSize: CGFloat
    public var sidePadding: CGFloat
    public var bottomPadding: CGFloat

    public init(theme: ThemeMetrics, keyHeight: String) {
        toolbarHeight = CGFloat(theme.toolbarHeight)
        rowHeight = CGFloat(theme.rowHeight[keyHeight] ?? theme.rowHeight["medium"] ?? 52)
        keyGap = CGFloat(theme.keyGap)
        hexGap = CGFloat(theme.hexGap)
        keyCornerRadius = CGFloat(theme.keyCornerRadius)
        hexCornerRadius = CGFloat(theme.hexCornerRadius)
        keyLabelSize = CGFloat(theme.keyLabelSize)
        specialLabelSize = CGFloat(theme.specialLabelSize)
        sidePadding = CGFloat(theme.sidePadding)
        bottomPadding = CGFloat(theme.bottomPadding)
    }
}

public struct KeyboardLayoutConfig: Equatable {
    public var width: CGFloat
    public var page: KeyboardPage
    public var mode: KeyboardMode
    public var keyboardId: String
    public var numberRow: Bool
    public var needsNextKeyboard: Bool

    public init(width: CGFloat, page: KeyboardPage = .letters, mode: KeyboardMode = .hex, keyboardId: String = "qwertz",
                numberRow: Bool = false, needsNextKeyboard: Bool = false) {
        self.width = width
        self.page = page
        self.mode = mode
        self.keyboardId = keyboardId
        self.numberRow = numberRow
        self.needsNextKeyboard = needsNextKeyboard
    }
}

/// A fully computed keyboard (key frames + hit testing), SPEC §3.
public struct KeyboardLayout {
    public let config: KeyboardLayoutConfig
    public let metrics: KeyboardMetrics
    public let keys: [KeyItem]
    public let totalHeight: CGFloat
    /// Unit width `u` of the letter / symbol board.
    public let unit: CGFloat
    /// Top of the first board row.
    public let rowsTop: CGFloat
    public let boardRowCount: Int
    public let numberRowTop: CGFloat?
    public let bottomRowTop: CGFloat
    /// True when the board (letter area) uses hexagons.
    public let isHexBoard: Bool
    /// Bottom-row punctuation replacements of the active keyboard (pinyin).
    public let bottomPunctuation: [String: String]

    public var toolbarHeight: CGFloat { metrics.toolbarHeight }
    public var rowHeight: CGFloat { metrics.rowHeight }
    public var width: CGFloat { config.width }

    /// Hit-test metric factor `k = (√3/2)·u/H` (SPEC §3.5).
    public var hexK: CGFloat {
        (CGFloat(3).squareRoot() / 2) * unit / metrics.rowHeight
    }

    public static func build(config: KeyboardLayoutConfig, metrics: KeyboardMetrics, layouts: LayoutsFile) -> KeyboardLayout {
        let W = max(config.width, 1)
        let sp = metrics.sidePadding
        let H = metrics.rowHeight
        let kb = layouts.keyboard(config.keyboardId)
        let hexLetters = config.mode == .hex
        let letterBoard = hexLetters ? kb.hex : kb.normal
        let numberRowOnLetters = !hexLetters && config.numberRow

        // Height is defined by the letters page of the current mode, so the
        // keyboard does not jump when switching to the symbol pages.
        let bandRows = CGFloat(letterBoard.rows.count + (numberRowOnLetters ? 1 : 0))
        let bandHeight = bandRows * H + (hexLetters ? H / 3 : 0)
        let toolbar = metrics.toolbarHeight
        let bottomTop = toolbar + bandHeight
        let total = bottomTop + H + metrics.bottomPadding

        let board: BoardSpec
        let isHexBoard: Bool
        let bottomKeys: [KeySpec]
        switch config.page {
        case .letters:
            board = letterBoard
            isHexBoard = hexLetters
            bottomKeys = layouts.bottomRows[hexLetters ? "hex" : "normal"] ?? []
        case .symbols1:
            board = layouts.symbolPages["symbols1"] ?? kb.normal
            isHexBoard = false
            bottomKeys = layouts.bottomRows["symbols"] ?? []
        case .symbols2:
            board = layouts.symbolPages["symbols2"] ?? kb.normal
            isHexBoard = false
            bottomKeys = layouts.bottomRows["symbols"] ?? []
        }

        var keys: [KeyItem] = []
        var y = toolbar
        var numberTop: CGFloat? = nil

        // Number row (normal mode, letters page, setting numberRow).
        if config.page == .letters && numberRowOnLetters {
            numberTop = y
            let specs = layouts.numberRow.map { KeySpec(key: $0, width: 1) }
            appendFilledRow(specs, area: .number, row: 0, y: y, width: W, sp: sp, H: H, into: &keys)
            y += H
        }

        // Board rows, vertically centred in the remaining band.
        let remaining = bottomTop - y
        let boardHeight = CGFloat(board.rows.count) * H
        let rowsTop = y + (remaining - boardHeight) / 2
        let u = (W - 2 * sp) / CGFloat(max(board.width, 0.5))

        for (r, row) in board.rows.enumerated() {
            let rowTop = rowsTop + CGFloat(r) * H
            if isHexBoard {
                let a = u / 2 - metrics.hexGap / 2
                let h = 4 * H / 3 - metrics.hexGap
                for (i, spec) in row.keys.enumerated() {
                    let cx = sp + (CGFloat(row.indent) + CGFloat(i) + 0.5) * u
                    let cy = rowTop + H / 2
                    let frame = CGRect(x: cx - a, y: cy - h / 2, width: 2 * a, height: h)
                    keys.append(KeyItem(index: keys.count, key: spec.key, area: .letters, row: r, column: i,
                                        frame: frame, isHex: true, center: CGPoint(x: cx, y: cy),
                                        hexHalfWidth: a, hexHeight: h))
                }
            } else {
                var x = sp + CGFloat(row.indent) * u
                for (i, spec) in row.keys.enumerated() {
                    let w = CGFloat(spec.width) * u
                    let frame = CGRect(x: x, y: rowTop, width: w, height: H)
                    keys.append(KeyItem(index: keys.count, key: spec.key, area: .letters, row: r, column: i,
                                        frame: frame, isHex: false, center: CGPoint(x: frame.midX, y: frame.midY),
                                        hexHalfWidth: 0, hexHeight: 0))
                    x += w
                }
            }
        }

        // Bottom row: weights scaled to fill the width; optional $nextkb.
        var bottom = bottomKeys
        if config.needsNextKeyboard {
            if let idx = bottom.firstIndex(where: { $0.key == "$123" || $0.key == "$abc" }) {
                bottom.insert(KeySpec(key: "$nextkb", width: 1), at: idx + 1)
            } else {
                bottom.insert(KeySpec(key: "$nextkb", width: 1), at: 0)
            }
            if let s = bottom.firstIndex(where: { $0.key == "$space" }) {
                bottom[s] = KeySpec(key: "$space", width: max(1, bottom[s].width - 1))
            }
        }
        appendFilledRow(bottom, area: .bottom, row: 0, y: bottomTop, width: W, sp: sp, H: H, into: &keys)

        return KeyboardLayout(config: config, metrics: metrics, keys: keys, totalHeight: total, unit: u,
                              rowsTop: rowsTop, boardRowCount: board.rows.count, numberRowTop: numberTop,
                              bottomRowTop: bottomTop, isHexBoard: isHexBoard,
                              bottomPunctuation: kb.bottomPunctuation ?? [:])
    }

    private static func appendFilledRow(_ specs: [KeySpec], area: KeyArea, row: Int, y: CGFloat, width W: CGFloat,
                                        sp: CGFloat, H: CGFloat, into keys: inout [KeyItem]) {
        let total = specs.reduce(0.0) { $0 + $1.width }
        guard total > 0 else { return }
        let unit = (W - 2 * sp) / CGFloat(total)
        var x = sp
        for (i, spec) in specs.enumerated() {
            let w = CGFloat(spec.width) * unit
            let frame = CGRect(x: x, y: y, width: w, height: H)
            keys.append(KeyItem(index: keys.count, key: spec.key, area: area, row: row, column: i,
                                frame: frame, isHex: false, center: CGPoint(x: frame.midX, y: frame.midY),
                                hexHalfWidth: 0, hexHeight: 0))
            x += w
        }
    }

    // MARK: Hit testing (SPEC §3.5)

    public func hitTest(_ p: CGPoint) -> KeyItem? {
        if p.y < metrics.toolbarHeight { return nil }
        if p.y >= bottomRowTop {
            return nearestByX(keys.filter { $0.area == .bottom }, x: p.x)
        }
        if let nTop = numberRowTop, p.y < nTop + metrics.rowHeight {
            return nearestByX(keys.filter { $0.area == .number }, x: p.x)
        }
        let board = keys.filter { $0.area == .letters }
        if board.isEmpty { return nil }
        if isHexBoard {
            let k = hexK
            var best: KeyItem? = nil
            var bestD = CGFloat.greatestFiniteMagnitude
            for key in board {
                let dx = p.x - key.center.x
                let dy = (p.y - key.center.y) * k
                let d = dx * dx + dy * dy
                if d < bestD {
                    bestD = d
                    best = key
                }
            }
            return best
        }
        var row = Int(((p.y - rowsTop) / metrics.rowHeight).rounded(.down))
        row = min(max(row, 0), boardRowCount - 1)
        let rowKeys = board.filter { $0.row == row }
        return nearestByX(rowKeys, x: p.x)
    }

    /// The key whose x-range contains x, else the nearest one by x.
    private func nearestByX(_ candidates: [KeyItem], x: CGFloat) -> KeyItem? {
        if let hit = candidates.first(where: { x >= $0.frame.minX && x < $0.frame.maxX }) {
            return hit
        }
        var best: KeyItem? = nil
        var bestD = CGFloat.greatestFiniteMagnitude
        for key in candidates {
            let d = x < key.frame.minX ? key.frame.minX - x : x - key.frame.maxX
            if d < bestD {
                bestD = d
                best = key
            }
        }
        return best
    }

    public func key(at index: Int) -> KeyItem? {
        guard index >= 0 && index < keys.count else { return nil }
        return keys[index]
    }
}

/// Long-press popup geometry (SPEC §4.4).
public struct PopupLayout: Equatable {
    /// Cell rectangles in alternative order.
    public let cells: [CGRect]
    /// Indices per row; row 0 is the bottom row (closest to the finger).
    public let rows: [[Int]]
    public let frame: CGRect
    public let cellSize: CGSize

    public static func make(anchor: CGRect, count: Int, unit: CGFloat, rowHeight H: CGFloat,
                            width W: CGFloat, sidePadding sp: CGFloat, below: Bool = false) -> PopupLayout {
        let cellW = max(unit, 30)
        let cellH = 0.9 * H
        let usable = max(W - 2 * sp, cellW)
        let maxPerRow = max(1, Int((usable / cellW).rounded(.down)))
        let rowCount = max(1, Int((Double(count) / Double(maxPerRow)).rounded(.up)))
        let perRow = max(1, Int((Double(count) / Double(rowCount)).rounded(.up)))
        let popupW = CGFloat(min(perRow, max(count, 1))) * cellW
        let popupH = CGFloat(rowCount) * cellH
        var x = anchor.midX - popupW / 2
        x = min(max(x, sp), max(sp, W - sp - popupW))
        var top: CGFloat
        if below {
            top = anchor.maxY
        } else {
            top = anchor.minY - popupH
            if top < 0 { top = 0 }
        }
        let frame = CGRect(x: x, y: top, width: popupW, height: popupH)
        var cells: [CGRect] = []
        var rows: [[Int]] = []
        for r in 0..<rowCount {
            let start = r * perRow
            let end = min(count, start + perRow)
            if start >= end { break }
            rows.append(Array(start..<end))
        }
        for i in 0..<count {
            let r = i / perRow
            let c = i % perRow
            // Row 0 is the bottom row (above popup: nearest the key; below: top row).
            let rowTop: CGFloat
            if below {
                rowTop = frame.minY + CGFloat(r) * cellH
            } else {
                rowTop = frame.maxY - CGFloat(r + 1) * cellH
            }
            cells.append(CGRect(x: frame.minX + CGFloat(c) * cellW, y: rowTop, width: cellW, height: cellH))
        }
        return PopupLayout(cells: cells, rows: rows, frame: frame, cellSize: CGSize(width: cellW, height: cellH))
    }

    /// The alternative under a pointer: x picks the column (clamped), y picks
    /// the row in multi-row mode (clamped).
    public func selection(at p: CGPoint) -> Int? {
        guard !rows.isEmpty else { return nil }
        var rowIndex = 0
        if rows.count > 1 {
            var bestD = CGFloat.greatestFiniteMagnitude
            for (r, indices) in rows.enumerated() {
                guard let first = indices.first else { continue }
                let cell = cells[first]
                let d: CGFloat
                if p.y < cell.minY { d = cell.minY - p.y } else if p.y > cell.maxY { d = p.y - cell.maxY } else { d = 0 }
                if d < bestD {
                    bestD = d
                    rowIndex = r
                }
            }
        }
        let indices = rows[rowIndex]
        guard let first = indices.first else { return nil }
        let rowMinX = cells[first].minX
        var col = Int(((p.x - rowMinX) / cellSize.width).rounded(.down))
        col = min(max(col, 0), indices.count - 1)
        return indices[col]
    }
}
