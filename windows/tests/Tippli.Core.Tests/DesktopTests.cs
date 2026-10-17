using System.Text.Json.Nodes;
using Tippli.Core.Desktop;
using Tippli.Core.Engine;
using Tippli.Core.Settings;
using Tippli.Core.Shared;

namespace Tippli.Core.Tests;

public sealed class FakeInjector : IKeystrokeInjector
{
    public readonly List<IReadOnlyList<InjectedStroke>> Batches = new();
    public bool CanInjectNow { get; set; } = true;
    public void Inject(IReadOnlyList<InjectedStroke> strokes) => Batches.Add(strokes.ToList());
}

public class DesktopBufferTests
{
    [Fact]
    public void AppendBackspaceReset()
    {
        var b = new DesktopBuffer();
        b.Append("hallo");
        b.Append(" 😀");
        Assert.Equal("hallo 😀", b.Text);
        b.Backspace(); // a whole surrogate pair / grapheme
        Assert.Equal("hallo ", b.Text);
        b.Append("é");
        b.Backspace();
        Assert.Equal("hallo ", b.Text);
        b.Reset(true);
        Assert.Equal("", b.Text);
        Assert.True(b.StartsAtLineStart);
        b.Backspace(); // empty: nothing is known any more
        Assert.Equal("", b.Text);
        Assert.False(b.StartsAtLineStart);
    }

    [Fact]
    public void BufferIsLimited()
    {
        var b = new DesktopBuffer();
        b.Reset(true);
        b.Append(new string('a', 1990));
        b.Append("0123456789abc");
        Assert.Equal(DesktopBuffer.MaxChars, b.Text.Length);
        Assert.EndsWith("0123456789abc", b.Text);
        Assert.False(b.StartsAtLineStart);
    }

    [Fact]
    public void ReliableTextSkipsUnknownFirstWord()
    {
        var b = new DesktopBuffer();
        b.Append("lo wie gehts");
        Assert.Equal("wie gehts", b.ReliableText);
        b.Reset(false);
        b.Append("hallo");
        Assert.Equal("", b.ReliableText);
        b.MarkStartKnown();
        Assert.Equal("hallo", b.ReliableText);
        b.Reset(false);
        b.Append(" und dann");
        Assert.Equal("und dann", b.ReliableText);
    }

    [Fact]
    public void RemoveSuffixMismatchResets()
    {
        var b = new DesktopBuffer();
        b.Reset(true);
        b.Append("abc def");
        b.RemoveSuffix("def");
        Assert.Equal("abc ", b.Text);
        b.RemoveSuffix("xyz");
        Assert.Equal("", b.Text);
        Assert.False(b.StartsAtLineStart);
    }
}

public class DeadKeyTests
{
    [Theory]
    [InlineData("^", "a", "â")]
    [InlineData("^", "E", "Ê")]
    [InlineData("´", "e", "é")]
    [InlineData("`", "A", "À")]
    [InlineData("¨", "u", "ü")]
    [InlineData("¨", "O", "Ö")]
    [InlineData("~", "n", "ñ")]
    [InlineData("¸", "c", "ç")]
    [InlineData("ˇ", "s", "š")]
    [InlineData("°", "a", "å")]
    [InlineData("'", "e", "é")]
    [InlineData("\"", "u", "ü")]
    [InlineData("^", "q", "^q")]
    [InlineData("´", " ", "´")]
    [InlineData("^", "^", "^^")]
    [InlineData("¨", "1", "¨1")]
    [InlineData("", "a", "a")]
    public void Compose(string dead, string next, string expected)
        => Assert.Equal(expected, DeadKeyComposer.Compose(dead, next));

    [Fact]
    public void KnownDeadChars()
    {
        Assert.True(DeadKeyComposer.IsKnownDeadChar("^"));
        Assert.False(DeadKeyComposer.IsKnownDeadChar("x"));
    }
}

public class DesktopEngineTests
{
    private readonly ManualScheduler _time = new();
    private readonly FakeInjector _injector = new();
    private readonly FakeAi _ai = new();
    private readonly DesktopBuffer _buffer = new();
    private readonly DesktopTextHost _host;
    private readonly CorrectionEngine _engine;
    private readonly DesktopSession _session;

    public DesktopEngineTests()
    {
        Sync.NoContext();
        _host = new DesktopTextHost(_buffer, _injector);
        _engine = new CorrectionEngine(_host, _ai, _time, _time) { Options = new EngineOptions { Trigger = Triggers.Live } };
        _session = new DesktopSession(_engine, _buffer);
        _session.SetActive(true);
        _session.OnReset(lineStart: true);
    }

    private void Keys(string s)
    {
        foreach (var r in s.EnumerateRunes()) _session.OnKey(DesktopKeyKind.Character, r.ToString());
    }

