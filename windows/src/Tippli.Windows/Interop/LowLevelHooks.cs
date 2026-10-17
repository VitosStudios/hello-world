using System;
using System.Runtime.InteropServices;
using Tippli.Windows.Services;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.Interop;

/// <summary>A key event seen by the low-level keyboard hook.</summary>
internal readonly record struct KeyEvent(int Vk, uint Scan, uint Flags, bool Down, UIntPtr ExtraInfo)
{
    public bool Injected => (Flags & LLKHF_INJECTED) != 0;
    public bool Extended => (Flags & LLKHF_EXTENDED) != 0;
    public bool OurOwn => ExtraInfo == (UIntPtr)InputInjector.Signature;
}

/// <summary>
/// WH_KEYBOARD_LL + WH_MOUSE_LL installed on the UI thread (the WPF dispatcher pumps the messages). The callbacks
/// must stay fast: no I/O, no network. The delegates are kept in fields so the GC never collects them while
/// Windows still holds the function pointers.
/// </summary>
internal sealed class LowLevelHooks : IDisposable
{
    private readonly LowLevelHookProc _keyboardProc;
    private readonly LowLevelHookProc _mouseProc;
    private IntPtr _keyboardHook;
    private IntPtr _mouseHook;

    public LowLevelHooks()
    {
        _keyboardProc = KeyboardCallback;
        _mouseProc = MouseCallback;
    }

    /// <summary>Called for every key event. Return true to swallow the event.</summary>
    public Func<KeyEvent, bool>? KeyboardEvent { get; set; }

    /// <summary>Called for mouse button presses (screen coordinates in physical pixels, injected flag).</summary>
    public Action<int, int, bool>? MouseButtonDown { get; set; }

    public bool IsInstalled => _keyboardHook != IntPtr.Zero;

    public bool Install()
    {
        Uninstall();
        var module = GetModuleHandle(null);
        _keyboardHook = SetWindowsHookEx(WH_KEYBOARD_LL, _keyboardProc, module, 0);
        if (_keyboardHook == IntPtr.Zero) Log.Error($"SetWindowsHookEx(WH_KEYBOARD_LL) failed: {Marshal.GetLastWin32Error()}");
        _mouseHook = SetWindowsHookEx(WH_MOUSE_LL, _mouseProc, module, 0);
        if (_mouseHook == IntPtr.Zero) Log.Error($"SetWindowsHookEx(WH_MOUSE_LL) failed: {Marshal.GetLastWin32Error()}");
        return _keyboardHook != IntPtr.Zero;
    }

    public void Uninstall()
    {
        if (_keyboardHook != IntPtr.Zero) UnhookWindowsHookEx(_keyboardHook);
        if (_mouseHook != IntPtr.Zero) UnhookWindowsHookEx(_mouseHook);
        _keyboardHook = IntPtr.Zero;
        _mouseHook = IntPtr.Zero;
    }

    private IntPtr KeyboardCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode == HC_ACTION)
        {
            try
            {
                var data = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(lParam);
                int msg = unchecked((int)(long)wParam);
                bool down = msg is WM_KEYDOWN or WM_SYSKEYDOWN;
                var ev = new KeyEvent((int)data.vkCode, data.scanCode, data.flags, down, data.dwExtraInfo);
                if (KeyboardEvent?.Invoke(ev) == true) return (IntPtr)1;
            }
            catch (Exception ex)
            {
                Log.Error("keyboard hook", ex);
            }
        }
        return CallNextHookEx(_keyboardHook, nCode, wParam, lParam);
    }

    private IntPtr MouseCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode == HC_ACTION)
        {
            int msg = unchecked((int)(long)wParam);
            if (msg is WM_LBUTTONDOWN or WM_RBUTTONDOWN or WM_MBUTTONDOWN or WM_XBUTTONDOWN)
            {
                try
                {
                    var data = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
                    MouseButtonDown?.Invoke(data.pt.X, data.pt.Y, (data.flags & LLMHF_INJECTED) != 0);
                }
                catch (Exception ex)
                {
                    Log.Error("mouse hook", ex);
                }
            }
        }
        return CallNextHookEx(_mouseHook, nCode, wParam, lParam);
    }

    public void Dispose() => Uninstall();
}
