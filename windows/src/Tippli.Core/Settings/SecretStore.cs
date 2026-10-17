namespace Tippli.Core.Settings;

/// <summary>Secure storage for API keys (<c>apiKey.&lt;providerId&gt;</c>, SPEC §8). Windows: DPAPI.</summary>
public interface ISecretStore
{
    string? Get(string key);
    /// <summary>Stores the value; null or empty removes it.</summary>
    void Set(string key, string? value);
}

public static class SecretKeys
{
    public static string ApiKey(string providerId) => "apiKey." + providerId;
}

/// <summary>Non-persistent store (tests, fallback when DPAPI is unavailable).</summary>
public sealed class InMemorySecretStore : ISecretStore
{
    private readonly Dictionary<string, string> _values = new(StringComparer.Ordinal);

    public string? Get(string key) => _values.TryGetValue(key, out var v) ? v : null;

    public void Set(string key, string? value)
    {
        if (string.IsNullOrEmpty(value)) _values.Remove(key);
        else _values[key] = value;
    }
}
