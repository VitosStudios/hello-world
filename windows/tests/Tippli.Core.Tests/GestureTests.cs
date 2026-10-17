using Tippli.Core.Keyboard;
using Tippli.Core.Shared;
using Tippli.Core.Text;

namespace Tippli.Core.Tests;

/// <summary>A text field for the keyboard controller (caret at the end).</summary>
public sealed class FakeKeyboardHost : IKeyboardHost
{
    public string Text = "";
    public bool Known = true;
    public int CursorMoves;
    public int Enters;
    public int NoOps;
    public int WordFallbacks;
    public Func<bool>? Undo;
    public readonly List<string> Log = new();

    public string TextBeforeCursor(int maxChars) => Text.Length <= maxChars ? Text : Text[^maxChars..];
    public bool IsContextKnown => Known;
    public void CommitText(string text) { Text += text; Log.Add("type:" + text); }
    public void DeleteBackward() { Text = Graphemes.RemoveLast(Text); Log.Add("back"); }
    public void DeleteBeforeCursor(string text) { Assert.EndsWith(text, Text); Text = Text[..^text.Length]; Log.Add("del:" + text); }
    public void DeleteWordFallback() { WordFallbacks++; Log.Add("ctrlback"); }
    public void InsertRestored(string text) { Text += text; Log.Add("restore:" + text); }
    public void MoveCursor(int delta) { CursorMoves += delta; Log.Add("cursor:" + delta); }
    public void PerformEnter() { Enters++; Text += "\n"; }
    public bool TryUndoCorrection() => Undo?.Invoke() ?? false;
    public void Feedback(KeyboardFeedback kind) { if (kind == KeyboardFeedback.NoOp) NoOps++; }
}

public class GestureTests
{
    private readonly ManualScheduler _time = new();
    private readonly FakeKeyboardHost _host = new();
    private readonly KeyboardController _kb;
    private readonly GestureInterpreter _g;
    private KeyboardGeometry _geo;

    public GestureTests()
    {
        _kb = new KeyboardController(_host, _time, SharedData.Default.Accents)
        {
            Options = new KeyboardOptions { AutoCapitalize = false, LanguageShort = "DE-CH" },
        };
        _g = new GestureInterpreter(_kb, _time) { LongPressMs = 350, AlternativesProvider = _kb.AlternativesFor };
        _geo = GeometryTests.Build("qwertz", hex: true, width: 411);
        _g.Geometry = _geo;
    }

    private KeyShape Key(string code) => _geo.Keys.First(k => k.Code == code);

    private void Tap(string code, int id = 1)
    {
        var k = Key(code);
        _g.PointerDown(id, k.CenterX, k.CenterY);
        _time.Advance(30);
        _g.PointerUp(id, k.CenterX, k.CenterY);
    }

    private void Swipe(string code, double dx, double dy, int steps = 8, int id = 1)
    {
        var k = Key(code);
        _g.PointerDown(id, k.CenterX, k.CenterY);
        for (int i = 1; i <= steps; i++)
        {
            _time.Advance(10);
            _g.PointerMove(id, k.CenterX + dx * i / steps, k.CenterY + dy * i / steps);
        }
        _g.PointerUp(id, k.CenterX + dx, k.CenterY + dy);
    }

    [Fact]
    public void TapTypesCharacter()
    {
        Tap("h");
        Tap("a");
        Assert.Equal("ha", _host.Text);
        Assert.Empty(_kb.PressedKeys);
    }

    [Fact]
    public void SmallDriftStillTypesOriginalKey()
    {
        var k = Key("g");
        _g.PointerDown(1, k.Cx, k.Cy);
        _g.PointerMove(1, k.Cx + 8, k.Cy + 6);
        _g.PointerUp(1, k.Cx + 8, k.Cy + 6);
        Assert.Equal("g", _host.Text);
    }

    [Fact]
    public void SwipeUpTypesUppercaseWithoutTouchingShift()
    {
        Swipe("t", 0, -40);
        Assert.Equal("T", _host.Text);
        Assert.Equal(ShiftState.Off, _kb.Shift);
        Tap("t");
        Assert.Equal("Tt", _host.Text);
    }

