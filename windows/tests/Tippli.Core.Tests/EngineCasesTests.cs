using System.Text.Json;
using Tippli.Core.Text;

namespace Tippli.Core.Tests;

/// <summary>All vectors of shared/tests/engine-cases.json (SPEC §2, §5.2–5.6).</summary>
public class EngineCasesTests
{
    private static readonly Lazy<JsonDocument> Cases = new(() =>
        JsonDocument.Parse(File.ReadAllText(TestPaths.SharedFile("tests", "engine-cases.json"))));

    private static IEnumerable<JsonElement> Category(string name) => Cases.Value.RootElement.GetProperty(name).EnumerateArray();

    public static IEnumerable<object[]> Indices(string name)
        => Category(name).Select((_, i) => new object[] { i });

    private static JsonElement Case(string name, int index) => Category(name).ElementAt(index);

    private static string? Str(JsonElement e) => e.ValueKind == JsonValueKind.Null ? null : e.GetString();

    [Fact]
    public void EveryCategoryHasCases()
    {
        foreach (var name in new[] { "selectSegment", "cleanOutput", "computeEdit", "wordDelete", "autoCapitalize", "doubleSpacePeriod", "canApply" })
            Assert.NotEmpty(Category(name));
    }

    [Theory]
    [MemberData(nameof(Indices), "selectSegment")]
    public void SelectSegment(int index)
    {
        var c = Case("selectSegment", index);
        var result = TextAlgorithms.SelectSegment(c.GetProperty("before").GetString()!, c.GetProperty("explicit").GetBoolean(),
            c.GetProperty("pinyin").GetBoolean());
        var expected = c.GetProperty("expected");
        if (expected.ValueKind == JsonValueKind.Null)
        {
            Assert.Null(result);
            return;
        }
        Assert.NotNull(result);
        Assert.Equal(expected.GetProperty("context").GetString(), result!.Context);
        Assert.Equal(expected.GetProperty("segment").GetString(), result.Segment);
        Assert.Equal(expected.GetProperty("trailing").GetString(), result.Trailing);
    }

    [Theory]
    [MemberData(nameof(Indices), "cleanOutput")]
    public void CleanOutput(int index)
    {
        var c = Case("cleanOutput", index);
        var result = TextAlgorithms.CleanOutput(c.GetProperty("segment").GetString()!, c.GetProperty("output").GetString(),
            c.GetProperty("pinyin").GetBoolean());
        Assert.Equal(Str(c.GetProperty("expected")), result);
    }

    [Theory]
    [MemberData(nameof(Indices), "computeEdit")]
    public void ComputeEdit(int index)
    {
        var c = Case("computeEdit", index);
        var result = TextAlgorithms.ComputeEdit(c.GetProperty("segment").GetString()!, c.GetProperty("corrected").GetString()!,
            c.GetProperty("suffix").GetString()!);
        var expected = c.GetProperty("expected");
        if (expected.ValueKind == JsonValueKind.Null)
        {
            Assert.Null(result);
            return;
        }
        Assert.NotNull(result);
        Assert.Equal(expected.GetProperty("delete").GetString(), result!.Delete);
        Assert.Equal(expected.GetProperty("insert").GetString(), result.Insert);
    }

    [Theory]
    [MemberData(nameof(Indices), "wordDelete")]
    public void WordDelete(int index)
    {
        var c = Case("wordDelete", index);
        Assert.Equal(c.GetProperty("expected").GetString(), TextAlgorithms.WordDelete(c.GetProperty("before").GetString()!));
    }

    [Theory]
    [MemberData(nameof(Indices), "autoCapitalize")]
    public void AutoCapitalize(int index)
    {
        var c = Case("autoCapitalize", index);
        Assert.Equal(c.GetProperty("expected").GetBoolean(), TextAlgorithms.AutoCapitalize(c.GetProperty("before").GetString()!));
    }

    [Theory]
    [MemberData(nameof(Indices), "doubleSpacePeriod")]
    public void DoubleSpacePeriod(int index)
    {
        var c = Case("doubleSpacePeriod", index);
        Assert.Equal(c.GetProperty("expected").GetBoolean(), TextAlgorithms.DoubleSpacePeriod(c.GetProperty("before").GetString()!));
    }

    [Theory]
    [MemberData(nameof(Indices), "canApply")]
    public void CanApply(int index)
    {
        var c = Case("canApply", index);
        Assert.Equal(c.GetProperty("expected").GetBoolean(), TextAlgorithms.CanApply(c.GetProperty("beforeNow").GetString()!,
            c.GetProperty("segment").GetString()!, c.GetProperty("trailing").GetString()!, c.GetProperty("typedSince").GetString()!));
    }

    [Fact]
    public void ComputeEditNeverSplitsGraphemes()
    {
        // "e" + combining acute vs. "e" + combining grave: the shared prefix "e" is not a grapheme boundary.
        var edit = TextAlgorithms.ComputeEdit("café ", "cafè ", "");
        Assert.NotNull(edit);
        Assert.Equal("é ", edit!.Delete);
        Assert.Equal("è ", edit.Insert);
    }

    [Fact]
    public void WordDeleteHandlesSupplementaryLetters()
    {
        Assert.Equal("𠀀𠀁", TextAlgorithms.WordDelete("ab 𠀀𠀁"));
    }
}
