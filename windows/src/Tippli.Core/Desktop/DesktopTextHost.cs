using Tippli.Core.Engine;
using Tippli.Core.Text;

namespace Tippli.Core.Desktop;

public enum StrokeKind
{
    /// <summary>Unicode text (KEYEVENTF_UNICODE / keyboardSetUnicodeString). '\n' is typed as Shift+Enter.</summary>
    Text,
    /// <summary><see cref="InjectedStroke.Count"/> × Backspace.</summary>
    Backspace,
    /// <summary>Ctrl+Backspace (word delete when the text is unknown).</summary>
    WordBackspace,
    Enter,
    ShiftEnter,
    Left,
    Right,
}

public readonly record struct InjectedStroke(StrokeKind Kind, string? Text = null, int Count = 1)
{
    public static InjectedStroke Type(string text) => new(StrokeKind.Text, text, 1);
    public static InjectedStroke Backspaces(int count) => new(StrokeKind.Backspace, null, count);
}

/// <summary>Sends synthetic keystrokes to the focused program, tagged so our own hook ignores them.</summary>
public interface IKeystrokeInjector
{
    /// <summary>Injects all strokes as one atomic batch (not interleaved with user input).</summary>
    void Inject(IReadOnlyList<InjectedStroke> strokes);

    /// <summary>False while modifiers are physically held (a Backspace would become Ctrl+Backspace …).</summary>
    bool CanInjectNow { get; }
}

/// <summary>
/// <see cref="ITextHost"/> for the desktop (SPEC §10): text before the caret = the buffer, delete = one Backspace
/// per grapheme, insert = injected Unicode text; the buffer is updated accordingly.
/// </summary>
public sealed class DesktopTextHost : ITextHost, IEditGate, IContextHint
{
    private readonly DesktopBuffer _buffer;
    private readonly IKeystrokeInjector _injector;
    private List<InjectedStroke>? _batch;

    public DesktopTextHost(DesktopBuffer buffer, IKeystrokeInjector injector)
    {
        _buffer = buffer;
        _injector = injector;
    }

    public DesktopBuffer Buffer => _buffer;

    public string TextBeforeCursor(int maxChars)
    {
        var t = _buffer.ReliableText;
        return t.Length <= maxChars ? t : t[^maxChars..];
    }

    public string TextAfterCursor(int maxChars) => "";

    public string SelectedText() => "";

    public bool CanEditNow => _injector.CanInjectNow;

    public bool HasUnknownTextBefore => !_buffer.StartsAtLineStart;

    public void Delete(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        Enqueue(InjectedStroke.Backspaces(Graphemes.Count(text)));
        _buffer.RemoveSuffix(text);
    }

    public void Insert(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        Enqueue(InjectedStroke.Type(text));
        _buffer.Append(text);
    }

    public void Batch(Action block)
    {
        if (_batch != null)
        {
            block();
            return;
        }
        _batch = new List<InjectedStroke>();
        try
        {
            block();
        }
        finally
        {
            var strokes = _batch;
            _batch = null;
            if (strokes.Count > 0) _injector.Inject(strokes);
        }
    }

    private void Enqueue(InjectedStroke stroke)
    {
        if (_batch != null) _batch.Add(stroke);
        else _injector.Inject(new[] { stroke });
    }
}