    [Fact]
    public void ShiftOneShotDoubleTapAndLongPress()
    {
        Tap("$shift");
        Assert.Equal(ShiftState.OneShot, _kb.Shift);
        Tap("a");
        Tap("a");
        Assert.Equal("Aa", _host.Text);

        _time.Advance(GestureThresholds.DoubleTap + 100);
        Tap("$shift");
        Tap("$shift"); // within DOUBLE_TAP
        Assert.Equal(ShiftState.CapsLock, _kb.Shift);
        Tap("b");
        Tap("c");
        Assert.Equal("AaBC", _host.Text);
        _time.Advance(400);
        Tap("$shift");
        Assert.Equal(ShiftState.Off, _kb.Shift);

        var s = Key("$shift");
        _g.PointerDown(1, s.Cx, s.Cy);
        _time.Advance(400);
        _g.PointerUp(1, s.Cx, s.Cy);
        Assert.Equal(ShiftState.CapsLock, _kb.Shift);
    }

    [Fact]
    public void SwipeLeftDeletesWordsWithSteps()
    {
        _host.Text = "eins zwei drei vier ";
        Swipe("k", -45, 0);
        Assert.Equal("eins zwei drei ", _host.Text);

        _host.Text = "eins zwei drei vier ";
        Swipe("k", -125, -10, steps: 20); // 40 → 1 word, 80 → 2, 120 → 3
        Assert.Equal("eins ", _host.Text);
        Assert.Equal(4, _kb.RestoreCount);
    }

    [Fact]
    public void SwipeRightRestoresThenUndoesThenNoOp()
    {
        _host.Text = "hallo welt";
        Swipe("j", -50, 0);
        Assert.Equal("hallo ", _host.Text);
        Swipe("j", 50, 0);
        Assert.Equal("hallo welt", _host.Text);

        bool undone = false;
        _host.Undo = () => { undone = true; return true; };
        Swipe("j", 50, 0);
        Assert.True(undone);

        _host.Undo = () => false;
        Swipe("j", 50, 0);
        Assert.Equal(1, _host.NoOps);
    }

    [Fact]
    public void TypingClearsRestoreStack()
    {
        _host.Text = "hallo welt";
        Swipe("j", -50, 0);
        Tap("x");
        Swipe("j", 50, 0);
        Assert.Equal("hallo x", _host.Text);
    }

    [Fact]
    public void LongPressPopupSelectsAlternative()
    {
        var a = Key("a");
        _g.PointerDown(1, a.Cx, a.Cy);
        _time.Advance(360);
        var popup = _kb.Popup;
        Assert.NotNull(popup);
        Assert.Equal(SharedData.Default.Accents.Accents["a"], popup!.Items);
        Assert.Equal(0, popup.Selected);
        var (cx, cy) = popup.Layout.CellOrigin(3);
        _g.PointerMove(1, cx + popup.Layout.CellWidth / 2, cy + popup.Layout.CellHeight / 2);
        Assert.Equal(3, popup.Selected);
        _g.PointerUp(1, cx + popup.Layout.CellWidth / 2, cy + popup.Layout.CellHeight / 2);
        Assert.Equal(SharedData.Default.Accents.Accents["a"][3], _host.Text);
        Assert.Null(_kb.Popup);
    }

    [Fact]
    public void LongPressFirstRowShowsDigitFirstAndUppercaseWithShift()
    {
        Tap("$shift");
        var e = Key("e");
        _g.PointerDown(1, e.Cx, e.Cy);
        _time.Advance(360);
        Assert.Equal("3", _kb.Popup!.Items[0]);
        Assert.Equal("È", _kb.Popup.Items[1]);
        _g.PointerUp(1, e.Cx, e.Cy);
        Assert.Equal("3", _host.Text);
    }

    [Fact]
    public void PopupCancelledFarBelowKey()
    {
        var s = Key("s");
        _g.PointerDown(1, s.Cx, s.Cy);
        _time.Advance(360);
        Assert.NotNull(_kb.Popup);
        _g.PointerMove(1, s.Cx, s.Bottom + 2 * _geo.H);
        _g.PointerUp(1, s.Cx, s.Bottom + 2 * _geo.H);
        Assert.Equal("", _host.Text);
    }

    [Fact]
    public void LongPressWithoutAlternativesIsSlowTap()
    {
        var b = Key("b");
        _g.PointerDown(1, b.Cx, b.Cy);
        _time.Advance(800);
        Assert.Null(_kb.Popup);
        _g.PointerUp(1, b.Cx, b.Cy);
        Assert.Equal("b", _host.Text);
    }