    [Fact]
    public void EnterFlushDecision()
    {
        Assert.True(EnterFlushPolicy.ShouldHold(true, false, false, false, 1500, true));
        Assert.False(EnterFlushPolicy.ShouldHold(false, false, false, false, 1500, true)); // excluded / off
        Assert.False(EnterFlushPolicy.ShouldHold(true, true, false, false, 1500, true)); // Ctrl+Enter
        Assert.False(EnterFlushPolicy.ShouldHold(true, false, true, false, 1500, true));
        Assert.False(EnterFlushPolicy.ShouldHold(true, false, false, true, 1500, true));
        Assert.False(EnterFlushPolicy.ShouldHold(true, false, false, false, 0, true)); // waitOnSendMs = 0
        Assert.False(EnterFlushPolicy.ShouldHold(true, false, false, false, 1500, false)); // nothing pending
    }

    [Fact]
    public void CorrectionIsInjectedAsBackspacesPerGraphemeAndText()
    {
        Keys("ich bin hir ");
        _time.RunPending();
        Assert.Equal("ich bin hir ", _ai.Last.Segment); // live: the last completed word triggered
        Keys("un");
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Equal("Ich bin hier un", _buffer.Text);
        var batch = Assert.Single(_injector.Batches); // one atomic SendInput
        Assert.Equal(new[] { InjectedStroke.Backspaces(14), InjectedStroke.Type("Ich bin hier un") }, batch);
    }

    [Fact]
    public void EmojiCountsAsOneBackspace()
    {
        Keys("hallo 😀 wi gehts ");
        _time.RunPending();
        _ai.Last.Reply.SetResult("hallo 😀 wie geht's ");
        var batch = Assert.Single(_injector.Batches);
        Assert.Equal(InjectedStroke.Backspaces(" gehts ".Length), batch[0]);
        Assert.Equal(InjectedStroke.Type("e geht's "), batch[1]);
        Assert.Equal("hallo 😀 wie geht's ", _buffer.Text);
    }

    [Fact]
    public void ModifiersHeldBlockEdits()
    {
        Keys("ich bin hir ");
        _time.RunPending();
        _injector.CanInjectNow = false;
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Empty(_injector.Batches);
        Assert.Equal("ich bin hir ", _buffer.Text);
    }

    [Fact]
    public void ResetKeysInvalidateInFlightRequest()
    {
        Keys("ich bin hir ");
        _time.RunPending();
        _session.OnKey(DesktopKeyKind.Reset); // arrow key / click / shortcut
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Empty(_injector.Batches);
        Assert.Equal("", _buffer.Text);
    }

    [Fact]
    public void DeadKeysAreComposedIntoTheBuffer()
    {
        Keys("caf");
        _session.OnKey(DesktopKeyKind.DeadKey, "´");
        Assert.Equal("caf", _buffer.Text);
        Keys("e");
        Assert.Equal("café", _buffer.Text);
        _session.OnKey(DesktopKeyKind.DeadKey, "^");
        _session.OnKey(DesktopKeyKind.Backspace);
        Assert.Equal("café", _buffer.Text); // backspace cancelled the dead key
        _session.OnKey(DesktopKeyKind.Backspace);
        Assert.Equal("caf", _buffer.Text);
    }

    [Fact]
    public void InactiveSessionBuffersNothing()
    {
        _session.SetActive(false); // excluded app / password field
        Keys("geheim123 ");
        _time.Advance(2000);
        Assert.Equal("", _buffer.Text);
        Assert.Empty(_ai.Calls);
    }

    [Fact]
    public void UnknownStartNeverTouchesFirstWord()
    {
        _session.OnKey(DesktopKeyKind.Reset); // e.g. mouse click into the middle of a word
        Keys("lo wie gehs ");
        _time.RunPending();
        Assert.Equal("wie gehs ", _ai.Last.Segment);
        Assert.Equal("…", _ai.Last.Context); // tells the model there is text before
        _ai.Last.Reply.SetResult("wie geht's ");
        Assert.Equal("lo wie geht's ", _buffer.Text);
        Assert.Equal(InjectedStroke.Backspaces(2), _injector.Batches[0][0]); // "s " only
    }

    [Fact]
    public void EnterFlushThenBufferStartsAtLineStart()
    {
        _engine.Options.Trigger = Triggers.Manual;
        Keys("hallo wie gehts");
        Assert.True(EnterFlushPolicy.ShouldHold(_session.Active, false, false, false, _engine.Options.WaitOnSendMs, _engine.HasPendingCorrection()));
        var flush = _engine.FlushAsync();
        _ai.Last.Reply.SetResult("Hallo, wie geht's?");
        Assert.True(flush.Wait(5000));
        Assert.Equal("Hallo, wie geht's?", _buffer.Text);
        _session.OnKey(DesktopKeyKind.Enter);
        Assert.Equal("", _buffer.Text);
        Assert.True(_buffer.StartsAtLineStart);
        Assert.False(_engine.HasPendingCorrection());
    }
}

