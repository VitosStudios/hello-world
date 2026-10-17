using Tippli.Core.Keyboard;
using Tippli.Core.Shared;

namespace Tippli.Core.Tests;

public class GeometryTests
{
    private static readonly SharedData Data = SharedData.Default;

    public static KeyboardGeometry Build(string kb, bool hex, KeyboardPage page = KeyboardPage.Letters, double width = 400,
        string height = "medium", bool numberRow = false)
        => KeyboardGeometry.Build(new GeometryRequest(width, Data.Layouts, kb, hex, page, Data.Theme.Metrics.RowHeightFor(height),
            numberRow, Data.Theme.Metrics));

    public static IEnumerable<object[]> AllKeyboards()
    {
        foreach (var id in SharedData.Default.Layouts.Keyboards.Keys)
            foreach (var w in new[] { 320.0, 411.0, 800.0 })
                yield return new object[] { id, w };
    }

    [Theory]
    [MemberData(nameof(AllKeyboards))]
    public void EveryHexKeyCentreHitsItself(string kb, double width)
    {
        var g = Build(kb, hex: true, width: width);
        Assert.True(g.HexKeys);
        Assert.NotEmpty(g.LetterKeys);
        foreach (var key in g.LetterKeys)
        {
            Assert.True(key.IsHex);
            Assert.Same(key, g.HitTest(key.Cx, key.Cy));
        }
        foreach (var key in g.BottomKeys) Assert.Same(key, g.HitTest(key.CenterX, key.CenterY));
    }

    [Theory]
    [MemberData(nameof(AllKeyboards))]
    public void EveryNormalKeyCentreHitsItself(string kb, double width)
    {
        var g = Build(kb, hex: false, width: width, numberRow: true);
        Assert.False(g.HexKeys);
        Assert.Equal(10, g.NumberKeys.Count);
        foreach (var key in g.Keys) Assert.Same(key, g.HitTest(key.CenterX, key.CenterY));
    }

    [Fact]
    public void HexGeometryFollowsSpec()
    {
        var m = Data.Theme.Metrics;
        double W = 411, H = m.RowHeightFor("medium");
        var g = Build("qwertz", hex: true, width: W);
        double u = (W - 2 * m.SidePadding) / 11.5;
        Assert.Equal(u, g.U, 6);
        var q = g.LetterKeys.First(k => k.Code == "q");
        Assert.Equal(m.SidePadding + 0.5 * u, q.Cx, 6);
        Assert.Equal(m.ToolbarHeight + H / 6 + H / 2, q.Cy, 6);
        Assert.Equal(u / 2 - m.HexGap / 2, q.HexA, 6);
        Assert.Equal(4 * H / 3 - m.HexGap, q.HexH, 6);
        var a = g.LetterKeys.First(k => k.Code == "a");
        Assert.Equal(m.SidePadding + (0.5 + 0.5) * u, a.Cx, 6); // indent 0.5
        Assert.Equal(2, g.LetterKeys.Count(k => k.Code == "$space"));
        // total = toolbar + rows·H + H/3 + bottomPadding (3 letter rows + bottom row)
        Assert.Equal(m.ToolbarHeight + 4 * H + H / 3 + m.BottomPadding, g.Height, 6);
        Assert.Equal(KeyboardGeometry.TotalHeight(m, H, true, false), g.Height, 6);
    }

    [Fact]
    public void BottomRowFillsWidthAndUsesBottomPunctuation()
    {
        var m = Data.Theme.Metrics;
        var g = Build("pinyin", hex: false, width: 500);
        var first = g.BottomKeys[0];
        var last = g.BottomKeys[^1];
        Assert.Equal(m.SidePadding, first.X, 6);
        Assert.Equal(500 - m.SidePadding, last.X + last.Width, 6);
        Assert.Contains(g.BottomKeys, k => k.Code == "，");
        Assert.Contains(g.BottomKeys, k => k.Code == "。");
    }

