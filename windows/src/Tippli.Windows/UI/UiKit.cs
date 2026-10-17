using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Tippli.Windows.Services;

namespace Tippli.Windows.UI;

/// <summary>Builders for the code-only WPF UI (colours are dynamic resources from <see cref="ThemeManager"/>).</summary>
internal static class UiKit
{
    public const string FontFamilyName = "Segoe UI Variable Text, Segoe UI, Segoe UI Emoji, Segoe UI Symbol";
    public static readonly FontFamily Font = new(FontFamilyName);

    public static T Fg<T>(this T element, string resource) where T : FrameworkElement
    {
        switch (element)
        {
            case Control c: c.SetResourceReference(Control.ForegroundProperty, resource); break;
            case TextBlock t: t.SetResourceReference(TextBlock.ForegroundProperty, resource); break;
        }
        return element;
    }

    public static T Bg<T>(this T element, string resource) where T : FrameworkElement
    {
        switch (element)
        {
            case Control c: c.SetResourceReference(Control.BackgroundProperty, resource); break;
            case Border b: b.SetResourceReference(Border.BackgroundProperty, resource); break;
            case Panel p: p.SetResourceReference(Panel.BackgroundProperty, resource); break;
        }
        return element;
    }

    public static T WithMargin<T>(this T element, double left, double top, double right, double bottom) where T : FrameworkElement
    {
        element.Margin = new Thickness(left, top, right, bottom);
        return element;
    }

    public static TextBlock Text(string text, double size = 13, FontWeight? weight = null, string color = "Tippli.keyText", bool wrap = true)
    {
        var t = new TextBlock
        {
            Text = text,
            FontSize = size,
            FontWeight = weight ?? FontWeights.Normal,
            TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap,
        };
        t.SetResourceReference(TextBlock.ForegroundProperty, color);
        return t;
    }

    public static TextBlock Title(string text) => Text(text, 22, FontWeights.SemiBold).WithMargin(0, 0, 0, 14);

    public static TextBlock Header(string text) => Text(text, 15, FontWeights.SemiBold).WithMargin(0, 0, 0, 8);

    public static TextBlock Hint(string text) => Text(text, 12, null, "Tippli.secondaryText").WithMargin(0, 2, 0, 6);

    public static Border Card(params UIElement[] children)
    {
        var stack = new StackPanel();
        foreach (var c in children) stack.Children.Add(c);
        return CardOf(stack);
    }

    public static Border CardOf(UIElement content)
    {
        var b = new Border
        {
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(18, 14, 18, 14),
            Margin = new Thickness(0, 0, 0, 12),
            BorderThickness = new Thickness(1),
            Child = content,
        };
        b.SetResourceReference(Border.BackgroundProperty, "Tippli.card");
        b.SetResourceReference(Border.BorderBrushProperty, "Tippli.divider");
        return b;
    }

    public static Button Button(string text, Action onClick, bool accent = false)
    {
        var b = new Button { Content = text, HorizontalAlignment = HorizontalAlignment.Left };
        if (accent) b.SetResourceReference(FrameworkElement.StyleProperty, "Tippli.AccentButton");
        b.Click += (_, _) => onClick();
        return b;
    }

    public static Button Chip(string text, Action onClick)
    {
        var b = new Button { Content = text };
        b.SetResourceReference(FrameworkElement.StyleProperty, "Tippli.ChipButton");
        b.Click += (_, _) => onClick();
        return b;
    }

    public static CheckBox Switch(string label, bool value, Action<bool> changed)
    {
        var c = new CheckBox { Content = label, IsChecked = value };
        c.SetResourceReference(FrameworkElement.StyleProperty, "Tippli.Switch");
        c.Checked += (_, _) => changed(true);
        c.Unchecked += (_, _) => changed(false);
        return c;
    }

    public static CheckBox Check(string label, bool value, Action<bool> changed)
    {
        var c = new CheckBox { Content = label, IsChecked = value };
        c.Checked += (_, _) => changed(true);
        c.Unchecked += (_, _) => changed(false);
        return c;
    }

