using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using Tippli.Core.Ai;
using Tippli.Core.Models;
using Tippli.Core.Settings;
using Tippli.Core.Shared;

namespace Tippli.Core.Tests;

[Collection("AiClient")] // plain-mode memory is process wide
public class AiClientTests
{
    private static readonly SharedData Data = SharedData.Default;
    private readonly FakeHttpHandler _handler = new();
    private readonly AiClient _client;

    public AiClientTests()
    {
        AiClient.ResetPlainModes();
        _client = new AiClient(new HttpClient(_handler));
    }

    private static ProviderConfig Config(string providerId, string model, string key = "secret-key")
    {
        var p = Data.FindProvider(providerId)!;
        return new ProviderConfig(p, model, p.BaseUrl, key);
    }

    private static AiPrompt Prompt(AiTask task, bool json = false) => new(task, "SYSTEM", "USER", json);

    private const string AnthropicOk = """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"Hallo "},{"type":"text","text":"Welt"}],"stop_reason":"end_turn"}""";
    private const string OpenAiOk = """{"choices":[{"message":{"role":"assistant","content":"Hallo Welt"},"finish_reason":"stop"}]}""";

    private JsonObject LastBody() => JsonNode.Parse(_handler.Requests[^1].Body)!.AsObject();

    [Theory]
    [InlineData("claude-opus-5-5", null, true, true)]
    [InlineData("claude-opus-5", null, true, true)]
    [InlineData("claude-fable-5-1", null, true, true)]
    [InlineData("claude-sonnet-5-5", "between_tools", true, true)]
    [InlineData("claude-haiku-5-5", "disabled", true, false)]
    [InlineData("claude-haiku-4-5", null, false, false)]
    public async Task AnthropicModelProfiles(string model, string? thinking, bool effort, bool fallbacks)
    {
        _handler.Enqueue(HttpStatusCode.OK, AnthropicOk);
        var text = await _client.CompleteAsync(Config("anthropic", model), Prompt(AiTask.Correction));
        Assert.Equal("Hallo Welt", text); // thinking blocks ignored

        var req = _handler.Requests[^1];
        Assert.Equal("https://api.anthropic.com/v1/messages", req.Url);
        Assert.Equal("secret-key", req.Headers["x-api-key"]);
        Assert.Equal("2023-06-01", req.Headers["anthropic-version"]);
        Assert.StartsWith("application/json", req.Headers["Content-Type"]);
        Assert.False(req.Headers.ContainsKey("Authorization"));
        if (fallbacks) Assert.Equal("server-side-fallback-2026-07-01", req.Headers["anthropic-beta"]);
        else Assert.False(req.Headers.ContainsKey("anthropic-beta"));

        var body = LastBody();
        Assert.Equal(model, (string?)body["model"]);
        Assert.Equal(2048, (int)body["max_tokens"]!);
        Assert.Equal("SYSTEM", (string?)body["system"]);
        Assert.Equal("user", (string?)body["messages"]![0]!["role"]);
        Assert.Equal("USER", (string?)body["messages"]![0]!["content"]);
        Assert.False(body.ContainsKey("temperature"));
        Assert.False(body.ContainsKey("top_p"));
        Assert.Equal(thinking, (string?)body["thinking"]?["type"]);
        Assert.Equal(effort ? "low" : null, (string?)body["output_config"]?["effort"]);
        Assert.Equal(fallbacks ? "default" : null, (string?)body["fallbacks"]);
    }

    [Theory]
    [InlineData(AiTask.Correction, 2048, "low")]
    [InlineData(AiTask.Test, 2048, "low")]
    [InlineData(AiTask.Mood, 1024, "low")]
    [InlineData(AiTask.Tips, 8000, "medium")]
    [InlineData(AiTask.Translate, 4000, "medium")]
    public async Task AnthropicTokensAndEffortPerTask(AiTask task, int maxTokens, string effort)
    {
        _handler.Enqueue(HttpStatusCode.OK, AnthropicOk);
        await _client.CompleteAsync(Config("anthropic", "claude-sonnet-5-5"), Prompt(task));
        var body = LastBody();
        Assert.Equal(maxTokens, (int)body["max_tokens"]!);
        Assert.Equal(effort, (string?)body["output_config"]!["effort"]);
    }

