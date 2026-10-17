using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;
using Tippli.Core.Ai;
using Tippli.Core.Desktop;
using Tippli.Core.Engine;
using Tippli.Core.Keyboard;
using Tippli.Core.Settings;
using Tippli.Core.Shared;
using Tippli.Core.Text;
using Tippli.Windows.Interop;
using Tippli.Windows.UI;
using static Tippli.Windows.Interop.NativeMethods;

namespace Tippli.Windows.Services;

/// <summary>What the tips window works on (SPEC §7.2 / §10).</summary>
internal sealed record TipsContext(
    string Text,
    bool IsSelection,
    IntPtr Target,
    IReadOnlyList<HistoryEntry> History,
    string BufferText,
    bool ScrollToEmotion);

/// <summary>
/// The desktop engine of SPEC §10: low-level hooks → key classification → <see cref="DesktopSession"/> (buffer +
/// correction engine); focus/foreground tracking, excluded apps, password fields (UI Automation), Enter flush,
/// hotkeys, tips and the on-screen keyboard. Everything runs on the WPF UI thread.
/// </summary>
internal sealed class DesktopController : IDisposable
{
    private sealed class PendingEnter
    {
        public required bool Shift;
    }

    private readonly Dispatcher _dispatcher;
    private readonly LowLevelHooks _hooks = new();
    private readonly KeyTranslator _translator = new();
    private readonly FocusWatcher _focus = new();
    private readonly FieldInspector _inspector;
    private readonly HttpClient _http;
    private readonly uint _ownPid = (uint)Environment.ProcessId;
    private readonly LinkedList<string> _recentApps = new();
    private HotkeyManager? _hotkeys;

    private string _foregroundProcess = "";
    private bool _excluded;
    private bool _password;
    private bool _ownForeground;
    private bool _fieldCheckPending;
    private IntPtr _lastTarget;
    private PendingEnter? _pendingEnter;
    private long _lastErrorNotice = long.MinValue / 2;

    private OnScreenKeyboardWindow? _keyboardWindow;
    private SettingsWindow? _settingsWindow;
    private TipsWindow? _tipsWindow;

    public DesktopController(Dispatcher dispatcher, SharedData data, SettingsStore settings, ISecretStore secrets, AppState state,
        ThemeManager theme)
    {
        _dispatcher = dispatcher;
        Data = data;
        Settings = settings;
        Secrets = secrets;
        State = state;
        Theme = theme;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(100) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Tippli-Windows/1.0");
        Ai = new AiService(data, settings, secrets, new AiClient(_http));
        Scheduler = new DispatcherScheduler(dispatcher);
        Buffer = new DesktopBuffer();
        Injector = new InputInjector();
        Host = new DesktopTextHost(Buffer, Injector);
        Engine = new CorrectionEngine(Host, Ai, SystemClock.Instance, Scheduler);
        Session = new DesktopSession(Engine, Buffer);
        KeyboardHost = new OskKeyboardHost(this);
        _inspector = new FieldInspector(dispatcher);
    }

    public SharedData Data { get; }
    public SettingsStore Settings { get; }
    public ISecretStore Secrets { get; }
    public AppState State { get; }
    public ThemeManager Theme { get; }
    public AiService Ai { get; }
    public IScheduler Scheduler { get; }
    public IClock Clock => SystemClock.Instance;
    public DesktopBuffer Buffer { get; }
    public InputInjector Injector { get; }
    public DesktopTextHost Host { get; }
    public CorrectionEngine Engine { get; }
    public DesktopSession Session { get; }
    public IKeyboardHost KeyboardHost { get; }
    public Strings Strings => Data.Strings;

    /// <summary>Ctrl+Alt+letter → registered?</summary>
    public Dictionary<char, bool> HotkeyStatus { get; } = new();

    public IReadOnlyCollection<string> RecentApps => _recentApps;
    public bool EnterBusy => _pendingEnter != null;
    public bool KeyboardVisible => _keyboardWindow?.IsVisible == true;

    /// <summary>Status for tray and keyboard toolbar changed.</summary>
    public event Action? StatusChanged;
    /// <summary>The user typed/clicked outside the on-screen keyboard (restore stack, auto-cap).</summary>
    public event Action? PhysicalInput;
    public event Action<bool>? EnterBusyChanged;
    /// <summary>A notification for the tray balloon (title, text).</summary>
    public event Action<string, string>? Notify;

    // ---- start / stop ---------------------------------------------------------------------------------

