using System.Text;

namespace Tippli.Core.Desktop;

/// <summary>
/// Our own dead-key composition (SPEC §9.3/§9.4): the dead key's spacing character + the next character are
/// composed via the combining mark and NFC; without a precomposed character the result is dead char + letter
/// (what Windows and macOS type in that case). Dead key + space = the accent itself.
/// </summary>
public static class DeadKeyComposer
{
    private static readonly Dictionary<char, char> CombiningMarks = new()
    {
        ['`'] = '̀',
        ['´'] = '́',
        ['\''] = '́', // US-International
        ['^'] = '̂',
        ['ˆ'] = '̂',
        ['~'] = '̃',
        ['˜'] = '̃',
        ['¯'] = '̄',
        ['˘'] = '̆',
        ['˙'] = '̇',
        ['¨'] = '̈',
        ['"'] = '̈', // US-International
        ['°'] = '̊',
        ['˚'] = '̊',
        ['˝'] = '̋',
        ['ˇ'] = '̌',
        ['¸'] = '̧',
        ['˛'] = '̨',
    };

    public static bool IsKnownDeadChar(string dead) => dead.Length == 1 && CombiningMarks.ContainsKey(dead[0]);

    public static string Compose(string dead, string next)
    {
        if (string.IsNullOrEmpty(dead)) return next ?? "";
        if (string.IsNullOrEmpty(next)) return dead;
        if (next == " " || next == " ") return dead;
        if (dead.Length == 1 && next.Length == 1 && CombiningMarks.TryGetValue(dead[0], out var mark))
        {
            var composed = (next + mark).Normalize(NormalizationForm.FormC);
            if (composed.IndexOf(mark) < 0) return composed;
        }
        return dead + next;
    }
}
