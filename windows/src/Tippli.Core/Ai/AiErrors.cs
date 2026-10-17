namespace Tippli.Core.Ai;

/// <summary>Error keys of SPEC §6.7 (<c>strings.errors.*</c>).</summary>
public enum AiErrorKind
{
    NoKey,
    InvalidKey,
    BadModel,
    RateLimited,
    Server,
    Network,
    Timeout,
    Refused,
    BadResponse,
}

public sealed class AiException : Exception
{
    public AiException(AiErrorKind kind, string? detail = null, int? statusCode = null, Exception? inner = null)
        : base(detail is null ? kind.ToString() : $"{kind}: {detail}", inner)
    {
        Kind = kind;
        Detail = detail;
        StatusCode = statusCode;
    }

    public AiErrorKind Kind { get; }
    public string? Detail { get; }
    public int? StatusCode { get; }
}

public static class AiErrors
{
    /// <summary>Maps an HTTP status code to the error key (§6.7). 400/422 and other 4xx → badResponse.</summary>
    public static AiErrorKind FromStatus(int status) => status switch
    {
        401 or 403 => AiErrorKind.InvalidKey,
        404 => AiErrorKind.BadModel,
        429 => AiErrorKind.RateLimited,
        >= 500 => AiErrorKind.Server,
        _ => AiErrorKind.BadResponse,
    };

    /// <summary>The key in <c>strings-de.json</c> ("errors.noKey", …).</summary>
    public static string StringKey(AiErrorKind kind)
    {
        var name = kind.ToString();
        return "errors." + char.ToLowerInvariant(name[0]) + name[1..];
    }

    public static string Message(AiErrorKind kind) => Shared.Strings.Default.Get(StringKey(kind), kind.ToString());
}
