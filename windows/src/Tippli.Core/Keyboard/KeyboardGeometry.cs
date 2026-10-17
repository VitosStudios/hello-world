using Tippli.Core.Models;

namespace Tippli.Core.Keyboard;

public enum KeyboardPage
{
    Letters,
    Symbols1,
    Symbols2,
}

public enum KeyRowKind
{
    Number,
    Letter,
    Bottom,
}

/// <summary>One laid-out key (SPEC §3.3/§3.4).</summary>
public sealed class KeyShape
{
    /// <summary>Key code: the character (bottom punctuation already mapped) or a special key like <c>$space</c>.</summary>
    public required string Code { get; init; }
    public required KeyRowKind RowKind { get; init; }
    /// <summary>Index of the letter row (0 = first letter row); -1 for number and bottom rows.</summary>
    public required int Row { get; init; }
    /// <summary>Index of the key within its row.</summary>
    public required int Index { get; init; }

    /// <summary>Row cell (before the gap inset). For hex keys the bounding box of the hexagon.</summary>
    public required double X { get; init; }
    public required double Y { get; init; }
    public required double Width { get; init; }
    public required double Height { get; init; }

    public bool IsHex { get; init; }
    public double Cx { get; init; }
    public double Cy { get; init; }
    /// <summary>Hex half width <c>a</c> (§3.4).</summary>
    public double HexA { get; init; }
    /// <summary>Hex full height <c>h</c> (§3.4).</summary>
    public double HexH { get; init; }

    public bool IsSpecial => Code.Length > 1 && Code[0] == '$';
    public bool IsCharacter => !IsSpecial;
    public bool IsLetter => IsCharacter && Code.Length > 0 && char.IsLetter(Code, 0);
    public double Top => IsHex ? Cy - HexH / 2 : Y;
    public double Bottom => IsHex ? Cy + HexH / 2 : Y + Height;
    public double CenterX => IsHex ? Cx : X + Width / 2;
    public double CenterY => IsHex ? Cy : Y + Height / 2;

    /// <summary>The six hexagon vertices (pointy top, clockwise from the top).</summary>
    public (double X, double Y)[] HexVertices()
    {
        double a = HexA, h = HexH;
        return new[]
        {
            (Cx, Cy - h / 2), (Cx + a, Cy - h / 4), (Cx + a, Cy + h / 4),
            (Cx, Cy + h / 2), (Cx - a, Cy + h / 4), (Cx - a, Cy - h / 4),
        };
    }

    public override string ToString() => $"{Code}@{RowKind}{Row}:{Index}";
}

/// <summary>Inputs for <see cref="KeyboardGeometry.Build"/>.</summary>
public sealed record GeometryRequest(
    double Width,
    LayoutsFile Layouts,
    string KeyboardId,
    bool HexMode,
    KeyboardPage Page,
    double RowHeight,
    bool NumberRow,
    ThemeMetrics Metrics);

/// <summary>Popup placement for long-press alternatives (SPEC §4.4).</summary>
public sealed record PopupLayout(double X, double Y, double CellWidth, double CellHeight, int Columns, int Rows, int Count)
{
    public double Width => Columns * CellWidth;
    public double Height => Rows * CellHeight;

    public (double X, double Y) CellOrigin(int index)
    {
        int row = Rows > 1 ? index / Columns : 0;
        int col = Rows > 1 ? index % Columns : index;
        return (X + col * CellWidth, Y + row * CellHeight);
    }

    /// <summary>Cell under the pointer: x clamped to first/last column, y picks the row in two-row mode.</summary>
    public int IndexAt(double x, double y)
    {
        int col = (int)Math.Floor((x - X) / CellWidth);
        col = Math.Clamp(col, 0, Columns - 1);
        int row = 0;
        if (Rows > 1) row = y < Y + CellHeight ? 0 : 1;
        int index = row * Columns + col;
        return Math.Clamp(index, 0, Count - 1);
    }
}

/// <summary>Laid-out keyboard: key rectangles/hexagons and hit testing (SPEC §3).</summary>
public sealed class KeyboardGeometry
{
    private KeyboardGeometry() { }

    public double Width { get; private init; }
    public double Height { get; private init; }
    /// <summary>Unit width u of the letter rows.</summary>
    public double U { get; private init; }
    /// <summary>Row height H (theme metrics; bottom row, number row, letter rows of the letters page).</summary>
    public double H { get; private init; }
    /// <summary>Height of the letter/symbol rows (H on the letters page; symbol rows share the letters-page height).</summary>
    public double LetterRowHeight { get; private init; }
    public double ToolbarHeight { get; private init; }
    public double LetterTop { get; private init; }
    public double LetterBottom { get; private init; }
    public double NumberRowTop { get; private init; } = double.NaN;
    public double BottomRowTop { get; private init; }
    public int LetterRowCount { get; private init; }
    /// <summary>True when the letter rows use hexagons (hex mode on the letter page).</summary>
    public bool HexKeys { get; private init; }
    public bool HexMode { get; private init; }
    public KeyboardPage Page { get; private init; }
    public ThemeMetrics Metrics { get; private init; } = new();

