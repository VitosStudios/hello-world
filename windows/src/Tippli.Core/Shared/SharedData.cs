using System.Text.Json.Nodes;
using Tippli.Core.Models;

namespace Tippli.Core.Shared;

/// <summary>All shared resources of <c>shared/</c>, parsed once.</summary>
public sealed class SharedData
{
    private static readonly Lazy<SharedData> LazyDefault = new(FromEmbeddedResources, isThreadSafe: true);

    /// <summary>Data from the resources embedded into Tippli.Core.</summary>
    public static SharedData Default => LazyDefault.Value;

    public required LayoutsFile Layouts { get; init; }
    public required AccentsFile Accents { get; init; }
    public required LanguagesFile Languages { get; init; }
    public required ProvidersFile Providers { get; init; }
    public required EmojiFile Emoji { get; init; }
    public required ThemeFile Theme { get; init; }
    public required PromptParts PromptParts { get; init; }
    public required PromptTemplates Prompts { get; init; }
    public required JsonObject SettingsDefaults { get; init; }
    public required JsonObject AllowedValues { get; init; }
    public required Strings Strings { get; init; }

    public static SharedData FromEmbeddedResources() => Load(SharedResources.ReadText);

    /// <summary>Loads everything through <paramref name="read"/> (logical name → file content).</summary>
    public static SharedData Load(Func<string, string> read)
    {
        var settingsRoot = Json.ParseNode(read("settings-defaults.json"))?.AsObject() ?? new JsonObject();
        return new SharedData
        {
            Layouts = Json.Deserialize<LayoutsFile>(read("layouts/layouts.json")),
            Accents = Json.Deserialize<AccentsFile>(read("layouts/accents.json")),
            Languages = Json.Deserialize<LanguagesFile>(read("languages.json")),
            Providers = Json.Deserialize<ProvidersFile>(read("providers.json")),
            Emoji = Json.Deserialize<EmojiFile>(read("emoji.json")),
            Theme = Json.Deserialize<ThemeFile>(read("theme.json")),
            PromptParts = Json.Deserialize<PromptParts>(read("prompts/prompt-parts.json")),
            Prompts = new PromptTemplates(
                read("prompts/correction-system.txt"),
                read("prompts/correction-user.txt"),
                read("prompts/mood-system.txt"),
                read("prompts/tips-system.txt"),
                read("prompts/tips-user.txt"),
                read("prompts/translate-system.txt")),
            SettingsDefaults = settingsRoot["settings"] as JsonObject ?? new JsonObject(),
            AllowedValues = settingsRoot["allowedValues"] as JsonObject ?? new JsonObject(),
            Strings = new Strings(read("strings-de.json")),
        };
    }

    public Language? FindLanguage(string? id)
        => Languages.Languages.FirstOrDefault(l => string.Equals(l.Id, id, StringComparison.OrdinalIgnoreCase));

    public ProviderPreset? FindProvider(string? id)
        => Providers.Providers.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase));

    public KeyboardDef? FindKeyboard(string? id)
        => id != null && Layouts.Keyboards.TryGetValue(id, out var kb) ? kb : null;

    public TranslateTarget? FindTranslateTarget(string? id)
        => Languages.TranslateTargets.FirstOrDefault(t => string.Equals(t.Id, id, StringComparison.OrdinalIgnoreCase));

    /// <summary>Allowed string values for a setting (e.g. <c>trigger</c>), empty when unrestricted.</summary>
    public IReadOnlyList<string> AllowedStrings(string key)
        => AllowedValues[key] is JsonArray a
            ? a.Select(n => n?.GetValue<string>() ?? "").Where(s => s.Length > 0).ToList()
            : Array.Empty<string>();

    /// <summary>Allowed numeric range for a setting (e.g. <c>pauseMs</c>).</summary>
    public (double Min, double Max)? AllowedRange(string key)
    {
        if (AllowedValues[key] is not JsonObject o) return null;
        var min = o["min"] is JsonValue mn && mn.TryGetValue<double>(out var a) ? a : double.MinValue;
        var max = o["max"] is JsonValue mx && mx.TryGetValue<double>(out var b) ? b : double.MaxValue;
        return (min, max);
    }
}
