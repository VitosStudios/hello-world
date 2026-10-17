using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Tippli.Core.Models;
using Tippli.Core.Shared;

namespace Tippli.Core.Settings;

/// <summary>
/// Settings with the exact keys of shared/settings-defaults.json (SPEC §8) plus the derived keys
/// <c>model.&lt;providerId&gt;</c> and <c>keyboard.&lt;languageId&gt;</c>. Windows: <c>%APPDATA%\Tippli\settings.json</c>.
/// Values are validated against <c>allowedValues</c>; unknown or broken values fall back to the defaults.
/// </summary>
public sealed class SettingsStore
{
    private readonly SharedData _data;
    private readonly object _lock = new();
    private JsonObject _values = new();

    public SettingsStore(SharedData data, string? filePath = null)
    {
        _data = data;
        FilePath = filePath;
        foreach (var (k, v) in data.SettingsDefaults) _values[k] = v?.DeepClone();
    }

    public static string DefaultDirectory
        => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Tippli");

    public static string DefaultFilePath => Path.Combine(DefaultDirectory, "settings.json");

    /// <summary>File the settings are stored in; null = memory only.</summary>
    public string? FilePath { get; }

    public bool FileExisted { get; private set; }

    /// <summary>Raised after a value changed (key).</summary>
    public event Action<string>? Changed;

    public SharedData Data => _data;

    // ---- load / save ------------------------------------------------------------------------------------

    public void Load()
    {
        if (FilePath == null || !File.Exists(FilePath)) return;
        FileExisted = true;
        JsonObject? stored = null;
        try
        {
            stored = Json.ParseNode(File.ReadAllText(FilePath)) as JsonObject;
        }
        catch
        {
            try { File.Copy(FilePath, FilePath + ".broken", overwrite: true); } catch { /* ignore */ }
        }
        if (stored == null) return;
        lock (_lock)
        {
            foreach (var (key, value) in stored)
            {
                if (value == null) continue;
                if (_data.SettingsDefaults.ContainsKey(key))
                {
                    if (IsCompatible(_data.SettingsDefaults[key], value)) _values[key] = Normalize(key, value.DeepClone());
                }
                else if ((key.StartsWith("model.", StringComparison.Ordinal) || key.StartsWith("keyboard.", StringComparison.Ordinal))
                         && value is JsonValue jv && jv.TryGetValue<string>(out var s) && s.Length > 0)
                {
                    _values[key] = s;
                }
            }
            EnsureLanguageConsistency();
        }
    }

