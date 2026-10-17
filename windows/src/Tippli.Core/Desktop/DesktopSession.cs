using Tippli.Core.Engine;

namespace Tippli.Core.Desktop;

/// <summary>Enter/Shift+Enter handling of SPEC §10.</summary>
public static class EnterFlushPolicy
{
    /// <summary>
    /// Swallow Enter (then flush and re-inject) only when live correction is active for this field, no Ctrl/Alt/Win
    /// is held, <c>waitOnSendMs &gt; 0</c> and a correction is pending or in flight.
    /// </summary>
    public static bool ShouldHold(bool sessionActive, bool ctrl, bool alt, bool win, int waitOnSendMs, bool hasPendingCorrection)
        => sessionActive && !ctrl && !alt && !win && waitOnSendMs > 0 && hasPendingCorrection;
}

/// <summary>Kinds of physical key presses the desktop engine distinguishes (SPEC §10 buffer rules).</summary>
public enum DesktopKeyKind
{
    /// <summary>Printable character(s) after translation.</summary>
    Character,
    /// <summary>A dead key (its spacing character is pending).</summary>
    DeadKey,
    Backspace,
    Enter,
    /// <summary>Navigation, Escape, Tab, any Ctrl/Win shortcut …: the buffer is reset.</summary>
    Reset,
    /// <summary>Modifiers and keys that do not change the text.</summary>
    Ignore,
}

/// <summary>
/// Platform independent glue between physical key events, the <see cref="DesktopBuffer"/> and the
/// <see cref="CorrectionEngine"/> (SPEC §10). The platform layer translates key codes and calls these methods.
/// </summary>
public sealed class DesktopSession
{
    private readonly CorrectionEngine _engine;
    private readonly DesktopBuffer _buffer;

    public DesktopSession(CorrectionEngine engine, DesktopBuffer buffer)
    {
        _engine = engine;
        _buffer = buffer;
    }

    public DesktopBuffer Buffer => _buffer;
    public CorrectionEngine Engine => _engine;

    /// <summary>
    /// Live correction enabled and the field is not excluded/password/our own window. When false nothing is
    /// buffered (privacy) and nothing is sent.
    /// </summary>
    public bool Active { get; private set; }

    /// <summary>The spacing character of a pending dead key.</summary>
    public string? PendingDeadKey { get; private set; }

    public void SetActive(bool active)
    {
        if (Active == active) return;
        Active = active;
        PendingDeadKey = null;
        _buffer.Reset(false);
        _engine.OnNonAppendChange();
    }

    /// <summary>Handles one physical key press. Returns the text appended to the buffer (or null).</summary>
    public string? OnKey(DesktopKeyKind kind, string? text = null)
    {
        switch (kind)
        {
            case DesktopKeyKind.Character:
                return OnText(text ?? "");
            case DesktopKeyKind.DeadKey:
                OnDeadKey(text ?? "");
                return null;
            case DesktopKeyKind.Backspace:
                OnBackspace();
                return null;
            case DesktopKeyKind.Enter:
                OnReset(lineStart: true);
                return null;
            case DesktopKeyKind.Reset:
                OnReset(lineStart: false);
                return null;
            default:
                return null;
        }
    }

    /// <summary>Typed characters (dead-key composition applied here).</summary>
    public string? OnText(string text)
    {
        if (!Active || string.IsNullOrEmpty(text)) return null;
        var t = text;
        if (PendingDeadKey != null)
        {
            t = DeadKeyComposer.Compose(PendingDeadKey, text);
            PendingDeadKey = null;
        }
        _buffer.Append(t);
        _engine.OnTextTyped(t);
        return t;
    }

    public void OnDeadKey(string deadChar)
    {
        if (!Active) return;
        if (PendingDeadKey != null)
        {
            // Two dead keys in a row: the system types both characters.
            var prev = PendingDeadKey;
            PendingDeadKey = null;
            var t = DeadKeyComposer.Compose(prev, deadChar);
            _buffer.Append(t);
            _engine.OnTextTyped(t);
            return;
        }
        PendingDeadKey = deadChar;
    }

    public void OnBackspace()
    {
        if (!Active) return;
        if (PendingDeadKey != null)
        {
            PendingDeadKey = null; // Backspace cancels the dead key in most layouts
            return;
        }
        _buffer.Backspace();
        _engine.OnNonAppendChange();
    }

    /// <summary>Mouse click, navigation keys, shortcuts (lineStart=false) or Enter (lineStart=true).</summary>
    public void OnReset(bool lineStart)
    {
        PendingDeadKey = null;
        _buffer.Reset(lineStart && Active);
        _engine.OnNonAppendChange();
    }

    /// <summary>Focus/foreground change: new field.</summary>
    public void OnFieldChanged(bool aiAllowed)
    {
        PendingDeadKey = null;
        _buffer.Reset(false);
        _engine.OnFieldChanged(aiAllowed);
    }
}
