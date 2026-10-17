using System.Text.Json;
using Tippli.Core.Ai;
using Tippli.Core.Engine;
using Tippli.Core.Models;
using Tippli.Core.Settings;
using Tippli.Core.Shared;

namespace Tippli.Core.Tests;

public class SharedDataTests
{
    private static SharedData Data => SharedData.Default;

    [Fact]
    public void EmbeddedResourcesAreComplete()
    {
        foreach (var name in new[]
                 {
                     "layouts/layouts.json", "layouts/accents.json", "languages.json", "providers.json", "emoji.json", "theme.json",
                     "settings-defaults.json", "strings-de.json", "prompts/correction-system.txt", "prompts/correction-user.txt",
                     "prompts/mood-system.txt", "prompts/tips-system.txt", "prompts/tips-user.txt", "prompts/translate-system.txt",
                     "prompts/prompt-parts.json",
                 })
            Assert.True(SharedResources.Exists(name), name);
    }

    [Fact]
    public void EmbeddedDataEqualsSharedFolder()
    {
        // The embedded copy must be the build-time content of shared/ (never copied by hand).
        Assert.Equal(File.ReadAllText(TestPaths.SharedFile("providers.json")), SharedResources.ReadText("providers.json"));
        Assert.Equal(File.ReadAllText(TestPaths.SharedFile("prompts", "correction-system.txt")),
            SharedResources.ReadText("prompts/correction-system.txt"));
    }

    [Fact]
    public void ModelsAreParsed()
    {
        Assert.Contains("qwertz", Data.Layouts.Keyboards.Keys);
        Assert.Equal(1.5, Data.Layouts.BottomRows["normal"][0].W);
        Assert.Equal("$123", Data.Layouts.BottomRows["normal"][0].K);
        Assert.Equal(10, Data.Layouts.NumberRow.Count);
        Assert.NotEmpty(Data.Accents.Accents["a"]);
        Assert.Contains(Data.Languages.Languages, l => l.Id == "zh-Hans" && l.Pinyin);
        Assert.NotEmpty(Data.Emoji.Categories);
        Assert.Equal(40, Data.Theme.Metrics.ToolbarHeight);
        Assert.Equal(52, Data.Theme.Metrics.RowHeightFor("medium"));
        Assert.Equal(new ThemeColor(255, 0x4F, 0x46, 0xE5), Data.Theme.Palette(false).Accent);
        Assert.Equal(new ThemeColor(0x2E, 0, 0, 0), Data.Theme.Palette(false).KeyShadow);
        Assert.Equal("Claude", Data.FindProvider("anthropic")!.ShortName);
        Assert.Equal("Copilot", Data.FindProvider("github")!.ShortName);
    }

    [Fact]
    public void DefaultModelComesFromProvidersJson()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(TestPaths.SharedFile("providers.json")));
        var settings = new SettingsStore(Data);
        foreach (var p in doc.RootElement.GetProperty("providers").EnumerateArray())
        {
            var id = p.GetProperty("id").GetString()!;
            Assert.Equal(p.GetProperty("defaultModel").GetString(), settings.GetModel(id));
        }
    }

    [Fact]
    public void LiveTriggerIsDefaultAndAllowed()
    {
        var settings = new SettingsStore(Data);
        Assert.Equal(Triggers.Live, settings.Trigger);
        Assert.Equal(Triggers.All, Data.AllowedStrings("trigger"));
        Assert.True(Data.Strings.Has("settings.triggerLive"));
        Assert.Equal(Triggers.Live, new EngineOptions().Trigger);
    }

    [Fact]
    public void StringsLookup()
    {
        Assert.Equal("Ersetzen", Data.Strings["tips.replace"]);
        Assert.Equal(4, Data.Strings.GetList("tips.emotionLevels").Count);
        Assert.Equal("fallback", Data.Strings.Get("does.not.exist", "fallback"));
        Assert.Equal(AiErrors.Message(AiErrorKind.InvalidKey), Data.Strings["errors.invalidKey"]);
        foreach (var kind in Enum.GetValues<AiErrorKind>())
            Assert.True(Data.Strings.Has(AiErrors.StringKey(kind)), kind.ToString());
    }
}

public class PromptTests
{
    private static readonly SharedData Data = SharedData.Default;
    private readonly PromptBuilder _builder = new(Data);

    [Fact]
    public void KeyboardLayoutDescription()
    {
        Assert.Equal("QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm",
            PromptBuilder.KeyboardLayoutDescription(Data.FindKeyboard("qwertz")!));
        Assert.Equal("QWERTY: qwertyuiop / asdfghjkl / zxcvbnm", _builder.KeyboardLayoutDescription("qwerty"));
        Assert.Equal("AZERTY (Français): azertyuiop / qsdfghjklm / wxcvbn'", _builder.KeyboardLayoutDescription("azerty"));
    }