    [Fact]
    public void BackspaceRepeatSwitchesToWordMode()
    {
        _host.Text = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod";
        var back = Key("$back");
        _g.PointerDown(1, back.CenterX, back.CenterY);
        Assert.Single(_host.Log, l => l == "back"); // immediately on down
        _time.Advance(GestureThresholds.RepeatDelay + GestureThresholds.RepeatInterval * 10 + 5);
        Assert.Equal(12, _host.Log.Count(l => l == "back")); // 1 + 11 repeats
        Assert.DoesNotContain(_host.Log, l => l.StartsWith("del:"));
        _time.Advance(GestureThresholds.RepeatInterval);
        _time.Advance(GestureThresholds.WordRepeatInterval * 2);
        Assert.True(_host.Log.Count(l => l.StartsWith("del:")) >= 2);
        _g.PointerUp(1, back.CenterX, back.CenterY);
        int count = _host.Log.Count;
        _time.Advance(2000);
        Assert.Equal(count, _host.Log.Count);
    }

    [Fact]
    public void FastTypingRuleCommitsOlderPointer()
    {
        var h = Key("h");
        var i = Key("i");
        _g.PointerDown(1, h.Cx, h.Cy);
        _g.PointerDown(2, i.Cx, i.Cy); // h committed now
        Assert.Equal("h", _host.Text);
        _g.PointerUp(2, i.Cx, i.Cy);
        _g.PointerUp(1, h.Cx, h.Cy); // ignored
        Assert.Equal("hi", _host.Text);
    }

    [Fact]
    public void SpaceDragMovesCursorAndNeverTypesSpace()
    {
        var space = _geo.LetterKeys.First(k => k.Code == "$space");
        _g.PointerDown(1, space.Cx, space.Cy);
        _g.PointerMove(1, space.Cx + 13, space.Cy);
        _g.PointerMove(1, space.Cx + 35, space.Cy);
        Assert.Equal(3, _host.CursorMoves);
        _g.PointerMove(1, space.Cx - 21, space.Cy);
        _g.PointerUp(1, space.Cx - 21, space.Cy);
        Assert.Equal(-2, _host.CursorMoves);
        Assert.Equal("", _host.Text);
    }

    [Fact]
    public void DoubleSpaceBecomesPeriodAndSymbolsReturnToLetters()
    {
        _kb.Options.DoubleSpacePeriod = true;
        _host.Text = "hallo";
        Tap("$space");
        Tap("$space");
        Assert.Equal("hallo. ", _host.Text);

        Tap("$123");
        Assert.Equal(KeyboardPanel.Symbols1, _kb.Panel);
        _geo = GeometryTests.Build("qwertz", hex: true, page: _kb.Page, width: 411);
        _g.Geometry = _geo;
        Tap("1");
        Tap("$space");
        Assert.Equal(KeyboardPanel.Letters, _kb.Panel);
        Assert.Equal("hallo. 1 ", _host.Text);
    }

    [Fact]
    public void AutoCapitalisationAfterSentenceEnd()
    {
        _kb.Options.AutoCapitalize = true;
        _kb.OnFieldStart();
        Assert.Equal(ShiftState.OneShotAuto, _kb.Shift);
        Tap("h");
        Assert.Equal(ShiftState.Off, _kb.Shift);
        Tap(".");
        Tap("$space");
        Assert.Equal(ShiftState.OneShotAuto, _kb.Shift);
        Tap("j");
        Assert.Equal("H. J", _host.Text);
    }

    [Fact]
    public void AutoCapNotOnUnknownDesktopContext()
    {
        _kb.Options.AutoCapitalize = true;
        _host.Known = false;
        _kb.OnFieldStart();
        Assert.Equal(ShiftState.Off, _kb.Shift);
    }

    [Fact]
    public void EmojiRecents()
    {
        _kb.TypeEmoji("😀");
        _kb.TypeEmoji("❤️");
        _kb.TypeEmoji("😀");
        Assert.Equal(new[] { "😀", "❤️" }, _kb.EmojiRecents);
        Assert.Equal("😀❤️😀", _host.Text);
        for (int i = 0; i < 40; i++) _kb.TypeEmoji(char.ConvertFromUtf32(0x1F600 + i));
        Assert.Equal(KeyboardController.EmojiRecentsMax, _kb.EmojiRecents.Count);
    }

    [Fact]
    public void Labels()
    {
        Assert.Equal("DE-CH", _kb.LabelFor(Key("$space")));
        Assert.Equal("", _kb.LabelFor(Key("$space"), firstSpace: false));
        Assert.Equal("123", _kb.LabelFor(Key("$123")));
        Tap("$shift");
        Assert.Equal("Ü", _kb.LabelFor(Key("ü")));
    }
}
