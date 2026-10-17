using Tippli.Core.Models;
using Tippli.Core.Text;

namespace Tippli.Core.Keyboard;

/// <summary>Long-press alternatives (SPEC §4.4).</summary>
public static class KeyAlternatives
{
    /// <summary>
    /// Alternatives = optional digit (first letter row, index &lt; 10, letters page) + accents of the lowercase key.
    /// With shift/caps every alternative is upper-cased (ß → ẞ).
    /// </summary>
    public static IReadOnlyList<string> For(KeyShape key, AccentsFile accents, bool digits, bool accentsEnabled, bool upper,
        KeyboardPage page = KeyboardPage.Letters)
    {
        if (key.IsSpecial) return Array.Empty<string>();
        var list = new List<string>();
        if (digits && page == KeyboardPage.Letters && key.RowKind == KeyRowKind.Letter && key.Row == 0 && key.Index < 10)
            list.Add(((key.Index + 1) % 10).ToString(System.Globalization.CultureInfo.InvariantCulture));
        if (accentsEnabled && accents.Accents.TryGetValue(key.Code.ToLowerInvariant(), out var acc))
        {
            foreach (var a in acc)
                if (!string.IsNullOrEmpty(a)) list.Add(a);
        }
        if (upper)
        {
            for (int i = 0; i < list.Count; i++) list[i] = TextAlgorithms.UpperCaseKey(list[i]);
        }
        return list;
    }

    /// <summary>The tiny long-press digit shown on hex keys of the first row (§3.6), or null.</summary>
    public static string? DigitHint(KeyShape key, KeyboardPage page)
        => page == KeyboardPage.Letters && key.RowKind == KeyRowKind.Letter && key.Row == 0 && key.Index < 10 && !key.IsSpecial
            ? ((key.Index + 1) % 10).ToString(System.Globalization.CultureInfo.InvariantCulture)
            : null;
}
