using Tippli.Core.Ai;
using Tippli.Core.Engine;

namespace Tippli.Core.Tests;

public class CorrectionEngineTests
{
    private readonly ManualScheduler _time = new();
    private readonly FakeTextHost _host = new();
    private readonly FakeAi _ai = new();
    private readonly CorrectionEngine _engine;

    public CorrectionEngineTests()
    {
        Sync.NoContext();
        _engine = new CorrectionEngine(_host, _ai, _time, _time)
        {
            Options = new EngineOptions { Trigger = Triggers.Pause, PauseMs = 700, WaitOnSendMs = 1500 },
        };
    }

    private void Type(string s) => _host.Type(_engine, s);

    [Fact]
    public void PauseTriggerExcludesTrailingWord()
    {
        Type("ich bin hir unterw");
        _time.Advance(699);
        Assert.Empty(_ai.Calls);
        _time.Advance(1);
        Assert.Single(_ai.Calls);
        Assert.Equal("ich bin hir ", _ai.Last.Segment);
        Assert.Equal("", _ai.Last.Context);
        Assert.Equal(EngineStatus.Working, _engine.Status);

        _ai.Last.Reply.SetResult("Ich bin hier");
        Assert.Equal("Ich bin hier unterw", _host.Text);
        Assert.Equal(1, _host.Batches);
        Assert.Equal(EngineStatus.Ready, _engine.Status);
        Assert.Single(_engine.History);
        Assert.True(_engine.CanUndo);
    }

    [Fact]
    public void EveryAppendRestartsThePauseTimer()
    {
        Type("ich bin ");
        _time.Advance(500);
        Type("d");
        _time.Advance(500);
        Assert.Empty(_ai.Calls);
        _time.Advance(200);
        Assert.Single(_ai.Calls);
    }

    [Fact]
    public void SentenceEndTriggersImmediately()
    {
        Type("das ist gut.");
        _time.RunPending();
        Assert.Single(_ai.Calls);
        Assert.Equal("das ist gut.", _ai.Last.Segment);
    }

