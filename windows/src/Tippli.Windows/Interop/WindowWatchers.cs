using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Windows.Interop;
using Tippli.Windows.Services;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.Interop;

/// <summary>
/// SetWinEventHook(EVENT_SYSTEM_FOREGROUND) and EVENT_OBJECT_FOCUS (out of context; delivered to the UI thread's
/// message loop). The callback delegate is kept alive in a field.
/// </summary>
internal sealed class FocusWatcher : IDisposable
{
    private readonly WinEventProc _proc;
    private IntPtr _foregroundHook;
    private IntPtr _focusHook;
    private (IntPtr Hwnd, int Obj, int Child) _lastFocus;

    public FocusWatcher() => _proc = OnWinEvent;

    public event Action<IntPtr>? ForegroundChanged;
    public event Action<IntPtr>? FocusChanged;

    public void Start()
    {
        _foregroundHook = SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, IntPtr.Zero, _proc, 0, 0, WINEVENT_OUTOFCONTEXT);
        _focusHook = SetWinEventHook(EVENT_OBJECT_FOCUS, EVENT_OBJECT_FOCUS, IntPtr.Zero, _proc, 0, 0, WINEVENT_OUTOFCONTEXT);
        if (_foregroundHook == IntPtr.Zero) Log.Error("SetWinEventHook(FOREGROUND) failed");
    }

    private void OnWinEvent(IntPtr hook, uint eventType, IntPtr hwnd, int idObject, int idChild, uint thread, uint time)
    {
        try
        {
            if (eventType == EVENT_SYSTEM_FOREGROUND)
            {
                _lastFocus = default;
                ForegroundChanged?.Invoke(hwnd);
            }
            else if (eventType == EVENT_OBJECT_FOCUS)
            {
                var key = (hwnd, idObject, idChild);
                if (key == _lastFocus) return;
                _lastFocus = key;
                FocusChanged?.Invoke(hwnd);
            }
        }
        catch (Exception ex)
        {
            Log.Error("win event", ex);
        }
    }

    public void Dispose()
    {
        if (_foregroundHook != IntPtr.Zero) UnhookWinEvent(_foregroundHook);
        if (_focusHook != IntPtr.Zero) UnhookWinEvent(_focusHook);
        _foregroundHook = _focusHook = IntPtr.Zero;
    }
}

/// <summary>Global hotkeys (Ctrl+Alt+K/H/B/J) via RegisterHotKey on a hidden message-only HwndSource.</summary>
internal sealed class HotkeyManager : IDisposable
{
    private readonly HwndSource _source;
    private readonly Dictionary<int, Action> _actions = new();
    private int _nextId = 0x5450;

    public HotkeyManager()
    {
        var p = new HwndSourceParameters("TippliHotkeys")
        {
            ParentWindow = new IntPtr(-3), // HWND_MESSAGE
            WindowStyle = 0,
            Width = 0,
            Height = 0,
        };
        _source = new HwndSource(p);
        _source.AddHook(WndProc);
    }

    /// <summary>Registers Ctrl+Alt+<paramref name="vk"/>. Returns false if another program owns the combination.</summary>
    public bool Register(int vk, Action action)
    {
        int id = _nextId++;
        if (!RegisterHotKey(_source.Handle, id, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, (uint)vk))
        {
            Log.Info($"Hotkey Ctrl+Alt+0x{vk:X2} unavailable ({System.Runtime.InteropServices.Marshal.GetLastWin32Error()})");
            return false;
        }
        _actions[id] = action;
        return true;
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_HOTKEY && _actions.TryGetValue(wParam.ToInt32(), out var action))
        {
            handled = true;
            try { action(); }
            catch (Exception ex) { Log.Error("hotkey", ex); }
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        foreach (var id in _actions.Keys) UnregisterHotKey(_source.Handle, id);
        _actions.Clear();
        _source.Dispose();
    }
}

/// <summary>Process and window helpers.</summary>
internal static class WindowInfo
{
    private static readonly Dictionary<uint, string> NameCache = new();
    private static readonly HashSet<string> ShellClasses = new(StringComparer.OrdinalIgnoreCase)
    {
        "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "NotifyIconOverflowWindow", "TopLevelWindowForOverflowXamlIsland",
        "Progman", "WorkerW", "Windows.UI.Core.CoreWindow", "XamlExplorerHostIslandWindow", "#32768",
    };

    public static uint ProcessId(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return 0;
        GetWindowThreadProcessId(hwnd, out var pid);
        return pid;
    }

    /// <summary>Executable name without extension (e.g. "WINWORD"), "" if unknown.</summary>
    public static string ProcessName(uint pid)
    {
        if (pid == 0) return "";
        if (NameCache.TryGetValue(pid, out var cached)) return cached;
        string name = "";
        var handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (handle != IntPtr.Zero)
        {
            try
            {
                var sb = new StringBuilder(1024);
                uint size = (uint)sb.Capacity;
                if (QueryFullProcessImageName(handle, 0, sb, ref size)) name = Path.GetFileNameWithoutExtension(sb.ToString());
            }
            finally
            {
                CloseHandle(handle);
            }
        }
        if (NameCache.Count > 256) NameCache.Clear();
        NameCache[pid] = name;
        return name;
    }

    public static string ClassName(IntPtr hwnd)
    {
        var sb = new StringBuilder(256);
        return GetClassName(hwnd, sb, sb.Capacity) > 0 ? sb.ToString() : "";
    }

    public static bool IsShellWindow(IntPtr hwnd) => hwnd == IntPtr.Zero || ShellClasses.Contains(ClassName(hwnd));

    public static IntPtr RootWindowAt(int x, int y)
    {
        var hwnd = WindowFromPoint(new POINT { X = x, Y = y });
        return hwnd == IntPtr.Zero ? IntPtr.Zero : GetAncestor(hwnd, GA_ROOT);
    }
}
