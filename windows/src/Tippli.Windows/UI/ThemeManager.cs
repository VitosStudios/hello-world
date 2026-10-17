using System;
using System.IO;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media;
using Microsoft.Win32;
using Tippli.Core.Models;
using Tippli.Core.Settings;
using Tippli.Core.Shared;
using Tippli.Windows.Services;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.UI;

/// <summary>
/// Puts the colours of shared/theme.json into the application resources as <c>Tippli.&lt;name&gt;</c> brushes
/// (light/dark by the <c>theme</c> setting or the Windows app theme) and loads the control styles.
/// </summary>
internal sealed class ThemeManager
{
    private readonly SharedData _data;
    private readonly SettingsStore _settings;
    private readonly Application _app;

    public ThemeManager(Application app, SharedData data, SettingsStore settings)
    {
        _app = app;
        _data = data;
        _settings = settings;
        Palette = data.Theme.Palette(false);
        SystemEvents.UserPreferenceChanged += (_, e) =>
        {
            if (e.Category is UserPreferenceCategory.General or UserPreferenceCategory.Color)
                _app.Dispatcher.BeginInvoke(Apply);
        };
    }

    public bool IsDark { get; private set; }
    public ThemePalette Palette { get; private set; }
    public ThemeMetrics Metrics => _data.Theme.Metrics;

    public event Action? Changed;

    public void LoadStyles()
    {
        try
        {
            using var stream = typeof(ThemeManager).Assembly.GetManifestResourceStream("Tippli.Styles.xaml")
                ?? throw new FileNotFoundException("Tippli.Styles.xaml");
            if (XamlReader.Load(stream) is ResourceDictionary dictionary) _app.Resources.MergedDictionaries.Add(dictionary);
        }
        catch (Exception ex)
        {
            // The app stays fully functional with the default WPF look.
            Log.Error("styles could not be loaded", ex);
        }
    }

    public void Apply()
    {
        var setting = _settings.Theme;
        IsDark = setting == "dark" || (setting == "system" && SystemPrefersDark());
        Palette = _data.Theme.Palette(IsDark);
        var res = _app.Resources;
        foreach (var name in Palette.Names) res["Tippli." + name] = Brush(Palette.Get(name));
        var accent = Palette.Accent;
        res["Tippli.accentSoft"] = Brush(accent with { A = 0x33 });
        res["Tippli.hover"] = Brush(IsDark ? new ThemeColor(255, 0x34, 0x36, 0x3D) : new ThemeColor(255, 0xEE, 0xF0, 0xF5));
        res["Tippli.window"] = Brush(Palette.Panel);
        res["Tippli.text"] = Brush(Palette.KeyText);
        Changed?.Invoke();
    }

    public static bool SystemPrefersDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
        catch
        {
            return false;
        }
    }

    public static Color ToColor(ThemeColor c) => Color.FromArgb(c.A, c.R, c.G, c.B);

    public static SolidColorBrush Brush(ThemeColor c)
    {
        var b = new SolidColorBrush(ToColor(c));
        b.Freeze();
        return b;
    }

    /// <summary>Dark title bar (Windows 10 20H1+/11) and rounded corners (Windows 11) for our windows.</summary>
    public void ApplyWindowChrome(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero) return;
        int dark = IsDark ? 1 : 0;
        DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref dark, sizeof(int));
        int round = DWMWCP_ROUND;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref round, sizeof(int));
    }
}
