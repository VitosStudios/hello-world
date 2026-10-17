using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Tippli.Core.Ai;
using Tippli.Core.Engine;
using Tippli.Core.Models;
using Tippli.Core.Settings;
using Tippli.Core.Shared;
using Tippli.Windows.Services;

namespace Tippli.Windows.UI;

/// <summary>
/// Settings / companion window (SPEC §7.3): KI-Anbieter, Korrektur, Sprachen, Tastatur, Tipps, Datenschutz,
/// Computer (Mac/PC), Über Tippli. First run: welcome + "connect AI" + "try it" (onboarding).
/// </summary>
internal sealed class SettingsWindow : Window
{
    private readonly DesktopController _c;
    private readonly bool _welcome;
    private readonly ListBox _nav = new() { Margin = new Thickness(10, 6, 10, 10) };
    private readonly ScrollViewer _scroll;
    private readonly List<(string Id, string Title, Func<UIElement> Build)> _pages;
    private CorrectionEngine? _tryEngine;
    private bool _navigating;

    public SettingsWindow(DesktopController controller, bool welcome)
    {
        _c = controller;
        _welcome = welcome;
        Title = S.Get("settings.title", "Einstellungen") + " – Tippli";
        Width = 920;
        Height = 720;
        MinWidth = 740;
        MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        UiKit.SetupWindow(this, controller.Theme);

        _pages = new()
        {
            ("provider", S.Get("settings.sectionProvider", "KI-Anbieter"), BuildProvider),
            ("correction", S.Get("settings.sectionCorrection", "Korrektur"), BuildCorrection),
            ("languages", S.Get("settings.sectionLanguages", "Sprachen"), BuildLanguages),
            ("keyboard", S.Get("settings.sectionKeyboard", "Tastatur"), BuildKeyboard),
            ("tips", S.Get("settings.sectionTips", "Tipps"), BuildTips),
            ("privacy", S.Get("settings.sectionPrivacy", "Datenschutz"), BuildPrivacy),
            ("desktop", S.Get("settings.sectionDesktop", "Computer (Mac/PC)"), BuildDesktop),
            ("about", S.Get("settings.sectionAbout", "Über Tippli"), BuildAbout),
        };

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(236) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // left: brand + navigation
        var left = new DockPanel();
        left.SetResourceReference(Panel.BackgroundProperty, "Tippli.background");
        var brand = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(20, 22, 12, 14) };
        if (UiKit.AppIcon != null) brand.Children.Add(new Image { Source = UiKit.AppIcon, Width = 36, Height = 36, Margin = new Thickness(0, 0, 10, 0) });
        var brandText = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        brandText.Children.Add(UiKit.Text(S.Get("app.name", "Tippli"), 18, FontWeights.SemiBold, wrap: false));
        brandText.Children.Add(UiKit.Text(S.Get("app.tagline", ""), 11, null, "Tippli.secondaryText", wrap: true));
        brandText.MaxWidth = 160;
        brand.Children.Add(brandText);
        DockPanel.SetDock(brand, Dock.Top);
        left.Children.Add(brand);
        foreach (var (id, title, _) in _pages)
        {
            var item = new ListBoxItem { Content = title, Tag = id };
            item.SetResourceReference(StyleProperty, "Tippli.NavItem");
            _nav.Items.Add(item);
        }
        _nav.SelectionChanged += (_, _) =>
        {
            if (!_navigating && _nav.SelectedItem is ListBoxItem { Tag: string id }) ShowPage(id);
        };
        left.Children.Add(_nav);
        grid.Children.Add(left);