    public IReadOnlyList<KeyShape> Keys { get; private init; } = Array.Empty<KeyShape>();
    public IReadOnlyList<KeyShape> LetterKeys { get; private init; } = Array.Empty<KeyShape>();
    public IReadOnlyList<KeyShape> NumberKeys { get; private init; } = Array.Empty<KeyShape>();
    public IReadOnlyList<KeyShape> BottomKeys { get; private init; } = Array.Empty<KeyShape>();

    /// <summary>Total height for a configuration (§3.1) without building the keys.</summary>
    public static double TotalHeight(ThemeMetrics m, double rowHeight, bool hexMode, bool numberRow, int letterRows = 3)
    {
        int rows = letterRows + 1 + (!hexMode && numberRow ? 1 : 0);
        return m.ToolbarHeight + rows * rowHeight + (hexMode ? rowHeight / 3 : 0) + m.BottomPadding;
    }

    public static KeyboardGeometry Build(GeometryRequest req)
    {
        var m = req.Metrics;
        double sp = m.SidePadding;
        double H = req.RowHeight;
        double W = Math.Max(req.Width, 2 * sp + 1);
        double inner = W - 2 * sp;

        req.Layouts.Keyboards.TryGetValue(req.KeyboardId, out var kb);
        kb ??= req.Layouts.Keyboards.Values.FirstOrDefault() ?? new KeyboardDef();

        LayoutDef letters;
        string bottomId;
        bool hexKeys;
        if (req.Page == KeyboardPage.Letters)
        {
            letters = req.HexMode ? kb.Hex : kb.Normal;
            bottomId = req.HexMode ? "hex" : "normal";
            hexKeys = req.HexMode;
        }
        else
        {
            var pageId = req.Page == KeyboardPage.Symbols1 ? "symbols1" : "symbols2";
            letters = req.Layouts.SymbolPages.TryGetValue(pageId, out var lp) ? lp : new LayoutDef();
            bottomId = "symbols";
            hexKeys = false;
        }

        var punct = kb.BottomPunctuation ?? new Dictionary<string, string>();
        var keys = new List<KeyShape>();
        var numberKeys = new List<KeyShape>();
        var letterKeys = new List<KeyShape>();
        var bottomKeys = new List<KeyShape>();

        bool numberRow = !req.HexMode && req.NumberRow && req.Layouts.NumberRow.Count > 0;
        int letterPageRows = (req.HexMode ? kb.Hex : kb.Normal).Rows.Count;
        double lettersPageHeight = TotalHeight(m, H, req.HexMode, numberRow, letterPageRows);

        double y = m.ToolbarHeight;
        double numberTop = double.NaN;
        double rowH = H;
        if (req.Page == KeyboardPage.Letters)
        {
            // Hex mode: H/6 above the first and below the last letter row (§3.1).
            if (req.HexMode) y += H / 6;
            if (numberRow)
            {
                numberTop = y;
                numberKeys.AddRange(ScaledRow(req.Layouts.NumberRow, KeyRowKind.Number, sp, inner, y, H, punct: null));
                y += H;
            }
        }
        else
        {
            // §3.7: symbol pages keep the height of the letters page (hex padding / number row included); the bottom
            // row stays where it is and the symbol rows share the remaining height evenly.
            double bottomTopOfLetters = lettersPageHeight - m.BottomPadding - H;
            rowH = (bottomTopOfLetters - y) / Math.Max(1, letters.Rows.Count);
        }

        double letterTop = y;
        double U = letters.Width > 0 ? letters.Width : 10;
        double u = inner / U;
        for (int r = 0; r < letters.Rows.Count; r++)
        {
            var row = letters.Rows[r];
            double rowTop = letterTop + r * rowH;
            double acc = 0;
            for (int i = 0; i < row.Keys.Count; i++)
            {
                var k = row.Keys[i];
                double w = k.W <= 0 ? 1 : k.W;
                if (hexKeys)
                {
                    double cx = sp + (row.Indent + acc + w / 2) * u;
                    double cy = rowTop + H / 2;
                    double a = u * w / 2 - m.HexGap / 2;
                    double h = 4 * H / 3 - m.HexGap;
                    letterKeys.Add(new KeyShape
                    {
                        Code = k.K, RowKind = KeyRowKind.Letter, Row = r, Index = i,
                        X = cx - u * w / 2, Y = cy - 2 * H / 3, Width = u * w, Height = 4 * H / 3,
                        IsHex = true, Cx = cx, Cy = cy, HexA = a, HexH = h,
                    });
                }
                else
                {
                    letterKeys.Add(new KeyShape
                    {
                        Code = k.K, RowKind = KeyRowKind.Letter, Row = r, Index = i,
                        X = sp + (row.Indent + acc) * u, Y = rowTop, Width = w * u, Height = rowH,
                    });
                }
                acc += w;
            }
        }
        y = letterTop + letters.Rows.Count * rowH;
        double letterBottom = y;
        if (req.HexMode && req.Page == KeyboardPage.Letters) y += H / 6;

        double bottomTop = y;
        if (req.Layouts.BottomRows.TryGetValue(bottomId, out var bottomRow))
            bottomKeys.AddRange(ScaledRow(bottomRow, KeyRowKind.Bottom, sp, inner, y, H, punct));
        y += H;

        keys.AddRange(numberKeys);
        keys.AddRange(letterKeys);
        keys.AddRange(bottomKeys);

        return new KeyboardGeometry
        {
            Width = W,
            Height = y + m.BottomPadding,
            U = u,
            H = H,
            LetterRowHeight = rowH,
            ToolbarHeight = m.ToolbarHeight,
            LetterTop = letterTop,
            LetterBottom = letterBottom,
            NumberRowTop = numberTop,
            BottomRowTop = bottomTop,
            LetterRowCount = letters.Rows.Count,
            HexKeys = hexKeys,
            HexMode = req.HexMode,
            Page = req.Page,
            Metrics = m,
            Keys = keys,
            LetterKeys = letterKeys,
            NumberKeys = numberKeys,
            BottomKeys = bottomKeys,
        };
    }