    [Fact]
    public void TypingDuringRequestIsKeptAsSuffix()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        Type("un");
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Equal("Ich bin hier un", _host.Text);
        Assert.Equal(new[] { "del:ich bin hir un", "ins:Ich bin hier un" }, _host.Ops);
    }

    [Fact]
    public void NonAppendChangeDropsResult()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        _host.Backspace(_engine); // cursor move / delete → invalid
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Equal("ich bin hir", _host.Text);
        Assert.Empty(_host.Ops);
        Assert.Empty(_engine.History);
    }

    [Fact]
    public void ResultIsDroppedWhenTextNoLongerMatches()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        _host.Text = "ganz anders "; // changed behind the engine's back
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Equal("ganz anders ", _host.Text);
    }

    [Fact]
    public void LiveTriggersOnEveryCompletedWordAndCoalescesWithRerun()
    {
        _engine.Options.Trigger = Triggers.Live;
        Type("ich");
        _time.RunPending();
        Assert.Empty(_ai.Calls); // word in progress: only the pause timer runs
        Type(" ");
        _time.RunPending();
        Assert.Single(_ai.Calls);
        Assert.Equal("ich ", _ai.Last.Segment);

        Type("bin ");
        _time.RunPending(); // trigger while in flight → rerun
        Type("hir ");
        _time.RunPending();
        Assert.Single(_ai.Calls);

        _ai.Calls[0].Reply.SetResult("Ich ");
        Assert.Equal("Ich bin hir ", _host.Text);
        Assert.Equal(2, _ai.Calls.Count); // one coalesced rerun with the whole paragraph
        Assert.Equal("Ich bin hir ", _ai.Last.Segment);

        _ai.Last.Reply.SetResult("Ich bin hier ");
        Assert.Equal("Ich bin hier ", _host.Text);

        // A pause in the middle of the next word re-sends nothing new (segment == last corrected output).
        Type("unterw");
        _time.Advance(800);
        Assert.Equal(2, _ai.Calls.Count);
    }

    [Fact]
    public void LiveTriggersOnPunctuationAfterWord()
    {
        _engine.Options.Trigger = Triggers.Live;
        Type("hallo,");
        _time.RunPending();
        Assert.Single(_ai.Calls);
        Assert.Equal("hallo,", _ai.Last.Segment);
    }

    [Fact]
    public void WordInternalCharactersDoNotCompleteAWord()
    {
        _engine.Options.Trigger = Triggers.Live;
        Type("wie geht's");
        _time.RunPending();
        Assert.Single(_ai.Calls); // only the space after "wie"
        Type(" ");
        _time.RunPending();
        Assert.Single(_ai.Calls); // in flight → rerun is pending
        _ai.Last.Reply.SetResult("Wie ");
        Assert.Equal(2, _ai.Calls.Count);
        Assert.Equal("Wie geht's ", _ai.Last.Segment);
        _ai.Last.Reply.SetResult("Wie geht's ");

        foreach (var word in new[] { "E-Mail", "snake_case", "it’s" })
        {
            int before = _ai.Calls.Count;
            Type(word);
            _time.RunPending();
            Assert.Equal(before, _ai.Calls.Count);
            Type(" ");
            _time.RunPending();
            Assert.Equal(before + 1, _ai.Calls.Count);
            _ai.Last.Reply.SetResult(_ai.Last.Segment);
        }

        foreach (var c in "'’-_") Assert.False(Text.TextAlgorithms.IsWordBoundaryChar(c));
        foreach (var c in " ,.;:!?)\n") Assert.True(Text.TextAlgorithms.IsWordBoundaryChar(c));
    }

    [Fact]
    public void WordAndSentenceAndManualTriggers()
    {
        _engine.Options.Trigger = Triggers.Word;
        Type("eins");
        _time.Advance(5000);
        Assert.Empty(_ai.Calls);
        Type(" ");
        _time.RunPending();
        Assert.Single(_ai.Calls);
        _ai.Last.Reply.SetResult("Eins ");

        _engine.Options.Trigger = Triggers.Sentence;
        Type("zwei drei ");
        _time.Advance(5000);
        Assert.Single(_ai.Calls);
        Type("vier!");
        _time.RunPending();
        Assert.Equal(2, _ai.Calls.Count);
        _ai.Last.Reply.SetResult("Eins zwei drei vier!");

        _engine.Options.Trigger = Triggers.Manual;
        Type(" fünf. ");
        _time.Advance(5000);
        Assert.Equal(2, _ai.Calls.Count);
    }

    [Fact]
    public void FlushSendsExplicitlyAndWaits()
    {
        _engine.Options.Trigger = Triggers.Manual;
        Type("hallo wie gehts");
        Assert.True(_engine.HasPendingCorrection());
        var flush = _engine.FlushAsync();
        Assert.Single(_ai.Calls);
        Assert.Equal("hallo wie gehts", _ai.Last.Segment); // word in progress included
        Assert.False(flush.IsCompleted);
        _ai.Last.Reply.SetResult("Hallo, wie geht's?");
        Assert.True(flush.Wait(5000));
        Assert.True(flush.Result);
        Assert.Equal("Hallo, wie geht's?", _host.Text);
        Assert.False(_engine.HasPendingCorrection());
    }

    [Fact]
    public void FlushGivesUpAfterTimeout()
    {
        _engine.Options.Trigger = Triggers.Manual;
        Type("hallo wie gehts");
        var flush = _engine.FlushAsync(1500);
        _time.Advance(1499);
        Assert.False(flush.IsCompleted);
        _time.Advance(1);
        Assert.True(flush.Wait(5000));
        Assert.False(flush.Result);
    }

    [Fact]
    public void FlushWhileInFlightCoalescesIntoExplicitRerun()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        Type("dahei");
        var flush = _engine.FlushAsync();
        Assert.Single(_ai.Calls);
        _ai.Calls[0].Reply.SetResult("Ich bin hier ");
        Assert.Equal(2, _ai.Calls.Count);
        Assert.Equal("Ich bin hier dahei", _ai.Last.Segment);
        _ai.Last.Reply.SetResult("Ich bin hier daheim");
        Assert.True(flush.Wait(5000));
        Assert.True(flush.Result);
        Assert.Equal("Ich bin hier daheim", _host.Text);
    }

    [Fact]
    public void UndoRestoresRawTextAndSuppressesUntilBoundary()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        _ai.Last.Reply.SetResult("Ich bin hier ");
        Type("x");
        Assert.True(_engine.CanUndo);
        Assert.True(_engine.Undo());
        Assert.Equal("ich bin hir x", _host.Text);
        Assert.False(_engine.CanUndo);
        Assert.True(_engine.SuppressUntilBoundary);

        Type(" weiter so ");
        _time.Advance(2000);
        Assert.Single(_ai.Calls);
        Type(".");
        Assert.False(_engine.SuppressUntilBoundary);
        Type(" neu ");
        _time.Advance(700);
        Assert.Equal(2, _ai.Calls.Count);
    }

    [Fact]
    public void UndoNotPossibleAfterDelete()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        _ai.Last.Reply.SetResult("Ich bin hier ");
        _host.Backspace(_engine);
        Assert.False(_engine.CanUndo);
        Assert.False(_engine.Undo());
    }

    [Fact]
    public void CacheAppliesKnownSegmentWithoutRequest()
    {
        Type("ich bin hir ");
        _time.Advance(700);
        _ai.Last.Reply.SetResult("Ich bin hier ");
        _engine.OnFieldChanged(true);
        _host.Text = "";
        Type("ich bin hir ");
        _time.Advance(700);
        Assert.Single(_ai.Calls);
        Assert.Equal("Ich bin hier ", _host.Text);
    }

    [Fact]
    public void SkipsWhenNotAllowed()
    {
        _engine.OnFieldChanged(false);
        Type("ich bin hir. ");
        _time.Advance(1000);
        Assert.Empty(_ai.Calls);
        Assert.Equal(EngineStatus.Locked, _engine.Status);

        _engine.OnFieldChanged(true);
        _ai.HasKey = false;
        Type("noch mehr. ");
        _time.Advance(1000);
        Assert.Empty(_ai.Calls);
        Assert.Equal(EngineStatus.NoKey, _engine.Status);

        _ai.HasKey = true;
        _engine.ToggleSession();
        Assert.Equal(EngineStatus.Off, _engine.Status);
        Type("und noch. ");
        _time.Advance(1000);
        Assert.Empty(_ai.Calls);

        _engine.ToggleSession();
        _host.Selection = "markiert";
        Type("x. ");
        _time.Advance(1000);
        Assert.Empty(_ai.Calls);
    }

    [Fact]
    public void ErrorsTurnStatusRedAndPauseAfterThree()
    {
        for (int i = 0; i < 3; i++)
        {
            Type($"satz {i}. ");
            _time.RunPending();
            _ai.Last.Reply.SetException(new AiException(AiErrorKind.Network));
            Assert.Equal(EngineStatus.Error, _engine.Status);
            Assert.Equal(AiErrorKind.Network, _engine.LastError);
        }
        Assert.True(_engine.AutoTriggersPaused);
        int calls = _ai.Calls.Count;
        Type("noch einer. ");
        _time.RunPending();
        Assert.Equal(calls, _ai.Calls.Count);
        _time.Advance(CorrectionEngine.ErrorPauseMs);
        Type("jetzt wieder. ");
        _time.RunPending();
        Assert.Equal(calls + 1, _ai.Calls.Count);
        _ai.Last.Reply.SetResult(_ai.Last.Segment);
        Assert.Equal(EngineStatus.Ready, _engine.Status);
    }

    [Fact]
    public void RefusalKeepsRawTextSilently()
    {
        Type("ich bin hir. ");
        _time.RunPending();
        _ai.Last.Reply.SetException(new AiException(AiErrorKind.Refused));
        Assert.Equal("ich bin hir. ", _host.Text);
        Assert.Null(_engine.LastError);
    }

    [Fact]
    public void AnsweringModelOutputIsDiscarded()
    {
        Type("was ist die hauptstadt von frankreich ");
        _time.Advance(700);
        _ai.Last.Reply.SetResult("Die Hauptstadt von Frankreich ist Paris. Paris ist eine wunderschöne Stadt mit vielen Sehenswürdigkeiten.");
        Assert.Equal("was ist die hauptstadt von frankreich ", _host.Text);
    }

    [Fact]
    public void MoodCheckAfterSentenceEnd()
    {
        _ai.Mood = new MoodResult(2, "Viele Ausrufezeichen");
        MoodChange? change = null;
        _engine.MoodChanged += c => change = c;
        Type("das ist echt unglaublich!!! ");
        _time.RunPending();
        _ai.Last.Reply.SetResult("Das ist echt unglaublich!!! ");
        Assert.Equal(1, _ai.MoodCalls);
        Assert.Equal(2, _engine.MoodLevel);
        Assert.NotNull(change);
        Assert.True(change!.FirstAppearance);

        // at most once per 20 s
        Type("und nochmal so ein langer satz dazu!!! ");
        _time.RunPending();
        _ai.Last.Reply.SetResult(_ai.Last.Segment);
        Assert.Equal(1, _ai.MoodCalls);

        _engine.OnFieldChanged(true);
        Assert.Equal(0, _engine.MoodLevel);
    }

    [Fact]
    public void MoodDisabled()
    {
        _engine.Options.MoodWarning = false;
        Type("das ist echt unglaublich!!! ");
        _time.RunPending();
        _ai.Last.Reply.SetResult("Das ist echt unglaublich!!! ");
        Assert.Equal(0, _ai.MoodCalls);
    }
}