    public void Start()
    {
        ApplySettings();
        Settings.Changed += OnSettingChanged;
        Engine.StatusChanged += RaiseStatus;
        Engine.UndoChanged += RaiseStatus;
        Engine.CorrectionFailed += OnCorrectionFailed;

        _translator.InitCapsLock();
        _hooks.KeyboardEvent = OnKeyboardEvent;
        _hooks.MouseButtonDown = OnMouseButtonDown;
        if (!_hooks.Install()) Notify?.Invoke("Tippli", Strings.Get("desktop.hookFailed", "Die Live-Korrektur konnte nicht gestartet werden."));

        _focus.ForegroundChanged += OnForegroundChanged;
        _focus.FocusChanged += OnFocusChanged;
        _focus.Start();
        _inspector.Completed += OnFieldInspected;

        _hotkeys = new HotkeyManager();
        HotkeyStatus['K'] = _hotkeys.Register(VK.K, ToggleLiveCorrection);
        HotkeyStatus['H'] = _hotkeys.Register(VK.H, () => _ = ShowTipsAsync(TipsSource.Hotkey));
        HotkeyStatus['B'] = _hotkeys.Register(VK.B, ToggleKeyboard);
        HotkeyStatus['J'] = _hotkeys.Register(VK.J, () => _ = CorrectNowAsync());

        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.SessionSwitch += OnSessionSwitch;

        AutoStart.Apply(Settings.DesktopLaunchAtLogin);
        OnForegroundChanged(GetForegroundWindow());
    }

