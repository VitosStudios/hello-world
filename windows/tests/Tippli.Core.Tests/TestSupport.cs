using System.Net;
using System.Text;
using Tippli.Core.Ai;
using Tippli.Core.Engine;

namespace Tippli.Core.Tests;

/// <summary>Locates the repository's shared/ folder by walking up from the test binaries.</summary>
public static class TestPaths
{
    private static readonly Lazy<string> SharedDir = new(() =>
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = Path.Combine(dir.FullName, "shared", "tests", "engine-cases.json");
            if (File.Exists(candidate)) return Path.Combine(dir.FullName, "shared");
            dir = dir.Parent;
        }
        throw new DirectoryNotFoundException("Could not find shared/tests/engine-cases.json above " + AppContext.BaseDirectory);
    });

    public static string Shared => SharedDir.Value;

    public static string SharedFile(params string[] parts) => Path.Combine(new[] { Shared }.Concat(parts).ToArray());
}

/// <summary>Virtual time: a scheduler and clock that only advance when the test says so.</summary>
public sealed class ManualScheduler : IScheduler, IClock
{
    private sealed class Item
    {
        public long Due;
        public long Seq;
        public required Action Action;
        public bool Cancelled;
    }

    private readonly List<Item> _items = new();
    private long _seq;

    public long NowMs { get; private set; } = 1_000_000;

    public int PendingCount => _items.Count(i => !i.Cancelled);

    public IDisposable Schedule(int delayMs, Action action)
    {
        var item = new Item { Due = NowMs + Math.Max(0, delayMs), Seq = _seq++, Action = action };
        _items.Add(item);
        return new ActionDisposable(() => item.Cancelled = true);
    }

    /// <summary>Advances time, running every due callback in order (including ones scheduled meanwhile).</summary>
    public void Advance(int ms)
    {
        long target = NowMs + ms;
        while (true)
        {
            var next = _items.Where(i => !i.Cancelled && i.Due <= target).OrderBy(i => i.Due).ThenBy(i => i.Seq).FirstOrDefault();
            if (next == null) break;
            _items.Remove(next);
            if (next.Due > NowMs) NowMs = next.Due;
            next.Action();
        }
        _items.RemoveAll(i => i.Cancelled);
        NowMs = target;
    }

    public void RunPending() => Advance(0);
}

/// <summary>A text field with the caret at the end.</summary>
public sealed class FakeTextHost : ITextHost
{
    public string Text = "";
    public string Selection = "";
    public int Batches;
    public readonly List<string> Ops = new();

    public string TextBeforeCursor(int maxChars) => Text.Length <= maxChars ? Text : Text[^maxChars..];
    public string TextAfterCursor(int maxChars) => "";
    public string SelectedText() => Selection;

    public void Delete(string text)
    {
        Assert.EndsWith(text, Text, StringComparison.Ordinal);
        Text = Text[..^text.Length];
        Ops.Add("del:" + text);
    }

    public void Insert(string text)
    {
        Text += text;
        Ops.Add("ins:" + text);
    }

    public void Batch(Action block)
    {
        Batches++;
        block();
    }

    /// <summary>User types character by character (like a keyboard).</summary>
    public void Type(CorrectionEngine engine, string s)
    {
        foreach (var r in s.EnumerateRunes())
        {
            var t = r.ToString();
            Text += t;
            engine.OnTextTyped(t);
        }
    }

    public void Backspace(CorrectionEngine engine)
    {
        Text = Text[..^1];
        engine.OnNonAppendChange();
    }
}

/// <summary>AI whose answers the test controls.</summary>
public sealed class FakeAi : ICorrectionAi
{
    public sealed record Call(string Context, string Segment, TaskCompletionSource<string> Reply);

    public bool HasKey { get; set; } = true;
    public readonly List<Call> Calls = new();
    public Func<string, string>? AutoReply;
    public MoodResult Mood = new(0, "");
    public int MoodCalls;

    public Task<string> CorrectAsync(string context, string segment, CancellationToken cancellationToken = default)
    {
        var tcs = new TaskCompletionSource<string>();
        Calls.Add(new Call(context, segment, tcs));
        if (AutoReply != null) tcs.SetResult(AutoReply(segment));
        return tcs.Task;
    }

    public Task<MoodResult> MoodAsync(string paragraph, CancellationToken cancellationToken = default)
    {
        MoodCalls++;
        return Task.FromResult(Mood);
    }

    public Call Last => Calls[^1];
}

/// <summary>HTTP handler that records requests and returns canned responses.</summary>
public sealed class FakeHttpHandler : HttpMessageHandler
{
    public sealed record Recorded(HttpMethod Method, string Url, Dictionary<string, string> Headers, string Body);

    public readonly List<Recorded> Requests = new();
    public readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> Responses = new();
    public Func<HttpRequestMessage, HttpResponseMessage>? Default;

    public void Enqueue(HttpStatusCode status, string body)
        => Responses.Enqueue(_ => new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") });

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var h in request.Headers) headers[h.Key] = string.Join(",", h.Value);
        if (request.Content != null)
            foreach (var h in request.Content.Headers) headers[h.Key] = string.Join(",", h.Value);
        var body = request.Content == null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
        Requests.Add(new Recorded(request.Method, request.RequestUri!.ToString(), headers, body));
        var responder = Responses.Count > 0 ? Responses.Dequeue() : Default
            ?? throw new InvalidOperationException("No response queued");
        return responder(request);
    }
}

public static class Sync
{
    /// <summary>Makes awaits in the engine continue inline on the test thread (deterministic tests).</summary>
    public static void NoContext() => SynchronizationContext.SetSynchronizationContext(null);
}
