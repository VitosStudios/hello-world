using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Tippli.Core.Models;

/// <summary>shared/layouts/layouts.json (SPEC §3).</summary>
public sealed class LayoutsFile
{
    public int Version { get; set; }
    public Dictionary<string, List<KeyDef>> BottomRows { get; set; } = new();
    public List<KeyDef> NumberRow { get; set; } = new();
    public Dictionary<string, KeyboardDef> Keyboards { get; set; } = new();
    public Dictionary<string, LayoutDef> SymbolPages { get; set; } = new();
}

public sealed class KeyboardDef
{
    public string Name { get; set; } = "";
    public Dictionary<string, string>? BottomPunctuation { get; set; }
    public LayoutDef Normal { get; set; } = new();
    public LayoutDef Hex { get; set; } = new();
}

public sealed class LayoutDef
{
    /// <summary>Total width in key units (U).</summary>
    public double Width { get; set; } = 10;
    public List<RowDef> Rows { get; set; } = new();
}

public sealed class RowDef
{
    public double Indent { get; set; }
    public List<KeyDef> Keys { get; set; } = new();
}

/// <summary>A key: either a plain string (width 1) or <c>{"k": key, "w": widthUnits}</c>.</summary>
[JsonConverter(typeof(KeyDefConverter))]
public sealed class KeyDef
{
    public KeyDef() { }

    public KeyDef(string k, double w = 1)
    {
        K = k;
        W = w;
    }

    public string K { get; set; } = "";
    public double W { get; set; } = 1;

    /// <summary>Keys starting with <c>$</c> are special keys ($shift, $space, …).</summary>
    public bool IsSpecial => K.Length > 1 && K[0] == '$';

    public override string ToString() => W == 1 ? K : $"{K}({W.ToString(CultureInfo.InvariantCulture)})";
}

public sealed class KeyDefConverter : JsonConverter<KeyDef>
{
    public override KeyDef Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        switch (reader.TokenType)
        {
            case JsonTokenType.String:
                return new KeyDef(reader.GetString() ?? "");
            case JsonTokenType.Number:
                return new KeyDef(reader.GetDouble().ToString(CultureInfo.InvariantCulture));
            case JsonTokenType.StartObject:
                var key = new KeyDef();
                while (reader.Read())
                {
                    if (reader.TokenType == JsonTokenType.EndObject) return key;
                    if (reader.TokenType != JsonTokenType.PropertyName) continue;
                    var name = reader.GetString() ?? "";
                    reader.Read();
                    if (name.Equals("k", StringComparison.OrdinalIgnoreCase))
                    {
                        key.K = reader.TokenType switch
                        {
                            JsonTokenType.String => reader.GetString() ?? "",
                            JsonTokenType.Number => reader.GetDouble().ToString(CultureInfo.InvariantCulture),
                            _ => key.K,
                        };
                    }
                    else if (name.Equals("w", StringComparison.OrdinalIgnoreCase))
                    {
                        if (reader.TokenType == JsonTokenType.Number) key.W = reader.GetDouble();
                        else if (reader.TokenType == JsonTokenType.String &&
                                 double.TryParse(reader.GetString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var w))
                            key.W = w;
                    }
                    else
                    {
                        reader.Skip();
                    }
                }
                throw new JsonException("Unterminated key object");
            default:
                throw new JsonException($"Invalid key token {reader.TokenType}");
        }
    }

    public override void Write(Utf8JsonWriter writer, KeyDef value, JsonSerializerOptions options)
    {
        if (value.W == 1)
        {
            writer.WriteStringValue(value.K);
            return;
        }
        writer.WriteStartObject();
        writer.WriteString("k", value.K);
        writer.WriteNumber("w", value.W);
        writer.WriteEndObject();
    }
}

/// <summary>shared/layouts/accents.json (SPEC §4.4).</summary>
public sealed class AccentsFile
{
    public int Version { get; set; }
    public Dictionary<string, List<string>> Accents { get; set; } = new();
}
