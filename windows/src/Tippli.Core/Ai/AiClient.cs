using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Tippli.Core.Models;
using Tippli.Core.Shared;

namespace Tippli.Core.Ai;

/// <summary>Everything needed to talk to one provider/model.</summary>
public sealed record ProviderConfig(ProviderPreset Preset, string Model, string BaseUrl, string ApiKey);

/// <summary>Anthropic per-model request profile (SPEC §6.2).</summary>
public sealed record AnthropicProfile(string? ThinkingType, bool SendEffort, bool Fallbacks, string? BetaHeader);

/// <summary>
/// HTTP client for the two protocols of SPEC §6: Anthropic Messages and OpenAI-compatible Chat Completions,
/// including the per-model profiles, the optional-parameter fallback ("plain mode"), error mapping and refusals.
/// </summary>
public sealed class AiClient
{
    public const string AnthropicVersion = "2023-06-01";
    public const string ServerSideFallbackBeta = "server-side-fallback-2026-07-01";

    /// <summary>provider|model combinations that rejected optional parameters (process lifetime, §6.3).</summary>
    private static readonly ConcurrentDictionary<string, byte> PlainModes = new(StringComparer.OrdinalIgnoreCase);

    private readonly HttpClient _http;

    public AiClient(HttpClient http) => _http = http;

    public static void ResetPlainModes() => PlainModes.Clear();

    public static bool IsPlainMode(string providerId, string model) => PlainModes.ContainsKey(providerId + "|" + model);

    public static int MaxTokens(AiTask task) => task switch
    {
        AiTask.Mood => 1024,
        AiTask.Tips => 8000,
        AiTask.Translate => 4000,
        _ => 2048, // correction + connection test
    };

    public static string Effort(AiTask task) => task is AiTask.Tips or AiTask.Translate ? "medium" : "low";

    public static TimeSpan DefaultTimeout(AiTask task) => task switch
    {
        AiTask.Correction => TimeSpan.FromSeconds(10),
        AiTask.Mood => TimeSpan.FromSeconds(15),
        AiTask.Test => TimeSpan.FromSeconds(20),
        _ => TimeSpan.FromSeconds(45),
    };

    public static AnthropicProfile AnthropicProfileFor(string model)
    {
        var m = (model ?? "").Trim().ToLowerInvariant();
        if (m.StartsWith("claude-opus-5", StringComparison.Ordinal) || m.StartsWith("claude-fable-5", StringComparison.Ordinal))
            return new AnthropicProfile(null, true, true, ServerSideFallbackBeta);
        if (m.StartsWith("claude-sonnet-5-5", StringComparison.Ordinal))
            return new AnthropicProfile("between_tools", true, true, ServerSideFallbackBeta);
        if (m.StartsWith("claude-haiku-5-5", StringComparison.Ordinal))
            return new AnthropicProfile("disabled", true, false, null);
        return new AnthropicProfile(null, false, false, null);
    }

    public static bool UsesReasoningEffort(ProviderPreset preset, string model)
    {
        if (string.IsNullOrWhiteSpace(preset.ReasoningEffort)) return false;
        var m = (model ?? "").Trim().ToLowerInvariant();
        return m.StartsWith("gpt-5", StringComparison.Ordinal) || m.StartsWith('o');
    }

    public static bool HasOptionalParameters(ProviderPreset preset, string model, AiPrompt prompt)
        => preset.SendTemperature || UsesReasoningEffort(preset, model) || (prompt.Json && preset.JsonMode);

    private static string Combine(string baseUrl, string path) => (baseUrl ?? "").TrimEnd('/') + "/" + path;

    // ---- completion -----------------------------------------------------------------------------------

