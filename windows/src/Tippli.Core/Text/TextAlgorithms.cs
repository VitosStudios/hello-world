using System.Globalization;
using System.Text;

namespace Tippli.Core.Text;

/// <summary>Result of <see cref="TextAlgorithms.SelectSegment"/> (SPEC §5.2).</summary>
public sealed record SegmentSelection(string Context, string Segment, string Trailing);

/// <summary>Result of <see cref="TextAlgorithms.ComputeEdit"/> (SPEC §5.4.5).</summary>
public sealed record TextEdit(string Delete, string Insert);

/// <summary>
/// The text algorithms of SPEC §5.2–5.6. Behaviour is identical to shared/tools/reference_engine.py and verified
/// against shared/tests/engine-cases.json. Character classes are evaluated on code points (like Python),
/// indices are UTF-16 (all cut points are whitespace or line boundaries, so units do not matter).
/// </summary>
public static class TextAlgorithms
{
    public const int MaxSegment = 400;
    public const int MaxContext = 600;

    public const string SentenceEnd = ".!?…。！？";
    private const string WordExtra = "'’-_";
    private const string Closers = ")]}\"'»”’";

    private static readonly (string Open, string Close)[] QuotePairs =
    {
        ("\"", "\""), ("„", "“"), ("“", "”"), ("«", "»"), ("'", "'"), ("‘", "’"),
    };

    // ---- character classes -------------------------------------------------------------------------

    public static bool IsWs(char c) => char.IsWhiteSpace(c);

    public static bool IsSentenceEndChar(char c) => SentenceEnd.IndexOf(c) >= 0;

    public static bool IsLetterOrDigit(Rune r)
    {
        var cat = Rune.GetUnicodeCategory(r);
        return cat <= UnicodeCategory.OtherLetter
               || cat is UnicodeCategory.DecimalDigitNumber or UnicodeCategory.LetterNumber or UnicodeCategory.OtherNumber;
    }

    public static bool IsLetter(Rune r) => Rune.GetUnicodeCategory(r) <= UnicodeCategory.OtherLetter;

    public static bool HasLetter(string s)
    {
        foreach (var r in s.EnumerateRunes())
            if (IsLetter(r)) return true;
        return false;
    }

    /// <summary>Number of code points (Python <c>len</c>).</summary>
    public static int CodePointLength(string s)
    {
        int n = 0;
        foreach (var _ in s.EnumerateRunes()) n++;
        return n;
    }

    /// <summary>Index of the code point that ends right before <paramref name="index"/>.</summary>
    internal static int PrevIndex(string s, int index)
        => index >= 2 && char.IsLowSurrogate(s[index - 1]) && char.IsHighSurrogate(s[index - 2]) ? index - 2 : index - 1;

    /// <summary>The code point that ends right before <paramref name="index"/>.</summary>
    public static Rune RuneBefore(string s, int index)
    {
        if (index <= 0) return Rune.ReplacementChar;
        int j = PrevIndex(s, index);
        return Rune.DecodeFromUtf16(s.AsSpan(j, index - j), out var r, out _) == System.Buffers.OperationStatus.Done
            ? r
            : Rune.ReplacementChar;
    }

    private static bool IsWordRune(Rune r) => IsLetterOrDigit(r) || (r.IsBmp && WordExtra.IndexOf((char)r.Value) >= 0);

    // ---- §5.2 selectSegment -------------------------------------------------------------------------