    [Fact]
    public async Task AnthropicRefusal()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}}""");
        var ex = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("anthropic", "claude-haiku-5-5"), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.Refused, ex.Kind);
    }

    [Fact]
    public async Task OpenAiPresetFields()
    {
        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        var text = await _client.CompleteAsync(Config("openai", "gpt-5-mini"), Prompt(AiTask.Tips, json: true));
        Assert.Equal("Hallo Welt", text);
        var req = _handler.Requests[^1];
        Assert.Equal("https://api.openai.com/v1/chat/completions", req.Url);
        Assert.Equal("Bearer secret-key", req.Headers["Authorization"]);
        var body = LastBody();
        Assert.Equal("system", (string?)body["messages"]![0]!["role"]);
        Assert.Equal("SYSTEM", (string?)body["messages"]![0]!["content"]);
        Assert.Equal("USER", (string?)body["messages"]![1]!["content"]);
        Assert.Equal(8000, (int)body["max_completion_tokens"]!);
        Assert.False(body.ContainsKey("max_tokens"));
        Assert.False(body.ContainsKey("temperature")); // sendTemperature false
        Assert.Equal("low", (string?)body["reasoning_effort"]);
        Assert.Equal("json_object", (string?)body["response_format"]!["type"]);
    }

    [Fact]
    public async Task OpenAiReasoningMinimumTokensAndNoJsonForText()
    {
        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        await _client.CompleteAsync(Config("openai", "gpt-5-nano"), Prompt(AiTask.Correction));
        var body = LastBody();
        Assert.Equal(4000, (int)body["max_completion_tokens"]!); // at least 4000 with reasoningEffort
        Assert.False(body.ContainsKey("response_format"));

        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        await _client.CompleteAsync(Config("openai", "gpt-4.1-mini"), Prompt(AiTask.Correction));
        Assert.False(LastBody().ContainsKey("reasoning_effort")); // only gpt-5* / o*
    }

    [Fact]
    public async Task GeminiAndGithubPresets()
    {
        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        await _client.CompleteAsync(Config("gemini", "gemini-flash-lite-latest"), Prompt(AiTask.Mood, json: true));
        var body = LastBody();
        Assert.Equal("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", _handler.Requests[^1].Url);
        Assert.Equal(0.2, (double)body["temperature"]!, 6);
        Assert.Equal(1024, (int)body["max_tokens"]!);
        Assert.Equal("json_object", (string?)body["response_format"]!["type"]);
        Assert.False(body.ContainsKey("reasoning_effort"));

        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        await _client.CompleteAsync(Config("github", "openai/gpt-4.1-mini"), Prompt(AiTask.Correction));
        var req = _handler.Requests[^1];
        Assert.Equal("https://models.github.ai/inference/chat/completions", req.Url);
        Assert.Equal("application/vnd.github+json", req.Headers["Accept"]);
        Assert.Equal("2022-11-28", req.Headers["X-GitHub-Api-Version"]);
    }

    [Fact]
    public async Task CustomServerWithoutKeyOmitsAuthorization()
    {
        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        var p = Data.FindProvider("custom")!;
        await _client.CompleteAsync(new ProviderConfig(p, "llama3.2", "http://localhost:11434/v1/", ""), Prompt(AiTask.Correction));
        var req = _handler.Requests[^1];
        Assert.Equal("http://localhost:11434/v1/chat/completions", req.Url);
        Assert.False(req.Headers.ContainsKey("Authorization"));
    }

    [Theory]
    [InlineData(400)]
    [InlineData(422)]
    public async Task BadRequestRetriesOnceInPlainModeAndRemembersIt(int status)
    {
        _handler.Enqueue((HttpStatusCode)status, """{"error":{"message":"Unsupported parameter: temperature"}}""");
        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        var text = await _client.CompleteAsync(Config("mistral", "mistral-small-latest"), Prompt(AiTask.Tips, json: true));
        Assert.Equal("Hallo Welt", text);
        Assert.Equal(2, _handler.Requests.Count);
        var first = JsonNode.Parse(_handler.Requests[0].Body)!.AsObject();
        var second = JsonNode.Parse(_handler.Requests[1].Body)!.AsObject();
        Assert.True(first.ContainsKey("temperature"));
        Assert.True(first.ContainsKey("response_format"));
        Assert.False(second.ContainsKey("temperature"));
        Assert.False(second.ContainsKey("response_format"));
        Assert.False(second.ContainsKey("reasoning_effort"));
        Assert.True(AiClient.IsPlainMode("mistral", "mistral-small-latest"));

        // remembered: the next request is plain right away
        _handler.Enqueue(HttpStatusCode.OK, OpenAiOk);
        await _client.CompleteAsync(Config("mistral", "mistral-small-latest"), Prompt(AiTask.Correction));
        Assert.Equal(3, _handler.Requests.Count);
        Assert.False(LastBody().ContainsKey("temperature"));
    }

    [Fact]
    public async Task BadRequestMapsToBadResponse()
    {
        // Anthropic: no optional parameters → no retry.
        _handler.Enqueue(HttpStatusCode.BadRequest, """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}""");
        var ex = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("anthropic", "claude-haiku-5-5"), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.BadResponse, ex.Kind);
        Assert.Single(_handler.Requests);

        // OpenAI-compatible: retried once in plain mode; a second 400 surfaces as badResponse.
        _handler.Enqueue(HttpStatusCode.BadRequest, """{"error":{"message":"bad"}}""");
        _handler.Enqueue(HttpStatusCode.BadRequest, """{"error":{"message":"still bad"}}""");
        var ex2 = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("custom", "x"), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.BadResponse, ex2.Kind);
        Assert.Equal("still bad", ex2.Detail);
        Assert.Equal(3, _handler.Requests.Count);
    }

    [Theory]
    [InlineData(401, AiErrorKind.InvalidKey)]
    [InlineData(403, AiErrorKind.InvalidKey)]
    [InlineData(404, AiErrorKind.BadModel)]
    [InlineData(429, AiErrorKind.RateLimited)]
    [InlineData(500, AiErrorKind.Server)]
    [InlineData(503, AiErrorKind.Server)]
    [InlineData(529, AiErrorKind.Server)]
    public async Task ErrorMapping(int status, AiErrorKind kind)
    {
        _handler.Default = _ => new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent("""{"type":"error","error":{"type":"x","message":"nope"}}""") };
        var ex = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("anthropic", "claude-haiku-5-5"), Prompt(AiTask.Correction)));
        Assert.Equal(kind, ex.Kind);
        Assert.Equal("nope", ex.Detail);
        var ex2 = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("openai", "gpt-5-mini"), Prompt(AiTask.Correction)));
        Assert.Equal(kind, ex2.Kind);
    }

    [Fact]
    public async Task NoKeyNetworkAndTimeout()
    {
        var noKey = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("anthropic", "claude-haiku-5-5", key: ""), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.NoKey, noKey.Kind);
        Assert.Empty(_handler.Requests);

        _handler.Default = _ => throw new HttpRequestException("DNS");
        var net = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("anthropic", "claude-haiku-5-5"), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.Network, net.Kind);

        var slow = new SlowHandler();
        var client = new AiClient(new HttpClient(slow));
        var timeout = await Assert.ThrowsAsync<AiException>(() =>
            client.CompleteAsync(Config("openai", "gpt-5-mini"), Prompt(AiTask.Correction), timeout: TimeSpan.FromMilliseconds(50)));
        Assert.Equal(AiErrorKind.Timeout, timeout.Kind);
    }

    [Fact]
    public async Task OpenAiRefusalAndArrayContent()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"choices":[{"message":{"content":null,"refusal":"Nein."},"finish_reason":"stop"}]}""");
        var ex = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("openai", "gpt-5-mini"), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.Refused, ex.Kind);

        _handler.Enqueue(HttpStatusCode.OK, """{"choices":[{"message":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}""");
        Assert.Equal("AB", await _client.CompleteAsync(Config("openai", "gpt-5-mini"), Prompt(AiTask.Correction)));

        _handler.Enqueue(HttpStatusCode.OK, "not json");
        var bad = await Assert.ThrowsAsync<AiException>(() => _client.CompleteAsync(Config("openai", "gpt-5-mini"), Prompt(AiTask.Correction)));
        Assert.Equal(AiErrorKind.BadResponse, bad.Kind);
    }

    [Fact]
    public async Task ModelListUsesAuthHeadersAndSortsChatModelsFirst()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"data":[{"id":"text-embedding-3-small"},{"id":"gpt-5-mini"},{"id":"babbage-002"},{"id":"gpt-4.1"},{"id":"tts-1"}]}""");
        var ids = await _client.ListModelsAsync(Config("openai", "gpt-5-mini"));
        Assert.Equal(new[] { "gpt-4.1", "gpt-5-mini", "babbage-002", "text-embedding-3-small", "tts-1" }, ids);
        Assert.Equal("https://api.openai.com/v1/models", _handler.Requests[^1].Url);
        Assert.Equal("Bearer secret-key", _handler.Requests[^1].Headers["Authorization"]);
        Assert.Equal(HttpMethod.Get, _handler.Requests[^1].Method);

        _handler.Enqueue(HttpStatusCode.OK, """{"data":[{"id":"claude-sonnet-5-5","type":"model"},{"id":"claude-haiku-5-5","type":"model"}],"has_more":false}""");
        var claude = await _client.ListModelsAsync(Config("anthropic", "claude-haiku-5-5"));
        Assert.Equal(new[] { "claude-haiku-5-5", "claude-sonnet-5-5" }, claude);
        var req = _handler.Requests[^1];
        Assert.StartsWith("https://api.anthropic.com/v1/models", req.Url);
        Assert.Equal("secret-key", req.Headers["x-api-key"]);
        Assert.Equal("2023-06-01", req.Headers["anthropic-version"]);

        _handler.Enqueue(HttpStatusCode.Unauthorized, """{"error":{"message":"bad key"}}""");
        var ex = await Assert.ThrowsAsync<AiException>(() => _client.ListModelsAsync(Config("openai", "gpt-5-mini")));
        Assert.Equal(AiErrorKind.InvalidKey, ex.Kind);
    }

    [Fact]
    public async Task ServiceUsesSettingsSecretsAndDefaultModel()
    {
        var settings = new SettingsStore(Data);
        var secrets = new InMemorySecretStore();
        var service = new AiService(Data, settings, secrets, _client);
        Assert.False(service.HasKey);
        await Assert.ThrowsAsync<AiException>(() => service.CorrectAsync("", "hallo "));

        secrets.Set(SecretKeys.ApiKey("anthropic"), "k-123");
        Assert.True(service.HasKey);
        _handler.Enqueue(HttpStatusCode.OK, AnthropicOk);
        await service.CorrectAsync("ctx", "seg ");
        var body = LastBody();
        Assert.Equal(Data.FindProvider("anthropic")!.DefaultModel, (string?)body["model"]);
        Assert.Contains("Keyboard: QWERTZ", (string?)body["system"]);
        Assert.Contains("<segment>seg </segment>", (string?)body["messages"]![0]!["content"]);

        _handler.Enqueue(HttpStatusCode.OK, """{"content":[{"type":"text","text":"{\"level\": 2, \"reason\": \"Ärger\"}"}],"stop_reason":"end_turn"}""");
        var mood = await service.MoodAsync("Ich bin sauer!!!");
        Assert.Equal(2, mood.Level);

        settings.Provider = "custom";
        Assert.True(service.HasKey); // keyRequired false
        settings.CustomBaseUrl = "http://127.0.0.1:1234/v1";
        Assert.Equal("http://127.0.0.1:1234/v1", service.CurrentConfig().BaseUrl);
        settings.SetModel("custom", "qwen3");
        Assert.Equal("qwen3", service.CurrentConfig().Model);
    }

    private sealed class SlowHandler : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            await Task.Delay(TimeSpan.FromSeconds(30), cancellationToken);
            return new HttpResponseMessage(HttpStatusCode.OK);
        }
    }
}
