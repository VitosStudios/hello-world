using System.Reflection;
using System.Text;

namespace Tippli.Core.Shared;

/// <summary>Reads the files of <c>shared/</c> that are embedded into Tippli.Core at build time (SPEC §2).</summary>
public static class SharedResources
{
    private static readonly Assembly Assembly = typeof(SharedResources).Assembly;

    /// <summary>Logical names of the embedded files, e.g. <c>layouts/layouts.json</c> or <c>prompts/tips-system.txt</c>.</summary>
    public static IReadOnlyList<string> Names => Assembly.GetManifestResourceNames();

    public static bool Exists(string logicalName) => Assembly.GetManifestResourceInfo(logicalName) != null;

    public static string ReadText(string logicalName)
    {
        using var stream = Assembly.GetManifestResourceStream(logicalName)
            ?? throw new FileNotFoundException($"Embedded shared resource '{logicalName}' not found.", logicalName);
        using var reader = new StreamReader(stream, new UTF8Encoding(false), detectEncodingFromByteOrderMarks: true);
        return reader.ReadToEnd();
    }
}