    /// <param name="minStart">
    /// Earliest index the segment may start at (§5.5 undo protection, §10 unknown desktop buffer start). If it points
    /// inside a word it is moved to after the next whitespace (step 0).
    /// </param>
    public static SegmentSelection? SelectSegment(string before, bool explicitTrigger, bool pinyin, int minStart = 0,
        int maxSegment = MaxSegment, int maxContext = MaxContext)
    {
        before ??= "";
        if (minStart < 0) minStart = 0;
        if (minStart > 0 && minStart < before.Length && !IsWs(before[minStart - 1]))
        {
            // never start inside a word: skip to after the next whitespace
            while (minStart < before.Length && !IsWs(before[minStart])) minStart++;
            minStart = Math.Min(minStart + 1, before.Length);
        }
        int p = Math.Max(before.LastIndexOf('\n') + 1, minStart);
        int end = before.Length;
        string trailing = "";
        bool inProgress = before.Length > 0 && IsLetterOrDigit(RuneBefore(before, before.Length));
        if (!explicitTrigger && !pinyin && inProgress)
        {
            int k = -1;
            for (int i = before.Length - 1; i >= 0; i--)
            {
                if (IsWs(before[i])) { k = i; break; }
            }
            if (k < p) return null;
            end = k + 1;
            trailing = before[end..];
        }

        int segStart;
        if (end - p > maxSegment)
        {
            int window = end - maxSegment;
            if (window > 0 && window < before.Length && char.IsLowSurrogate(before[window]) && char.IsHighSurrogate(before[window - 1]))
                window++;
            int found = -1;
            for (int i = window; i < end; i++)
            {
                if (IsWs(before[i]) && i - 1 >= 0 && IsSentenceEndChar(before[i - 1])) { found = i + 1; break; }
            }
            if (found < 0)
            {
                for (int i = window; i < end; i++)
                {
                    if (IsWs(before[i])) { found = i + 1; break; }
                }
            }
            segStart = found >= 0 ? found : window;
        }
        else
        {
            segStart = p;
        }

        if (segStart >= end) return null; // minStart at/after the end: nothing to correct
        string segment = before[segStart..end];
        if (!HasLetter(segment)) return null;

        int ctxStart = Math.Max(0, segStart - maxContext);
        if (ctxStart > 0)
        {
            for (int i = ctxStart; i < segStart; i++)
            {
                if (IsWs(before[i])) { ctxStart = i + 1; break; }
            }
            if (ctxStart < before.Length && char.IsLowSurrogate(before[ctxStart]) && ctxStart > 0 && char.IsHighSurrogate(before[ctxStart - 1]))
                ctxStart++;
        }
        string context = before[ctxStart..segStart];
        return new SegmentSelection(context, segment, trailing);
    }

    // ---- §5.4.2 cleanOutput ----------------------------------------------------------------------------

    public static string LeadingWhitespace(string s)
    {
        int i = 0;
        while (i < s.Length && IsWs(s[i])) i++;
        return s[..i];
    }

    public static string TrailingWhitespace(string s)
    {
        int i = s.Length;
        while (i > 0 && IsWs(s[i - 1])) i--;
        return s[i..];
    }

    public static string? CleanOutput(string segment, string? output, bool pinyin)
    {
        if (output == null) return null;
        string t = output;
        int idx = t.IndexOf("<segment>", StringComparison.Ordinal);
        if (idx >= 0) t = t[(idx + "<segment>".Length)..];
        idx = t.IndexOf("</segment>", StringComparison.Ordinal);
        if (idx >= 0) t = t[..idx];
        t = t.Trim();
        if (t.StartsWith("```", StringComparison.Ordinal))
        {
            int nl = t.IndexOf('\n');
            t = nl >= 0 ? t[(nl + 1)..] : t[3..];
            if (t.EndsWith("```", StringComparison.Ordinal)) t = t[..^3];
            t = t.Trim();
        }

        string sTrim = segment.Trim();
        foreach (var (open, close) in QuotePairs)
        {
            if (t.Length >= 2 && t.StartsWith(open, StringComparison.Ordinal) && t.EndsWith(close, StringComparison.Ordinal)
                && !sTrim.StartsWith(open, StringComparison.Ordinal))
            {
                t = t[open.Length..^close.Length].Trim();
                break;
            }
        }

        if (t.Length == 0) return null;
        if (t.Contains("<context>", StringComparison.Ordinal) || t.Contains("</context>", StringComparison.Ordinal)) return null;
        if (!pinyin)
        {
            int len = CodePointLength(sTrim);
            if (len >= 12)
            {
                int tl = CodePointLength(t);
                if (tl > len * 1.5 + 10 || tl < len * 0.5 - 5) return null;
            }
        }
        return LeadingWhitespace(segment) + t + TrailingWhitespace(segment);
    }