public class SettingsTests
{
    private static readonly SharedData Data = SharedData.Default;

    private static string TempFile()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tippli-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        return Path.Combine(dir, "settings.json");
    }

    [Fact]
    public void DefaultsMatchSharedFile()
    {
        var s = new SettingsStore(Data);
        foreach (var (key, value) in Data.SettingsDefaults)
            Assert.True(JsonNode.DeepEquals(value, s.GetNode(key)), key);
        Assert.Equal("live", s.Trigger);
        Assert.Equal(700, s.PauseMs);
        Assert.Contains("WindowsTerminal", s.DesktopExcludedApps);
    }

    [Fact]
    public void SaveWritesExactKeysAndLoadRoundTrips()
    {
        var path = TempFile();
        var s = new SettingsStore(Data, path);
        s.Trigger = "sentence";
        s.PauseMs = 900;
        s.Languages = new List<string> { "en", "de-CH" };
        s.ActiveLanguage = "en";
        s.SetModel("anthropic", "claude-sonnet-5-5");
        s.SetKeyboardId("en", "qwertz");
        s.EmojiRecents = new List<string> { "😀" };

        var json = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
        foreach (var (key, _) in Data.SettingsDefaults) Assert.True(json.ContainsKey(key), key);
        Assert.Equal("claude-sonnet-5-5", (string?)json["model.anthropic"]);
        Assert.Equal("qwertz", (string?)json["keyboard.en"]);
        Assert.Equal(Data.SettingsDefaults.Count + 2, json.Count);

        var t = new SettingsStore(Data, path);
        t.Load();
        Assert.True(t.FileExisted);
        Assert.Equal("sentence", t.Trigger);
        Assert.Equal(900, t.PauseMs);
        Assert.Equal(new[] { "en", "de-CH" }, t.Languages);
        Assert.Equal("en", t.ActiveLanguage);
        Assert.Equal("claude-sonnet-5-5", t.GetModel("anthropic"));
        Assert.Equal("qwertz", t.GetKeyboardId("en"));
        Assert.Equal("qwerty", t.GetKeyboardId("it"));
        Assert.Equal("😀", Assert.Single(t.EmojiRecents));
    }

    [Fact]
    public void InvalidValuesFallBackAndRangesClamp()
    {
        var path = TempFile();
        File.WriteAllText(path, """
            { "trigger": "sometimes", "pauseMs": 99999, "longPressMs": 10, "aiEnabled": "yes",
              "languages": ["xx", "fr"], "activeLanguage": "de-CH", "provider": "nope", "unknownKey": 1 }
            """);
        var s = new SettingsStore(Data, path);
        s.Load();
        Assert.Equal("live", s.Trigger);
        Assert.Equal(2000, s.PauseMs);
        Assert.Equal(200, s.LongPressMs);
        Assert.True(s.AiEnabled);
        Assert.Equal(new[] { "fr" }, s.Languages);
        Assert.Equal("fr", s.ActiveLanguage);
        Assert.Equal("anthropic", s.Provider);
        Assert.DoesNotContain("unknownKey", s.Keys);

        s.WaitOnSendMs = -5;
        Assert.Equal(0, s.WaitOnSendMs);
        s.Trigger = "never";
        Assert.Equal("live", s.Trigger);
    }

    [Fact]
    public void BrokenFileKeepsDefaults()
    {
        var path = TempFile();
        File.WriteAllText(path, "{ this is not json");
        var s = new SettingsStore(Data, path);
        s.Load();
        Assert.Equal("live", s.Trigger);
        Assert.True(File.Exists(path + ".broken"));
    }

    [Fact]
    public void ChangedEventAndDerivedDefaults()
    {
        var s = new SettingsStore(Data);
        var changed = new List<string>();
        s.Changed += changed.Add;
        s.KeyboardMode = "normal";
        s.KeyboardMode = "normal"; // unchanged → no event
        Assert.Equal(new[] { "keyboardMode" }, changed);
        Assert.Equal(Data.FindProvider("openai")!.DefaultModel, s.GetModel("openai"));
        s.SetModel("openai", Data.FindProvider("openai")!.DefaultModel);
        Assert.DoesNotContain("model.openai", s.Keys);
        Assert.Equal(Data.FindProvider("anthropic")!.BaseUrl, s.BaseUrlFor(Data.FindProvider("anthropic")!));
    }
}
