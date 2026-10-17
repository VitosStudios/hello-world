using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Tippli.Core.Keyboard;
using Tippli.Core.Models;

namespace Tippli.Windows.UI;

/// <summary>
/// Custom-drawn keyboard (SPEC §3/§7.4): hexagons and rounded rectangles from the Core geometry, theme colours,
/// labels and the long-press popup. Mouse, touch and pen pointers feed the Core <see cref="GestureInterpreter"/>.
/// The toolbar band is left transparent for hit testing so the WPF toolbar below it receives the clicks.
/// </summary>
internal sealed class KeyboardView : FrameworkElement
{
    private const int MousePointerId = -1;
    private const int TouchIdOffset = 1000;

    private readonly Typeface _typeface = new(UiKit.Font, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
    private readonly Typeface _typefaceBold = new(UiKit.Font, FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal);
    private readonly Dictionary<ThemeColor, SolidColorBrush> _brushes = new();
    private readonly Dictionary<KeyShape, Geometry> _shapes = new();
    private KeyboardGeometry? _geometry;
    private ThemePalette? _palette;

    public KeyboardView()
    {
        Focusable = false;
        SnapsToDevicePixels = true;
        Stylus.SetIsPressAndHoldEnabled(this, false);
        Stylus.SetIsFlicksEnabled(this, false);
        Stylus.SetIsTapFeedbackEnabled(this, false);
        Stylus.SetIsTouchFeedbackEnabled(this, false);
    }

    public KeyboardController? Controller { get; set; }
    public GestureInterpreter? Gestures { get; set; }
    public ThemeMetrics Metrics { get; set; } = new();
    public bool LongPressDigits { get; set; } = true;
    public bool EnterBusy { get; set; }

    public ThemePalette? Palette
    {
        get => _palette;
        set
        {
            _palette = value;
            _brushes.Clear();
            InvalidateVisual();
        }
    }

    public KeyboardGeometry? Geometry
    {
        get => _geometry;
        set
        {
            _geometry = value;
            _shapes.Clear();
            if (Gestures != null)
            {
                Gestures.CancelAll();
                Gestures.Geometry = value;
            }
            InvalidateVisual();
        }
    }

    private double ToolbarHeight => _geometry?.ToolbarHeight ?? Metrics.ToolbarHeight;

    private SolidColorBrush Brush(ThemeColor c)
    {
        if (!_brushes.TryGetValue(c, out var b))
        {
            b = ThemeManager.Brush(c);
            _brushes[c] = b;
        }
        return b;
    }

    // ---- rendering ---------------------------------------------------------------------------------------

    protected override void OnRender(DrawingContext dc)
    {
        var g = _geometry;
        var p = _palette;
        if (g == null || p == null) return;
        double top = ToolbarHeight;
        dc.DrawRectangle(Brush(p.Background), null, new Rect(0, top, Math.Max(0, ActualWidth), Math.Max(0, ActualHeight - top)));

        var pressed = Controller?.PressedKeys ?? (IReadOnlyCollection<KeyShape>)Array.Empty<KeyShape>();
        bool firstSpace = true;
        double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        foreach (var key in g.Keys)
        {
            bool isFirstSpace = false;
            if (key.Code == "$space")
            {
                isFirstSpace = firstSpace;
                firstSpace = false;
            }
            DrawKey(dc, g, p, key, pressed.Contains(key), isFirstSpace, dpi);
        }
        if (Controller?.Popup is { } popup) DrawPopup(dc, g, p, popup, dpi);
    }

    private Geometry ShapeOf(KeyShape key)
    {
        if (_shapes.TryGetValue(key, out var shape)) return shape;
        if (key.IsHex)
        {
            shape = RoundedPolygon(key.HexVertices(), Metrics.HexCornerRadius);
        }
        else
        {
            double gap = Metrics.KeyGap / 2;
            var rect = new Rect(key.X + gap, key.Y + gap, Math.Max(1, key.Width - 2 * gap), Math.Max(1, key.Height - 2 * gap));
            var r = new RectangleGeometry(rect, Metrics.KeyCornerRadius, Metrics.KeyCornerRadius);
            r.Freeze();
            shape = r;
        }
        _shapes[key] = shape;
        return shape;
    }

    /// <summary>Polygon with corners rounded by quadratic Béziers (hexagon corners, §3.4).</summary>
    private static Geometry RoundedPolygon((double X, double Y)[] pts, double radius)
    {
        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            int n = pts.Length;
            for (int i = 0; i < n; i++)
            {
                var p = new Point(pts[i].X, pts[i].Y);
                var prev = new Point(pts[(i + n - 1) % n].X, pts[(i + n - 1) % n].Y);
                var next = new Point(pts[(i + 1) % n].X, pts[(i + 1) % n].Y);
                Vector toPrev = prev - p, toNext = next - p;
                double r = Math.Min(radius, Math.Min(toPrev.Length, toNext.Length) / 2);
                toPrev.Normalize();
                toNext.Normalize();
                var a = p + toPrev * r;
                var b = p + toNext * r;
                if (i == 0) ctx.BeginFigure(a, isFilled: true, isClosed: true);
                else ctx.LineTo(a, isStroked: true, isSmoothJoin: true);
                ctx.QuadraticBezierTo(p, b, isStroked: true, isSmoothJoin: true);
            }
        }
        geometry.Freeze();
        return geometry;
    }

