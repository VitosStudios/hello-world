using Tippli.Core.Engine;
using Tippli.Core.Models;
using Tippli.Core.Settings;
using Tippli.Core.Shared;

namespace Tippli.Core.Ai;

/// <summary>All AI tasks with the current settings: correction, mood, tips, translation, connection test, model list.</summary>
public sealed class AiService : ICorrectionAi
{
    private readonly SharedData _data;
    private readonly SettingsStore _settings;
    private readonly ISecretStore _secrets;
    private readonly AiClient _client;
    private readonly PromptBuilder _prompts;

    public AiService(SharedData data, SettingsStore settings, ISecretStore secrets, AiClient client)
    {
        _data = data;
        _settings = settings;
        _secrets = secrets;
        _client = client;
        _prompts = new PromptBuilder(data);
    }

    public PromptBuilder Prompts => _prompts;

    public ProviderConfig CurrentConfig() => ConfigFor(_settings.ActiveProvider);

    public ProviderConfig ConfigFor(ProviderPreset preset, string? model = null, string? apiKey = null, string? baseUrl = null)
        => new(preset,
            string.IsNullOrWhiteSpace(model) ? _settings.GetModel(preset.Id) : model.Trim(),
            string.IsNullOrWhiteSpace(baseUrl) ? _settings.BaseUrlFor(preset) : baseUrl.Trim(),
            apiKey ?? _secrets.Get(SecretKeys.ApiKey(preset.Id)) ?? "");

    public bool HasKey
    {
        get
        {
            var p = _settings.ActiveProvider;
            return !p.KeyRequired || !string.IsNullOrWhiteSpace(_secrets.Get(SecretKeys.ApiKey(p.Id)));
        }
    }

    private string KeyboardIdForActiveLanguage() => _settings.GetKeyboardId(_settings.ActiveLanguage);

    public Task<string> CorrectAsync(string context, string segment, CancellationToken cancellationToken = default)
    {
        var prompt = _prompts.Correction(_settings.ActiveLanguageInfo, _settings.Strength, KeyboardIdForActiveLanguage(), context, segment);
        return _client.CompleteAsync(CurrentConfig(), prompt, cancellationToken);
    }

    public async Task<MoodResult> MoodAsync(string paragraph, CancellationToken cancellationToken = default)
    {
        var text = await _client.CompleteAsync(CurrentConfig(), _prompts.Mood(paragraph), cancellationToken).ConfigureAwait(false);
        return AiJson.ParseMood(text);
    }

    public TipsSections EnabledTipsSections()
        => new(_settings.TipsRewrites, _settings.TipsExplanations, _settings.TipsWriting, _settings.TipsEmotion);

    public async Task<TipsResult> TipsAsync(string text, IReadOnlyList<HistoryEntry> history, CancellationToken cancellationToken = default)
    {
        var prompt = _prompts.Tips(_settings.ActiveLanguageInfo, _settings.RewriteVariants, EnabledTipsSections(), text, history);
        var answer = await _client.CompleteAsync(CurrentConfig(), prompt, cancellationToken).ConfigureAwait(false);
        return AiJson.ParseTips(answer);
    }

    public async Task<string> TranslateAsync(string targetId, string text, CancellationToken cancellationToken = default)
    {
        var target = _data.FindTranslateTarget(targetId) ?? new TranslateTarget { Id = targetId, Name = targetId, PromptName = targetId };
        var answer = await _client.CompleteAsync(CurrentConfig(), _prompts.Translate(target, text), cancellationToken).ConfigureAwait(false);
        return answer.Trim();
    }

    /// <summary>"Verbindung testen": the correction prompt with <c>testSegment</c>. Returns the corrected text.</summary>
    public async Task<string> TestAsync(ProviderConfig config, CancellationToken cancellationToken = default)
    {
        var prompt = _prompts.Test(_settings.ActiveLanguageInfo, _settings.Strength, KeyboardIdForActiveLanguage());
        var answer = await _client.CompleteAsync(config, prompt, cancellationToken).ConfigureAwait(false);
        var cleaned = Text.TextAlgorithms.CleanOutput(_data.PromptParts.TestSegment, answer, _settings.ActiveLanguageInfo.Pinyin);
        if (cleaned == null) throw new AiException(AiErrorKind.BadResponse, answer.Length > 120 ? answer[..120] : answer);
        return cleaned.Trim();
    }

    public Task<IReadOnlyList<string>> ListModelsAsync(ProviderConfig config, CancellationToken cancellationToken = default)
        => _client.ListModelsAsync(config, cancellationToken);
}