    /// <summary>A group of radio buttons; returns the panel.</summary>
    public static StackPanel Radios(IEnumerable<(string Value, string Label)> options, string current, Action<string> changed)
    {
        var panel = new StackPanel();
        var group = "g" + Guid.NewGuid().ToString("N");
        foreach (var (value, label) in options)
        {
            var r = new RadioButton { Content = label, GroupName = group, IsChecked = value == current, Tag = value };
            r.Checked += (_, _) => changed(value);
            panel.Children.Add(r);
        }
        return panel;
    }

    /// <summary>Slider with a value label.</summary>
    public static FrameworkElement Slider(string label, double min, double max, double step, double value, Func<double, string> format,
        Action<double> changed)
    {
        var grid = new Grid { Margin = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(80) });
        var title = Text(label);
        var valueText = Text(format(value), 13, FontWeights.SemiBold, "Tippli.accent", wrap: false);
        valueText.HorizontalAlignment = HorizontalAlignment.Right;
        valueText.VerticalAlignment = VerticalAlignment.Center;
        var slider = new Slider
        {
            Minimum = min,
            Maximum = max,
            Value = value,
            TickFrequency = step,
            IsSnapToTickEnabled = true,
            SmallChange = step,
            LargeChange = step * 4,
            Margin = new Thickness(0, 6, 0, 0),
        };
        slider.ValueChanged += (_, e) =>
        {
            valueText.Text = format(e.NewValue);
            changed(e.NewValue);
        };
        var stack = new StackPanel();
        stack.Children.Add(title);
        stack.Children.Add(slider);
        grid.Children.Add(stack);
        Grid.SetColumn(valueText, 1);
        grid.Children.Add(valueText);
        return grid;
    }

    public static FrameworkElement Labeled(string label, UIElement control, double labelWidth = 170)
    {
        var grid = new Grid { Margin = new Thickness(0, 5, 0, 5) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(labelWidth) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var t = Text(label);
        t.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(t);
        Grid.SetColumn(control, 1);
        grid.Children.Add(control);
        return grid;
    }

    public static TextBlock Link(string text, string url)
    {
        var link = new Hyperlink(new Run(text)) { NavigateUri = new Uri(url) };
        link.RequestNavigate += (_, e) =>
        {
            OpenUrl(e.Uri.AbsoluteUri);
            e.Handled = true;
        };
        var t = new TextBlock(link) { Margin = new Thickness(0, 4, 0, 4), FontSize = 13 };
        return t;
    }

    public static void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            Log.Error("open url", ex);
        }
    }

    public static StackPanel Row(params UIElement[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static WrapPanel Wrap(params UIElement[] children)
    {
        var p = new WrapPanel();
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    private static BitmapImage? _icon;

    /// <summary>The app icon (shared/branding/icon-256.png).</summary>
    public static ImageSource? AppIcon
    {
        get
        {
            if (_icon != null) return _icon;
            try
            {
                using var s = typeof(UiKit).Assembly.GetManifestResourceStream("branding/icon-256.png");
                if (s == null) return null;
                var ms = new MemoryStream();
                s.CopyTo(ms);
                ms.Position = 0;
                var img = new BitmapImage();
                img.BeginInit();
                img.CacheOption = BitmapCacheOption.OnLoad;
                img.StreamSource = ms;
                img.EndInit();
                img.Freeze();
                _icon = img;
            }
            catch (Exception ex)
            {
                Log.Error("icon", ex);
            }
            return _icon;
        }
    }

    /// <summary>Common window setup: font, colours, icon, dark title bar.</summary>
    public static void SetupWindow(Window w, ThemeManager theme)
    {
        w.FontFamily = Font;
        w.FontSize = 13;
        w.SetResourceReference(Control.BackgroundProperty, "Tippli.panel");
        w.SetResourceReference(Control.ForegroundProperty, "Tippli.keyText");
        if (AppIcon != null) w.Icon = AppIcon;
        w.SourceInitialized += (_, _) => theme.ApplyWindowChrome(w);
        void OnTheme() => theme.ApplyWindowChrome(w);
        theme.Changed += OnTheme;
        w.Closed += (_, _) => theme.Changed -= OnTheme;
    }
}
