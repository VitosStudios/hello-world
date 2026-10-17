using System.Text.RegularExpressions;
using Tippli.Core.Engine;
using Tippli.Core.Models;
using Tippli.Core.Shared;

namespace Tippli.Core.Ai;

public enum AiTask
{
    Correction,
    Mood,
    Tips,
    Translate,
    Test,
}

/// <summary>A built prompt: system + user text, and whether a JSON object is expected.</summary>
public sealed record AiPrompt(AiTask Task, string System, string User, bool Json);

/// <summary>Which tips sections are enabled (§7.2, settings tips*).</summary>
public sealed record TipsSections(bool Rewrites = true, bool Explanations = true, bool Writing = true, bool Emotion = true);

/// <summary>Builds the prompts of SPEC §6.4 from shared/prompts. Placeholders are replaced literally in one pass.</summary>
public sealed partial class PromptBuilder
{
    private readonly SharedData _data;

    public PromptBuilder(SharedData data) => _data = data;

    [GeneratedRegex(@"\{\{([A-Za-z0-9_]+)\}\}")]
    private static partial Regex PlaceholderRegex();

    /// <summary>Replaces <c>{{name}}</c> placeholders literally (values are never re-scanned).</summary>
    public static string Fill(string template, IReadOnlyDictionary<string, string> values)
        => PlaceholderRegex().Replace(template, m => values.TryGetValue(m.Groups[1].Value, out var v) ? v : m.Value);

    /// <summary>
    /// <c>keyboardLayout</c> (§6.4): the keyboard's name + ": " + its three letter rows (normal variant) with only
    /// the plain character keys, rows joined with " / ".
    /// </summary>
    public static string KeyboardLayoutDescription(KeyboardDef keyboard)
    {
        var rows = keyboard.Normal.Rows
            .Take(3)
            .Select(r => string.Concat(r.Keys.Where(k => !k.IsSpecial && !k.K.StartsWith('$')).Select(k => k.K)));
        return keyboard.Name + ": " + string.Join(" / ", rows);
    }

    public string KeyboardLayoutDescription(string keyboardId)
        => _data.FindKeyboard(keyboardId) is { } kb ? KeyboardLayoutDescription(kb) : keyboardId;

    public AiPrompt Correction(Language language, string strength, string keyboardId, string context, string segment)
    {
        var parts = _data.PromptParts;
        var strengthRule = parts.StrengthRules.TryGetValue(strength, out var s) ? s
            : parts.StrengthRules.TryGetValue("normal", out var n) ? n : "";
        var system = Fill(_data.Prompts.CorrectionSystem, new Dictionary<string, string>
        {
            ["language"] = language.PromptName,
            ["languageRules"] = language.Rules,
            ["strengthRule"] = strengthRule,
            ["keyboardLayout"] = KeyboardLayoutDescription(keyboardId),
        });
        var user = Fill(_data.Prompts.CorrectionUser, new Dictionary<string, string>
        {
            ["context"] = context,
            ["segment"] = segment,
        });
        return new AiPrompt(AiTask.Correction, system, user, false);
    }

    /// <summary>Connection test: correction prompts with an empty context and <c>testSegment</c>.</summary>
    public AiPrompt Test(Language language, string strength, string keyboardId)
        => Correction(language, strength, keyboardId, "", _data.PromptParts.TestSegment) with { Task = AiTask.Test };

    public AiPrompt Tips(Language language, IEnumerable<string> enabledVariants, TipsSections sections, string text,
        IReadOnlyList<HistoryEntry> history)
    {
        var parts = _data.PromptParts;
        var enabled = new HashSet<string>(enabledVariants, StringComparer.OrdinalIgnoreCase);
        var variants = sections.Rewrites
            ? parts.RewriteVariants.Where(v => enabled.Contains(v.Id))
                .Select(v => $"{v.Id} (\"{v.Label}\": {v.Instruction})").ToList()
            : new List<string>();

        var disabled = new List<string>();
        string Section(string key, string fallback) => parts.Sections.TryGetValue(key, out var v) ? v : fallback;
        if (!sections.Rewrites || variants.Count == 0) disabled.Add(Section("rewrites", "rewrites"));
        if (!sections.Explanations) disabled.Add(Section("explanations", "explanations"));
        if (!sections.Writing) disabled.Add(Section("writing", "tips"));
        if (!sections.Emotion) disabled.Add(Section("emotion", "emotion"));

        var system = Fill(_data.Prompts.TipsSystem, new Dictionary<string, string>
        {
            ["uiLanguage"] = parts.UiLanguage,
            ["language"] = language.PromptName,
            ["variants"] = variants.Count > 0 ? string.Join(", ", variants) : "none",
            ["disabledSections"] = disabled.Count > 0 ? string.Join(", ", disabled) : "none",
        });
        var user = Fill(_data.Prompts.TipsUser, new Dictionary<string, string>
        {
            ["text"] = text,
            ["corrections"] = CorrectionsBlock(history),
        });
        return new AiPrompt(AiTask.Tips, system, user, true);
    }

    /// <summary>§5.8: lines <c>raw -> corrected</c>, newest last, max 20, or <c>(none)</c>.</summary>
    public static string CorrectionsBlock(IReadOnlyList<HistoryEntry> history)
    {
        var lines = history.TakeLast(CorrectionEngine.HistoryMax)
            .Where(h => !string.Equals(h.Raw.Trim(), h.Corrected.Trim(), StringComparison.Ordinal))
            .Select(h => $"{h.Raw.Trim()} -> {h.Corrected.Trim()}")
            .ToList();
        return lines.Count == 0 ? "(none)" : string.Join("\n", lines);
    }

    public AiPrompt Mood(string paragraph)
    {
        var system = Fill(_data.Prompts.MoodSystem, new Dictionary<string, string> { ["uiLanguage"] = _data.PromptParts.UiLanguage });
        return new AiPrompt(AiTask.Mood, system, paragraph, true);
    }

    public AiPrompt Translate(TranslateTarget target, string text)
    {
        var system = Fill(_data.Prompts.TranslateSystem, new Dictionary<string, string> { ["targetLanguage"] = target.PromptName });
        return new AiPrompt(AiTask.Translate, system, text, false);
    }
}
