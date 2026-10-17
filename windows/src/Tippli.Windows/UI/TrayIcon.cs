using System;
using System.Drawing;
using System.Drawing.Imaging;
using Tippli.Windows.Services;
using WinForms = System.Windows.Forms;

namespace Tippli.Windows.UI;

/// <summary>
/// Tray icon + context menu (SPEC §10): status line (provider + on/off), Live-Korrektur an/aus,
/// Bildschirmtastatur, Tipps für Auswahl, Einstellungen…, Beenden.
/// </summary>
internal sealed class TrayIcon : IDisposable
{
    private readonly DesktopController _c;
    private readonly WinForms.NotifyIcon _icon;
    private readonly WinForms.ToolStripMenuItem _status;
    private readonly WinForms.ToolStripMenuItem _live;
    private readonly WinForms.ToolStripMenuItem _keyboard;
    private readonly Icon _normalIcon;
    private readonly Icon _offIcon;
    private readonly IntPtr _offIconHandle;

    public TrayIcon(DesktopController controller, Action quit)
    {
        _c = controller;
        var s = controller.Strings;
        _normalIcon = LoadIcon();
        (_offIcon, _offIconHandle) = GrayIcon(_normalIcon);

        _status = new WinForms.ToolStripMenuItem(controller.StatusLine) { Enabled = false };
        _live = new WinForms.ToolStripMenuItem(s.Get("settings.hotkeyToggle", "Live-Korrektur an/aus"), null, (_, _) => _c.ToggleLiveCorrection())
        {
            ShortcutKeyDisplayString = "Strg+Alt+K",
        };
        _keyboard = new WinForms.ToolStripMenuItem(s.Get("desktop.trayKeyboard", "Bildschirmtastatur"), null, (_, _) => _c.ToggleKeyboard())
        {
            ShortcutKeyDisplayString = "Strg+Alt+B",
        };
        var tips = new WinForms.ToolStripMenuItem(s.Get("desktop.trayTips", "Tipps für Auswahl"), null,
            (_, _) => _ = _c.ShowTipsAsync(DesktopController.TipsSource.Tray))
        {
            ShortcutKeyDisplayString = "Strg+Alt+H",
        };
        var settings = new WinForms.ToolStripMenuItem(s.Get("settings.title", "Einstellungen") + "…", null, (_, _) => _c.OpenSettings());
        var exit = new WinForms.ToolStripMenuItem(s.Get("desktop.trayQuit", "Beenden"), null, (_, _) => quit());

        var menu = new WinForms.ContextMenuStrip { ShowImageMargin = false, ShowCheckMargin = true };
        menu.Items.Add(_status);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add(_live);
        menu.Items.Add(_keyboard);
        menu.Items.Add(tips);
        menu.Items.Add(settings);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add(exit);
        menu.Opening += (_, _) => Update();

        _icon = new WinForms.NotifyIcon
        {
            Icon = _normalIcon,
            Text = "Tippli",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.MouseDoubleClick += (_, e) =>
        {
            if (e.Button == WinForms.MouseButtons.Left) _c.OpenSettings();
        };
        _icon.BalloonTipClicked += (_, _) => _c.OpenSettings();

        controller.StatusChanged += Update;
        controller.Notify += ShowBalloon;
        Update();
    }

    public void Update()
    {
        var line = _c.StatusLine;
        _status.Text = line;
        _live.Checked = _c.Settings.DesktopLiveCorrection;
        _keyboard.Checked = _c.KeyboardVisible;
        _icon.Icon = _c.Settings.DesktopLiveCorrection ? _normalIcon : _offIcon;
        var tip = "Tippli – " + line;
        _icon.Text = tip.Length > 127 ? tip[..127] : tip;
    }

    public void ShowBalloon(string title, string text)
    {
        try
        {
            _icon.ShowBalloonTip(4000, title, text, WinForms.ToolTipIcon.None);
        }
        catch (Exception ex)
        {
            Log.Error("balloon", ex);
        }
    }

    private static Icon LoadIcon()
    {
        try
        {
            using var s = typeof(TrayIcon).Assembly.GetManifestResourceStream("branding/icon.ico");
            if (s != null) return new Icon(s, WinForms.SystemInformation.SmallIconSize);
        }
        catch (Exception ex)
        {
            Log.Error("tray icon", ex);
        }
        return (Icon)SystemIcons.Application.Clone();
    }

    /// <summary>Desaturated, half transparent variant shown while live correction is off.</summary>
    private static (Icon Icon, IntPtr Handle) GrayIcon(Icon source)
    {
        try
        {
            using var bmp = source.ToBitmap();
            using var gray = new Bitmap(bmp.Width, bmp.Height, PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(gray))
            {
                var matrix = new ColorMatrix(new[]
                {
                    new[] { 0.3f, 0.3f, 0.3f, 0, 0 },
                    new[] { 0.59f, 0.59f, 0.59f, 0, 0 },
                    new[] { 0.11f, 0.11f, 0.11f, 0, 0 },
                    new[] { 0f, 0, 0, 0.55f, 0 },
                    new[] { 0f, 0, 0, 0, 1 },
                });
                using var attributes = new ImageAttributes();
                attributes.SetColorMatrix(matrix);
                g.DrawImage(bmp, new Rectangle(0, 0, bmp.Width, bmp.Height), 0, 0, bmp.Width, bmp.Height, GraphicsUnit.Pixel, attributes);
            }
            var handle = gray.GetHicon();
            return (Icon.FromHandle(handle), handle);
        }
        catch
        {
            return ((Icon)source.Clone(), IntPtr.Zero);
        }
    }

    public void Dispose()
    {
        _c.StatusChanged -= Update;
        _c.Notify -= ShowBalloon;
        _icon.Visible = false;
        _icon.Dispose();
        _offIcon.Dispose();
        if (_offIconHandle != IntPtr.Zero) Interop.NativeMethods.DestroyIcon(_offIconHandle);
        _normalIcon.Dispose();
    }
}