    public void Dispose()
    {
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.SessionSwitch -= OnSessionSwitch;
        _hooks.Dispose();
        _focus.Dispose();
        _inspector.Dispose();
        _hotkeys?.Dispose();
        _http.Dispose();
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Resume) _dispatcher.BeginInvoke(ReinstallHooks);
    }

    private void OnSessionSwitch(object? sender, SessionSwitchEventArgs e)
    {
        if (e.Reason is SessionSwitchReason.SessionUnlock or SessionSwitchReason.ConsoleConnect or SessionSwitchReason.RemoteConnect)
            _dispatcher.BeginInvoke(ReinstallHooks);
    }

    /// <summary>Windows silently drops low-level hooks after sleep or repeated timeouts; re-install them.</summary>
    private void ReinstallHooks()
    {
        _hooks.Install();
        _translator.InitCapsLock();
        Session.OnReset(false);
    }

    // ---- settings ---------------------------------------------------------------------------------------

    private void ApplySettings()
    {
        Engine.Options = new EngineOptions
        {
            AiEnabled = Settings.AiEnabled,
            Trigger = Settings.Trigger,
            PauseMs = Settings.PauseMs,
            WaitOnSendMs = Settings.WaitOnSendMs,
            MoodWarning = Settings.MoodWarning && Settings.TipsEmotion,
            Pinyin = Settings.ActiveLanguageInfo.Pinyin,
        };
    }

    private void OnSettingChanged(string key)
    {
        ApplySettings();
        if (key is "provider" or "customBaseUrl" || key.StartsWith("model.", StringComparison.Ordinal))
        {
            Engine.ResetError();
            Engine.ClearCache();
        }
        if (key is "activeLanguage" or "strength" || key.StartsWith("keyboard.", StringComparison.Ordinal)) Engine.ClearCache();
        if (key is "desktopLiveCorrection" or "desktopExcludedApps")
        {
            _excluded = IsExcluded(_foregroundProcess);
            UpdateActive();
        }
        if (key == "desktopLaunchAtLogin") AutoStart.Apply(Settings.DesktopLaunchAtLogin);
        if (key == "theme") Theme.Apply();
        RaiseStatus();
    }

    /// <summary>Called after an API key was stored or removed.</summary>
    public void OnApiKeyChanged()
    {
        Engine.ResetError();
        RaiseStatus();
    }

    public void ToggleLiveCorrection()
    {
        Settings.DesktopLiveCorrection = !Settings.DesktopLiveCorrection;
        Notify?.Invoke("Tippli", Settings.DesktopLiveCorrection
            ? Strings.Get("desktop.liveOn", "Live-Korrektur an")
            : Strings.Get("desktop.liveOff", "Live-Korrektur aus"));
    }

    // ---- status -----------------------------------------------------------------------------------------

    private void RaiseStatus() => StatusChanged?.Invoke();

    public string StatusLine
    {
        get
        {
            var provider = Settings.ActiveProvider.ShortName;
            string state;
            if (!Settings.DesktopLiveCorrection) state = Strings.Get("desktop.liveOff", "Live-Korrektur aus");
            else if (!Settings.AiEnabled) state = Strings.Get("toolbar.aiOff", "KI aus");
            else if (!Ai.HasKey) state = Strings.Get("toolbar.aiNoKey", "Kein API-Schlüssel");
            else if (Engine.LastError != null) state = Strings.Get("toolbar.aiError", "KI-Fehler");
            else state = Strings.Get("desktop.liveOn", "Live-Korrektur an");
            return $"{provider} · {state}";
        }
    }

    public string ErrorMessage => Engine.LastError is { } e ? AiErrors.Message(e) : "";

    private void OnCorrectionFailed(AiErrorKind kind)
    {
        RaiseStatus();
        long now = Clock.NowMs;
        if (KeyboardVisible || now - _lastErrorNotice < 5 * 60_000) return;
        _lastErrorNotice = now;
        Notify?.Invoke(Strings.Get("toolbar.aiError", "KI-Fehler"), AiErrors.Message(kind));
    }

    // ---- foreground / focus -----------------------------------------------------------------------------

    private bool IsExcluded(string process)
    {
        if (string.IsNullOrEmpty(process)) return false;
        foreach (var app in Settings.DesktopExcludedApps)
        {
            var name = app.Trim();
            if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
            if (string.Equals(name, process, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    private bool ComputeActive() => Settings.DesktopLiveCorrection && !_excluded && !_password && !_ownForeground;

    private void UpdateActive()
    {
        Session.SetActive(ComputeActive());
        Engine.SetFieldAllowed(Session.Active && !_fieldCheckPending);
        RaiseStatus();
    }

    private void OnForegroundChanged(IntPtr hwnd)
    {
        if (_pendingEnter != null) CompletePendingEnter(_pendingEnter, null);
        uint pid = WindowInfo.ProcessId(hwnd);
        _ownForeground = pid == _ownPid;
        _foregroundProcess = _ownForeground ? "" : WindowInfo.ProcessName(pid);
        if (!_ownForeground && !WindowInfo.IsShellWindow(hwnd))
        {
            _lastTarget = hwnd;
            RememberApp(_foregroundProcess);
        }
        _excluded = IsExcluded(_foregroundProcess);
        NewField();
    }

    private void OnFocusChanged(IntPtr hwnd)
    {
        if (hwnd != IntPtr.Zero && WindowInfo.ProcessId(GetAncestor(hwnd, GA_ROOT)) == _ownPid) return;
        if (_pendingEnter != null) CompletePendingEnter(_pendingEnter, null);
        NewField();
    }

    private void NewField()
    {
        _password = false;
        _fieldCheckPending = Settings.DesktopLiveCorrection && !_excluded && !_ownForeground;
        Session.OnFieldChanged(false);
        UpdateActive();
        if (_fieldCheckPending) _inspector.Request();
        PhysicalInput?.Invoke();
    }

    private void OnFieldInspected(FieldInspector.Result r)
    {
        if (r.Generation != _inspector.Generation) return;
        _password = r.IsPassword;
        _fieldCheckPending = false;
        UpdateActive();
        if (Session.Active && r.Value != null && !Buffer.StartsAtLineStart && string.Equals(r.Value, Buffer.Text, StringComparison.Ordinal))
        {
            // The field contains exactly what we saw being typed: the buffer starts at the field start.
            Buffer.MarkStartKnown();
            PhysicalInput?.Invoke();
        }
    }

    private void RememberApp(string process)
    {
        if (string.IsNullOrEmpty(process)) return;
        _recentApps.Remove(process);
        _recentApps.AddFirst(process);
        while (_recentApps.Count > 15) _recentApps.RemoveLast();
    }

    // ---- low level input ----------------------------------------------------------------------------------

    private void OnMouseButtonDown(int x, int y, bool injected)
    {
        if (injected) return;
        var root = WindowInfo.RootWindowAt(x, y);
        if (root != IntPtr.Zero && WindowInfo.ProcessId(root) == _ownPid) return; // our keyboard / windows
        if (_pendingEnter != null) CompletePendingEnter(_pendingEnter, null);
        Session.OnKey(DesktopKeyKind.Reset);
        if (Session.Active) _inspector.Request();
        Scheduler.Post(() => PhysicalInput?.Invoke());
    }

    /// <summary>The keyboard hook (runs inside the hook callback: fast, no I/O). Returns true to swallow.</summary>
    private bool OnKeyboardEvent(KeyEvent e)
    {
        if (e.Injected) return false; // ours (TIPP signature) and everybody else's synthetic input
        if (!e.Down) return false;
        if (e.Vk == VK.CAPITAL)
        {
            _translator.CapsLock = !_translator.CapsLock;
            return false;
        }
        if (VK.IsModifier(e.Vk)) return false;
        if (_pendingEnter != null) return KeyDuringPendingEnter(e);

        bool swallow = HandleKey(e, allowHold: true);
        Scheduler.Post(() => PhysicalInput?.Invoke());
        return swallow;
    }

    private bool HandleKey(KeyEvent e, bool allowHold)
    {
        if (!Session.Active) return false;
        bool ctrl = IsKeyDown(VK.CONTROL), alt = IsKeyDown(VK.MENU), win = IsKeyDown(VK.LWIN) || IsKeyDown(VK.RWIN);
        bool shift = IsKeyDown(VK.SHIFT);
        int vk = e.Vk;

        if (vk == VK.BACK)
        {
            Session.OnKey(ctrl || alt || win ? DesktopKeyKind.Reset : DesktopKeyKind.Backspace);
            return false;
        }
        if (vk == VK.RETURN)
        {
            if (ctrl || alt || win)
            {
                Session.OnKey(DesktopKeyKind.Reset);
                return false;
            }
            if (allowHold && EnterFlushPolicy.ShouldHold(Session.Active, ctrl, alt, win, Settings.WaitOnSendMs, Engine.HasPendingCorrection()))
            {
                BeginPendingEnter(shift);
                return true;
            }
            Session.OnKey(DesktopKeyKind.Enter);
            return false;
        }
        if (vk is VK.TAB or VK.ESCAPE or VK.APPS or VK.PROCESSKEY || VK.IsNavigation(vk)
            || vk is VK.KANA or VK.JUNJA or VK.FINAL or VK.KANJI or VK.CONVERT or VK.NONCONVERT or VK.ACCEPT or VK.MODECHANGE)
        {
            Session.OnKey(DesktopKeyKind.Reset);
            return false;
        }
        if (vk == VK.DELETE)
        {
            if (ctrl || alt || win) Session.OnKey(DesktopKeyKind.Reset); // plain Delete only changes text after the caret
            return false;
        }
        if (VK.IsFunctionOrMedia(vk)) return false;
        if ((ctrl && !alt) || (alt && !ctrl) || win)
        {
            Session.OnKey(DesktopKeyKind.Reset); // any Ctrl/Alt/Win shortcut (Ctrl+Alt = AltGr is typing)
            return false;
        }

        var (kind, text) = _translator.Translate(vk, e.Scan, KeyTranslator.ForegroundLayout());
        if (kind == TranslationKind.DeadKey) Session.OnKey(DesktopKeyKind.DeadKey, text);
        else if (kind == TranslationKind.Characters) Session.OnKey(DesktopKeyKind.Character, text);
        return false;
    }

    // ---- Enter flush (SPEC §4.3 / §10) ----------------------------------------------------------------------

    private void BeginPendingEnter(bool shift)
    {
        var pending = new PendingEnter { Shift = shift };
        _pendingEnter = pending;
        EnterBusyChanged?.Invoke(true);
        _ = RunPendingEnterAsync(pending);
    }

    private async Task RunPendingEnterAsync(PendingEnter pending)
    {
        try
        {
            await Engine.FlushAsync(Settings.WaitOnSendMs);
        }
        catch (Exception ex)
        {
            Log.Error("flush", ex);
        }
        CompletePendingEnter(pending, null);
    }

    /// <summary>Re-injects the held Enter (plus a key that ended the wait early).</summary>
    private void CompletePendingEnter(PendingEnter pending, KeyEvent? replay)
    {
        if (_pendingEnter != pending) return;
        _pendingEnter = null;
        var enter = new[] { new InjectedStroke(pending.Shift ? StrokeKind.ShiftEnter : StrokeKind.Enter) };
        if (replay is { } key)
        {
            Injector.ReplayKeyDown(key.Vk, key.Scan, key.Extended, enter);
            Session.OnKey(DesktopKeyKind.Enter);
            HandleKey(key, allowHold: false);
        }
        else
        {
            Injector.Inject(enter);
            Session.OnKey(DesktopKeyKind.Enter);
        }
        EnterBusyChanged?.Invoke(false);
    }

    private bool KeyDuringPendingEnter(KeyEvent e)
    {
        var pending = _pendingEnter!;
        if (e.Vk == VK.RETURN)
        {
            CompletePendingEnter(pending, null); // second Enter: continue at once
            return true;
        }
        CompletePendingEnter(pending, e); // keep the order: Enter first, then this key
        return true;
    }

    /// <summary>Enter from the on-screen keyboard.</summary>
    public void EnterFromKeyboard()
    {
        if (_pendingEnter != null)
        {
            CompletePendingEnter(_pendingEnter, null);
            return;
        }
        if (EnterFlushPolicy.ShouldHold(Session.Active, false, false, false, Settings.WaitOnSendMs, Engine.HasPendingCorrection()))
        {
            BeginPendingEnter(false);
            return;
        }
        Injector.Inject(new[] { new InjectedStroke(StrokeKind.Enter) });
        Session.OnKey(DesktopKeyKind.Enter);
    }

    // ---- correct now / tips -----------------------------------------------------------------------------------

    /// <summary>Ctrl+Alt+J / ✨: selected text → corrected via paste; otherwise flush the buffer.</summary>
    public async Task CorrectNowAsync()
    {
        try
        {
            if (!Settings.AiEnabled || _password || _excluded || _ownForeground) return;
            if (!Ai.HasKey)
            {
                Notify?.Invoke("Tippli", AiErrors.Message(AiErrorKind.NoKey));
                return;
            }
            var selection = await ClipboardService.CopySelectionAsync(Injector);
            if (!string.IsNullOrWhiteSpace(selection))
            {
                var output = await Ai.CorrectAsync("", selection);
                var corrected = TextAlgorithms.CleanOutput(selection, output, Settings.ActiveLanguageInfo.Pinyin);
                if (corrected != null && corrected != selection)
                {
                    await ClipboardService.PasteTextAsync(Injector, corrected);
                    Session.OnKey(DesktopKeyKind.Reset);
                }
                return;
            }
            await Engine.FlushAsync(10_000);
        }
        catch (AiException ex)
        {
            Notify?.Invoke(Strings.Get("toolbar.aiError", "KI-Fehler"), AiErrors.Message(ex.Kind));
        }
        catch (Exception ex)
        {
            Log.Error("correct now", ex);
        }
    }

    public enum TipsSource
    {
        Hotkey,
        Tray,
        Keyboard,
        Emotion,
    }

    /// <summary>Opens the tips window for the selection (copied via Ctrl+C) or, as fallback, the buffer.</summary>
    public async Task ShowTipsAsync(TipsSource source)
    {
        try
        {
            IntPtr target = source == TipsSource.Tray ? _lastTarget : GetForegroundWindow();
            if (target == IntPtr.Zero || WindowInfo.ProcessId(target) == _ownPid) target = _lastTarget;
            var history = Engine.History.ToList();
            var bufferText = Host.TextBeforeCursor(4000);

            string? selection = null;
            bool canCopy = !_password && !_excluded && target != IntPtr.Zero && IsWindow(target);
            if (source != TipsSource.Emotion && canCopy)
            {
                if (GetForegroundWindow() != target)
                {
                    SetForegroundWindow(target);
                    await Task.Delay(150);
                }
                selection = await ClipboardService.CopySelectionAsync(Injector);
            }
            bool isSelection = !string.IsNullOrWhiteSpace(selection);
            var text = isSelection ? selection! : bufferText;
            if (text.Length > 4000) text = isSelection ? text[..4000] : Graphemes.TakeLast(text, 4000);
            if (source == TipsSource.Emotion && !isSelection) text = TextAlgorithms.LastParagraph(text);

            var context = new TipsContext(text, isSelection, target, history, bufferText, source == TipsSource.Emotion);
            _tipsWindow?.Close();
            _tipsWindow = new TipsWindow(this, context);
            _tipsWindow.Closed += (_, _) => _tipsWindow = null;
            _tipsWindow.Show();
            _tipsWindow.Activate();
        }
        catch (Exception ex)
        {
            Log.Error("tips", ex);
        }
    }

    /// <summary>"Ersetzen"/"Einfügen" from the tips window (SPEC §10).</summary>
    public async Task ApplyFromTipsAsync(TipsContext context, string newText, bool insert)
    {
        try
        {
            if (context.Target != IntPtr.Zero && IsWindow(context.Target))
            {
                SetForegroundWindow(context.Target);
                await Task.Delay(200);
            }
            if (insert)
            {
                if (context.IsSelection) Injector.Inject(new[] { new InjectedStroke(StrokeKind.Right) });
                await ClipboardService.PasteTextAsync(Injector, "\n" + newText);
            }
            else if (context.IsSelection)
            {
                await ClipboardService.PasteTextAsync(Injector, newText);
            }
            else if (context.BufferText.Length > 0)
            {
                Injector.Inject(new[] { InjectedStroke.Backspaces(Graphemes.Count(context.BufferText)), InjectedStroke.Type(newText) });
            }
            else
            {
                await ClipboardService.PasteTextAsync(Injector, newText);
            }
            Engine.OnTextReplaced();
            Session.OnKey(DesktopKeyKind.Reset);
        }
        catch (Exception ex)
        {
            Log.Error("apply tips", ex);
        }
    }

    // ---- windows ----------------------------------------------------------------------------------------------

    public void ToggleKeyboard()
    {
        if (KeyboardVisible) HideKeyboard();
        else ShowKeyboard();
    }

    public void ShowKeyboard()
    {
        _keyboardWindow ??= new OnScreenKeyboardWindow(this);
        _keyboardWindow.ShowNoActivate();
        RaiseStatus();
    }

    public void HideKeyboard()
    {
        _keyboardWindow?.Hide();
        RaiseStatus();
    }

    public void OpenSettings(bool welcome = false, string? page = null)
    {
        if (_settingsWindow == null)
        {
            _settingsWindow = new SettingsWindow(this, welcome);
            _settingsWindow.Closed += (_, _) => _settingsWindow = null;
            _settingsWindow.Show();
        }
        if (page != null) _settingsWindow.ShowPage(page);
        if (_settingsWindow.WindowState == WindowState.Minimized) _settingsWindow.WindowState = WindowState.Normal;
        _settingsWindow.Activate();
    }

    public void CloseAllWindows()
    {
        _tipsWindow?.Close();
        _settingsWindow?.Close();
        _keyboardWindow?.CloseForShutdown();
    }

    // ---- on-screen keyboard host ----------------------------------------------------------------------------

    /// <summary>The on-screen keyboard types into the focused program and feeds the buffer as if typed (SPEC §10).</summary>
    private sealed class OskKeyboardHost : IKeyboardHost
    {
        private readonly DesktopController _c;

        public OskKeyboardHost(DesktopController c) => _c = c;

        public string TextBeforeCursor(int maxChars) => _c.Host.TextBeforeCursor(maxChars);

        public bool IsContextKnown => _c.Session.Active && _c.Buffer.StartsAtLineStart;

        public void CommitText(string text)
        {
            _c.Injector.Inject(new[] { InjectedStroke.Type(text) });
            _c.Session.OnText(text);
        }

        public void DeleteBackward()
        {
            _c.Injector.Inject(new[] { InjectedStroke.Backspaces(1) });
            _c.Session.OnBackspace();
        }

        public void DeleteBeforeCursor(string text)
        {
            _c.Injector.Inject(new[] { InjectedStroke.Backspaces(Graphemes.Count(text)) });
            if (_c.Session.Active) _c.Buffer.RemoveSuffix(text);
            _c.Engine.OnNonAppendChange();
        }

        public void DeleteWordFallback()
        {
            _c.Injector.Inject(new[] { new InjectedStroke(StrokeKind.WordBackspace) });
            _c.Session.OnReset(false);
        }

        public void InsertRestored(string text)
        {
            _c.Injector.Inject(new[] { InjectedStroke.Type(text) });
            if (_c.Session.Active) _c.Buffer.Append(text);
            _c.Engine.OnNonAppendChange();
        }

        public void MoveCursor(int delta)
        {
            if (delta == 0) return;
            _c.Injector.Inject(new[] { new InjectedStroke(delta < 0 ? StrokeKind.Left : StrokeKind.Right, null, Math.Abs(delta)) });
            _c.Session.OnReset(false);
        }

        public void PerformEnter() => _c.EnterFromKeyboard();

        public bool TryUndoCorrection() => _c.Engine.Undo();

        public void Feedback(KeyboardFeedback kind)
        {
            if (kind == KeyboardFeedback.KeyDown && _c.Settings.Sound) KeySound.Play();
        }
    }
}
