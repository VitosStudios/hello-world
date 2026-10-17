using System.Text.Json.Nodes;

namespace Tippli.Core.Shared;

/// <summary>UI copy from shared/strings-de.json, addressed by dotted paths such as <c>tips.replace</c>.</summary>
public sealed class Strings
{
    private readonly JsonNode? _root;

    public Strings(string json)
    {
        try { _root = Json.ParseNode(json); }
        catch { _root = null; }
    }

    public static Strings Default => SharedData.Default.Strings;

    public string this[string path] => Get(path);

    /// <summary>The string at <paramref name="path"/>; <paramref name="fallback"/> (or the path) when missing.</summary>
    public string Get(string path, string? fallback = null)
    {
        var node = Find(path);
        if (node is JsonValue v && v.TryGetValue<string>(out var s)) return s;
        return fallback ?? path;
    }

    public bool Has(string path) => Find(path) is JsonValue;

    public IReadOnlyList<string> GetList(string path)
    {
        if (Find(path) is not JsonArray a) return Array.Empty<string>();
        var list = new List<string>(a.Count);
        foreach (var n in a)
            if (n is JsonValue v && v.TryGetValue<string>(out var s)) list.Add(s);
        return list;
    }

    private JsonNode? Find(string path)
    {
        var node = _root;
        foreach (var part in path.Split('.'))
        {
            if (node is not JsonObject o || !o.TryGetPropertyValue(part, out node)) return null;
        }
        return node;
    }
}
