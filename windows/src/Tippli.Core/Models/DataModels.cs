using System.Globalization;

namespace Tippli.Core.Models;

/// <summary>shared/languages.json (SPEC §5.9).</summary>
public sealed class LanguagesFile
{
    public int Version { get; set; }
    public List<Language> Languages { get; set; } = new();
    public List<TranslateTarget> TranslateTargets { get; set; } = new();
}

public sealed class Language
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Short { get; set; } = "";
    public string Keyboard { get; set; } = "qwertz";
    public string PromptName { get; set; } = "";
    public string Rules { get; set; } = "";
    public bool Pinyin { get; set; }
}

public sealed class TranslateTarget
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string PromptName { get; set; } = "";
}

/// <summary>shared/providers.json (SPEC §6.1).</summary>
public sealed class ProvidersFile
{
    public int Version { get; set; }
    public List<ProviderPreset> Providers { get; set; } = new();
}

public sealed class ProviderPreset
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    /// <summary><c>anthropic</c> or <c>openai</c>.</summary>
    public string Protocol { get; set; } = "openai";
    public string BaseUrl { get; set; } = "";
    public bool BaseUrlEditable { get; set; }
    public string DefaultModel { get; set; } = "";
    public List<ModelOption> Models { get; set; } = new();
    public string KeyUrl { get; set; } = "";
    public string KeyHint { get; set; } = "";
    public bool KeyRequired { get; set; } = true;
    public bool JsonMode { get; set; }
    public string MaxTokensField { get; set; } = "max_tokens";
    public bool SendTemperature { get; set; }
    public string? ReasoningEffort { get; set; }
    public Dictionary<string, string>? ExtraHeaders { get; set; }

    public bool IsAnthropic => string.Equals(Protocol, "anthropic", StringComparison.OrdinalIgnoreCase);

    /// <summary>Short display name for the toolbar ("Claude (Anthropic)" → "Claude").</summary>
    public string ShortName
    {
        get
        {
            var n = Name;
            foreach (var sep in new[] { " (", " /", " –", " -" })
            {
                var i = n.IndexOf(sep, StringComparison.Ordinal);
                if (i > 0) n = n[..i];
            }
            return n.Trim();
        }
    }
}

public sealed class ModelOption
{
    public string Id { get; set; } = "";
    public string Label { get; set; } = "";
}

/// <summary>shared/emoji.json (SPEC §4.9).</summary>
public sealed class EmojiFile
{
    public int Version { get; set; }
    public List<EmojiCategory> Categories { get; set; } = new();
}

public sealed class EmojiCategory
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Icon { get; set; } = "";
    public List<string> Emoji { get; set; } = new();
}

/// <summary>shared/prompts/prompt-parts.json (SPEC §6.4).</summary>
public sealed class PromptParts
{
    public int Version { get; set; }
    public string UiLanguage { get; set; } = "German";
    public Dictionary<string, string> StrengthRules { get; set; } = new();
    public List<RewriteVariant> RewriteVariants { get; set; } = new();
    public Dictionary<string, string> Sections { get; set; } = new();
    public string TestSegment { get; set; } = "hallo wie gehs dir hzete?";
}

public sealed class RewriteVariant
{
    public string Id { get; set; } = "";
    public string Label { get; set; } = "";
    public string Instruction { get; set; } = "";
}

/// <summary>The prompt templates of shared/prompts/*.txt.</summary>
public sealed record PromptTemplates(
    string CorrectionSystem,
    string CorrectionUser,
    string MoodSystem,
    string TipsSystem,
    string TipsUser,
    string TranslateSystem);

/// <summary>shared/theme.json (SPEC §7.4).</summary>
public sealed class ThemeFile
{
    public int Version { get; set; }
    public Dictionary<string, string> Brand { get; set; } = new();
    public Dictionary<string, string> Light { get; set; } = new();
    public Dictionary<string, string> Dark { get; set; } = new();
    public ThemeMetrics Metrics { get; set; } = new();

    public ThemePalette Palette(bool dark) => new(dark ? Dark : Light, dark);
}

public sealed class ThemeMetrics
{
    public double ToolbarHeight { get; set; } = 40;
    public Dictionary<string, double> RowHeight { get; set; } = new() { ["small"] = 46, ["medium"] = 52, ["large"] = 58 };
    public double KeyGap { get; set; } = 5;
    public double HexGap { get; set; } = 3;
    public double KeyCornerRadius { get; set; } = 7;
    public double HexCornerRadius { get; set; } = 5;
    public double KeyLabelSize { get; set; } = 22;
    public double SpecialLabelSize { get; set; } = 15;
    public double SidePadding { get; set; } = 3;
    public double BottomPadding { get; set; } = 4;

    public double RowHeightFor(string keyHeight)
        => RowHeight.TryGetValue(keyHeight, out var h) ? h : RowHeight.TryGetValue("medium", out var m) ? m : 52;
}

/// <summary>An sRGB colour parsed from <c>#RRGGBB</c> or <c>#RRGGBBAA</c>.</summary>
public readonly record struct ThemeColor(byte A, byte R, byte G, byte B)
{
    public static ThemeColor Parse(string value)
        => TryParse(value, out var c) ? c : throw new FormatException($"Invalid colour '{value}'");

    public static bool TryParse(string? value, out ThemeColor color)
    {
        color = default;
        if (string.IsNullOrWhiteSpace(value)) return false;
        var s = value.Trim().TrimStart('#');
        if (s.Length != 6 && s.Length != 8) return false;
        if (!uint.TryParse(s, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var v)) return false;
        color = s.Length == 6
            ? new ThemeColor(255, (byte)(v >> 16), (byte)(v >> 8), (byte)v)
            : new ThemeColor((byte)v, (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8));
        return true;
    }

    public override string ToString() => $"#{R:X2}{G:X2}{B:X2}{A:X2}";
}

/// <summary>Named colours of one theme variant with safe fallbacks.</summary>
public sealed class ThemePalette
{
    private readonly Dictionary<string, string> _values;

    public ThemePalette(Dictionary<string, string> values, bool isDark)
    {
        _values = values;
        IsDark = isDark;
    }

    public bool IsDark { get; }

    public IEnumerable<string> Names => _values.Keys;

    public ThemeColor Get(string name)
    {
        if (_values.TryGetValue(name, out var v) && ThemeColor.TryParse(v, out var c)) return c;
        return IsDark ? new ThemeColor(255, 0x2A, 0x2C, 0x31) : new ThemeColor(255, 0xFF, 0xFF, 0xFF);
    }

    public ThemeColor Background => Get("background");
    public ThemeColor Toolbar => Get("toolbar");
    public ThemeColor Key => Get("key");
    public ThemeColor KeyPressed => Get("keyPressed");
    public ThemeColor SpecialKey => Get("specialKey");
    public ThemeColor SpaceKey => Get("spaceKey");
    public ThemeColor KeyText => Get("keyText");
    public ThemeColor SecondaryText => Get("secondaryText");
    public ThemeColor Accent => Get("accent");
    public ThemeColor AccentText => Get("accentText");
    public ThemeColor Popup => Get("popup");
    public ThemeColor PopupSelected => Get("popupSelected");
    public ThemeColor KeyShadow => Get("keyShadow");
    public ThemeColor Warning => Get("warning");
    public ThemeColor Danger => Get("danger");
    public ThemeColor Success => Get("success");
    public ThemeColor Panel => Get("panel");
    public ThemeColor Card => Get("card");
    public ThemeColor Divider => Get("divider");
}