    // ---- §5.4.5 computeEdit ----------------------------------------------------------------------------

    public static TextEdit? ComputeEdit(string segment, string corrected, string suffix)
    {
        if (string.Equals(segment, corrected, StringComparison.Ordinal)) return null;
        int p = 0;
        while (p < segment.Length && p < corrected.Length && segment[p] == corrected[p]) p++;
        // Never split a surrogate pair or a grapheme (delete happens per grapheme on desktop).
        while (p > 0 && (!Graphemes.IsBoundary(segment, p) || !Graphemes.IsBoundary(corrected, p))) p--;
        return new TextEdit(segment[p..] + suffix, corrected[p..] + suffix);
    }

    // ---- §5.6 small helpers ----------------------------------------------------------------------------

    /// <summary>The text a swipe-left removes.</summary>
    public static string WordDelete(string before)
    {
        int n = before.Length;
        if (n == 0) return "";
        if (before[n - 1] == '\n') return "\n";
        int i = n;
        while (i > 0 && IsWs(before[i - 1]) && before[i - 1] != '\n') i--;
        if (i == 0 || before[i - 1] == '\n') return before[i..];
        int j = i;
        if (IsWordRune(RuneBefore(before, i)))
        {
            while (j > 0 && IsWordRune(RuneBefore(before, j))) j = PrevIndex(before, j);
        }
        else
        {
            while (j > 0)
            {
                var r = RuneBefore(before, j);
                if (Rune.IsWhiteSpace(r) || IsLetterOrDigit(r)) break;
                j = PrevIndex(before, j);
            }
        }
        return before[j..];
    }

    public static bool AutoCapitalize(string before)
    {
        if (before.Length == 0) return true;
        if (before[^1] == '\n') return true;
        if (!IsWs(before[^1])) return false;
        string t = before.TrimEnd();
        if (t.Length == 0) return true;
        if (t[^1] == '\n') return true;
        return IsSentenceEndChar(t[^1]);
    }

    /// <summary>Called when space is typed again within DOUBLE_SPACE: true if the last space becomes ". ".</summary>
    public static bool DoubleSpacePeriod(string before)
    {
        if (before.Length < 2 || before[^1] != ' ') return false;
        var c = RuneBefore(before, before.Length - 1);
        return IsLetterOrDigit(c) || (c.IsBmp && Closers.IndexOf((char)c.Value) >= 0);
    }

    public static bool CanApply(string beforeNow, string segment, string trailing, string typedSince)
        => beforeNow.EndsWith(segment + trailing + typedSince, StringComparison.Ordinal);

    /// <summary>True if <paramref name="text"/> contains a sentence end or newline (§4.3).</summary>
    public static bool ContainsSentenceEnd(string text)
    {
        foreach (var c in text)
            if (c == '\n' || IsSentenceEndChar(c)) return true;
        return false;
    }

    /// <summary>
    /// Whitespace or punctuation that completes a word (§5.3). The word-internal characters <c>' ’ - _</c> never
    /// complete a word, so "geht's" or "E-Mail" do not trigger in the middle of the word.
    /// </summary>
    public static bool IsWordBoundaryChar(char c) => (IsWs(c) || char.IsPunctuation(c)) && WordExtra.IndexOf(c) < 0;

    /// <summary>Upper-cases a key/alternative, special case ß → ẞ (§4.4).</summary>
    public static string UpperCaseKey(string s)
    {
        if (s == "ß") return "ẞ";
        return s.ToUpperInvariant();
    }

    /// <summary>The paragraph (text after the last newline) of <paramref name="before"/>.</summary>
    public static string LastParagraph(string before) => before[(before.LastIndexOf('\n') + 1)..];
}
