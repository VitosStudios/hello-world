using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Tippli.Core.Desktop;
using Tippli.Windows.Services;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.Interop;

/// <summary>
/// Injects keystrokes with <c>SendInput</c> (SPEC §9.4): text as KEYEVENTF_UNICODE (surrogate pairs as two
/// events), Backspace/Enter/arrows as virtual keys. Every event carries <see cref="Signature"/> ("TIPP") in
/// dwExtraInfo so the hook can recognise it. One call = one atomic batch (not interleaved with user input).
/// </summary>
internal sealed class InputInjector : IKeystrokeInjector
{
    public const uint Signature = 0x54495050;
    private static readonly int InputSize = Marshal.SizeOf<INPUT>();

    public bool CanInjectNow => !IsKeyDown(VK.CONTROL) && !IsKeyDown(VK.MENU) && !IsKeyDown(VK.LWIN) && !IsKeyDown(VK.RWIN);

    public void Inject(IReadOnlyList<InjectedStroke> strokes)
    {
        var inputs = new List<INPUT>(64);
        foreach (var s in strokes)
        {
            switch (s.Kind)
            {
                case StrokeKind.Text:
                    AddText(inputs, s.Text ?? "");
                    break;
                case StrokeKind.Backspace:
                    for (int i = 0; i < s.Count; i++) AddTap(inputs, VK.BACK);
                    break;
                case StrokeKind.WordBackspace:
                    for (int i = 0; i < s.Count; i++)
                    {
                        inputs.Add(Key(VK.CONTROL, false));
                        AddTap(inputs, VK.BACK);
                        inputs.Add(Key(VK.CONTROL, true));
                    }
                    break;
                case StrokeKind.Enter:
                    AddEnter(inputs, false);
                    break;
                case StrokeKind.ShiftEnter:
                    AddEnter(inputs, true);
                    break;
                case StrokeKind.Left:
                    for (int i = 0; i < s.Count; i++) AddTap(inputs, VK.LEFT);
                    break;
                case StrokeKind.Right:
                    for (int i = 0; i < s.Count; i++) AddTap(inputs, VK.RIGHT);
                    break;
            }
        }
        Send(inputs);
    }

    /// <summary>Ctrl + key (copy/paste); physically held modifiers must be released first.</summary>
    public void SendCtrlCombo(int vk)
    {
        var inputs = new List<INPUT>
        {
            Key(VK.CONTROL, false),
            Key(vk, false),
            Key(vk, true),
            Key(VK.CONTROL, true),
        };
        Send(inputs);
    }

    /// <summary>Releases modifiers that are still physically down (e.g. Ctrl+Alt of a hotkey).</summary>
    public void ReleaseHeldModifiers()
    {
        var inputs = new List<INPUT>();
        foreach (var vk in new[] { VK.LCONTROL, VK.RCONTROL, VK.LMENU, VK.RMENU, VK.LSHIFT, VK.RSHIFT, VK.LWIN, VK.RWIN })
        {
            if (IsKeyDown(vk)) inputs.Add(Key(vk, true));
        }
        Send(inputs);
    }

    public static bool AnyModifierDown()
        => IsKeyDown(VK.CONTROL) || IsKeyDown(VK.MENU) || IsKeyDown(VK.SHIFT) || IsKeyDown(VK.LWIN) || IsKeyDown(VK.RWIN);

    /// <summary>Re-sends a swallowed key press (key down only; its physical key-up follows normally).</summary>
    public void ReplayKeyDown(int vk, uint scan, bool extended, IReadOnlyList<InjectedStroke>? before = null)
    {
        var inputs = new List<INPUT>();
        if (before != null)
        {
            foreach (var s in before)
            {
                if (s.Kind == StrokeKind.Enter) AddEnter(inputs, false);
                else if (s.Kind == StrokeKind.ShiftEnter) AddEnter(inputs, true);
            }
        }
        uint flags = extended ? KEYEVENTF_EXTENDEDKEY : 0;
        inputs.Add(new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion { ki = new KEYBDINPUT { wVk = (ushort)vk, wScan = (ushort)scan, dwFlags = flags, dwExtraInfo = (IntPtr)Signature } },
        });
        Send(inputs);
    }

    private static void AddText(List<INPUT> inputs, string text)
    {
        for (int i = 0; i < text.Length; i++)
        {
            char c = text[i];
            if (c == '\r') continue;
            if (c == '\n')
            {
                AddEnter(inputs, true); // newline inside a text: Shift+Enter never sends a chat message
                continue;
            }
            // Surrogate pairs are sent as two separate UNICODE events (high then low).
            inputs.Add(Unicode(c, false));
            inputs.Add(Unicode(c, true));
        }
    }

    /// <summary>Enter or Shift+Enter, independent of the physical Shift state (restored afterwards).</summary>
    private static void AddEnter(List<INPUT> inputs, bool shift)
    {
        bool lShift = IsKeyDown(VK.LSHIFT), rShift = IsKeyDown(VK.RSHIFT);
        bool shiftDown = lShift || rShift;
        if (shift && !shiftDown) inputs.Add(Key(VK.LSHIFT, false));
        if (!shift && shiftDown)
        {
            if (lShift) inputs.Add(Key(VK.LSHIFT, true));
            if (rShift) inputs.Add(Key(VK.RSHIFT, true));
        }
        AddTap(inputs, VK.RETURN);
        if (shift && !shiftDown) inputs.Add(Key(VK.LSHIFT, true));
        if (!shift && shiftDown)
        {
            if (lShift) inputs.Add(Key(VK.LSHIFT, false));
            if (rShift) inputs.Add(Key(VK.RSHIFT, false));
        }
    }

    private static void AddTap(List<INPUT> inputs, int vk)
    {
        inputs.Add(Key(vk, false));
        inputs.Add(Key(vk, true));
    }

    private static INPUT Key(int vk, bool up)
    {
        uint flags = (up ? KEYEVENTF_KEYUP : 0) | (VK.IsExtended(vk) ? KEYEVENTF_EXTENDEDKEY : 0);
        return new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = (ushort)vk,
                    wScan = (ushort)MapVirtualKey((uint)vk, MAPVK_VK_TO_VSC),
                    dwFlags = flags,
                    time = 0,
                    dwExtraInfo = (IntPtr)Signature,
                },
            },
        };
    }

    private static INPUT Unicode(char c, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion
        {
            ki = new KEYBDINPUT
            {
                wVk = 0,
                wScan = c,
                dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0),
                time = 0,
                dwExtraInfo = (IntPtr)Signature,
            },
        },
    };

    private static void Send(List<INPUT> inputs)
    {
        if (inputs.Count == 0) return;
        var array = inputs.ToArray();
        uint sent = SendInput((uint)array.Length, array, InputSize);
        if (sent != array.Length)
            Log.Info($"SendInput sent {sent}/{array.Length} events (error {Marshal.GetLastWin32Error()}) - target may be elevated (UIPI).");
    }
}
