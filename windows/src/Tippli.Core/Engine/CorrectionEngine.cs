using System.Text;
using Tippli.Core.Ai;
using Tippli.Core.Text;

namespace Tippli.Core.Engine;

/// <summary>The text field as seen by the engine (SPEC §5).</summary>
public interface ITextHost
{
    string TextBeforeCursor(int maxChars);
    string TextAfterCursor(int maxChars);
    string SelectedText();
    /// <summary>Delete exactly this text right before the caret.</summary>
    void Delete(string text);
    /// <summary>Insert at the caret.</summary>
    void Insert(string text);
    /// <summary>Group delete+insert to avoid flicker (desktop: one atomic SendInput).</summary>
    void Batch(Action block);
}

/// <summary>Optional: a host that may temporarily refuse engine edits (desktop: while Ctrl/Alt/Win are held).</summary>
public interface IEditGate
{
    bool CanEditNow { get; }
}

/// <summary>Optional: a host that knows there is unseen text before what it reports (desktop after a click).</summary>
public interface IContextHint
{
    bool HasUnknownTextBefore { get; }
}

/// <summary>The AI calls the engine needs.</summary>
public interface ICorrectionAi
{
    bool HasKey { get; }
    Task<string> CorrectAsync(string context, string segment, CancellationToken cancellationToken = default);
    Task<MoodResult> MoodAsync(string paragraph, CancellationToken cancellationToken = default);
}

public static class Triggers
{
    public const string Live = "live";
    public const string Pause = "pause";
    public const string Word = "word";
    public const string Sentence = "sentence";
    public const string Manual = "manual";
    public static readonly IReadOnlyList<string> All = new[] { Live, Pause, Word, Sentence, Manual };
}

/// <summary>Settings the engine uses (refreshed whenever the settings change).</summary>
public sealed class EngineOptions
{
    public bool AiEnabled { get; set; } = true;
    public string Trigger { get; set; } = Triggers.Live;
    public int PauseMs { get; set; } = 700;
    public int WaitOnSendMs { get; set; } = 1500;
    /// <summary><c>moodWarning</c> and <c>tipsEmotion</c>.</summary>
    public bool MoodWarning { get; set; } = true;
    public bool Pinyin { get; set; }
}

public enum EngineStatus
{
    Off,
    Locked,
    NoKey,
    Ready,
    Working,
    Error,
}

public sealed record HistoryEntry(string Raw, string Corrected);

public sealed record MoodChange(int Level, string Reason, bool FirstAppearance);

/// <summary>
/// Platform independent correction engine (SPEC §5): triggers, one request in flight with rerun coalescing,
/// invalidation on non-append changes, cleaning, applying with typed-since suffix, flush, undo, history, cache
/// and the mood check. All calls must come from one thread (the UI thread); async continuations return there.
/// </summary>
public sealed class CorrectionEngine
{
    public const int HistoryMax = 20;
    public const int CacheMax = 20;
    public const int ErrorPauseAfter = 3;
    public const int ErrorPauseMs = 30_000;
    public const int MoodIntervalMs = 20_000;
    public const int MoodMinChange = 20;
    public const int MoodMaxChars = 1000;

    private sealed class InFlight
    {
        public required SegmentSelection Selection;
        public readonly StringBuilder TypedSince = new();
        public bool Invalid;
    }

    private sealed class LastCorrection
    {
        public required string Raw;
        public required string Corrected;
        /// <summary>X of §5.5: the suffix re-typed at apply time plus everything typed since.</summary>
        public required string TypedAfter;
    }

    private readonly ITextHost _host;
    private readonly ICorrectionAi _ai;
    private readonly IClock _clock;
    private readonly IScheduler _scheduler;
    private readonly List<HistoryEntry> _history = new();
    private readonly LinkedList<KeyValuePair<string, string>> _cache = new();
    private readonly List<TaskCompletionSource> _idleWaiters = new();

    private IDisposable? _debounce;
    private InFlight? _inFlight;
    private bool _rerun;
    private bool _rerunExplicit;
    private LastCorrection? _lastCorrection;
    private bool _suppressUntilBoundary;
    private string? _lastCorrectedOutput;
    private int _consecutiveErrors;
    private long _autoPausedUntil = long.MinValue / 2;
    private long _lastMoodAt = long.MinValue / 2;
    private string? _lastMoodParagraph;
    private int _moodGeneration;