    [Fact]
    public void CorrectionPromptFillsAllPlaceholders()
    {
        var lang = Data.FindLanguage("de-CH")!;
        var p = _builder.Correction(lang, "normal", "qwertz", "Vorher.", "ich bin hir ");
        Assert.DoesNotContain("{{", p.System);
        Assert.DoesNotContain("{{", p.User);
        Assert.Contains(lang.PromptName, p.System);
        Assert.Contains(lang.Rules, p.System);
        Assert.Contains(Data.PromptParts.StrengthRules["normal"], p.System);
        Assert.Contains("Keyboard: QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm", p.System);
        Assert.Equal("<context>Vorher.</context>\n<segment>ich bin hir </segment>", p.User.TrimEnd('\n'));
        Assert.False(p.Json);
    }

    [Fact]
    public void PlaceholdersAreReplacedLiterallyOnce()
    {
        var result = PromptBuilder.Fill("<a>{{x}}</a><b>{{y}}</b>", new Dictionary<string, string> { ["x"] = "{{y}}", ["y"] = "Y" });
        Assert.Equal("<a>{{y}}</a><b>Y</b>", result);
    }

    [Fact]
    public void TipsPromptVariantsAndDisabledSections()
    {
        var lang = Data.FindLanguage("de-CH")!;
        var p = _builder.Tips(lang, new[] { "formal", "shorter" }, new TipsSections(Rewrites: true, Explanations: false, Writing: true, Emotion: false),
            "Text", new[] { new HistoryEntry("ich bin hir ", "Ich bin hier "), new HistoryEntry("ok ", "ok ") });
        Assert.True(p.Json);
        Assert.Contains("formal (\"Formeller\": more formal and polite), shorter (\"Kürzer\": shorter and to the point)", p.System);
        Assert.Contains("explanations, emotion", p.System);
        Assert.Contains(Data.PromptParts.UiLanguage, p.System);
        Assert.Contains("<text>Text</text>", p.User);
        Assert.Contains("ich bin hir -> Ich bin hier", p.User);
        Assert.DoesNotContain("ok -> ok", p.User);

        var none = _builder.Tips(lang, Array.Empty<string>(), new TipsSections(), "T", Array.Empty<HistoryEntry>());
        Assert.Contains("(none)", none.User);
        Assert.Contains("rewrites", none.System); // no variants → rewrites disabled
    }

    [Fact]
    public void MoodTranslateAndTestPrompts()
    {
        var mood = _builder.Mood("Das ist UNFASSBAR!!!");
        Assert.True(mood.Json);
        Assert.Equal("Das ist UNFASSBAR!!!", mood.User);
        Assert.Contains(Data.PromptParts.UiLanguage, mood.System);

        var tr = _builder.Translate(Data.FindTranslateTarget("en")!, "Hallo");
        Assert.Contains("English", tr.System);
        Assert.Equal("Hallo", tr.User);

        var test = _builder.Test(Data.FindLanguage("de-CH")!, "normal", "qwertz");
        Assert.Equal(AiTask.Test, test.Task);
        Assert.Contains("<context></context>", test.User);
        Assert.Contains(Data.PromptParts.TestSegment, test.User);
    }

    [Fact]
    public void TipsAndMoodJsonParsing()
    {
        var tips = AiJson.ParseTips("""
            Hier: ```json
            {"rewrites":[{"id":"formal","label":"Formeller","text":"Guten Tag"}],
             "explanations":[{"from":"hir","to":"hier","why":"Tippfehler"}],
             "tips":["Kürzer"],
             "emotion":{"level":"2","label":"Gereizt","reason":"Viele !","calmer":"Ruhig"}}
            ```
            """);
        Assert.Single(tips.Rewrites);
        Assert.Equal("Guten Tag", tips.Rewrites[0].Text);
        Assert.Equal("hier", tips.Explanations[0].To);
        Assert.Equal("Kürzer", tips.Tips[0]);
        Assert.Equal(2, tips.Emotion.Level);
        Assert.Equal("Ruhig", tips.Emotion.Calmer);

        var partial = AiJson.ParseTips("{\"tips\": []}");
        Assert.Empty(partial.Rewrites);
        Assert.Equal(0, partial.Emotion.Level);

        Assert.Equal(new MoodResult(3, "Wut"), AiJson.ParseMood("{\"level\": 3, \"reason\": \"Wut\"}"));
        var ex = Assert.Throws<AiException>(() => AiJson.ParseMood("kein json"));
        Assert.Equal(AiErrorKind.BadResponse, ex.Kind);
        Assert.Equal(AiErrorKind.BadResponse, Assert.Throws<AiException>(() => AiJson.ParseTips("{oops}")).Kind);
    }
}
