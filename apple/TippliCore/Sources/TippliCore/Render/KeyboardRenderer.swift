#if canImport(CoreGraphics) && canImport(CoreText)
import Foundation
import CoreGraphics
import CoreText

extension RGBA {
    public var cgColor: CGColor {
        CGColor(srgbRed: CGFloat(r), green: CGFloat(g), blue: CGFloat(b), alpha: CGFloat(a))
    }
}

/// Draws the keyboard (toolbar, keys, popups) with CoreGraphics + CoreText.
/// Shared by iOS (`UIView.draw`) and macOS (`NSView.draw`, `isFlipped = true`):
/// the context must use a top-left origin with y pointing down.
public enum KeyboardRenderer {

    // MARK: Fonts and text

    private static var fontCache: [String: CTFont] = [:]

    static func font(size: CGFloat, bold: Bool = false) -> CTFont {
        let key = (bold ? "b" : "r") + String(Double(size))
        if let f = fontCache[key] { return f }
        let type: CTFontUIFontType = bold ? .emphasizedSystem : .system
        let f = CTFontCreateUIFontForLanguage(type, size, nil) ?? CTFontCreateWithName("Helvetica" as CFString, size, nil)
        fontCache[key] = f
        return f
    }

    static func attributes(font: CTFont, color: CGColor) -> [NSAttributedString.Key: Any] {
        [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): color
        ]
    }

    static func line(_ text: String, font: CTFont, color: CGColor, maxWidth: CGFloat? = nil) -> CTLine {
        let attrs = attributes(font: font, color: color)
        let str = NSAttributedString(string: text, attributes: attrs)
        let l = CTLineCreateWithAttributedString(str as CFAttributedString)
        if let maxW = maxWidth, maxW > 4, lineWidth(l) > maxW {
            let token = CTLineCreateWithAttributedString(NSAttributedString(string: "…", attributes: attrs) as CFAttributedString)
            if let t = CTLineCreateTruncatedLine(l, Double(maxW), .end, token) {
                return t
            }
        }
        return l
    }

    static func lineWidth(_ l: CTLine) -> CGFloat {
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        var leading: CGFloat = 0
        return CGFloat(CTLineGetTypographicBounds(l, &ascent, &descent, &leading))
    }

    /// Draws a line centred at `center` (y-down context).
    static func draw(_ l: CTLine, centeredAt center: CGPoint, in ctx: CGContext) {
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        var leading: CGFloat = 0
        let w = CGFloat(CTLineGetTypographicBounds(l, &ascent, &descent, &leading))
        let x = center.x - w / 2
        let baseline = center.y + (ascent - descent) / 2
        draw(l, atBaseline: CGPoint(x: x, y: baseline), in: ctx)
    }

    static func draw(_ l: CTLine, atBaseline origin: CGPoint, in ctx: CGContext) {
        ctx.saveGState()
        ctx.textMatrix = .identity
        ctx.translateBy(x: origin.x, y: origin.y)
        ctx.scaleBy(x: 1, y: -1)
        ctx.textPosition = .zero
        CTLineDraw(l, ctx)
        ctx.restoreGState()
    }

    static func drawText(_ text: String, size: CGFloat, color: CGColor, center: CGPoint, maxWidth: CGFloat? = nil,
                         bold: Bool = false, in ctx: CGContext) {
        guard !text.isEmpty else { return }
        draw(line(text, font: font(size: size, bold: bold), color: color, maxWidth: maxWidth), centeredAt: center, in: ctx)
    }

    // MARK: Paths

    static func roundedRect(_ rect: CGRect, radius: CGFloat) -> CGPath {
        let r = max(0, min(radius, rect.width / 2, rect.height / 2))
        guard rect.width > 0, rect.height > 0 else { return CGPath(rect: .zero, transform: nil) }
        return CGPath(roundedRect: rect, cornerWidth: r, cornerHeight: r, transform: nil)
    }

    static func roundedPolygon(_ points: [CGPoint], radius: CGFloat) -> CGPath {
        let path = CGMutablePath()
        guard points.count >= 3 else { return path }
        let n = points.count
        let last = points[n - 1]
        let first = points[0]
        path.move(to: CGPoint(x: (last.x + first.x) / 2, y: (last.y + first.y) / 2))
        for i in 0..<n {
            let p1 = points[i]
            let p2 = points[(i + 1) % n]
            if radius > 0.1 {
                path.addArc(tangent1End: p1, tangent2End: p2, radius: radius)
            } else {
                path.addLine(to: p1)
            }
        }
        path.closeSubpath()
        return path
    }

    static func fill(_ path: CGPath, color: CGColor, in ctx: CGContext) {
        ctx.addPath(path)
        ctx.setFillColor(color)
        ctx.fillPath()
    }

    static func fillWithShadow(_ path: CGPath, color: CGColor, shadow: CGColor, in ctx: CGContext) {
        ctx.saveGState()
        ctx.translateBy(x: 0, y: 1)
        fill(path, color: shadow, in: ctx)
        ctx.restoreGState()
        fill(path, color: color, in: ctx)
    }

    // MARK: Entry point

    public static func draw(_ state: KeyboardRenderState, palette: ThemePalette, in ctx: CGContext) {
        let bounds = CGRect(origin: .zero, size: state.size)
        ctx.setFillColor(palette.background.cgColor)
        ctx.fill(bounds)
        let toolbarRect = CGRect(x: 0, y: 0, width: state.size.width, height: state.metrics.toolbarHeight)
        ctx.setFillColor(palette.toolbar.cgColor)
        ctx.fill(toolbarRect)

        if state.showKeys {
            for key in state.keys {
                drawKey(key, state: state, palette: palette, in: ctx)
            }
        }
        if let toast = state.toast {
            drawToast(toast, rect: toolbarRect, metrics: state.metrics, palette: palette, in: ctx)
        } else {
            for item in state.toolbar {
                drawToolbarItem(item, pressed: state.toolbarPressed == item.kind, state: state, palette: palette, in: ctx)
            }
        }
        if let popup = state.popup {
            drawPopup(popup, metrics: state.metrics, palette: palette, in: ctx)
        }
    }

    // MARK: Keys

    static func drawKey(_ key: RenderKey, state: KeyboardRenderState, palette: ThemePalette, in ctx: CGContext) {
        let m = state.metrics
        let item = key.item
        let path: CGPath
        let rect: CGRect
        if item.isHex {
            path = roundedPolygon(item.hexVertices, radius: m.hexCornerRadius)
            rect = item.frame
        } else {
            rect = item.visualFrame(keyGap: m.keyGap)
            path = roundedRect(rect, radius: m.keyCornerRadius)
        }

        let fillColor: RGBA
        var textColor = palette.keyText
        switch key.kind {
        case .character:
            fillColor = key.pressed ? palette.keyPressed : palette.key
        case .space:
            fillColor = key.pressed ? palette.keyPressed : palette.spaceKey
            textColor = palette.secondaryText
        case .enter:
            fillColor = key.pressed ? palette.accent.withAlpha(0.8) : palette.accent
            textColor = palette.accentText
        case .shift:
            if key.pressed {
                fillColor = palette.keyPressed
            } else {
                fillColor = key.shift.isActive ? palette.key : palette.specialKey
            }
        case .special:
            fillColor = key.pressed ? palette.keyPressed : palette.specialKey
        }
        fillWithShadow(path, color: fillColor.cgColor, shadow: palette.keyShadow.cgColor, in: ctx)

        let center = item.isHex ? item.center : CGPoint(x: rect.midX, y: rect.midY)
        let maxLabelWidth = max(10, rect.width - 6)

        if key.kind == .shift {
            drawShiftArrow(center: center, size: min(rect.width, rect.height) * 0.42, state: key.shift,
                           color: palette.keyText.cgColor, in: ctx)
        } else if key.showsSpinner {
            drawSpinner(center: center, radius: min(rect.width, rect.height) * 0.2, phase: state.animationPhase,
                        color: textColor.cgColor, in: ctx)
        } else if !key.label.isEmpty {
            let size: CGFloat
            if key.kind == .character {
                size = m.keyLabelSize
            } else if key.smallLabel {
                size = m.specialLabelSize
            } else {
                size = m.specialLabelSize + 5
            }
            drawText(key.label, size: size, color: textColor.cgColor, center: center, maxWidth: maxLabelWidth, in: ctx)
        }

        if let hint = key.hint {
            let hintCenter: CGPoint
            if item.isHex {
                hintCenter = CGPoint(x: item.center.x + item.hexHalfWidth * 0.5, y: item.center.y - item.hexHeight * 0.26)
            } else {
                hintCenter = CGPoint(x: rect.maxX - 7, y: rect.minY + 8)
            }
            drawText(hint, size: 9, color: palette.secondaryText.cgColor, center: hintCenter, in: ctx)
        }
    }

    static func drawShiftArrow(center: CGPoint, size: CGFloat, state: ShiftState, color: CGColor, in ctx: CGContext) {
        let w = size
        let h = size
        let top = center.y - h / 2
        let mid = top + h * 0.52
        let bottom = center.y + h / 2 - (state == .capsLock ? h * 0.22 : 0)
        let stem = w * 0.22
        let pts = [
            CGPoint(x: center.x, y: top),
            CGPoint(x: center.x + w / 2, y: mid),
            CGPoint(x: center.x + stem, y: mid),
            CGPoint(x: center.x + stem, y: bottom),
            CGPoint(x: center.x - stem, y: bottom),
            CGPoint(x: center.x - stem, y: mid),
            CGPoint(x: center.x - w / 2, y: mid)
        ]
        let path = roundedPolygon(pts, radius: 1.2)
        if state.isActive {
            fill(path, color: color, in: ctx)
        } else {
            ctx.addPath(path)
            ctx.setStrokeColor(color)
            ctx.setLineWidth(1.5)
            ctx.setLineJoin(.round)
            ctx.strokePath()
        }
        if state == .capsLock {
            let bar = CGRect(x: center.x - stem - 1, y: center.y + h / 2 - h * 0.12, width: stem * 2 + 2, height: max(1.5, h * 0.1))
            fill(roundedRect(bar, radius: 0.8), color: color, in: ctx)
        }
    }

    static func drawSpinner(center: CGPoint, radius: CGFloat, phase: CGFloat, color: CGColor, in ctx: CGContext) {
        let start = phase * 2 * CGFloat.pi
        ctx.saveGState()
        ctx.setStrokeColor(color)
        ctx.setLineWidth(2)
        ctx.setLineCap(.round)
        ctx.addArc(center: center, radius: max(radius, 4), startAngle: start, endAngle: start + 1.5 * CGFloat.pi, clockwise: false)
        ctx.strokePath()
        ctx.restoreGState()
    }

    // MARK: Toolbar

    static func indicatorColor(_ indicator: AIIndicator, phase: CGFloat, palette: ThemePalette) -> RGBA {
        switch indicator {
        case .ready: return palette.accent
        case .working:
            let pulse = 0.35 + 0.65 * abs(sin(Double(phase) * Double.pi))
            return palette.accent.withAlpha(pulse)
        case .error: return palette.danger
        case .off, .locked: return palette.secondaryText
        case .noKey, .fullAccessNeeded: return palette.warning
        }
    }

    static func drawToolbarItem(_ item: ToolbarItem, pressed: Bool, state: KeyboardRenderState, palette: ThemePalette,
                                in ctx: CGContext) {
        let f = item.frame
        var textColor = palette.keyText
        switch item.style {
        case .chip:
            fill(roundedRect(f, radius: f.height / 2), color: (pressed ? palette.keyPressed : palette.key).cgColor, in: ctx)
        case .warningChip:
            fill(roundedRect(f, radius: f.height / 2), color: palette.warning.cgColor, in: ctx)
            textColor = palette.accentText
        case .dangerChip:
            fill(roundedRect(f, radius: f.height / 2), color: palette.danger.cgColor, in: ctx)
            textColor = palette.accentText
        case .plain:
            if pressed {
                fill(roundedRect(f, radius: f.height / 2), color: palette.keyPressed.cgColor, in: ctx)
            }
        }
        let fontSize: CGFloat = 13
        let iconSize: CGFloat = 15

        if item.kind == .aiStatus {
            var x = f.minX + 9
            if item.icon.isEmpty {
                let dot = CGRect(x: x, y: f.midY - 4.5, width: 9, height: 9)
                ctx.setFillColor(indicatorColor(state.indicator, phase: state.animationPhase, palette: palette).cgColor)
                ctx.fillEllipse(in: dot)
                x += 13
            } else {
                let l = line(item.icon, font: font(size: iconSize - 2), color: textColor.cgColor)
                let w = lineWidth(l)
                draw(l, centeredAt: CGPoint(x: x + w / 2, y: f.midY), in: ctx)
                x += w + 4
            }
            if !item.title.isEmpty {
                let color = state.indicator == .off ? palette.secondaryText : textColor
                let l = line(item.title, font: font(size: fontSize, bold: true), color: color.cgColor, maxWidth: f.maxX - x - 4)
                var ascent: CGFloat = 0
                var descent: CGFloat = 0
                var leading: CGFloat = 0
                _ = CTLineGetTypographicBounds(l, &ascent, &descent, &leading)
                draw(l, atBaseline: CGPoint(x: x, y: f.midY + (ascent - descent) / 2), in: ctx)
            }
            return
        }

        let iconLine: CTLine? = item.icon.isEmpty ? nil : line(item.icon, font: font(size: iconSize), color: textColor.cgColor)
        let titleLine: CTLine? = item.title.isEmpty ? nil : line(item.title, font: font(size: fontSize, bold: item.style != .plain),
                                                                 color: textColor.cgColor, maxWidth: f.width - 8)
        let iw = iconLine.map { lineWidth($0) } ?? 0
        let tw = titleLine.map { lineWidth($0) } ?? 0
        let gap: CGFloat = (iconLine != nil && titleLine != nil) ? 4 : 0
        var x = f.midX - (iw + gap + tw) / 2
        if let il = iconLine {
            draw(il, centeredAt: CGPoint(x: x + iw / 2, y: f.midY), in: ctx)
            x += iw + gap
        }
        if let tl = titleLine {
            draw(tl, centeredAt: CGPoint(x: x + tw / 2, y: f.midY), in: ctx)
        }
    }

    static func drawToast(_ toast: Toast, rect: CGRect, metrics: KeyboardMetrics, palette: ThemePalette, in ctx: CGContext) {
        let r = rect.insetBy(dx: max(metrics.sidePadding, 4), dy: 4)
        fill(roundedRect(r, radius: r.height / 2), color: palette.card.cgColor, in: ctx)
        var right = r.maxX - 12
        if let action = toast.actionTitle {
            let l = line(action, font: font(size: 13, bold: true), color: palette.accent.cgColor)
            let w = lineWidth(l)
            right -= w
            draw(l, centeredAt: CGPoint(x: right + w / 2, y: r.midY), in: ctx)
            right -= 10
        }
        let maxW = right - (r.minX + 12)
        let l = line(toast.message.replacingOccurrences(of: "\n", with: " "), font: font(size: 13), color: palette.keyText.cgColor, maxWidth: maxW)
        var ascent: CGFloat = 0
        var descent: CGFloat = 0
        var leading: CGFloat = 0
        _ = CTLineGetTypographicBounds(l, &ascent, &descent, &leading)
        draw(l, atBaseline: CGPoint(x: r.minX + 12, y: r.midY + (ascent - descent) / 2), in: ctx)
    }

    // MARK: Popup

    static func drawPopup(_ popup: RenderPopup, metrics: KeyboardMetrics, palette: ThemePalette, in ctx: CGContext) {
        let frame = popup.frame.insetBy(dx: -3, dy: -3)
        let path = roundedRect(frame, radius: metrics.keyCornerRadius + 3)
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 2), blur: 8, color: palette.keyShadow.cgColor)
        fill(path, color: palette.popup.cgColor, in: ctx)
        ctx.restoreGState()
        let size = max(14, metrics.keyLabelSize - 2)
        for (i, cell) in popup.cells.enumerated() where i < popup.labels.count {
            let selected = popup.selected == i
            if selected {
                fill(roundedRect(cell.insetBy(dx: 1.5, dy: 1.5), radius: metrics.keyCornerRadius), color: palette.popupSelected.cgColor, in: ctx)
            }
            let color = selected ? palette.accentText : palette.keyText
            drawText(popup.labels[i], size: size, color: color.cgColor, center: CGPoint(x: cell.midX, y: cell.midY),
                     maxWidth: cell.width - 2, in: ctx)
        }
    }
}
#endif
