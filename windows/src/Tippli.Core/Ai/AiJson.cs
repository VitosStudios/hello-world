using System.Globalization;
using System.Text.Json;
using Tippli.Core.Shared;

namespace Tippli.Core.Ai;

public sealed record MoodResult(int Level, string Reason);

public sealed record TipsRewrite(string Id, string Label, string Text);

public sealed record TipsExplanation(string From, string To, string Why);

public sealed record TipsEmotion(int Level, string Label, string Reason, string Calmer);

public sealed record TipsResult(
    IReadOnlyList<TipsRewrite> Rewrites,
    IReadOnlyList<TipsExplanation> Explanations,
    IReadOnlyList<string> Tips,
    TipsEmotion Emotion);

/// <summary>JSON parsing for tips and mood answers (SPEC §6.5).</summary>
public static class AiJson
{
    /// <summary>Removes code fences, takes the text from the first '{' to the last '}' and parses it.</summary>
    public static JsonDocument ExtractObject(string text)
    {
        var t = (text ?? "").Replace("```json", "", StringComparison.OrdinalIgnoreCase).Replace("```", "");
        int a = t.IndexOf('{');
        int b = t.LastIndexOf('}');
        if (a < 0 || b <= a) throw new AiException(AiErrorKind.BadResponse, "no JSON object");
        try
        {
            var doc = JsonDocument.Parse(t[a..(b + 1)], Json.DocumentOptions);
            if (doc.RootElement.ValueKind != JsonValueKind.Object)
            {
                doc.Dispose();
                throw new AiException(AiErrorKind.BadResponse, "JSON is not an object");
            }
            return doc;
        }
        catch (JsonException ex)
        {
            throw new AiException(AiErrorKind.BadResponse, ex.Message, inner: ex);
        }
    }

    public static MoodResult ParseMood(string text)
    {
        using var doc = ExtractObject(text);
        var root = doc.RootElement;
        return new MoodResult(ReadLevel(root, "level"), root.GetString("reason") ?? "");
    }

    public static TipsResult ParseTips(string text)
    {
        using var doc = ExtractObject(text);
        var root = doc.RootElement;

        var rewrites = new List<TipsRewrite>();
        foreach (var e in Array(root, "rewrites"))
        {
            if (e.ValueKind != JsonValueKind.Object) continue;
            var t = e.GetString("text") ?? "";
            if (t.Trim().Length == 0) continue;
            rewrites.Add(new TipsRewrite(e.GetString("id") ?? "", e.GetString("label") ?? "", t));
        }

        var explanations = new List<TipsExplanation>();
        foreach (var e in Array(root, "explanations"))
        {
            if (e.ValueKind != JsonValueKind.Object) continue;
            explanations.Add(new TipsExplanation(e.GetString("from") ?? "", e.GetString("to") ?? "", e.GetString("why") ?? ""));
        }

        var tips = new List<string>();
        foreach (var e in Array(root, "tips"))
        {
            var s = e.ValueKind == JsonValueKind.String ? e.GetString() : e.ValueKind == JsonValueKind.Object ? e.GetString("text") : null;
            if (!string.IsNullOrWhiteSpace(s)) tips.Add(s!);
        }

        var emotion = new TipsEmotion(0, "", "", "");
        if (root.GetProperty("emotion", true) is { ValueKind: JsonValueKind.Object } em)
        {
            emotion = new TipsEmotion(ReadLevel(em, "level"), em.GetString("label") ?? "", em.GetString("reason") ?? "",
                em.GetString("calmer") ?? "");
        }
        return new TipsResult(rewrites, explanations, tips, emotion);
    }

    private static IEnumerable<JsonElement> Array(JsonElement root, string name)
    {
        if (root.GetProperty(name, true) is { ValueKind: JsonValueKind.Array } a)
            foreach (var e in a.EnumerateArray()) yield return e;
    }

    private static int ReadLevel(JsonElement e, string name)
    {
        var s = e.GetString(name);
        if (s != null && double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var d))
            return Math.Clamp((int)Math.Round(d), 0, 3);
        return 0;
    }
}