    public CorrectionEngine(ITextHost host, ICorrectionAi ai, IClock clock, IScheduler scheduler)
    {
        _host = host;
        _ai = ai;
        _clock = clock;
        _scheduler = scheduler;
    }

    public EngineOptions Options { get; set; } = new();
    public bool SessionEnabled { get; private set; } = true;
    public bool FieldAllowed { get; private set; } = true;
    public AiErrorKind? LastError { get; private set; }
    public string? LastErrorDetail { get; private set; }
    public int MoodLevel { get; private set; }
    public string MoodReason { get; private set; } = "";
    public IReadOnlyList<HistoryEntry> History => _history;
    public bool IsRequestInFlight => _inFlight != null;
    public bool SuppressUntilBoundary => _suppressUntilBoundary;
    public bool AutoTriggersPaused => _clock.NowMs < _autoPausedUntil;

    public event Action? StatusChanged;
    public event Action? UndoChanged;
    public event Action<MoodChange>? MoodChanged;
    public event Action<AiErrorKind>? CorrectionFailed;
    /// <summary>A correction was applied (raw → corrected).</summary>
    public event Action<HistoryEntry>? Applied;

    public bool AiAllowed => Options.AiEnabled && SessionEnabled && FieldAllowed && _ai.HasKey;

    public EngineStatus Status
    {
        get
        {
            if (!Options.AiEnabled || !SessionEnabled) return EngineStatus.Off;
            if (!FieldAllowed) return EngineStatus.Locked;
            if (!_ai.HasKey) return EngineStatus.NoKey;
            if (_inFlight != null) return EngineStatus.Working;
            if (LastError != null) return EngineStatus.Error;
            return EngineStatus.Ready;
        }
    }

    // ---- field / session ------------------------------------------------------------------------------

    /// <summary>The toolbar AI dot: toggles AI for the current field.</summary>
    public void ToggleSession()
    {
        SessionEnabled = !SessionEnabled;
        if (!SessionEnabled) CancelPending();
        StatusChanged?.Invoke();
    }

    /// <summary>A new field got focus (§5.1): everything field related is reset.</summary>
    public void OnFieldChanged(bool allowed)
    {
        CancelPending();
        _history.Clear();
        _lastCorrection = null;
        _suppressUntilBoundary = false;
        _lastCorrectedOutput = null;
        SessionEnabled = true;
        FieldAllowed = allowed;
        _lastMoodParagraph = null;
        _moodGeneration++;
        if (MoodLevel != 0)
        {
            MoodLevel = 0;
            MoodReason = "";
            MoodChanged?.Invoke(new MoodChange(0, "", false));
        }
        UndoChanged?.Invoke();
        StatusChanged?.Invoke();
        NotifyIdleIfIdle();
    }

    /// <summary>Changes whether the current field may use the AI (e.g. after the password check finished).</summary>
    public void SetFieldAllowed(bool allowed)
    {
        if (FieldAllowed == allowed) return;
        FieldAllowed = allowed;
        if (!allowed) CancelPending();
        StatusChanged?.Invoke();
    }

    /// <summary>Settings changed (key/provider): forget the error state.</summary>
    public void ResetError()
    {
        LastError = null;
        LastErrorDetail = null;
        _consecutiveErrors = 0;
        _autoPausedUntil = long.MinValue / 2;
        StatusChanged?.Invoke();
    }

    public void ClearCache() => _cache.Clear();

    private void CancelPending()
    {
        _debounce?.Dispose();
        _debounce = null;
        if (_inFlight != null)
        {
            _inFlight.Invalid = true;
            _inFlight.TypedSince.Clear();
        }
        _rerun = false;
        _rerunExplicit = false;
    }

    // ---- text changes -----------------------------------------------------------------------------------