    [Fact]
    public void NormalHeightWithNumberRow()
    {
        var m = Data.Theme.Metrics;
        double H = m.RowHeightFor("large");
        var g = Build("qwerty", hex: false, height: "large", numberRow: true);
        Assert.Equal(m.ToolbarHeight + 5 * H + m.BottomPadding, g.Height, 6);
        // the number row is only shown in normal mode
        Assert.Empty(Build("qwerty", hex: true, numberRow: true).NumberKeys);
    }

    [Fact]
    public void SymbolPagesAreAlwaysRectangular()
    {
        var g = Build("qwertz", hex: true, page: KeyboardPage.Symbols1);
        Assert.False(g.HexKeys);
        Assert.Contains(g.BottomKeys, k => k.Code == "$abc");
        Assert.Contains(g.LetterKeys, k => k.Code == "€");
        // same total height as the hex letter page (no jumping)
        Assert.Equal(Build("qwertz", hex: true).Height, g.Height, 6);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, false)]
    [InlineData(false, true)]
    public void SymbolPagesKeepTheLettersPageHeight(bool hex, bool numberRow)
    {
        var letters = Build("qwertz", hex, numberRow: numberRow);
        foreach (var page in new[] { KeyboardPage.Symbols1, KeyboardPage.Symbols2 })
        {
            var sym = Build("qwertz", hex, page, numberRow: numberRow);
            Assert.Equal(letters.Height, sym.Height, 6);
            Assert.Equal(letters.BottomRowTop, sym.BottomRowTop, 6); // bottom row does not move
            Assert.Empty(sym.NumberKeys);
            double expectedRow = (letters.BottomRowTop - letters.ToolbarHeight) / 3;
            Assert.Equal(expectedRow, sym.LetterRowHeight, 6);
            foreach (var key in sym.LetterKeys)
            {
                Assert.Equal(expectedRow, key.Height, 6);
                Assert.Same(key, sym.HitTest(key.CenterX, key.CenterY));
            }
        }
    }

    [Fact]
    public void ToolbarNeverHitsKeysAndEdgesClamp()
    {
        var g = Build("qwertz", hex: false);
        Assert.Null(g.HitTest(100, 5));
        Assert.Equal("$123", g.HitTest(-20, g.BottomRowTop + 10)!.Code);
        Assert.Equal("$enter", g.HitTest(10_000, g.BottomRowTop + 10)!.Code);
        Assert.Equal("q", g.HitTest(0, g.LetterTop + 1)!.Code);
        Assert.Equal("$back", g.HitTest(g.Width - 1, g.LetterTop + 2 * g.H + 5)!.Code);
    }

    [Fact]
    public void HexHitTestHasNoDeadZones()
    {
        var g = Build("qwertz", hex: true);
        for (double y = g.ToolbarHeight; y < g.BottomRowTop; y += 3)
            for (double x = 0; x < g.Width; x += 3)
                Assert.NotNull(g.HitTest(x, y));
    }

    [Fact]
    public void PopupLayoutIsCentredAndClamped()
    {
        var g = Build("qwertz", hex: false, width: 400);
        var a = g.LetterKeys.First(k => k.Code == "a");
        var popup = g.LayoutPopup(a, 13);
        Assert.InRange(popup.X, 0, g.Width - popup.Width);
        Assert.True(popup.CellWidth >= 30);
        Assert.Equal(0.9 * g.H, popup.CellHeight, 6);
        Assert.Equal(Math.Max(0, a.Top - popup.Rows * popup.CellHeight), popup.Y, 6); // may overlap the toolbar, clamped to 0
        int perRow = (int)Math.Floor(g.Width / Math.Max(g.U, 30));
        Assert.Equal(13 <= perRow ? 1 : 2, popup.Rows);

        var p = g.LayoutPopup(g.LetterKeys.First(k => k.Code == "p"), 3);
        Assert.Equal(1, p.Rows);
        Assert.Equal(0, p.IndexAt(-100, p.Y));
        Assert.Equal(2, p.IndexAt(10_000, p.Y));
    }
}