        _scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(32, 26, 32, 26) };
        Grid.SetColumn(_scroll, 1);
        grid.Children.Add(_scroll);
        Content = grid;

        Closed += (_, _) =>
        {
            _tryEngine = null;
            if (!_c.State.FirstRunDone)
            {
                _c.State.FirstRunDone = true;
                _c.State.Save();
            }
        };
        ShowPage("provider");
    }

    private Strings S => _c.Strings;
    private SettingsStore St => _c.Settings;

    public void ShowPage(string id)
    {
        var page = _pages.FirstOrDefault(p => p.Id == id);
        if (page.Build == null) return;
        _navigating = true;
        foreach (ListBoxItem item in _nav.Items)
            if ((string)item.Tag == id) _nav.SelectedItem = item;
        _navigating = false;
        _tryEngine = null;
        var content = new StackPanel { MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        content.Children.Add(page.Build());
        _scroll.Content = content;
        _scroll.ScrollToTop();
    }

    private void Rebuild(string id) => Dispatcher.BeginInvoke(DispatcherPriority.Background, () => ShowPage(id));

    private static StackPanel Stack(params UIElement[] children)
    {
        var p = new StackPanel();
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    // ---- KI-Anbieter ------------------------------------------------------------------------------------

    private UIElement BuildProvider()
    {
        var root = new StackPanel();
        if (_welcome || !_c.State.FirstRunDone) root.Children.Add(WelcomeCard());
        root.Children.Add(UiKit.Title(S.Get("settings.sectionProvider", "KI-Anbieter")));

        var preset = St.ActiveProvider;

        // provider picker
        var providerBox = new ComboBox { MinWidth = 320, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var p in _c.Data.Providers.Providers)
        {
            var item = new ComboBoxItem { Content = p.Name, Tag = p.Id };
            providerBox.Items.Add(item);
            if (p.Id == preset.Id) providerBox.SelectedItem = item;
        }
        providerBox.SelectionChanged += (_, _) =>
        {
            if (providerBox.SelectedItem is ComboBoxItem { Tag: string pid } && pid != St.Provider)
            {
                St.Provider = pid;
                Rebuild("provider");
            }
        };

        // API key (PasswordBox with reveal)
        var stored = _c.Secrets.Get(SecretKeys.ApiKey(preset.Id)) ?? "";
        var pwd = new PasswordBox { Password = stored, MinWidth = 320 };
        var plain = new TextBox { Text = stored, MinWidth = 320, Visibility = Visibility.Collapsed };
        var keyStatus = UiKit.Hint(stored.Length > 0 ? "🔒 " + S.Get("settings.apiKeySaved", "Gespeichert (verschlüsselt)") : "");
        bool syncing = false;
        var saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        string CurrentKey() => plain.Visibility == Visibility.Visible ? plain.Text.Trim() : pwd.Password.Trim();
        saveTimer.Tick += (_, _) =>
        {
            saveTimer.Stop();
            var key = CurrentKey();
            _c.Secrets.Set(SecretKeys.ApiKey(preset.Id), key);
            keyStatus.Text = key.Length > 0 ? "🔒 " + S.Get("settings.apiKeySaved", "Gespeichert (verschlüsselt)") : "";
            _c.OnApiKeyChanged();
        };
        pwd.PasswordChanged += (_, _) =>
        {
            if (syncing) return;
            syncing = true;
            plain.Text = pwd.Password;
            syncing = false;
            saveTimer.Stop();
            saveTimer.Start();
        };
        plain.TextChanged += (_, _) =>
        {
            if (syncing) return;
            syncing = true;
            pwd.Password = plain.Text;
            syncing = false;
            saveTimer.Stop();
            saveTimer.Start();
        };
        var reveal = new Button { Content = "👁", Padding = new Thickness(10, 5, 10, 5), Margin = new Thickness(8, 0, 0, 0), ToolTip = S.Get("desktop.reveal", "Anzeigen") };
        reveal.Click += (_, _) =>
        {
            bool show = plain.Visibility != Visibility.Visible;
            plain.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
            pwd.Visibility = show ? Visibility.Collapsed : Visibility.Visible;
        };
        var keyRow = new StackPanel { Orientation = Orientation.Horizontal };
        keyRow.Children.Add(new Grid { Children = { pwd, plain } });
        keyRow.Children.Add(reveal);

        var providerCard = UiKit.Card(
            UiKit.Labeled(S.Get("settings.provider", "Anbieter"), providerBox),
            UiKit.Labeled(S.Get("settings.apiKey", "API-Schlüssel"), keyRow),
            UiKit.Labeled("", Stack(
                UiKit.Hint(preset.KeyHint),
                keyStatus,
                string.IsNullOrWhiteSpace(preset.KeyUrl) ? new TextBlock() : UiKit.Link(S.Get("settings.getKey", "Schlüssel holen") + " ↗", preset.KeyUrl))));
        root.Children.Add(providerCard);

        // model
        var currentModel = St.GetModel(preset.Id);
        var modelBox = new ComboBox { MinWidth = 320, HorizontalAlignment = HorizontalAlignment.Left };
        var custom = new TextBox { Text = currentModel, MinWidth = 320, HorizontalAlignment = HorizontalAlignment.Left };
        bool modelSync = false;
        void FillModels(IEnumerable<(string Id, string Label)> models)
        {
            modelSync = true;
            modelBox.Items.Clear();
            foreach (var (mid, label) in models)
            {
                var item = new ComboBoxItem { Content = label, Tag = mid };
                modelBox.Items.Add(item);
                if (mid == St.GetModel(preset.Id)) modelBox.SelectedItem = item;
            }
            modelSync = false;
        }
        var presetModels = preset.Models.Select(m => (m.Id, string.IsNullOrWhiteSpace(m.Label) ? m.Id : m.Label)).ToList();
        if (!presetModels.Any(m => m.Id == currentModel) && currentModel.Length > 0) presetModels.Add((currentModel, currentModel));
        FillModels(presetModels);
        modelBox.SelectionChanged += (_, _) =>
        {
            if (modelSync || modelBox.SelectedItem is not ComboBoxItem { Tag: string mid }) return;
            St.SetModel(preset.Id, mid);
            custom.Text = mid;
        };
        void ApplyCustom()
        {
            var text = custom.Text.Trim();
            if (text.Length == 0 || text == St.GetModel(preset.Id)) return;
            St.SetModel(preset.Id, text);
            modelSync = true;
            modelBox.SelectedItem = modelBox.Items.OfType<ComboBoxItem>().FirstOrDefault(i => (string)i.Tag == text);
            modelSync = false;
        }
        custom.LostFocus += (_, _) => ApplyCustom();
        custom.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) ApplyCustom();
        };

        var modelsStatus = UiKit.Hint("");
        Button? loadButton = null;
        loadButton = UiKit.Button(S.Get("settings.loadModels", "Modelle laden"), async () =>
        {
            loadButton!.IsEnabled = false;
            modelsStatus.Text = "…";
            try
            {
                var ids = await _c.Ai.ListModelsAsync(_c.Ai.ConfigFor(preset, apiKey: CurrentKey()));
                var merged = presetModels.Concat(ids.Where(i => presetModels.All(p => p.Id != i)).Select(i => (i, i))).ToList();
                FillModels(merged);
                modelsStatus.Text = $"{ids.Count} ✓";
            }
            catch (AiException ex)
            {
                modelsStatus.Text = AiErrors.Message(ex.Kind) + (ex.Detail != null ? $" ({ex.Detail})" : "");
            }
            catch (Exception ex)
            {
                modelsStatus.Text = ex.Message;
            }
            finally
            {
                loadButton.IsEnabled = true;
            }
        });
        var modelCard = UiKit.Card(
            UiKit.Labeled(S.Get("settings.model", "Modell"), modelBox),
            UiKit.Labeled(S.Get("settings.customModel", "Eigenes Modell"), custom),
            UiKit.Labeled("", Stack(loadButton.WithMargin(0, 6, 0, 0), modelsStatus)));
        root.Children.Add(modelCard);

        // base url (custom server)
        TextBox? urlBox = null;
        if (preset.BaseUrlEditable)
        {
            urlBox = new TextBox { Text = St.BaseUrlFor(preset), MinWidth = 320, HorizontalAlignment = HorizontalAlignment.Left };
            urlBox.LostFocus += (_, _) =>
            {
                var url = urlBox.Text.Trim();
                if (url.Length > 0) St.CustomBaseUrl = url;
            };
            root.Children.Add(UiKit.Card(UiKit.Labeled(S.Get("settings.baseUrl", "Server-Adresse"), urlBox)));
        }

        // connection test
        var testStatus = UiKit.Text("", 13).WithMargin(0, 8, 0, 0);
        Button? testButton = null;
        testButton = UiKit.Button(S.Get("settings.test", "Verbindung testen"), async () =>
        {
            testButton!.IsEnabled = false;
            testStatus.SetResourceReference(TextBlock.ForegroundProperty, "Tippli.secondaryText");
            testStatus.Text = S.Get("settings.testRunning", "Teste…");
            try
            {
                var config = _c.Ai.ConfigFor(preset, model: custom.Text, apiKey: CurrentKey(), baseUrl: urlBox?.Text);
                var output = await _c.Ai.TestAsync(config);
                testStatus.SetResourceReference(TextBlock.ForegroundProperty, "Tippli.success");
                testStatus.Text = S.Get("settings.testOk", "Verbindung klappt ✓") + "\n„" + _c.Data.PromptParts.TestSegment + "“ → „" + output + "“";
                _c.OnApiKeyChanged();
            }
            catch (AiException ex)
            {
                testStatus.SetResourceReference(TextBlock.ForegroundProperty, "Tippli.danger");
                testStatus.Text = AiErrors.Message(ex.Kind) + (string.IsNullOrWhiteSpace(ex.Detail) ? "" : "\n" + ex.Detail);
            }
            catch (Exception ex)
            {
                testStatus.SetResourceReference(TextBlock.ForegroundProperty, "Tippli.danger");
                testStatus.Text = ex.Message;
            }
            finally
            {
                testButton.IsEnabled = true;
            }
        }, accent: true);
        root.Children.Add(UiKit.Card(testButton, testStatus));

        root.Children.Add(TryItCard());
        return root;
    }

    private UIElement WelcomeCard()
    {
        var panel = new StackPanel();
        panel.Children.Add(UiKit.Text(S.Get("onboarding.welcomeTitle", "Willkommen bei Tippli"), 22, FontWeights.SemiBold).WithMargin(0, 0, 0, 6));
        panel.Children.Add(UiKit.Text(S.Get("onboarding.welcomeText", ""), 14).WithMargin(0, 0, 0, 12));
        panel.Children.Add(UiKit.Text("① " + S.Get("onboarding.step2", "KI verbinden") + " – " +
            S.Get("desktop.welcomeConnect", "Anbieter wählen, API-Schlüssel einfügen, „Verbindung testen“."), 13).WithMargin(0, 2, 0, 2));
        panel.Children.Add(UiKit.Text("② " + S.Get("onboarding.step3", "Ausprobieren") + " – " +
            S.Get("onboarding.tryHere", "Hier ausprobieren: tipp absichtlich schlampig …"), 13).WithMargin(0, 2, 0, 2));
        panel.Children.Add(UiKit.Text("③ " + S.Get("desktop.welcomeTray",
            "Tippli läuft im Infobereich der Taskleiste und korrigiert live in allen Programmen. Strg+Alt+K schaltet die Live-Korrektur ein/aus, Strg+Alt+B zeigt die Bildschirmtastatur."), 13).WithMargin(0, 2, 0, 0));
        var border = new Border
        {
            Child = panel,
            CornerRadius = new CornerRadius(14),
            Padding = new Thickness(20, 16, 20, 16),
            Margin = new Thickness(0, 0, 0, 20),
        };
        border.SetResourceReference(Border.BackgroundProperty, "Tippli.accentSoft");
        return border;
    }

    private UIElement TryItCard()
    {
        var box = new TextBox
        {
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 96,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            FontSize = 15,
        };
        var status = UiKit.Hint("");
        var host = new TextBoxTextHost(box);
        var engine = new CorrectionEngine(host, _c.Ai, _c.Clock, _c.Scheduler)
        {
            Options = new EngineOptions
            {
                AiEnabled = true,
                Trigger = St.Trigger,
                PauseMs = St.PauseMs,
                WaitOnSendMs = St.WaitOnSendMs,
                MoodWarning = false,
                Pinyin = St.ActiveLanguageInfo.Pinyin,
            },
        };
        host.Engine = engine;
        _tryEngine = engine;
        void UpdateStatus()
        {
            status.Text = engine.Status switch
            {
                EngineStatus.Working => S.Get("toolbar.aiWorking", "Korrigiert…"),
                EngineStatus.NoKey => S.Get("toolbar.aiNoKey", "Kein API-Schlüssel"),
                EngineStatus.Error => S.Get("toolbar.aiError", "KI-Fehler") + ": " + (engine.LastError is { } e ? AiErrors.Message(e) : ""),
                _ => S.Get("toolbar.aiOn", "KI an") + " · " + St.ActiveProvider.ShortName + " · " + St.GetModel(St.Provider),
            };
        }
        engine.StatusChanged += UpdateStatus;
        UpdateStatus();
        var undo = UiKit.Button("↶ " + S.Get("toolbar.undo", "Original"), () => engine.Undo());
        var correct = UiKit.Button("✨ " + S.Get("toolbar.correctNow", "Jetzt korrigieren"), () => _ = engine.FlushAsync(10_000));
        correct.Margin = new Thickness(8, 0, 0, 0);
        return UiKit.Card(
            UiKit.Header("✍ " + S.Get("onboarding.step3", "Ausprobieren")),
            UiKit.Hint(S.Get("onboarding.tryHere", "Hier ausprobieren: tipp absichtlich schlampig …")),
            box,
            status,
            UiKit.Row(undo, correct));
    }

    // ---- Korrektur -------------------------------------------------------------------------------------

    private UIElement BuildCorrection()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionCorrection", "Korrektur")));
        root.Children.Add(UiKit.Card(UiKit.Switch(S.Get("settings.aiEnabled", "KI-Korrektur aktiv"), St.AiEnabled, v => St.AiEnabled = v)));

        var pause = UiKit.Slider(S.Get("settings.pauseMs", "Pause bis zur Korrektur"), 300, 2000, 50, St.PauseMs, v => SettingsStore.FormatMs((int)v),
            v => St.PauseMs = (int)v);
        pause.IsEnabled = St.Trigger is Triggers.Live or Triggers.Pause;
        var triggers = UiKit.Radios(new[]
        {
            (Triggers.Live, S.Get("settings.triggerLive", "Live – Wort für Wort (empfohlen)")),
            (Triggers.Pause, S.Get("settings.triggerPause", "Bei kurzer Tipp-Pause")),
            (Triggers.Word, S.Get("settings.triggerWord", "Nach jedem Wort")),
            (Triggers.Sentence, S.Get("settings.triggerSentence", "Nach jedem Satz")),
            (Triggers.Manual, S.Get("settings.triggerManual", "Nur auf Knopfdruck")),
        }, St.Trigger, v =>
        {
            St.Trigger = v;
            pause.IsEnabled = v is Triggers.Live or Triggers.Pause;
        });
        root.Children.Add(UiKit.Card(UiKit.Header(S.Get("settings.trigger", "Wann korrigieren?")), triggers, pause));

        root.Children.Add(UiKit.Card(
            UiKit.Header(S.Get("settings.strength", "Korrektur-Stärke")),
            UiKit.Radios(new[]
            {
                ("light", S.Get("settings.strengthLight", "Leicht")),
                ("normal", S.Get("settings.strengthNormal", "Normal")),
                ("strong", S.Get("settings.strengthStrong", "Stark")),
            }, St.Strength, v => St.Strength = v)));

        root.Children.Add(UiKit.Card(
            UiKit.Slider(S.Get("settings.waitOnSend", "Vor dem Senden auf Korrektur warten"), 0, 4000, 250, St.WaitOnSendMs,
                v => v <= 0 ? S.Get("toolbar.aiOff", "aus") : SettingsStore.FormatMs((int)v), v => St.WaitOnSendMs = (int)v),
            UiKit.Switch(S.Get("settings.moodWarning", "Warnung bei emotionalem Text"), St.MoodWarning, v => St.MoodWarning = v)));
        return root;
    }

    // ---- Sprachen ---------------------------------------------------------------------------------------

    private UIElement BuildLanguages()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionLanguages", "Sprachen")));
        var enabled = St.Languages;
        var all = _c.Data.Languages.Languages;
        var ordered = enabled.Select(id => all.FirstOrDefault(l => l.Id == id)).Where(l => l != null).Select(l => l!)
            .Concat(all.Where(l => !enabled.Contains(l.Id))).ToList();

        var list = new StackPanel();
        foreach (var lang in ordered)
        {
            bool on = enabled.Contains(lang.Id);
            var row = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(250) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
            var check = UiKit.Check($"{lang.Name}  ({lang.Short})", on, v =>
            {
                var langs = St.Languages;
                if (v && !langs.Contains(lang.Id)) langs.Add(lang.Id);
                if (!v && langs.Count > 1) langs.Remove(lang.Id);
                St.Languages = langs;
                Rebuild("languages");
            });
            row.Children.Add(check);
            var layout = new ComboBox { IsEnabled = on, Margin = new Thickness(8, 0, 8, 0) };
            foreach (var (kbId, kb) in _c.Data.Layouts.Keyboards)
            {
                var item = new ComboBoxItem { Content = kb.Name, Tag = kbId };
                layout.Items.Add(item);
                if (kbId == St.GetKeyboardId(lang.Id)) layout.SelectedItem = item;
            }
            layout.SelectionChanged += (_, _) =>
            {
                if (layout.SelectedItem is ComboBoxItem { Tag: string kbId }) St.SetKeyboardId(lang.Id, kbId);
            };
            layout.ToolTip = S.Get("settings.layoutFor", "Layout");
            Grid.SetColumn(layout, 1);
            row.Children.Add(layout);
            int index = enabled.IndexOf(lang.Id);
            if (index > 0)
            {
                var up = new Button { Content = "↑", Padding = new Thickness(8, 3, 8, 3), ToolTip = S.Get("desktop.moveUp", "Nach oben") };
                up.Click += (_, _) =>
                {
                    var langs = St.Languages;
                    int i = langs.IndexOf(lang.Id);
                    if (i > 0)
                    {
                        (langs[i - 1], langs[i]) = (langs[i], langs[i - 1]);
                        St.Languages = langs;
                        Rebuild("languages");
                    }
                };
                Grid.SetColumn(up, 2);
                row.Children.Add(up);
            }
            list.Children.Add(row);
        }
        root.Children.Add(UiKit.Card(UiKit.Header(S.Get("settings.languages", "Aktive Sprachen")),
            UiKit.Hint(S.Get("settings.languagesHint", "")), list));

        var active = new ComboBox { MinWidth = 280, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var id in enabled)
        {
            var lang = _c.Data.FindLanguage(id);
            if (lang == null) continue;
            var item = new ComboBoxItem { Content = lang.Name, Tag = id };
            active.Items.Add(item);
            if (id == St.ActiveLanguage) active.SelectedItem = item;
        }
        active.SelectionChanged += (_, _) =>
        {
            if (active.SelectedItem is ComboBoxItem { Tag: string id }) St.ActiveLanguage = id;
        };
        root.Children.Add(UiKit.Card(UiKit.Labeled(S.Get("toolbar.language", "Sprache wechseln"), active)));
        return root;
    }

    // ---- Tastatur ---------------------------------------------------------------------------------------

    private UIElement BuildKeyboard()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionKeyboard", "Tastatur")));
        root.Children.Add(UiKit.Card(
            UiKit.Header(S.Get("settings.keyboardMode", "Tastatur-Form")),
            UiKit.Radios(new[]
            {
                ("hex", "⬡ " + S.Get("settings.modeHex", "Hexagon (Waben)")),
                ("normal", "▭ " + S.Get("settings.modeNormal", "Normal")),
            }, St.KeyboardMode, v => St.KeyboardMode = v),
            UiKit.Button(S.Get("settings.hotkeyKeyboard", "Bildschirmtastatur zeigen/verstecken"), () => _c.ToggleKeyboard()).WithMargin(0, 8, 0, 0)));

        root.Children.Add(UiKit.Card(
            UiKit.Switch(S.Get("settings.longPressAccents", "Akzente bei langem Drücken"), St.LongPressAccents, v => St.LongPressAccents = v),
            UiKit.Switch(S.Get("settings.longPressDigits", "Zahlen bei langem Drücken (obere Reihe)"), St.LongPressDigits, v => St.LongPressDigits = v),
            UiKit.Slider(S.Get("settings.longPressMs", "Dauer für langes Drücken"), 200, 800, 25, St.LongPressMs, v => $"{(int)v} ms",
                v => St.LongPressMs = (int)v)));

        root.Children.Add(UiKit.Card(
            UiKit.Header(S.Get("settings.keyHeight", "Tastenhöhe")),
            UiKit.Radios(new[]
            {
                ("small", S.Get("settings.keyHeightSmall", "Klein")),
                ("medium", S.Get("settings.keyHeightMedium", "Mittel")),
                ("large", S.Get("settings.keyHeightLarge", "Gross")),
            }, St.KeyHeight, v => St.KeyHeight = v)));

        root.Children.Add(UiKit.Card(
            UiKit.Switch(S.Get("settings.sound", "Tastenton"), St.Sound, v => St.Sound = v),
            UiKit.Switch(S.Get("settings.autoCapitalize", "Automatische Grossschreibung"), St.AutoCapitalize, v => St.AutoCapitalize = v),
            UiKit.Switch(S.Get("settings.doubleSpacePeriod", "Doppel-Leerzeichen = Punkt"), St.DoubleSpacePeriod, v => St.DoubleSpacePeriod = v),
            UiKit.Switch(S.Get("settings.numberRow", "Zahlenreihe (normale Tastatur)"), St.NumberRow, v => St.NumberRow = v)));

        root.Children.Add(UiKit.Card(
            UiKit.Header(S.Get("settings.theme", "Erscheinungsbild")),
            UiKit.Radios(new[]
            {
                ("system", S.Get("settings.themeSystem", "Wie System")),
                ("light", S.Get("settings.themeLight", "Hell")),
                ("dark", S.Get("settings.themeDark", "Dunkel")),
            }, St.Theme, v => St.Theme = v)));

        var gestures = new StackPanel();
        foreach (var key in new[] { "swipeUp", "swipeLeft", "swipeRight", "longPress", "spaceDrag", "backspaceHold", "shiftDouble" })
            gestures.Children.Add(UiKit.Text("• " + S.Get("gestures." + key, key)).WithMargin(0, 2, 0, 2));
        root.Children.Add(UiKit.Card(UiKit.Header(S.Get("gestures.title", "Gesten")), gestures));
        return root;
    }

    // ---- Tipps ------------------------------------------------------------------------------------------

    private UIElement BuildTips()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionTips", "Tipps")));
        root.Children.Add(UiKit.Card(
            UiKit.Header(S.Get("settings.tipsSections", "Bereiche im Tipps-Fenster")),
            UiKit.Switch(S.Get("tips.rewrites", "Umformulierungen"), St.TipsRewrites, v => St.TipsRewrites = v),
            UiKit.Switch(S.Get("tips.explanations", "Was wurde korrigiert?"), St.TipsExplanations, v => St.TipsExplanations = v),
            UiKit.Switch(S.Get("tips.writing", "Schreibtipps"), St.TipsWriting, v => St.TipsWriting = v),
            UiKit.Switch(S.Get("tips.translate", "Übersetzen"), St.TipsTranslate, v => St.TipsTranslate = v),
            UiKit.Switch(S.Get("tips.emotion", "Emotions-Check"), St.TipsEmotion, v => St.TipsEmotion = v)));

        var variants = new WrapPanel();
        foreach (var v in _c.Data.PromptParts.RewriteVariants)
        {
            var c = UiKit.Check(v.Label, St.RewriteVariants.Contains(v.Id), on => Toggle("rewriteVariants", v.Id, on));
            c.Margin = new Thickness(0, 4, 22, 4);
            variants.Children.Add(c);
        }
        root.Children.Add(UiKit.Card(UiKit.Header(S.Get("settings.rewriteVariants", "Umformulierungen")), variants));

        var targets = new WrapPanel();
        foreach (var t in _c.Data.Languages.TranslateTargets)
        {
            var c = UiKit.Check(t.Name, St.TranslateTargets.Contains(t.Id), on => Toggle("translateTargets", t.Id, on));
            c.Margin = new Thickness(0, 4, 22, 4);
            targets.Children.Add(c);
        }
        root.Children.Add(UiKit.Card(UiKit.Header(S.Get("settings.translateTargets", "Übersetzungs-Sprachen")), targets));
        return root;
    }

    private void Toggle(string key, string id, bool on)
    {
        var list = St.GetStringList(key);
        if (on && !list.Contains(id)) list.Add(id);
        if (!on) list.Remove(id);
        St.SetStringList(key, list);
    }

    // ---- Datenschutz ----------------------------------------------------------------------------------

    private UIElement BuildPrivacy()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionPrivacy", "Datenschutz")));
        root.Children.Add(UiKit.Card(
            UiKit.Text(S.Get("settings.privacyText", ""), 14),
            UiKit.Hint(S.Get("desktop.privacyDesktop",
                "API-Schlüssel werden mit Windows (DPAPI) verschlüsselt gespeichert. Passwort-Felder und ausgeschlossene Programme werden nie gelesen.")).WithMargin(0, 10, 0, 0),
            UiKit.Button(S.Get("desktop.openFolder", "Einstellungsordner öffnen"), () =>
            {
                try { Process.Start(new ProcessStartInfo("explorer.exe", SettingsStore.DefaultDirectory) { UseShellExecute = true }); }
                catch (Exception ex) { Log.Error("open folder", ex); }
            }).WithMargin(0, 10, 0, 0)));
        return root;
    }

    // ---- Computer (Mac/PC) --------------------------------------------------------------------------------

    private UIElement BuildDesktop()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionDesktop", "Computer (Mac/PC)")));
        root.Children.Add(UiKit.Card(
            UiKit.Switch(S.Get("settings.desktopLive", "Live-Korrektur in allen Programmen"), St.DesktopLiveCorrection, v => St.DesktopLiveCorrection = v),
            UiKit.Switch(S.Get("settings.desktopLogin", "Beim Anmelden starten"), St.DesktopLaunchAtLogin, v => St.DesktopLaunchAtLogin = v),
            UiKit.Switch(S.Get("settings.desktopKeyboardOnStart", "Bildschirmtastatur beim Start zeigen"), St.DesktopShowKeyboardOnStart,
                v => St.DesktopShowKeyboardOnStart = v)));

        // excluded apps editor
        var listBox = new ListBox { Height = 170, Margin = new Thickness(0, 4, 0, 8), BorderThickness = new Thickness(1) };
        listBox.SetResourceReference(Control.BorderBrushProperty, "Tippli.divider");
        void Refill()
        {
            listBox.Items.Clear();
            foreach (var app in St.DesktopExcludedApps) listBox.Items.Add(app);
        }
        Refill();
        var input = new TextBox { MinWidth = 220 };
        void Add(string name)
        {
            name = name.Trim();
            if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
            if (name.Length == 0) return;
            var apps = St.DesktopExcludedApps;
            if (!apps.Contains(name, StringComparer.OrdinalIgnoreCase)) apps.Add(name);
            St.DesktopExcludedApps = apps;
            input.Text = "";
            Refill();
        }
        input.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) Add(input.Text);
        };
        var add = UiKit.Button(S.Get("desktop.add", "Hinzufügen"), () => Add(input.Text));
        add.Margin = new Thickness(8, 0, 0, 0);
        var remove = UiKit.Button(S.Get("desktop.remove", "Entfernen"), () =>
        {
            if (listBox.SelectedItem is not string sel) return;
            var apps = St.DesktopExcludedApps;
            apps.RemoveAll(a => string.Equals(a, sel, StringComparison.OrdinalIgnoreCase));
            St.DesktopExcludedApps = apps;
            Refill();
        });
        remove.Margin = new Thickness(8, 0, 0, 0);
        var recent = new ComboBox { MinWidth = 220, ToolTip = S.Get("desktop.recentApps", "Zuletzt benutzte Programme") };
        foreach (var app in _c.RecentApps) recent.Items.Add(app);
        var addRecent = UiKit.Button(S.Get("desktop.add", "Hinzufügen"), () =>
        {
            if (recent.SelectedItem is string app) Add(app);
        });
        addRecent.Margin = new Thickness(8, 0, 0, 0);
        root.Children.Add(UiKit.Card(
            UiKit.Header(S.Get("settings.excludedApps", "Ausgeschlossene Programme")),
            UiKit.Hint(S.Get("desktop.excludedHint", "Programmname ohne .exe, z. B. KeePass oder WINWORD.")),
            listBox,
            UiKit.Row(input, add, remove),
            UiKit.Hint(S.Get("desktop.recentApps", "Zuletzt benutzte Programme")).WithMargin(0, 10, 0, 2),
            UiKit.Row(recent, addRecent)));

        // hotkeys
        var hotkeys = new StackPanel();
        foreach (var (letter, key) in new[] { ('K', "settings.hotkeyToggle"), ('H', "settings.hotkeyTips"), ('B', "settings.hotkeyKeyboard"), ('J', "settings.hotkeyCorrectNow") })
        {
            bool ok = !_c.HotkeyStatus.TryGetValue(letter, out var registered) || registered;
            var row = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(130) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var combo = UiKit.Text($"Strg+Alt+{letter}", 13, FontWeights.SemiBold, ok ? "Tippli.accent" : "Tippli.danger", wrap: false);
            row.Children.Add(combo);
            var label = UiKit.Text(S.Get(key, key) + (ok ? "" : "  – " + S.Get("desktop.hotkeyTaken", "von einem anderen Programm belegt")));
            Grid.SetColumn(label, 1);
            row.Children.Add(label);
            hotkeys.Children.Add(row);
        }
        root.Children.Add(UiKit.Card(UiKit.Header(S.Get("settings.hotkeys", "Tastenkürzel")), hotkeys));
        return root;
    }

    // ---- Über Tippli ----------------------------------------------------------------------------------

    private UIElement BuildAbout()
    {
        var root = new StackPanel();
        root.Children.Add(UiKit.Title(S.Get("settings.sectionAbout", "Über Tippli")));
        var header = new StackPanel { Orientation = Orientation.Horizontal };
        if (UiKit.AppIcon != null) header.Children.Add(new Image { Source = UiKit.AppIcon, Width = 84, Height = 84, Margin = new Thickness(0, 0, 18, 0) });
        var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.0.0";
        var text = Stack(
            UiKit.Text(S.Get("app.keyboardName", "Tippli KI-Tastatur"), 20, FontWeights.SemiBold),
            UiKit.Text(S.Get("app.tagline", ""), 14, null, "Tippli.secondaryText"),
            UiKit.Text($"Version {version} · Windows", 12, null, "Tippli.secondaryText").WithMargin(0, 6, 0, 0));
        text.VerticalAlignment = VerticalAlignment.Center;
        header.Children.Add(text);
        root.Children.Add(UiKit.CardOf(header));
        root.Children.Add(UiKit.Card(UiKit.Text(S.Get("settings.privacyText", ""), 13, null, "Tippli.secondaryText")));
        return root;
    }
}