    /// <summary>The user typed <paramref name="text"/> (already inserted into the field).</summary>
    public void OnTextTyped(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        if (_inFlight != null && !_inFlight.Invalid) _inFlight.TypedSince.Append(text);
        if (_lastCorrection != null) _lastCorrection.TypedAfter += text;

        bool sentenceEnd = TextAlgorithms.ContainsSentenceEnd(text);
        if (_suppressUntilBoundary)
        {
            // No automatic correction until a sentence end or newline is typed (§5.5).
            if (sentenceEnd) _suppressUntilBoundary = false;
            return;
        }
        if (!AiAllowed || AutoTriggersPaused) return;

        var before = _host.TextBeforeCursor(text.Length + 2);
        bool prevWordChar = false, prevLetter = false;
        int start = before.Length - text.Length;
        if (start > 0 && before.EndsWith(text, StringComparison.Ordinal))
        {
            var r = TextAlgorithms.RuneBefore(before, start);
            prevWordChar = TextAlgorithms.IsLetterOrDigit(r);
            prevLetter = TextAlgorithms.IsLetter(r);
        }
        bool boundary = TextAlgorithms.IsWordBoundaryChar(text[0]);
        bool wordCompleted = boundary && prevWordChar;

        switch (Options.Trigger)
        {
            case Triggers.Live:
                if (sentenceEnd || wordCompleted) TriggerSoon();
                else RestartDebounce();
                break;
            case Triggers.Pause:
                if (sentenceEnd) TriggerSoon();
                else RestartDebounce();
                break;
            case Triggers.Word:
                if (sentenceEnd || (boundary && prevLetter)) TriggerSoon();
                break;
            case Triggers.Sentence:
                if (sentenceEnd) TriggerSoon();
                break;
            default:
                break; // manual
        }
    }

    /// <summary>Any change that is not an append (delete, cursor move, swipe, restore, …) (§5.1).</summary>
    public void OnNonAppendChange()
    {
        if (_inFlight != null)
        {
            _inFlight.Invalid = true;
            _inFlight.TypedSince.Clear();
        }
        if (_lastCorrection != null)
        {
            _lastCorrection = null;
            UndoChanged?.Invoke();
        }
    }

    private void TriggerSoon()
    {
        _debounce?.Dispose();
        _debounce = null;
        _scheduler.Post(() => _ = TriggerAsync(false));
    }

    private void RestartDebounce()
    {
        _debounce?.Dispose();
        _debounce = _scheduler.Schedule(Math.Max(50, Options.PauseMs), () =>
        {
            _debounce = null;
            _ = TriggerAsync(false);
        });
    }

    // ---- request ----------------------------------------------------------------------------------------

    /// <summary>Runs one correction (§5.3/§5.4). Explicit = ✨ / flush / hotkey (word in progress included).</summary>
    public async Task TriggerAsync(bool explicitTrigger)
    {
        if (!AiAllowed)
        {
            NotifyIdleIfIdle();
            return;
        }
        if (!explicitTrigger && (_suppressUntilBoundary || AutoTriggersPaused)) return;
        if (_host.SelectedText().Length > 0) return;
        if (_inFlight != null)
        {
            _rerun = true;
            _rerunExplicit |= explicitTrigger;
            return;
        }

        var before = _host.TextBeforeCursor(1000);
        var sel = TextAlgorithms.SelectSegment(before, explicitTrigger, Options.Pinyin);
        if (sel == null || string.Equals(sel.Segment, _lastCorrectedOutput, StringComparison.Ordinal))
        {
            NotifyIdleIfIdle();
            return;
        }
        if (TryGetCached(sel.Segment, out var cached))
        {
            Apply(sel, cached, "");
            NotifyIdleIfIdle();
            return;
        }

        var request = new InFlight { Selection = sel };
        _inFlight = request;
        StatusChanged?.Invoke();

        string? output = null;
        try
        {
            var context = sel.Context;
            if (context.Length == 0 && _host is IContextHint { HasUnknownTextBefore: true }) context = "…";
            output = await _ai.CorrectAsync(context, sel.Segment);
            _consecutiveErrors = 0;
            LastError = null;
            LastErrorDetail = null;
        }
        catch (AiException ex) when (ex.Kind == AiErrorKind.Refused)
        {
            // Refusal: keep the raw text silently (§6.2).
        }
        catch (AiException ex)
        {
            HandleError(ex.Kind, ex.Detail);
        }
        catch (OperationCanceledException)
        {
            HandleError(AiErrorKind.Timeout, null);
        }
        catch (Exception ex)
        {
            HandleError(AiErrorKind.Network, ex.Message);
        }

        _inFlight = null;
        if (output != null && !request.Invalid)
        {
            var cleaned = TextAlgorithms.CleanOutput(sel.Segment, output, Options.Pinyin);
            if (cleaned != null)
            {
                PutCache(sel.Segment, cleaned);
                Apply(sel, cleaned, request.TypedSince.ToString());
            }
        }
        StatusChanged?.Invoke();

        if (_rerun)
        {
            bool ex = _rerunExplicit;
            _rerun = false;
            _rerunExplicit = false;
            await TriggerAsync(ex);
        }
        NotifyIdleIfIdle();
    }

