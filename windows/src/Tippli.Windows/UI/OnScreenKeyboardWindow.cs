using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Shell;
using System.Windows.Threading;
using Tippli.Core.Engine;
using Tippli.Core.Keyboard;
using Tippli.Windows.Services;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.UI;

/// <summary>
/// The optional on-screen keyboard (SPEC §9.4/§10): topmost, borderless, resizable, never activated
/// (WS_EX_NOACTIVATE + MA_NOACTIVATE) so the target program keeps the focus. Same layouts, toolbar and gestures
/// as the mobile keyboards; keys are injected into the focused program and fed into the buffer.
/// </summary>
internal sealed class OnScreenKeyboardWindow : Window
{
    private const double DefaultLogicalWidth = 760;

    private readonly DesktopController _c;
    private readonly KeyboardController _controller;
    private readonly GestureInterpreter _gestures;
    private readonly KeyboardView _view = new();
    private readonly Grid _scaled = new();
    private readonly ScaleTransform _scale = new(1, 1);
    private readonly Grid _keyArea = new();
    private readonly Border _errorBar = new();
    private readonly TextBlock _errorText;
    private readonly Ellipse _dot = new() { Width = 10, Height = 10, Margin = new Thickness(0, 0, 6, 0) };
    private readonly TextBlock _providerText = new() { FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
    private readonly Button _aiButton;
    private readonly Button _undoButton;
    private readonly Button _emotionChip;
    private readonly Button _langButton;
    private readonly Button _layoutButton;
    private readonly DispatcherTimer _saveTimer;
    private readonly DispatcherTimer _langHoldTimer;
    private FrameworkElement? _emojiPanel;
    private FrameworkElement? _languagePanel;
    private double _zoom = 1;
    private double _naturalHeight;
    private bool _shuttingDown;
    private bool _langHeld;
    private bool _pulsing;

    public OnScreenKeyboardWindow(DesktopController controller)
    {
        _c = controller;
        var s = controller.Strings;
        Title = s.Get("app.keyboardName", "Tippli KI-Tastatur");
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.CanResize;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        Focusable = false;
        UseLayoutRounding = true;
        FontFamily = UiKit.Font;
        if (UiKit.AppIcon != null) Icon = UiKit.AppIcon;
        SetResourceReference(BackgroundProperty, "Tippli.toolbar");
        SetResourceReference(ForegroundProperty, "Tippli.keyText");
        WindowChrome.SetWindowChrome(this, new WindowChrome
        {
            CaptionHeight = 0,
            ResizeBorderThickness = new Thickness(6),
            GlassFrameThickness = new Thickness(0),
            CornerRadius = new CornerRadius(0),
            UseAeroCaptionButtons = false,
        });

        _controller = new KeyboardController(controller.KeyboardHost, controller.Clock, controller.Data.Accents);
        _controller.SetEmojiRecents(controller.Settings.EmojiRecents);
        _gestures = new GestureInterpreter(_controller, controller.Scheduler) { AlternativesProvider = _controller.AlternativesFor };
        _view.Controller = _controller;
        _view.Gestures = _gestures;

        // ---- toolbar (SPEC §7.1) ----
        var aiContent = new StackPanel { Orientation = Orientation.Horizontal };
        aiContent.Children.Add(_dot);
        _providerText.SetResourceReference(TextBlock.ForegroundProperty, "Tippli.keyText");
        aiContent.Children.Add(_providerText);
        _aiButton = ToolbarButton(aiContent, OnAiDot);
        _undoButton = ToolbarButton("↶ " + s.Get("toolbar.undo", "Original"), () => _c.Engine.Undo(), s.Get("toolbar.undo", "Original"));
        _emotionChip = new Button { Content = s.Get("toolbar.emotionChip", "Emotional"), Focusable = false, Padding = new Thickness(10, 2, 10, 2), Margin = new Thickness(4, 0, 4, 0), FontSize = 12, Height = 26 };
        _emotionChip.SetResourceReference(StyleProperty, "Tippli.ChipButton");
        _emotionChip.Margin = new Thickness(4, 0, 4, 0);
        _emotionChip.Click += (_, _) => _ = _c.ShowTipsAsync(DesktopController.TipsSource.Emotion);
        var correctNow = ToolbarButton("✨", () => _ = _c.CorrectNowAsync(), s.Get("toolbar.correctNow", "Jetzt korrigieren"));
        var tips = ToolbarButton("💡 " + s.Get("toolbar.tips", "Tipps"), () => _ = _c.ShowTipsAsync(DesktopController.TipsSource.Keyboard),
            s.Get("toolbar.tips", "Tipps"));
        _langButton = ToolbarButton("", OnLanguageClick, s.Get("toolbar.language", "Sprache wechseln"));
        _langButton.PreviewMouseLeftButtonDown += (_, _) =>
        {
            _langHeld = false;
            _langHoldTimer?.Start();
        };
        _langButton.PreviewMouseLeftButtonUp += (_, _) => _langHoldTimer?.Stop();
        _layoutButton = ToolbarButton("", ToggleLayout);
        var settingsButton = ToolbarButton("⚙", () => _c.OpenSettings(), s.Get("toolbar.settings", "Einstellungen"));
        var closeButton = ToolbarButton("✕", Hide, s.Get("tips.close", "Schliessen"));

        var left = UiKit.Row(_aiButton, _undoButton, _emotionChip);
        var right = UiKit.Row(correctNow, tips, _langButton, _layoutButton, settingsButton, closeButton);
        var dragArea = new Border { Background = Brushes.Transparent, Cursor = Cursors.SizeAll };
        dragArea.MouseLeftButtonDown += (_, e) =>
        {
            if (e.ButtonState == MouseButtonState.Pressed)
            {
                try { DragMove(); } catch (InvalidOperationException) { }
            }
        };
        var toolbar = new Grid { Height = controller.Data.Theme.Metrics.ToolbarHeight, VerticalAlignment = VerticalAlignment.Top };
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        left.VerticalAlignment = VerticalAlignment.Center;
        right.VerticalAlignment = VerticalAlignment.Center;
        left.Margin = new Thickness(4, 0, 0, 0);
        right.Margin = new Thickness(0, 0, 4, 0);
        toolbar.Children.Add(left);
        Grid.SetColumn(dragArea, 1);
        toolbar.Children.Add(dragArea);
        Grid.SetColumn(right, 2);
        toolbar.Children.Add(right);
        toolbar.SetResourceReference(Panel.BackgroundProperty, "Tippli.toolbar");

        // ---- error bar (tap on the red dot) ----
        _errorText = UiKit.Text("", 13);
        _errorText.VerticalAlignment = VerticalAlignment.Center;
        var errorSettings = UiKit.Button(s.Get("onboarding.openSettings", "Einstellungen öffnen"), () =>
        {
            _errorBar.Visibility = Visibility.Collapsed;
            _c.OpenSettings(page: "provider");
        });
        errorSettings.Focusable = false;
        errorSettings.Margin = new Thickness(12, 0, 6, 0);
        var errorClose = UiKit.Button("✕", () => _errorBar.Visibility = Visibility.Collapsed);
        errorClose.Focusable = false;
        var errorRow = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(errorClose, Dock.Right);
        DockPanel.SetDock(errorSettings, Dock.Right);
        errorRow.Children.Add(errorClose);
        errorRow.Children.Add(errorSettings);
        errorRow.Children.Add(_errorText);
        _errorBar.Child = errorRow;
        _errorBar.Padding = new Thickness(12, 8, 8, 8);
        _errorBar.Margin = new Thickness(8, 4, 8, 0);
        _errorBar.CornerRadius = new CornerRadius(10);
        _errorBar.VerticalAlignment = VerticalAlignment.Top;
        _errorBar.Visibility = Visibility.Collapsed;
        _errorBar.SetResourceReference(Border.BackgroundProperty, "Tippli.card");

        // ---- layout: toolbar + key area (panels) + keyboard view on top (draws popups over the toolbar) ----
        _keyArea.Margin = new Thickness(0, controller.Data.Theme.Metrics.ToolbarHeight, 0, 0);
        _scaled.Children.Add(toolbar);
        _scaled.Children.Add(_keyArea);
        _scaled.Children.Add(_view);
        _scaled.Children.Add(_errorBar);
        Grid.SetZIndex(_errorBar, 10);
        _errorBar.Margin = new Thickness(8, controller.Data.Theme.Metrics.ToolbarHeight + 4, 8, 0);
        _scaled.LayoutTransform = _scale;
        _scaled.HorizontalAlignment = HorizontalAlignment.Left;
        _scaled.VerticalAlignment = VerticalAlignment.Top;
        Content = _scaled;

        _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(600) };
        _saveTimer.Tick += (_, _) =>
        {
            _saveTimer.Stop();
            SavePlacement();
        };
        _langHoldTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _langHoldTimer.Tick += (_, _) =>
        {
            _langHoldTimer.Stop();
            _langHeld = true;
            ShowLanguagePanel();
        };

        // ---- events ----
        _controller.Changed += () => _view.InvalidateVisual();
        _controller.PanelChanged += OnPanelChanged;
        _controller.EmojiRecentsChanged += recents => _c.Settings.SetStringList("emojiRecents", recents.ToList());
        controller.Settings.Changed += OnSettingChanged;
        controller.StatusChanged += UpdateToolbar;
        controller.Engine.UndoChanged += UpdateToolbar;
        controller.Engine.MoodChanged += _ => UpdateToolbar();
        controller.PhysicalInput += OnPhysicalInput;
        controller.EnterBusyChanged += busy =>
        {
            _view.EnterBusy = busy;
            _view.InvalidateVisual();
        };
        controller.Theme.Changed += ApplyTheme;
        SizeChanged += (_, _) => OnResized();
        LocationChanged += (_, _) => _saveTimer.Start();
        SourceInitialized += OnSourceInitialized;

        ApplyTheme();
        ApplyOptions();
        RestorePlacement();
        UpdateToolbar();
    }

