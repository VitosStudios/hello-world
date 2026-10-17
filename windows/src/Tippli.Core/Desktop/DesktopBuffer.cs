using Tippli.Core.Text;

namespace Tippli.Core.Desktop;

/// <summary>
/// Our best knowledge of the text before the caret in the focused field of another program (SPEC §10), max 2000 chars.
/// </summary>
public sealed class DesktopBuffer
{
    public const int MaxChars = 2000;

    public string Text { get; private set; } = "";

    /// <summary>
    /// True when the buffer starts at a known line/field start (after Enter, or an empty field). Otherwise the
    /// text before the buffer is unknown and the first, possibly partial word must not be touched.
    /// </summary>
    public bool StartsAtLineStart { get; private set; }

    public event Action? Changed;

    public void Append(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        Text += text;
        if (Text.Length > MaxChars)
        {
            Text = Graphemes.TakeLast(Text, MaxChars);
            StartsAtLineStart = false;
        }
        Changed?.Invoke();
    }

    /// <summary>Backspace removes the last grapheme; on an empty buffer nothing is known any more.</summary>
    public void Backspace()
    {
        if (Text.Length == 0)
        {
            StartsAtLineStart = false;
        }
        else
        {
            Text = Graphemes.RemoveLast(Text);
        }
        Changed?.Invoke();
    }

    /// <summary>Removes <paramref name="text"/> from the end (engine/keyboard deletes). Mismatch → reset.</summary>
    public void RemoveSuffix(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        if (Text.EndsWith(text, StringComparison.Ordinal))
        {
            Text = Text[..^text.Length];
            Changed?.Invoke();
        }
        else
        {
            Reset(false);
        }
    }

    public void Reset(bool atLineStart)
    {
        Text = "";
        StartsAtLineStart = atLineStart;
        Changed?.Invoke();
    }

    /// <summary>Marks the start as known (e.g. UI Automation reported that the field contains exactly the buffer).</summary>
    public void MarkStartKnown()
    {
        if (StartsAtLineStart) return;
        StartsAtLineStart = true;
        Changed?.Invoke();
    }

    /// <summary>
    /// The part of the buffer the engine may work on: everything when the start is known, otherwise only the
    /// text after the first whitespace (the first word may be the tail of a word we never saw).
    /// </summary>
    public string ReliableText
    {
        get
        {
            if (StartsAtLineStart) return Text;
            for (int i = 0; i < Text.Length; i++)
                if (char.IsWhiteSpace(Text[i])) return Text[(i + 1)..];
            return "";
        }
    }
}