    private void HandleError(AiErrorKind kind, string? detail)
    {
        LastError = kind;
        LastErrorDetail = detail;
        _consecutiveErrors++;
        if (_consecutiveErrors >= ErrorPauseAfter)
        {
            _consecutiveErrors = 0;
            _autoPausedUntil = _clock.NowMs + ErrorPauseMs;
        }
        CorrectionFailed?.Invoke(kind);
    }

    private bool Apply(SegmentSelection sel, string corrected, string typedSince)
    {
        if (_host is IEditGate { CanEditNow: false }) return false;
        var before = _host.TextBeforeCursor(1000 + sel.Trailing.Length + typedSince.Length + 64);
        if (!TextAlgorithms.CanApply(before, sel.Segment, sel.Trailing, typedSince)) return false;

        var suffix = sel.Trailing + typedSince;
        _lastCorrectedOutput = corrected;
        var edit = TextAlgorithms.ComputeEdit(sel.Segment, corrected, suffix);
        if (edit == null) return true;

        _host.Batch(() =>
        {
            _host.Delete(edit.Delete);
            _host.Insert(edit.Insert);
        });

        var entry = new HistoryEntry(sel.Segment, corrected);
        _history.Add(entry);
        if (_history.Count > HistoryMax) _history.RemoveRange(0, _history.Count - HistoryMax);
        _lastCorrection = new LastCorrection { Raw = sel.Segment, Corrected = corrected, TypedAfter = suffix };
        UndoChanged?.Invoke();
        Applied?.Invoke(entry);

        if (TextAlgorithms.ContainsSentenceEnd(sel.Segment)) MaybeCheckMood();
        return true;
    }

    // ---- flush / pending --------------------------------------------------------------------------------

    /// <summary>True if a correction is pending, in flight, or the text before the cursor is not corrected yet.</summary>
    public bool HasPendingCorrection()
    {
        if (!AiAllowed) return false;
        if (_inFlight != null || _debounce != null || _rerun) return true;
        var sel = TextAlgorithms.SelectSegment(_host.TextBeforeCursor(1000), true, Options.Pinyin);
        if (sel == null) return false;
        if (string.Equals(sel.Segment, _lastCorrectedOutput, StringComparison.Ordinal)) return false;
        if (TryPeekCached(sel.Segment, out var c) && string.Equals(c, sel.Segment, StringComparison.Ordinal)) return false;
        return true;
    }

    /// <summary>
    /// Flush (§5.4.7): cancel the debounce, correct now (explicit) and wait up to <paramref name="timeoutMs"/>
    /// (default <c>waitOnSendMs</c>). Returns true if the engine became idle in time.
    /// </summary>
    public async Task<bool> FlushAsync(int? timeoutMs = null)
    {
        _debounce?.Dispose();
        _debounce = null;
        if (!HasPendingCorrection()) return true;
        int timeout = Math.Max(0, timeoutMs ?? Options.WaitOnSendMs);
        _ = TriggerAsync(true);
        var idle = WhenIdleAsync();
        if (idle.IsCompleted) return true;
        if (timeout == 0) return false;
        var done = await Task.WhenAny(idle, _scheduler.Delay(timeout));
        return done == idle;
    }

    /// <summary>Completes when no request is in flight and no rerun is pending.</summary>
    public Task WhenIdleAsync()
    {
        if (_inFlight == null && !_rerun) return Task.CompletedTask;
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _idleWaiters.Add(tcs);
        return tcs.Task;
    }

    private void NotifyIdleIfIdle()
    {
        if (_inFlight != null || _rerun || _idleWaiters.Count == 0) return;
        var waiters = _idleWaiters.ToList();
        _idleWaiters.Clear();
        foreach (var w in waiters) w.TrySetResult();
    }

    // ---- undo (§5.5) ------------------------------------------------------------------------------------

    public bool CanUndo
    {
        get
        {
            var lc = _lastCorrection;
            if (lc == null) return false;
            var tail = lc.Corrected + lc.TypedAfter;
            return _host.TextBeforeCursor(tail.Length + 8).EndsWith(tail, StringComparison.Ordinal);
        }
    }

