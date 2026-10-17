using System;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.Interop;

internal enum TranslationKind
{
    None,
    Characters,
    DeadKey,
}

/// <summary>
/// Translates a physical key press into characters with <c>ToUnicodeEx</c> using the keyboard layout of the
/// foreground window's thread. Flag 0x4 keeps the system's dead-key state untouched, so the target program still
/// composes accents itself; Tippli composes them separately (DeadKeyComposer).
/// </summary>
internal sealed class KeyTranslator
{
    private readonly byte[] _state = new byte[256];
    private readonly char[] _buffer = new char[16];

    /// <summary>Caps Lock state tracked from the hook (GetKeyState is thread local and unreliable here).</summary>
    public bool CapsLock { get; set; }

    public void InitCapsLock() => CapsLock = (GetKeyState(VK.CAPITAL) & 0x0001) != 0;

    public static IntPtr ForegroundLayout()
    {
        var hwnd = GetForegroundWindow();
        uint thread = hwnd == IntPtr.Zero ? 0 : GetWindowThreadProcessId(hwnd, out _);
        return GetKeyboardLayout(thread);
    }

    public (TranslationKind Kind, string Text) Translate(int vk, uint scanCode, IntPtr layout)
    {
        Array.Clear(_state);
        Mark(VK.SHIFT);
        Mark(VK.LSHIFT);
        Mark(VK.RSHIFT);
        Mark(VK.CONTROL);
        Mark(VK.LCONTROL);
        Mark(VK.RCONTROL);
        Mark(VK.MENU);
        Mark(VK.LMENU);
        Mark(VK.RMENU);
        if (CapsLock) _state[VK.CAPITAL] = 0x01;
        if (vk is >= 0 and < 256) _state[vk] |= 0x80;

        int n = ToUnicodeEx((uint)vk, scanCode, _state, _buffer, _buffer.Length, TOUNICODE_NO_STATE_CHANGE, layout);
        if (n < 0)
        {
            // Dead key: the buffer holds its spacing character.
            return _buffer[0] == '\0' ? (TranslationKind.None, "") : (TranslationKind.DeadKey, _buffer[0].ToString());
        }
        if (n == 0) return (TranslationKind.None, "");
        var text = new string(_buffer, 0, Math.Min(n, _buffer.Length));
        foreach (var c in text)
        {
            if (c < 0x20 || c == 0x7F) return (TranslationKind.None, ""); // control characters (Ctrl+…, Esc, …)
        }
        return (TranslationKind.Characters, text);
    }

    private void Mark(int vk)
    {
        if ((GetAsyncKeyState(vk) & 0x8000) != 0) _state[vk] = 0x80;
    }
}