    public void Save()
    {
        if (FilePath == null) return;
        string json;
        lock (_lock)
        {
            var ordered = new JsonObject();
            foreach (var (k, _) in _data.SettingsDefaults) ordered[k] = _values[k]?.DeepClone();
            foreach (var (k, v) in _values.OrderBy(p => p.Key, StringComparer.Ordinal))
                if (!ordered.ContainsKey(k)) ordered[k] = v?.DeepClone();
            json = ordered.ToJsonString(Json.WriteOptions);
        }
        var dir = Path.GetDirectoryName(FilePath);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, json);
        File.Move(tmp, FilePath, overwrite: true);
    }

    private static bool IsCompatible(JsonNode? def, JsonNode value) => def switch
    {
        JsonArray => value is JsonArray,
        JsonValue dv when dv.GetValueKind() is JsonValueKind.True or JsonValueKind.False
            => value is JsonValue v && v.GetValueKind() is JsonValueKind.True or JsonValueKind.False,
        JsonValue dv when dv.GetValueKind() == JsonValueKind.Number
            => value is JsonValue v && v.GetValueKind() == JsonValueKind.Number,
        JsonValue dv when dv.GetValueKind() == JsonValueKind.String
            => value is JsonValue v && v.GetValueKind() == JsonValueKind.String,
        _ => true,
    };

    private JsonNode? Normalize(string key, JsonNode? value)
    {
        var allowed = _data.AllowedStrings(key);
        if (allowed.Count > 0 && value is JsonValue sv && sv.TryGetValue<string>(out var s))
            return allowed.Contains(s) ? value : _data.SettingsDefaults[key]?.DeepClone();
        var range = _data.AllowedRange(key);
        if (range != null && TryGetNumber(value, out var d))
            return JsonValue.Create((int)Math.Round(Math.Clamp(d, range.Value.Min, range.Value.Max)));
        if (value is JsonArray arr)
        {
            var clean = new JsonArray();
            foreach (var item in arr)
                if (item is JsonValue iv && iv.TryGetValue<string>(out var str)) clean.Add(str);
            return clean;
        }
        return value;
    }

    private void EnsureLanguageConsistency()
    {
        var langs = GetStringList("languages").Where(l => _data.FindLanguage(l) != null).Distinct().ToList();
        if (langs.Count == 0) langs = DefaultStringList("languages");
        if (langs.Count == 0 && _data.Languages.Languages.Count > 0) langs.Add(_data.Languages.Languages[0].Id);
        _values["languages"] = ToArray(langs);
        var active = GetString("activeLanguage");
        if (!langs.Contains(active)) _values["activeLanguage"] = langs.FirstOrDefault() ?? "de-CH";
        if (_data.FindProvider(GetString("provider")) == null)
            _values["provider"] = _data.SettingsDefaults["provider"]?.DeepClone();
    }

    // ---- generic access ---------------------------------------------------------------------------------

    public JsonNode? GetNode(string key)
    {
        lock (_lock) return _values[key]?.DeepClone();
    }

    public string GetString(string key, string fallback = "")
    {
        lock (_lock)
            return _values[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : fallback;
    }

    public bool GetBool(string key, bool fallback = false)
    {
        lock (_lock)
            return _values[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : fallback;
    }

    public int GetInt(string key, int fallback = 0)
    {
        lock (_lock)
            return TryGetNumber(_values[key], out var d) ? (int)Math.Round(d) : fallback;
    }

    /// <summary>Reads a number from a parsed (JsonElement backed) or a created (int/long/double backed) value.</summary>
    private static bool TryGetNumber(JsonNode? node, out double value)
    {
        value = 0;
        if (node is not JsonValue v) return false;
        if (v.TryGetValue<double>(out value)) return true;
        if (v.TryGetValue<int>(out var i)) { value = i; return true; }
        if (v.TryGetValue<long>(out var l)) { value = l; return true; }
        if (v.TryGetValue<float>(out var f)) { value = f; return true; }
        if (v.TryGetValue<decimal>(out var m)) { value = (double)m; return true; }
        if (v.TryGetValue<JsonElement>(out var e) && e.ValueKind == JsonValueKind.Number) { value = e.GetDouble(); return true; }
        return false;
    }

    public List<string> GetStringList(string key)
    {
        lock (_lock)
        {
            if (_values[key] is not JsonArray a) return new List<string>();
            var list = new List<string>();
            foreach (var n in a)
                if (n is JsonValue v && v.TryGetValue<string>(out var s)) list.Add(s);
            return list;
        }
    }

    private List<string> DefaultStringList(string key)
        => _data.SettingsDefaults[key] is JsonArray a
            ? a.OfType<JsonValue>().Select(v => v.TryGetValue<string>(out var s) ? s : null).Where(s => s != null).Select(s => s!).ToList()
            : new List<string>();

    private static JsonArray ToArray(IEnumerable<string> items)
    {
        var a = new JsonArray();
        foreach (var i in items) a.Add(i);
        return a;
    }

    /// <summary>Sets a value (validated), saves and raises <see cref="Changed"/>.</summary>
    public void Set(string key, JsonNode? value, bool save = true)
    {
        lock (_lock)
        {
            if (_data.SettingsDefaults.ContainsKey(key))
            {
                if (value == null || !IsCompatible(_data.SettingsDefaults[key], value)) return;
                value = Normalize(key, value);
            }
            var old = _values[key];
            if (old != null && value != null && JsonNode.DeepEquals(old, value)) return;
            _values[key] = value;
            if (key is "languages" or "activeLanguage" or "provider") EnsureLanguageConsistency();
        }
        if (save) TrySave();
        Changed?.Invoke(key);
    }

    public void SetString(string key, string value, bool save = true) => Set(key, JsonValue.Create(value), save);
    public void SetBool(string key, bool value, bool save = true) => Set(key, JsonValue.Create(value), save);
    public void SetInt(string key, int value, bool save = true) => Set(key, JsonValue.Create(value), save);
    public void SetStringList(string key, IEnumerable<string> value, bool save = true) => Set(key, ToArray(value), save);

    public void ResetToDefault(string key)
    {
        if (_data.SettingsDefaults[key] is { } def) Set(key, def.DeepClone());
    }

    private void TrySave()
    {
        try { Save(); }
        catch { /* the next change saves again */ }
    }

    // ---- typed settings (keys of settings-defaults.json) --------------------------------------------------

    public string Provider { get => GetString("provider", "anthropic"); set => SetString("provider", value); }
    public string CustomBaseUrl { get => GetString("customBaseUrl"); set => SetString("customBaseUrl", value); }
    public bool AiEnabled { get => GetBool("aiEnabled", true); set => SetBool("aiEnabled", value); }
    public string Trigger { get => GetString("trigger", "live"); set => SetString("trigger", value); }
    public int PauseMs { get => GetInt("pauseMs", 700); set => SetInt("pauseMs", value); }
    public string Strength { get => GetString("strength", "normal"); set => SetString("strength", value); }
    public int WaitOnSendMs { get => GetInt("waitOnSendMs", 1500); set => SetInt("waitOnSendMs", value); }
    public bool MoodWarning { get => GetBool("moodWarning", true); set => SetBool("moodWarning", value); }
    public List<string> Languages { get => GetStringList("languages"); set => SetStringList("languages", value); }
    public string ActiveLanguage { get => GetString("activeLanguage", "de-CH"); set => SetString("activeLanguage", value); }
    public string KeyboardMode { get => GetString("keyboardMode", "hex"); set => SetString("keyboardMode", value); }
    public bool LongPressAccents { get => GetBool("longPressAccents", true); set => SetBool("longPressAccents", value); }
    public bool LongPressDigits { get => GetBool("longPressDigits", true); set => SetBool("longPressDigits", value); }
    public int LongPressMs { get => GetInt("longPressMs", 350); set => SetInt("longPressMs", value); }
    public string KeyHeight { get => GetString("keyHeight", "medium"); set => SetString("keyHeight", value); }
    public bool Haptics { get => GetBool("haptics", true); set => SetBool("haptics", value); }
    public bool Sound { get => GetBool("sound"); set => SetBool("sound", value); }
    public bool AutoCapitalize { get => GetBool("autoCapitalize", true); set => SetBool("autoCapitalize", value); }
    public bool DoubleSpacePeriod { get => GetBool("doubleSpacePeriod", true); set => SetBool("doubleSpacePeriod", value); }
    public bool NumberRow { get => GetBool("numberRow"); set => SetBool("numberRow", value); }
    public string Theme { get => GetString("theme", "system"); set => SetString("theme", value); }
    public bool TipsRewrites { get => GetBool("tipsRewrites", true); set => SetBool("tipsRewrites", value); }
    public bool TipsExplanations { get => GetBool("tipsExplanations", true); set => SetBool("tipsExplanations", value); }
    public bool TipsWriting { get => GetBool("tipsWriting", true); set => SetBool("tipsWriting", value); }
    public bool TipsTranslate { get => GetBool("tipsTranslate", true); set => SetBool("tipsTranslate", value); }
    public bool TipsEmotion { get => GetBool("tipsEmotion", true); set => SetBool("tipsEmotion", value); }
    public List<string> RewriteVariants { get => GetStringList("rewriteVariants"); set => SetStringList("rewriteVariants", value); }
    public List<string> TranslateTargets { get => GetStringList("translateTargets"); set => SetStringList("translateTargets", value); }
    public bool IncognitoNoAI { get => GetBool("incognitoNoAI", true); set => SetBool("incognitoNoAI", value); }
    public List<string> EmojiRecents { get => GetStringList("emojiRecents"); set => SetStringList("emojiRecents", value); }
    public bool DesktopLiveCorrection { get => GetBool("desktopLiveCorrection", true); set => SetBool("desktopLiveCorrection", value); }
    public bool DesktopLaunchAtLogin { get => GetBool("desktopLaunchAtLogin", true); set => SetBool("desktopLaunchAtLogin", value); }
    public bool DesktopShowKeyboardOnStart { get => GetBool("desktopShowKeyboardOnStart"); set => SetBool("desktopShowKeyboardOnStart", value); }
    public List<string> DesktopExcludedApps { get => GetStringList("desktopExcludedApps"); set => SetStringList("desktopExcludedApps", value); }

    // ---- derived keys -----------------------------------------------------------------------------------

    public ProviderPreset ActiveProvider
        => _data.FindProvider(Provider) ?? _data.Providers.Providers.FirstOrDefault() ?? new ProviderPreset { Id = "anthropic" };

    public Language ActiveLanguageInfo
        => _data.FindLanguage(ActiveLanguage) ?? _data.Languages.Languages.FirstOrDefault() ?? new Language { Id = "de-CH" };

    /// <summary><c>model.&lt;providerId&gt;</c>, default = the provider's <c>defaultModel</c> from providers.json.</summary>
    public string GetModel(string providerId)
    {
        var stored = GetString("model." + providerId);
        if (stored.Length > 0) return stored;
        return _data.FindProvider(providerId)?.DefaultModel ?? "";
    }

    public void SetModel(string providerId, string model)
    {
        var def = _data.FindProvider(providerId)?.DefaultModel;
        if (string.IsNullOrWhiteSpace(model) || model == def) Remove("model." + providerId);
        else SetString("model." + providerId, model.Trim());
    }

    /// <summary><c>keyboard.&lt;languageId&gt;</c>, default = the language's keyboard from languages.json.</summary>
    public string GetKeyboardId(string languageId)
    {
        var stored = GetString("keyboard." + languageId);
        if (stored.Length > 0 && _data.FindKeyboard(stored) != null) return stored;
        return _data.FindLanguage(languageId)?.Keyboard ?? "qwertz";
    }

    public void SetKeyboardId(string languageId, string keyboardId)
    {
        var def = _data.FindLanguage(languageId)?.Keyboard;
        if (string.IsNullOrWhiteSpace(keyboardId) || keyboardId == def) Remove("keyboard." + languageId);
        else SetString("keyboard." + languageId, keyboardId);
    }

    public string BaseUrlFor(ProviderPreset preset)
        => preset.BaseUrlEditable && CustomBaseUrl.Trim().Length > 0 ? CustomBaseUrl.Trim() : preset.BaseUrl;

    private void Remove(string key)
    {
        bool removed;
        lock (_lock) removed = _values.Remove(key);
        if (!removed) return;
        TrySave();
        Changed?.Invoke(key);
    }

    public IReadOnlyCollection<string> Keys
    {
        get { lock (_lock) return _values.Select(p => p.Key).ToList(); }
    }

    public static string FormatMs(int ms) => (ms / 1000.0).ToString("0.0#", CultureInfo.GetCultureInfo("de-CH")) + " s";
}