    public bool Undo()
    {
        var lc = _lastCorrection;
        if (lc == null) return false;
        var tail = lc.Corrected + lc.TypedAfter;
        if (_host is IEditGate { CanEditNow: false }) return false;
        if (!_host.TextBeforeCursor(tail.Length + 8).EndsWith(tail, StringComparison.Ordinal))
        {
            _lastCorrection = null;
            UndoChanged?.Invoke();
            return false;
        }
        var edit = TextAlgorithms.ComputeEdit(lc.Corrected, lc.Raw, lc.TypedAfter);
        if (_inFlight != null)
        {
            _inFlight.Invalid = true;
            _inFlight.TypedSince.Clear();
        }
        _debounce?.Dispose();
        _debounce = null;
        if (edit != null)
        {
            _host.Batch(() =>
            {
                _host.Delete(edit.Delete);
                _host.Insert(edit.Insert);
            });
        }
        _suppressUntilBoundary = true;
        _lastCorrection = null;
        _lastCorrectedOutput = lc.Raw;
        UndoChanged?.Invoke();
        StatusChanged?.Invoke();
        return true;
    }

    /// <summary>After a tips "replace" (§7.2): an engine change that clears history and undo.</summary>
    public void OnTextReplaced()
    {
        _history.Clear();
        _lastCorrection = null;
        _lastCorrectedOutput = null;
        if (_inFlight != null)
        {
            _inFlight.Invalid = true;
            _inFlight.TypedSince.Clear();
        }
        UndoChanged?.Invoke();
    }

    // ---- cache ------------------------------------------------------------------------------------------

    private bool TryGetCached(string segment, out string corrected)
    {
        for (var node = _cache.First; node != null; node = node.Next)
        {
            if (!string.Equals(node.Value.Key, segment, StringComparison.Ordinal)) continue;
            corrected = node.Value.Value;
            _cache.Remove(node);
            _cache.AddFirst(node);
            return true;
        }
        corrected = "";
        return false;
    }

    private bool TryPeekCached(string segment, out string corrected)
    {
        foreach (var kv in _cache)
        {
            if (!string.Equals(kv.Key, segment, StringComparison.Ordinal)) continue;
            corrected = kv.Value;
            return true;
        }
        corrected = "";
        return false;
    }

    private void PutCache(string segment, string corrected)
    {
        for (var node = _cache.First; node != null; node = node.Next)
        {
            if (string.Equals(node.Value.Key, segment, StringComparison.Ordinal))
            {
                _cache.Remove(node);
                break;
            }
        }
        _cache.AddFirst(new KeyValuePair<string, string>(segment, corrected));
        while (_cache.Count > CacheMax) _cache.RemoveLast();
    }

    // ---- mood check (§5.7) ------------------------------------------------------------------------------

    private void MaybeCheckMood()
    {
        if (!Options.MoodWarning || !FieldAllowed || !AiAllowed) return;
        long now = _clock.NowMs;
        if (now - _lastMoodAt < MoodIntervalMs) return;
        var paragraph = TextAlgorithms.LastParagraph(_host.TextBeforeCursor(4000));
        if (paragraph.Length > MoodMaxChars) paragraph = Graphemes.TakeLast(paragraph, MoodMaxChars);
        if (paragraph.Trim().Length == 0) return;
        if (_lastMoodParagraph != null && ChangeAmount(_lastMoodParagraph, paragraph) < MoodMinChange) return;
        _lastMoodAt = now;
        _lastMoodParagraph = paragraph;
        _ = RunMoodAsync(paragraph, _moodGeneration);
    }

    private static int ChangeAmount(string a, string b)
    {
        int p = 0;
        while (p < a.Length && p < b.Length && a[p] == b[p]) p++;
        return (a.Length - p) + (b.Length - p);
    }

    private async Task RunMoodAsync(string paragraph, int generation)
    {
        try
        {
            var result = await _ai.MoodAsync(paragraph);
            if (generation != _moodGeneration) return;
            int level = result.Level >= 2 ? result.Level : 0;
            bool first = MoodLevel < 2 && level >= 2;
            bool changed = level != MoodLevel || result.Reason != MoodReason;
            MoodLevel = level;
            MoodReason = level >= 2 ? result.Reason : "";
            if (changed) MoodChanged?.Invoke(new MoodChange(level, MoodReason, first));
        }
        catch
        {
            // Mood errors are silent.
        }
    }
}
