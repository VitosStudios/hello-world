using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Tippli.Core.Shared;

/// <summary>Tolerant JSON settings used for every shared file and every API response.</summary>
public static class Json
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>Options for files we write (settings): readable and without escaping of umlauts.</summary>
    public static readonly JsonSerializerOptions WriteOptions = new()
    {
        WriteIndented = true,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static readonly JsonDocumentOptions DocumentOptions = new()
    {
        AllowTrailingCommas = true,
        CommentHandling = JsonCommentHandling.Skip,
    };

    public static readonly JsonNodeOptions NodeOptions = new() { PropertyNameCaseInsensitive = false };

    public static T Deserialize<T>(string json) where T : new()
        => JsonSerializer.Deserialize<T>(json, Options) ?? new T();

    public static JsonNode? ParseNode(string json) => JsonNode.Parse(json, NodeOptions, DocumentOptions);

    /// <summary>Compact serialization without unicode escaping (request bodies).</summary>
    public static string Serialize(JsonNode node) => node.ToJsonString(new JsonSerializerOptions
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    });

    public static string? GetString(this JsonElement e, string name)
    {
        if (e.ValueKind != JsonValueKind.Object) return null;
        foreach (var p in e.EnumerateObject())
        {
            if (!string.Equals(p.Name, name, StringComparison.OrdinalIgnoreCase)) continue;
            return p.Value.ValueKind switch
            {
                JsonValueKind.String => p.Value.GetString(),
                JsonValueKind.Number => p.Value.GetRawText(),
                JsonValueKind.True => "true",
                JsonValueKind.False => "false",
                _ => null,
            };
        }
        return null;
    }

    public static JsonElement? GetProperty(this JsonElement e, string name, bool ignoreCase)
    {
        if (e.ValueKind != JsonValueKind.Object) return null;
        foreach (var p in e.EnumerateObject())
        {
            if (string.Equals(p.Name, name, ignoreCase ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
                return p.Value;
        }
        return null;
    }
}