    public async Task<string> CompleteAsync(ProviderConfig cfg, AiPrompt prompt, CancellationToken cancellationToken = default,
        TimeSpan? timeout = null)
    {
        if (cfg.Preset.KeyRequired && string.IsNullOrWhiteSpace(cfg.ApiKey)) throw new AiException(AiErrorKind.NoKey);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(timeout ?? DefaultTimeout(prompt.Task));
        try
        {
            return cfg.Preset.IsAnthropic
                ? await AnthropicAsync(cfg, prompt, cts.Token).ConfigureAwait(false)
                : await OpenAiAsync(cfg, prompt, cts.Token).ConfigureAwait(false);
        }
        catch (AiException)
        {
            throw;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new AiException(AiErrorKind.Timeout);
        }
        catch (HttpRequestException ex)
        {
            throw new AiException(AiErrorKind.Network, ex.Message, inner: ex);
        }
    }

    public static HttpRequestMessage BuildAnthropicRequest(ProviderConfig cfg, AiPrompt prompt)
    {
        var body = new JsonObject
        {
            ["model"] = cfg.Model,
            ["max_tokens"] = MaxTokens(prompt.Task),
            ["system"] = prompt.System,
            ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = prompt.User }),
        };
        var profile = AnthropicProfileFor(cfg.Model);
        if (profile.ThinkingType != null) body["thinking"] = new JsonObject { ["type"] = profile.ThinkingType };
        if (profile.SendEffort) body["output_config"] = new JsonObject { ["effort"] = Effort(prompt.Task) };
        if (profile.Fallbacks) body["fallbacks"] = "default";
        // Never temperature/top_p for Claude models (§6.2).

        var request = new HttpRequestMessage(HttpMethod.Post, Combine(cfg.BaseUrl, "messages"));
        request.Headers.TryAddWithoutValidation("x-api-key", cfg.ApiKey ?? "");
        request.Headers.TryAddWithoutValidation("anthropic-version", AnthropicVersion);
        if (profile.BetaHeader != null) request.Headers.TryAddWithoutValidation("anthropic-beta", profile.BetaHeader);
        request.Content = new StringContent(Json.Serialize(body), Encoding.UTF8, "application/json");
        return request;
    }

    private async Task<string> AnthropicAsync(ProviderConfig cfg, AiPrompt prompt, CancellationToken ct)
    {
        using var request = BuildAnthropicRequest(cfg, prompt);
        using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw StatusError((int)response.StatusCode, text);
        return ParseAnthropicResponse(text);
    }

    public static string ParseAnthropicResponse(string json)
    {
        using var doc = ParseDocument(json);
        var root = doc.RootElement;
        if (root.GetString("stop_reason") == "refusal") throw new AiException(AiErrorKind.Refused);
        if (root.GetProperty("content", false) is not { ValueKind: JsonValueKind.Array } content)
            throw new AiException(AiErrorKind.BadResponse, "missing content");
        var sb = new StringBuilder();
        foreach (var block in content.EnumerateArray())
        {
            if (block.GetString("type") == "text") sb.Append(block.GetString("text"));
        }
        return sb.ToString();
    }

    public static HttpRequestMessage BuildOpenAiRequest(ProviderConfig cfg, AiPrompt prompt, bool plain)
    {
        var p = cfg.Preset;
        int maxTokens = MaxTokens(prompt.Task);
        if (!string.IsNullOrWhiteSpace(p.ReasoningEffort)) maxTokens = Math.Max(maxTokens, 4000);
        var body = new JsonObject
        {
            ["model"] = cfg.Model,
            ["messages"] = new JsonArray(
                new JsonObject { ["role"] = "system", ["content"] = prompt.System },
                new JsonObject { ["role"] = "user", ["content"] = prompt.User }),
        };
        body[string.IsNullOrWhiteSpace(p.MaxTokensField) ? "max_tokens" : p.MaxTokensField] = maxTokens;
        if (!plain)
        {
            if (p.SendTemperature) body["temperature"] = 0.2;
            if (UsesReasoningEffort(p, cfg.Model)) body["reasoning_effort"] = p.ReasoningEffort;
            if (prompt.Json && p.JsonMode) body["response_format"] = new JsonObject { ["type"] = "json_object" };
        }

        var request = new HttpRequestMessage(HttpMethod.Post, Combine(cfg.BaseUrl, "chat/completions"));
        AddOpenAiHeaders(request, cfg);
        request.Content = new StringContent(Json.Serialize(body), Encoding.UTF8, "application/json");
        return request;
    }

    private static void AddOpenAiHeaders(HttpRequestMessage request, ProviderConfig cfg)
    {
        if (!string.IsNullOrWhiteSpace(cfg.ApiKey))
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", cfg.ApiKey.Trim());
        if (cfg.Preset.ExtraHeaders != null)
        {
            foreach (var (name, value) in cfg.Preset.ExtraHeaders)
            {
                if (name.Equals("Accept", StringComparison.OrdinalIgnoreCase)) request.Headers.Accept.Clear();
                request.Headers.TryAddWithoutValidation(name, value);
            }
        }
    }

    private async Task<string> OpenAiAsync(ProviderConfig cfg, AiPrompt prompt, CancellationToken ct)
    {
        var modeKey = cfg.Preset.Id + "|" + cfg.Model;
        bool plain = PlainModes.ContainsKey(modeKey);
        bool hadOptional = !plain && HasOptionalParameters(cfg.Preset, cfg.Model, prompt);

        var (status, text) = await SendOpenAiAsync(cfg, prompt, plain, ct).ConfigureAwait(false);
        if ((status == 400 || status == 422) && hadOptional)
        {
            PlainModes[modeKey] = 1;
            (status, text) = await SendOpenAiAsync(cfg, prompt, true, ct).ConfigureAwait(false);
        }
        if (status < 200 || status > 299) throw StatusError(status, text);
        return ParseOpenAiResponse(text);
    }

    private async Task<(int Status, string Text)> SendOpenAiAsync(ProviderConfig cfg, AiPrompt prompt, bool plain, CancellationToken ct)
    {
        using var request = BuildOpenAiRequest(cfg, prompt, plain);
        using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        return ((int)response.StatusCode, text);
    }

    public static string ParseOpenAiResponse(string json)
    {
        using var doc = ParseDocument(json);
        var root = doc.RootElement;
        if (root.GetProperty("choices", false) is not { ValueKind: JsonValueKind.Array } choices || choices.GetArrayLength() == 0)
            throw new AiException(AiErrorKind.BadResponse, "missing choices");
        var choice = choices[0];
        if (choice.GetProperty("message", false) is not { ValueKind: JsonValueKind.Object } message)
            throw new AiException(AiErrorKind.BadResponse, "missing message");

        string content = "";
        if (message.GetProperty("content", false) is { } c)
        {
            if (c.ValueKind == JsonValueKind.String) content = c.GetString() ?? "";
            else if (c.ValueKind == JsonValueKind.Array)
            {
                var sb = new StringBuilder();
                foreach (var part in c.EnumerateArray())
                {
                    if (part.ValueKind == JsonValueKind.String) sb.Append(part.GetString());
                    else if (part.GetString("text") is { } t) sb.Append(t);
                }
                content = sb.ToString();
            }
        }
        if (content.Length == 0)
        {
            var refusal = message.GetString("refusal");
            if (!string.IsNullOrWhiteSpace(refusal) || choice.GetString("finish_reason") == "content_filter")
                throw new AiException(AiErrorKind.Refused, refusal);
        }
        return content;
    }

    // ---- model list (§6.6) ----------------------------------------------------------------------------

    public async Task<IReadOnlyList<string>> ListModelsAsync(ProviderConfig cfg, CancellationToken cancellationToken = default)
    {
        if (cfg.Preset.KeyRequired && string.IsNullOrWhiteSpace(cfg.ApiKey)) throw new AiException(AiErrorKind.NoKey);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            using var request = BuildModelsRequest(cfg);
            using var response = await _http.SendAsync(request, cts.Token).ConfigureAwait(false);
            var text = await response.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) throw StatusError((int)response.StatusCode, text);
            return SortModels(ParseModelIds(text));
        }
        catch (AiException)
        {
            throw;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new AiException(AiErrorKind.Timeout);
        }
        catch (HttpRequestException ex)
        {
            throw new AiException(AiErrorKind.Network, ex.Message, inner: ex);
        }
    }

    public static HttpRequestMessage BuildModelsRequest(ProviderConfig cfg)
    {
        var url = Combine(cfg.BaseUrl, "models") + (cfg.Preset.IsAnthropic ? "?limit=1000" : "");
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (cfg.Preset.IsAnthropic)
        {
            request.Headers.TryAddWithoutValidation("x-api-key", cfg.ApiKey ?? "");
            request.Headers.TryAddWithoutValidation("anthropic-version", AnthropicVersion);
        }
        else
        {
            AddOpenAiHeaders(request, cfg);
        }
        return request;
    }

    public static List<string> ParseModelIds(string json)
    {
        using var doc = ParseDocument(json);
        var root = doc.RootElement;
        IEnumerable<JsonElement> items = root.ValueKind switch
        {
            JsonValueKind.Array => root.EnumerateArray(),
            JsonValueKind.Object when root.GetProperty("data", false) is { ValueKind: JsonValueKind.Array } d => d.EnumerateArray(),
            JsonValueKind.Object when root.GetProperty("models", false) is { ValueKind: JsonValueKind.Array } m => m.EnumerateArray(),
            _ => Enumerable.Empty<JsonElement>(),
        };
        var ids = new List<string>();
        foreach (var item in items)
        {
            var id = item.ValueKind == JsonValueKind.String ? item.GetString() : item.GetString("id") ?? item.GetString("name");
            if (!string.IsNullOrWhiteSpace(id)) ids.Add(id!);
        }
        return ids;
    }

    /// <summary>Sorted ids, Claude/GPT/Gemini chat models first.</summary>
    public static List<string> SortModels(IEnumerable<string> ids)
        => ids.Distinct(StringComparer.Ordinal)
            .OrderBy(id => IsPreferredChatModel(id) ? 0 : 1)
            .ThenBy(id => id, StringComparer.OrdinalIgnoreCase)
            .ToList();

    public static bool IsPreferredChatModel(string id)
    {
        var m = id.ToLowerInvariant();
        if (!(m.Contains("claude") || m.Contains("gpt") || m.Contains("gemini"))) return false;
        string[] nonChat = { "embed", "tts", "whisper", "dall-e", "image", "audio", "realtime", "transcribe", "moderation", "search" };
        return !nonChat.Any(m.Contains);
    }

    // ---- errors ---------------------------------------------------------------------------------------

    private static JsonDocument ParseDocument(string json)
    {
        try
        {
            return JsonDocument.Parse(json, Json.DocumentOptions);
        }
        catch (JsonException ex)
        {
            throw new AiException(AiErrorKind.BadResponse, ex.Message, inner: ex);
        }
    }

    public static AiException StatusError(int status, string body)
        => new(AiErrors.FromStatus(status), ExtractErrorMessage(body) ?? $"HTTP {status}", status);

    public static string? ExtractErrorMessage(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body, Json.DocumentOptions);
            var root = doc.RootElement;
            if (root.ValueKind == JsonValueKind.Array && root.GetArrayLength() > 0) root = root[0];
            if (root.GetProperty("error", false) is { } error)
            {
                if (error.ValueKind == JsonValueKind.String) return error.GetString();
                if (error.GetString("message") is { } msg) return msg;
            }
            return root.GetString("message");
        }
        catch
        {
            return string.IsNullOrWhiteSpace(body) ? null : body.Length > 200 ? body[..200] : body;
        }
    }
}