    private void DrawKey(DrawingContext dc, KeyboardGeometry g, ThemePalette p, KeyShape key, bool pressed, bool firstSpace, double dpi)
    {
        var shape = ShapeOf(key);
        ThemeColor fill;
        if (pressed) fill = p.KeyPressed;
        else if (key.Code == "$enter") fill = p.Accent;
        else if (key.Code == "$space") fill = p.SpaceKey;
        else if (key.IsSpecial) fill = p.SpecialKey;
        else fill = p.Key;

        // soft 1 DIP bottom shadow, no borders (§7.4)
        dc.PushTransform(new TranslateTransform(0, 1));
        dc.DrawGeometry(Brush(p.KeyShadow), null, shape);
        dc.Pop();
        dc.DrawGeometry(Brush(fill), null, shape);

        var controller = Controller;
        if (controller == null) return;
        string label = key.Code == "$enter" && EnterBusy ? "⋯" : controller.LabelFor(key, firstSpace);
        if (label.Length == 0) return;

        bool special = key.IsSpecial;
        ThemeColor color = key.Code == "$enter" && !pressed ? p.AccentText : key.Code == "$space" ? p.SecondaryText : p.KeyText;
        if (key.Code == "$shift" && controller.Shift != ShiftState.Off) color = p.Accent;

        double cellW = key.IsHex ? key.HexA * 2 : key.Width - Metrics.KeyGap;
        double cellH = key.IsHex ? key.HexH * 0.75 : key.Height - Metrics.KeyGap;
        double size = special ? Metrics.SpecialLabelSize : Metrics.KeyLabelSize;
        if (key.Code is "$shift" or "$back" or "$enter" or "$emoji") size = Metrics.SpecialLabelSize * 1.3;
        size = Math.Max(9, Math.Min(size, Math.Min(cellW, cellH) * 0.6));

        var ft = new FormattedText(label, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
            special ? _typefaceBold : _typeface, size, Brush(color), dpi);
        if (ft.Width > cellW * 0.9 && cellW > 4) ft.SetFontSize(size * cellW * 0.9 / ft.Width);
        double cx = key.CenterX, cy = key.CenterY;
        dc.DrawText(ft, new Point(cx - ft.Width / 2, cy - ft.Height / 2));

        if (key.Code == "$shift" && controller.Shift == ShiftState.CapsLock)
        {
            var pen = new Pen(Brush(p.Accent), 2);
            dc.DrawLine(pen, new Point(cx - ft.Width / 2, cy + ft.Height / 2), new Point(cx + ft.Width / 2, cy + ft.Height / 2));
        }

        if (key.IsHex && LongPressDigits && KeyAlternatives.DigitHint(key, controller.Page) is { } digit)
        {
            var hint = new FormattedText(digit, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, _typeface,
                Math.Max(8, size * 0.42), Brush(p.SecondaryText), dpi);
            dc.DrawText(hint, new Point(cx + key.HexA * 0.42 - hint.Width / 2, cy - key.HexH * 0.30 - hint.Height / 2));
        }
    }