    private Button ToolbarButton(object content, Action onClick, string? tooltip = null)
    {
        var b = new Button { Content = content, Focusable = false, ToolTip = tooltip };
        b.SetResourceReference(StyleProperty, "Tippli.ToolbarButton");
        b.Click += (_, _) => onClick();
        return b;
    }

    // ---- window behaviour -----------------------------------------------------------------------------

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        long ex = GetWindowLongPtr(hwnd, GWL_EXSTYLE).ToInt64();
        ex |= WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST;
        ex &= ~WS_EX_APPWINDOW;
        SetWindowLongPtr(hwnd, GWL_EXSTYLE, new IntPtr(ex));
        HwndSource.FromHwnd(hwnd)?.AddHook(WndProc);
        _c.Theme.ApplyWindowChrome(this);
    }

    private static IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_MOUSEACTIVATE)
        {
            handled = true;
            return new IntPtr(MA_NOACTIVATE);
        }
        return IntPtr.Zero;
    }

    public void ShowNoActivate()
    {
        ShowActivated = false;
        Show();
        Topmost = true;
        ApplyOptions();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_shuttingDown)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnClosing(e);
    }

    public void CloseForShutdown()
    {
        _shuttingDown = true;
        SavePlacement();
        Close();
    }

    private void RestorePlacement()
    {
        var st = _c.State;
        double width = st.KeyboardWidth ?? DefaultLogicalWidth;
        double height = st.KeyboardHeight ?? _naturalHeight;
        var work = SystemParameters.WorkArea;
        width = Math.Clamp(width, 360, Math.Max(360, SystemParameters.VirtualScreenWidth));
        height = Math.Clamp(height, _naturalHeight * 0.6, _naturalHeight * 3);
        double left = st.KeyboardLeft ?? work.Left + (work.Width - width) / 2;
        double top = st.KeyboardTop ?? work.Bottom - height - 12;
        var screen = new Rect(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop,
            SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight);
        if (!screen.IntersectsWith(new Rect(left, top, width, Math.Max(40, height))) || !screen.Contains(new Point(left + 40, top + 20)))
        {
            left = work.Left + (work.Width - width) / 2;
            top = work.Bottom - height - 12;
        }
        Width = width;
        Height = height;
        Left = left;
        Top = top;
        MinWidth = 360;
    }

    private void SavePlacement()
    {
        if (!IsLoaded) return;
        var st = _c.State;
        st.KeyboardLeft = Left;
        st.KeyboardTop = Top;
        st.KeyboardWidth = ActualWidth;
        st.KeyboardHeight = ActualHeight;
        st.Save();
    }

    // ---- sizing / geometry ------------------------------------------------------------------------------

    private void OnResized()
    {
        if (_naturalHeight <= 0 || ActualHeight <= 0) return;
        _zoom = Math.Clamp(ActualHeight / _naturalHeight, 0.6, 3);
        _scale.ScaleX = _scale.ScaleY = _zoom;
        RebuildGeometry();
        _saveTimer.Start();
    }

    private double LogicalWidth
    {
        get
        {
            double w = ActualWidth > 0 ? ActualWidth : double.IsNaN(Width) ? DefaultLogicalWidth * _zoom : Width;
            return Math.Max(300, w / _zoom);
        }
    }

    private void ApplyOptions()
    {
        var st = _c.Settings;
        var lang = st.ActiveLanguageInfo;
        bool hex = st.KeyboardMode == "hex";
        double rowHeight = _c.Data.Theme.Metrics.RowHeightFor(st.KeyHeight);
        double natural = KeyboardGeometry.TotalHeight(_c.Data.Theme.Metrics, rowHeight, hex, st.NumberRow);
        _controller.Options = new KeyboardOptions
        {
            AutoCapitalize = st.AutoCapitalize,
            DoubleSpacePeriod = st.DoubleSpacePeriod,
            LongPressAccents = st.LongPressAccents,
            LongPressDigits = st.LongPressDigits,
            Pinyin = lang.Pinyin,
            LanguageShort = lang.Short,
            AutoCapAllowed = _c.Session.Active,
        };
        _gestures.LongPressMs = st.LongPressMs;
        _view.Metrics = _c.Data.Theme.Metrics;
        _view.LongPressDigits = st.LongPressDigits;
        if (Math.Abs(natural - _naturalHeight) > 0.01)
        {
            bool first = _naturalHeight <= 0;
            _naturalHeight = natural;
            MinHeight = natural * 0.6;
            MaxHeight = natural * 3;
            if (!first) Height = natural * _zoom; // keep the zoom when rows are added/removed
        }
        RebuildGeometry();
        _controller.UpdateAutoCap();
        UpdateToolbar();
    }

    private void RebuildGeometry()
    {
        var st = _c.Settings;
        double width = LogicalWidth;
        double rowHeight = _c.Data.Theme.Metrics.RowHeightFor(st.KeyHeight);
        _scaled.Width = width;
        _scaled.Height = _naturalHeight;
        if (_controller.Panel == KeyboardPanel.Emoji)
        {
            _view.Geometry = null;
            return;
        }
        var request = new GeometryRequest(width, _c.Data.Layouts, st.GetKeyboardId(st.ActiveLanguage), st.KeyboardMode == "hex",
            _controller.Page, rowHeight, st.NumberRow, _c.Data.Theme.Metrics);
        _view.Geometry = KeyboardGeometry.Build(request);
    }

    private void ApplyTheme()
    {
        _view.Palette = _c.Theme.Palette;
        _c.Theme.ApplyWindowChrome(this);
        UpdateToolbar();
    }

    private void OnSettingChanged(string key)
    {
        if (key is "emojiRecents" or "desktopExcludedApps") return;
        ApplyOptions();
    }

    private void OnPhysicalInput()
    {
        if (!IsVisible) return;
        _controller.Options.AutoCapAllowed = _c.Session.Active;
        _controller.OnExternalChange();
    }

    // ---- toolbar ------------------------------------------------------------------------------------------

    private EngineStatus CurrentStatus => _c.Settings.DesktopLiveCorrection ? _c.Engine.Status : EngineStatus.Off;

    private void UpdateToolbar()
    {
        var s = _c.Strings;
        var p = _c.Theme.Palette;
        var status = CurrentStatus;
        _providerText.Text = (status == EngineStatus.Locked ? "🔒 " : "") + _c.Settings.ActiveProvider.ShortName;
        var dotColor = status switch
        {
            EngineStatus.Ready or EngineStatus.Working => p.Accent,
            EngineStatus.Error => p.Danger,
            _ => p.SecondaryText,
        };
        _dot.Fill = ThemeManager.Brush(dotColor);
        _aiButton.ToolTip = status switch
        {
            EngineStatus.Off => s.Get("toolbar.aiOff", "KI aus"),
            EngineStatus.Locked => s.Get("toolbar.aiLocked", "Geschütztes Feld – keine KI"),
            EngineStatus.NoKey => s.Get("toolbar.aiNoKey", "Kein API-Schlüssel"),
            EngineStatus.Working => s.Get("toolbar.aiWorking", "Korrigiert…"),
            EngineStatus.Error => s.Get("toolbar.aiError", "KI-Fehler") + ": " + _c.ErrorMessage,
            _ => s.Get("toolbar.aiOn", "KI an"),
        };
        SetPulsing(status == EngineStatus.Working);

        _undoButton.Visibility = _c.Engine.CanUndo ? Visibility.Visible : Visibility.Collapsed;
        int mood = _c.Engine.MoodLevel;
        _emotionChip.Visibility = mood >= 2 ? Visibility.Visible : Visibility.Collapsed;
        _emotionChip.Background = ThemeManager.Brush(mood >= 3 ? p.Danger : p.Warning);
        _emotionChip.Foreground = Brushes.White;
        _emotionChip.ToolTip = _c.Engine.MoodReason;

        _langButton.Content = _c.Settings.ActiveLanguageInfo.Short;
        bool hex = _c.Settings.KeyboardMode == "hex";
        _layoutButton.Content = hex ? "▭" : "⬡";
        _layoutButton.ToolTip = hex ? s.Get("toolbar.layoutNormal", "Normale Tastatur") : s.Get("toolbar.layoutHex", "Hexagon-Tastatur");
        _view.InvalidateVisual();
    }

    private void SetPulsing(bool on)
    {
        if (on == _pulsing) return;
        _pulsing = on;
        if (on)
        {
            var animation = new DoubleAnimation(1, 0.25, TimeSpan.FromMilliseconds(550)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever };
            _dot.BeginAnimation(OpacityProperty, animation);
        }
        else
        {
            _dot.BeginAnimation(OpacityProperty, null);
            _dot.Opacity = 1;
        }
    }

    private void OnAiDot()
    {
        switch (CurrentStatus)
        {
            case EngineStatus.Error:
                _errorText.Text = _c.ErrorMessage;
                _errorBar.Visibility = Visibility.Visible;
                break;
            case EngineStatus.NoKey:
                _c.OpenSettings(page: "provider");
                break;
            case EngineStatus.Locked:
                break;
            default:
                if (!_c.Settings.DesktopLiveCorrection) _c.ToggleLiveCorrection();
                else _c.Engine.ToggleSession();
                break;
        }
        UpdateToolbar();
    }

    private void ToggleLayout()
    {
        _c.Settings.KeyboardMode = _c.Settings.KeyboardMode == "hex" ? "normal" : "hex";
    }

    private void OnLanguageClick()
    {
        if (_langHeld)
        {
            _langHeld = false;
            return;
        }
        var langs = _c.Settings.Languages;
        if (langs.Count == 0) return;
        int i = langs.IndexOf(_c.Settings.ActiveLanguage);
        _c.Settings.ActiveLanguage = langs[(i + 1) % langs.Count];
    }

    // ---- panels -----------------------------------------------------------------------------------------------

    private void OnPanelChanged()
    {
        HideLanguagePanel();
        if (_controller.Panel == KeyboardPanel.Emoji)
        {
            _emojiPanel = BuildEmojiPanel();
            _keyArea.Children.Clear();
            _keyArea.Children.Add(_emojiPanel);
            _view.Visibility = Visibility.Collapsed;
        }
        else
        {
            _keyArea.Children.Clear();
            _emojiPanel = null;
            _view.Visibility = Visibility.Visible;
        }
        RebuildGeometry();
    }

    private FrameworkElement BuildEmojiPanel()
    {
        var data = _c.Data.Emoji;
        var grid = new Grid();
        grid.SetResourceReference(Panel.BackgroundProperty, "Tippli.background");
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(44) });

        var wrap = new WrapPanel { Margin = new Thickness(6, 4, 6, 4) };
        var scroll = new ScrollViewer
        {
            Content = wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            PanningMode = PanningMode.VerticalOnly,
            Focusable = false,
        };
        grid.Children.Add(scroll);

        void ShowCategory(IReadOnlyList<string> emoji)
        {
            wrap.Children.Clear();
            foreach (var e in emoji)
            {
                var b = new Button
                {
                    Content = e,
                    FontSize = 22,
                    Width = 44,
                    Height = 42,
                    Padding = new Thickness(0),
                    Focusable = false,
                    FontFamily = new FontFamily("Segoe UI Emoji"),
                };
                b.SetResourceReference(StyleProperty, "Tippli.ToolbarButton");
                b.Click += (_, _) => _controller.TypeEmoji(e);
                wrap.Children.Add(b);
            }
            scroll.ScrollToTop();
        }

        var bar = new DockPanel { LastChildFill = false, Margin = new Thickness(4, 2, 4, 4) };
        bar.SetResourceReference(Panel.BackgroundProperty, "Tippli.toolbar");
        Grid.SetRow(bar, 1);
        grid.Children.Add(bar);

        var abc = ToolbarButton(_c.Strings.Get("keys.letters", "ABC"), () => _controller.SetPanel(KeyboardPanel.Letters));
        DockPanel.SetDock(abc, Dock.Left);
        bar.Children.Add(abc);
        var back = ToolbarButton("⌫", () => _controller.EmojiBackspace());
        DockPanel.SetDock(back, Dock.Right);
        bar.Children.Add(back);

        var cats = new StackPanel { Orientation = Orientation.Horizontal };
        cats.Children.Add(ToolbarButton("⏱", () => ShowCategory(_controller.EmojiRecents.ToList())));
        foreach (var cat in data.Categories)
        {
            var b = ToolbarButton(cat.Icon, () => ShowCategory(cat.Emoji), cat.Name);
            b.FontFamily = new FontFamily("Segoe UI Emoji");
            cats.Children.Add(b);
        }
        var catScroll = new ScrollViewer
        {
            Content = cats,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            PanningMode = PanningMode.HorizontalOnly,
            Focusable = false,
        };
        DockPanel.SetDock(catScroll, Dock.Left);
        bar.Children.Add(catScroll);

        if (_controller.EmojiRecents.Count > 0) ShowCategory(_controller.EmojiRecents.ToList());
        else if (data.Categories.Count > 0) ShowCategory(data.Categories[0].Emoji);
        return grid;
    }

    private void ShowLanguagePanel()
    {
        HideLanguagePanel();
        var panel = new WrapPanel { Margin = new Thickness(10) };
        foreach (var id in _c.Settings.Languages)
        {
            var lang = _c.Data.FindLanguage(id);
            if (lang == null) continue;
            var b = UiKit.Chip(lang.Name, () =>
            {
                _c.Settings.ActiveLanguage = lang.Id;
                HideLanguagePanel();
            });
            b.Focusable = false;
            if (lang.Id == _c.Settings.ActiveLanguage) b.FontWeight = FontWeights.SemiBold;
            panel.Children.Add(b);
        }
        var close = UiKit.Chip("✕", HideLanguagePanel);
        close.Focusable = false;
        panel.Children.Add(close);
        var border = new Border { Child = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Focusable = false } };
        border.SetResourceReference(Border.BackgroundProperty, "Tippli.panel");
        _languagePanel = border;
        _keyArea.Children.Add(border);
        Grid.SetZIndex(border, 5);
        _view.Visibility = Visibility.Collapsed;
    }

    private void HideLanguagePanel()
    {
        if (_languagePanel == null) return;
        _keyArea.Children.Remove(_languagePanel);
        _languagePanel = null;
        if (_controller.Panel != KeyboardPanel.Emoji) _view.Visibility = Visibility.Visible;
    }
}
