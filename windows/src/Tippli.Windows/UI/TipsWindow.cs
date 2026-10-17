using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Tippli.Core.Ai;
using Tippli.Windows.Interop;
using Tippli.Windows.Services;

namespace Tippli.Windows.UI;

/// <summary>The desktop tips window (SPEC §7.2, §10).</summary>
internal sealed class TipsWindow : Window
{
    private readonly DesktopController _c;
    private readonly TipsContext _ctx;
    private readonly StackPanel _body = new();
    private readonly ScrollViewer _scroll;
    private readonly CancellationTokenSource _cts = new();
    private FrameworkElement? _emotionSection;

    public TipsWindow(DesktopController controller, TipsContext context)
    {
        _c = controller;
        _ctx = context;
        var s = controller.Strings;
        Title = s.Get("tips.title", "Tipps") + " – Tippli";
        Width = 500;
        Height = 660;
        MinWidth = 380;
        MinHeight = 360;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Topmost = true;
        ShowInTaskbar = true;
        UiKit.SetupWindow(this, controller.Theme);

        var root = new Grid { Margin = new Thickness(20, 16, 20, 16) };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        // header: "Tipps · Für die Auswahl | Für den ganzen Text" + close
        var header = new DockPanel { LastChildFill = true, Margin = new Thickness(0, 0, 0, 10) };
        var close = UiKit.Button(s.Get("tips.close", "Schliessen"), Close);
        DockPanel.SetDock(close, Dock.Right);
        header.Children.Add(close);
        var titleRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        titleRow.Children.Add(UiKit.Text("💡 " + s.Get("tips.title", "Tipps"), 20, FontWeights.SemiBold, wrap: false));
        var scope = UiKit.Text(" · " + (context.IsSelection ? s.Get("tips.scopeSelection", "Für die Auswahl") : s.Get("tips.scopeAll", "Für den ganzen Text")),
            14, null, "Tippli.secondaryText", wrap: false);
        scope.VerticalAlignment = VerticalAlignment.Center;
        titleRow.Children.Add(scope);
        header.Children.Add(titleRow);
        root.Children.Add(header);

        if (context.Text.Trim().Length > 0)
        {
            var preview = UiKit.Text(context.Text, 12.5, null, "Tippli.secondaryText");
            preview.MaxHeight = 70;
            preview.TextTrimming = TextTrimming.CharacterEllipsis;
            var previewBorder = new Border { Child = preview, Padding = new Thickness(12, 8, 12, 8), CornerRadius = new CornerRadius(10), Margin = new Thickness(0, 0, 0, 12) };
            previewBorder.SetResourceReference(Border.BackgroundProperty, "Tippli.background");
            Grid.SetRow(previewBorder, 1);
            root.Children.Add(previewBorder);
        }

        _scroll = new ScrollViewer { Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, PanningMode = PanningMode.VerticalOnly };
        Grid.SetRow(_scroll, 2);
        root.Children.Add(_scroll);
        Content = root;

        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) Close();
        };
        Loaded += (_, _) => Start();
        Closed += (_, _) => _cts.Cancel();
    }

    private Tippli.Core.Shared.Strings S => _c.Strings;

    private void Start()
    {
        _body.Children.Clear();
        if (_ctx.Text.Trim().Length == 0)
        {
            _body.Children.Add(UiKit.Text(S.Get("tips.emptyText", "Schreib zuerst etwas – dann gibt's Tipps."), 14, null, "Tippli.secondaryText"));
            return;
        }
        var st = _c.Settings;
        bool anyAnalysis = st.TipsRewrites || st.TipsExplanations || st.TipsWriting || st.TipsEmotion;
        if (anyAnalysis) _ = LoadTipsAsync();
        else if (st.TipsTranslate) _body.Children.Add(TranslateSection());
    }

    private async Task LoadTipsAsync()
    {
        _body.Children.Clear();
        var loading = new StackPanel { Margin = new Thickness(0, 20, 0, 0) };
        loading.Children.Add(new ProgressBar { IsIndeterminate = true, Margin = new Thickness(0, 0, 0, 10) });
        loading.Children.Add(UiKit.Text(S.Get("tips.loading", "Analysiere deinen Text…"), 13, null, "Tippli.secondaryText"));
        _body.Children.Add(loading);
        if (_c.Settings.TipsTranslate) _body.Children.Add(TranslateSection());
        try
        {
            var result = await _c.Ai.TipsAsync(_ctx.Text, _ctx.History, _cts.Token);
            Render(result);
        }
        catch (OperationCanceledException)
        {
        }
        catch (AiException ex)
        {
            RenderError(AiErrors.Message(ex.Kind));
        }
        catch (Exception ex)
        {
            Log.Error("tips request", ex);
            RenderError(AiErrors.Message(AiErrorKind.BadResponse));
        }
    }

    private void RenderError(string message)
    {
        _body.Children.Clear();
        var panel = new StackPanel();
        panel.Children.Add(UiKit.Text(message, 14, null, "Tippli.danger").WithMargin(0, 0, 0, 10));
        panel.Children.Add(UiKit.Button(S.Get("tips.retry", "Nochmals versuchen"), () => _ = LoadTipsAsync(), accent: true));
        _body.Children.Add(UiKit.CardOf(panel));
        if (_c.Settings.TipsTranslate) _body.Children.Add(TranslateSection());
    }

    private void Render(TipsResult r)
    {
        var st = _c.Settings;
        _body.Children.Clear();

        if (st.TipsRewrites && r.Rewrites.Count > 0)
        {
            _body.Children.Add(UiKit.Header(S.Get("tips.rewrites", "Umformulierungen")));
            foreach (var rw in r.Rewrites)
            {
                var label = string.IsNullOrWhiteSpace(rw.Label)
                    ? _c.Data.PromptParts.RewriteVariants.FirstOrDefault(v => v.Id == rw.Id)?.Label ?? rw.Id
                    : rw.Label;
                _body.Children.Add(TextCard(label, rw.Text, insert: false));
            }
        }

        if (st.TipsExplanations)
        {
            _body.Children.Add(UiKit.Header(S.Get("tips.explanations", "Was wurde korrigiert?")).WithMargin(0, 8, 0, 8));
            if (r.Explanations.Count == 0)
            {
                _body.Children.Add(UiKit.Hint(S.Get("tips.noCorrections", "Noch keine Korrekturen in diesem Text.")).WithMargin(0, 0, 0, 12));
            }
            else
            {
                var list = new StackPanel();
                foreach (var e in r.Explanations)
                {
                    var line = $"{e.From} → {e.To}" + (string.IsNullOrWhiteSpace(e.Why) ? "" : ": " + e.Why);
                    list.Children.Add(UiKit.Text("• " + line).WithMargin(0, 2, 0, 2));
                }
                _body.Children.Add(UiKit.CardOf(list));
            }
        }

        if (st.TipsWriting)
        {
            _body.Children.Add(UiKit.Header(S.Get("tips.writing", "Schreibtipps")).WithMargin(0, 8, 0, 8));
            if (r.Tips.Count == 0)
            {
                _body.Children.Add(UiKit.Hint(S.Get("tips.noTips", "Keine Tipps – der Text ist gut so.")).WithMargin(0, 0, 0, 12));
            }
            else
            {
                var list = new StackPanel();
                foreach (var tip in r.Tips) list.Children.Add(UiKit.Text("• " + tip).WithMargin(0, 2, 0, 2));
                _body.Children.Add(UiKit.CardOf(list));
            }
        }

        if (st.TipsEmotion)
        {
            var section = new StackPanel();
            section.Children.Add(UiKit.Header(S.Get("tips.emotion", "Emotions-Check")).WithMargin(0, 8, 0, 8));
            var levels = S.GetList("tips.emotionLevels");
            int level = Math.Clamp(r.Emotion.Level, 0, 3);
            var levelText = level < levels.Count ? levels[level] : level.ToString();
            if (!string.IsNullOrWhiteSpace(r.Emotion.Label) && !string.Equals(r.Emotion.Label, levelText, StringComparison.OrdinalIgnoreCase))
                levelText += " · " + r.Emotion.Label;
            var color = level >= 3 ? "Tippli.danger" : level == 2 ? "Tippli.warning" : level == 1 ? "Tippli.keyText" : "Tippli.success";
            var info = new StackPanel();
            info.Children.Add(UiKit.Text(levelText, 14, FontWeights.SemiBold, color));
            if (!string.IsNullOrWhiteSpace(r.Emotion.Reason)) info.Children.Add(UiKit.Text(r.Emotion.Reason).WithMargin(0, 4, 0, 0));
            section.Children.Add(UiKit.CardOf(info));
            if (level > 0 && !string.IsNullOrWhiteSpace(r.Emotion.Calmer))
                section.Children.Add(TextCard(S.Get("tips.calmer", "Ruhigere Version"), r.Emotion.Calmer, insert: false));
            _emotionSection = section;
            _body.Children.Add(section);
        }

        if (st.TipsTranslate) _body.Children.Add(TranslateSection());

        if (_ctx.ScrollToEmotion && _emotionSection != null)
            Dispatcher.BeginInvoke(DispatcherPriority.Loaded, () => _emotionSection.BringIntoView());
    }

    /// <summary>A card with a label, the text and Ersetzen/(Einfügen)/Kopieren. Clicking the text replaces.</summary>
    private Border TextCard(string label, string text, bool insert)
    {
        var panel = new StackPanel();
        panel.Children.Add(UiKit.Text(label, 12, FontWeights.SemiBold, "Tippli.accent").WithMargin(0, 0, 0, 4));
        var body = UiKit.Text(text, 14);
        body.Cursor = Cursors.Hand;
        body.MouseLeftButtonUp += (_, _) => Apply(text, false);
        panel.Children.Add(body);
        var buttons = UiKit.Row();
        buttons.Margin = new Thickness(0, 10, 0, 0);
        var replace = UiKit.Button(S.Get("tips.replace", "Ersetzen"), () => Apply(text, false), accent: true);
        replace.Margin = new Thickness(0, 0, 8, 0);
        buttons.Children.Add(replace);
        if (insert)
        {
            var ins = UiKit.Button(S.Get("tips.insert", "Einfügen"), () => Apply(text, true));
            ins.Margin = new Thickness(0, 0, 8, 0);
            buttons.Children.Add(ins);
        }
        Button? copy = null;
        copy = UiKit.Button(S.Get("tips.copy", "Kopieren"), () => Copy(text, copy!));
        buttons.Children.Add(copy);
        panel.Children.Add(buttons);
        return UiKit.CardOf(panel);
    }

    private FrameworkElement TranslateSection()
    {
        var section = new StackPanel();
        section.Children.Add(UiKit.Header(S.Get("tips.translate", "Übersetzen")).WithMargin(0, 8, 0, 8));
        var chips = new WrapPanel();
        var result = new StackPanel();
        foreach (var id in _c.Settings.TranslateTargets)
        {
            var target = _c.Data.FindTranslateTarget(id);
            if (target == null) continue;
            chips.Children.Add(UiKit.Chip(target.Name, () => _ = TranslateAsync(target.Id, target.Name, result)));
        }
        section.Children.Add(chips);
        section.Children.Add(result);
        return section;
    }

    private async Task TranslateAsync(string targetId, string name, StackPanel result)
    {
        result.Children.Clear();
        result.Children.Add(new ProgressBar { IsIndeterminate = true, Margin = new Thickness(0, 6, 0, 10) });
        try
        {
            var text = await _c.Ai.TranslateAsync(targetId, _ctx.Text, _cts.Token);
            result.Children.Clear();
            result.Children.Add(TextCard(name, text, insert: true));
        }
        catch (OperationCanceledException)
        {
        }
        catch (AiException ex)
        {
            result.Children.Clear();
            result.Children.Add(UiKit.Text(AiErrors.Message(ex.Kind), 13, null, "Tippli.danger").WithMargin(0, 4, 0, 8));
        }
        catch (Exception ex)
        {
            Log.Error("translate", ex);
            result.Children.Clear();
        }
    }

    private void Apply(string text, bool insert)
    {
        Close();
        _ = _c.ApplyFromTipsAsync(_ctx, text, insert);
    }

    private void Copy(string text, Button button)
    {
        if (!ClipboardService.SetText(text)) return;
        var original = button.Content;
        button.Content = S.Get("tips.copied", "Kopiert") + " ✓";
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1500) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            button.Content = original;
        };
        timer.Start();
    }
}