    private void DrawPopup(DrawingContext dc, KeyboardGeometry g, ThemePalette p, PopupState popup, double dpi)
    {
        var l = popup.Layout;
        var bounds = new Rect(l.X, l.Y, l.Width, l.Height);
        var shadow = bounds;
        shadow.Offset(0, 2);
        dc.DrawRoundedRectangle(Brush(p.KeyShadow), null, shadow, 10, 10);
        dc.DrawRoundedRectangle(Brush(p.Popup), null, bounds, 10, 10);
        for (int i = 0; i < popup.Items.Count; i++)
        {
            var (x, y) = l.CellOrigin(i);
            var cell = new Rect(x + 2, y + 2, l.CellWidth - 4, l.CellHeight - 4);
            bool selected = i == popup.Selected;
            if (selected) dc.DrawRoundedRectangle(Brush(p.PopupSelected), null, cell, 8, 8);
            var ft = new FormattedText(popup.Items[i], CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, _typeface,
                Math.Min(Metrics.KeyLabelSize, l.CellHeight * 0.5), Brush(selected ? p.AccentText : p.KeyText), dpi);
            dc.DrawText(ft, new Point(cell.X + (cell.Width - ft.Width) / 2, cell.Y + (cell.Height - ft.Height) / 2));
        }
    }

    // ---- hit testing --------------------------------------------------------------------------------------

    protected override HitTestResult? HitTestCore(PointHitTestParameters hitTestParameters)
    {
        var pt = hitTestParameters.HitPoint;
        if (pt.Y < ToolbarHeight && Controller?.Popup == null) return null; // let the toolbar receive it
        if (pt.X < 0 || pt.Y < 0 || pt.X > ActualWidth || pt.Y > ActualHeight) return null;
        return new PointHitTestResult(this, pt);
    }

    // ---- mouse / pen (pen is promoted to mouse) -------------------------------------------------------------

    private static bool FromTouch(MouseEventArgs e) => e.StylusDevice?.TabletDevice?.Type == TabletDeviceType.Touch;

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        if (FromTouch(e) || Gestures == null) return;
        var p = e.GetPosition(this);
        if (p.Y < ToolbarHeight) return;
        CaptureMouse();
        Gestures.PointerDown(MousePointerId, p.X, p.Y);
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        if (FromTouch(e) || Gestures == null || !IsMouseCaptured) return;
        var p = e.GetPosition(this);
        Gestures.PointerMove(MousePointerId, p.X, p.Y);
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        if (FromTouch(e) || Gestures == null || !IsMouseCaptured) return;
        var p = e.GetPosition(this);
        Gestures.PointerUp(MousePointerId, p.X, p.Y);
        ReleaseMouseCapture();
        e.Handled = true;
    }

    protected override void OnLostMouseCapture(MouseEventArgs e)
    {
        Gestures?.PointerCancel(MousePointerId);
        base.OnLostMouseCapture(e);
    }

    // ---- touch (multi-touch, each finger is its own pointer) ------------------------------------------------

    protected override void OnTouchDown(TouchEventArgs e)
    {
        if (Gestures == null) return;
        var p = e.GetTouchPoint(this).Position;
        if (p.Y < ToolbarHeight && Controller?.Popup == null) return;
        CaptureTouch(e.TouchDevice);
        Gestures.PointerDown(TouchIdOffset + e.TouchDevice.Id, p.X, p.Y);
        e.Handled = true;
    }

    protected override void OnTouchMove(TouchEventArgs e)
    {
        if (Gestures == null || e.TouchDevice.Captured != this) return;
        var p = e.GetTouchPoint(this).Position;
        Gestures.PointerMove(TouchIdOffset + e.TouchDevice.Id, p.X, p.Y);
        e.Handled = true;
    }

    protected override void OnTouchUp(TouchEventArgs e)
    {
        if (Gestures == null) return;
        var p = e.GetTouchPoint(this).Position;
        Gestures.PointerUp(TouchIdOffset + e.TouchDevice.Id, p.X, p.Y);
        ReleaseTouchCapture(e.TouchDevice);
        e.Handled = true;
    }

    protected override void OnLostTouchCapture(TouchEventArgs e)
    {
        Gestures?.PointerCancel(TouchIdOffset + e.TouchDevice.Id);
        base.OnLostTouchCapture(e);
    }

    /// <summary>Geometry of the current keys (for tests/diagnostics).</summary>
    public IEnumerable<KeyShape> Keys => _geometry?.Keys ?? Enumerable.Empty<KeyShape>();
}
