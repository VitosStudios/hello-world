using System;
using System.Collections.Specialized;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media.Imaging;
using Tippli.Windows.Services;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.Interop;

/// <summary>
/// Selection copy / paste with simulated Ctrl+C / Ctrl+V and clipboard save + restore. Runs on the WPF UI
/// thread (STA, required by the clipboard) and only waits asynchronously so the keyboard hook keeps running.
/// </summary>
internal static class ClipboardService
{
    private sealed class Saved
    {
        public string? Text;
        public string? Rtf;
        public string? Html;
        public StringCollection? Files;
        public BitmapSource? Image;
        public bool Empty => Text == null && Rtf == null && Html == null && Files == null && Image == null;
    }

    /// <summary>Copies the current selection of the focused program. Returns null if nothing was copied.</summary>
    public static async Task<string?> CopySelectionAsync(InputInjector injector)
    {
        await WaitForModifiersReleasedAsync(injector);
        var saved = Save();
        uint before = GetClipboardSequenceNumber();
        injector.SendCtrlCombo(VK.C);
        bool changed = false;
        for (int i = 0; i < 30; i++)
        {
            await Task.Delay(20);
            if (GetClipboardSequenceNumber() != before)
            {
                changed = true;
                break;
            }
        }
        string? text = null;
        if (changed)
        {
            await Task.Delay(30);
            text = Retry(() => Clipboard.ContainsText() ? Clipboard.GetText() : null);
        }
        Restore(saved);
        return string.IsNullOrEmpty(text) ? null : text;
    }

    /// <summary>Pastes <paramref name="text"/> into the focused program (replacing its selection).</summary>
    public static async Task PasteTextAsync(InputInjector injector, string text)
    {
        await WaitForModifiersReleasedAsync(injector);
        var saved = Save();
        if (!Retry(() => { Clipboard.SetDataObject(new DataObject(DataFormats.UnicodeText, text), true); return true; }))
        {
            Log.Info("Clipboard busy - paste skipped");
            return;
        }
        injector.SendCtrlCombo(VK.V);
        await Task.Delay(400); // the target reads the clipboard asynchronously
        Restore(saved);
    }

    public static bool SetText(string text) => Retry(() => { Clipboard.SetDataObject(new DataObject(DataFormats.UnicodeText, text), true); return true; });

    private static async Task WaitForModifiersReleasedAsync(InputInjector injector)
    {
        for (int i = 0; i < 40 && InputInjector.AnyModifierDown(); i++) await Task.Delay(15);
        if (InputInjector.AnyModifierDown()) injector.ReleaseHeldModifiers();
    }

    private static Saved Save()
    {
        var s = new Saved();
        try
        {
            if (Clipboard.ContainsText(TextDataFormat.UnicodeText)) s.Text = Clipboard.GetText(TextDataFormat.UnicodeText);
            if (Clipboard.ContainsText(TextDataFormat.Rtf)) s.Rtf = Clipboard.GetText(TextDataFormat.Rtf);
            if (Clipboard.ContainsText(TextDataFormat.Html)) s.Html = Clipboard.GetText(TextDataFormat.Html);
            if (Clipboard.ContainsFileDropList()) s.Files = Clipboard.GetFileDropList();
            if (Clipboard.ContainsImage()) s.Image = Clipboard.GetImage();
        }
        catch (Exception ex)
        {
            Log.Info("Clipboard save failed: " + ex.Message);
        }
        return s;
    }

    private static void Restore(Saved s)
    {
        Retry(() =>
        {
            if (s.Empty)
            {
                Clipboard.Clear();
                return true;
            }
            var data = new DataObject();
            if (s.Text != null) data.SetText(s.Text, TextDataFormat.UnicodeText);
            if (s.Rtf != null) data.SetText(s.Rtf, TextDataFormat.Rtf);
            if (s.Html != null) data.SetText(s.Html, TextDataFormat.Html);
            if (s.Files != null) data.SetFileDropList(s.Files);
            if (s.Image != null) data.SetImage(s.Image);
            Clipboard.SetDataObject(data, true);
            return true;
        });
    }

    private static T? Retry<T>(Func<T?> action)
    {
        for (int i = 0; i < 5; i++)
        {
            try
            {
                return action();
            }
            catch (Exception)
            {
                System.Threading.Thread.Sleep(15);
            }
        }
        return default;
    }
}
