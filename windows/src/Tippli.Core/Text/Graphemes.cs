using System.Globalization;

namespace Tippli.Core.Text;

/// <summary>Grapheme (user-perceived character) helpers based on <see cref="StringInfo"/> (extended grapheme clusters).</summary>
public static class Graphemes
{
    public static int Count(string s) => string.IsNullOrEmpty(s) ? 0 : new StringInfo(s).LengthInTextElements;

    /// <summary>UTF-16 index where the last grapheme of <paramref name="s"/> starts (0 for an empty string).</summary>
    public static int LastStart(string s)
    {
        if (string.IsNullOrEmpty(s)) return 0;
        int idx = 0, last = 0;
        while (idx < s.Length)
        {
            last = idx;
            idx += Math.Max(1, StringInfo.GetNextTextElementLength(s, idx));
        }
        return last;
    }

    public static string RemoveLast(string s) => string.IsNullOrEmpty(s) ? "" : s[..LastStart(s)];

    /// <summary>True if <paramref name="index"/> is a grapheme boundary of <paramref name="s"/>.</summary>
    public static bool IsBoundary(string s, int index)
    {
        if (index <= 0 || index >= s.Length) return true;
        int i = 0;
        while (i < index) i += Math.Max(1, StringInfo.GetNextTextElementLength(s, i));
        return i == index;
    }

    public static List<string> Split(string s)
    {
        var list = new List<string>();
        if (string.IsNullOrEmpty(s)) return list;
        var e = StringInfo.GetTextElementEnumerator(s);
        while (e.MoveNext()) list.Add(e.GetTextElement());
        return list;
    }

    /// <summary>Keeps at most <paramref name="max"/> UTF-16 units from the end without splitting a grapheme.</summary>
    public static string TakeLast(string s, int max)
    {
        if (s.Length <= max) return s;
        int start = s.Length - max;
        while (start < s.Length && !IsBoundary(s, start)) start++;
        return s[start..];
    }
}