    /// <summary>Bottom and number rows ignore U: their weights fill W − 2·sp exactly (§3.2).</summary>
    private static IEnumerable<KeyShape> ScaledRow(List<KeyDef> row, KeyRowKind kind, double sp, double inner, double top, double H,
        Dictionary<string, string>? punct)
    {
        double total = row.Sum(k => k.W <= 0 ? 1 : k.W);
        if (total <= 0) yield break;
        double acc = 0;
        for (int i = 0; i < row.Count; i++)
        {
            var k = row[i];
            double w = k.W <= 0 ? 1 : k.W;
            var code = k.K;
            if (punct != null && punct.TryGetValue(code, out var mapped)) code = mapped;
            yield return new KeyShape
            {
                Code = code, RowKind = kind, Row = -1, Index = i,
                X = sp + acc / total * inner, Y = top, Width = w / total * inner, Height = H,
            };
            acc += w;
        }
    }

    /// <summary>Hit test (SPEC §3.5). Returns null in the toolbar.</summary>
    public KeyShape? HitTest(double x, double y)
    {
        if (y < ToolbarHeight || Keys.Count == 0) return null;
        if (y >= BottomRowTop && BottomKeys.Count > 0) return ByX(BottomKeys, x);
        if (NumberKeys.Count > 0 && y >= NumberRowTop && y < LetterTop) return ByX(NumberKeys, x);
        if (LetterKeys.Count == 0) return BottomKeys.Count > 0 ? ByX(BottomKeys, x) : null;

        if (HexKeys)
        {
            double k = Math.Sqrt(3) / 2 * U / H;
            KeyShape? best = null;
            double bestD = double.MaxValue;
            foreach (var key in LetterKeys)
            {
                double dx = x - key.Cx, dy = (y - key.Cy) * k;
                double d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = key; }
            }
            return best;
        }

        int row = (int)Math.Floor((y - LetterTop) / LetterRowHeight);
        row = Math.Clamp(row, 0, Math.Max(0, LetterRowCount - 1));
        var rowKeys = LetterKeys.Where(kk => kk.Row == row).ToList();
        if (rowKeys.Count == 0) return null;
        foreach (var key in rowKeys)
            if (x >= key.X && x < key.X + key.Width) return key;
        return rowKeys.OrderBy(kk => Math.Abs(kk.CenterX - x)).First();
    }

    private static KeyShape ByX(IReadOnlyList<KeyShape> row, double x)
    {
        foreach (var key in row)
            if (x >= key.X && x < key.X + key.Width) return key;
        return x < row[0].X ? row[0] : row[^1];
    }

    /// <summary>Places the long-press popup above <paramref name="key"/> (SPEC §4.4).</summary>
    public PopupLayout LayoutPopup(KeyShape key, int count)
    {
        count = Math.Max(1, count);
        double cw = Math.Max(U, 30);
        double ch = 0.9 * H;
        int perRow = Math.Max(1, (int)Math.Floor(Width / cw));
        int cols, rows;
        if (count <= perRow)
        {
            cols = count;
            rows = 1;
        }
        else
        {
            rows = 2;
            cols = (count + 1) / 2;
            if (cols * cw > Width) cw = Width / cols;
        }
        double w = cols * cw;
        double x = Math.Clamp(key.CenterX - w / 2, 0, Math.Max(0, Width - w));
        double yTop = Math.Max(0, key.Top - rows * ch);
        return new PopupLayout(x, yTop, cw, ch, cols, rows, count);
    }
}
