using Tippli.Core.Engine;
using Tippli.Core.Models;
using Tippli.Core.Text;

namespace Tippli.Core.Keyboard;

public enum ShiftState
{
    Off,
    /// <summary>One-shot shift set by the user.</summary>
    OneShot,
    /// <summary>One-shot shift set by auto-capitalisation.</summary>
    OneShotAuto,
    CapsLock,
}

public enum KeyboardPanel
{
    Letters,
    Symbols1,
    Symbols2,
    Emoji,
}

public enum KeyboardFeedback
{
    KeyDown,
    PopupOpen,
    NoOp,
}

/// <summary>Text field operations the keyboard needs (mobile: InputConnection/proxy, desktop: injected keys + buffer).</summary>
public interface IKeyboardHost
{
    string TextBeforeCursor(int maxChars);
    /// <summary>True when an empty <see cref="TextBeforeCursor"/> really means "field/line start".</summary>
    bool IsContextKnown { get; }
    /// <summary>Typed text (an append).</summary>
    void CommitText(string text);
    /// <summary>Delete one grapheme before the cursor (or the selection).</summary>
    void DeleteBackward();
    /// <summary>Delete exactly <paramref name="text"/> right before the cursor.</summary>
    void DeleteBeforeCursor(string text);
    /// <summary>Delete a word when the text before the cursor is unknown (desktop: Ctrl+Backspace).</summary>
    void DeleteWordFallback();
    /// <summary>Re-insert text from the restore stack (a non-append change).</summary>
    void InsertRestored(string text);
    void MoveCursor(int delta);
    void PerformEnter();
    bool TryUndoCorrection();
    void Feedback(KeyboardFeedback kind);
}

/// <summary>Keyboard options taken from the settings.</summary>
public sealed class KeyboardOptions
{
    public bool AutoCapitalize { get; set; } = true;
    public bool DoubleSpacePeriod { get; set; } = true;
    public bool LongPressAccents { get; set; } = true;
    public bool LongPressDigits { get; set; } = true;
    public bool Pinyin { get; set; }
    public string LanguageShort { get; set; } = "";
    /// <summary>False for e-mail/URL/password fields (§4.8).</summary>
    public bool AutoCapAllowed { get; set; } = true;
}

/// <summary>Keyboard state: pages, shift, restore stack, emoji recents (SPEC §4.2–4.6, §4.9).</summary>
public sealed class KeyboardController : IGestureSink
{
    public const int RestoreStackMax = 20;
    public const int EmojiRecentsMax = 32;

    private readonly IKeyboardHost _host;
    private readonly IClock _clock;
    private readonly AccentsFile _accents;
    private readonly Stack<string> _restore = new();
    private readonly List<string> _recents = new();
    private readonly HashSet<KeyShape> _pressed = new();
    private long _lastShiftTap = long.MinValue / 2;
    private long _lastSpaceAt = long.MinValue / 2;
    private bool _lastKeyWasSpace;

    public KeyboardController(IKeyboardHost host, IClock clock, AccentsFile accents)
    {
        _host = host;
        _clock = clock;
        _accents = accents;
    }

    public KeyboardOptions Options { get; set; } = new();
    public KeyboardPanel Panel { get; private set; } = KeyboardPanel.Letters;
    public ShiftState Shift { get; private set; } = ShiftState.Off;
    public bool ShiftActive => Shift != ShiftState.Off;
    public PopupState? Popup { get; private set; }
    public IReadOnlyCollection<KeyShape> PressedKeys => _pressed;
    public IReadOnlyList<string> EmojiRecents => _recents;
    public int RestoreCount => _restore.Count;

    public KeyboardPage Page => Panel switch
    {
        KeyboardPanel.Symbols1 => KeyboardPage.Symbols1,
        KeyboardPanel.Symbols2 => KeyboardPage.Symbols2,
        _ => KeyboardPage.Letters,
    };

    /// <summary>Raised when anything visible changed (redraw).</summary>
    public event Action? Changed;
    /// <summary>Raised when the page/panel changed (geometry must be rebuilt).</summary>
    public event Action? PanelChanged;
    public event Action<IReadOnlyList<string>>? EmojiRecentsChanged;

    public void SetEmojiRecents(IEnumerable<string> recents)
    {
        _recents.Clear();
        _recents.AddRange(recents.Where(e => !string.IsNullOrEmpty(e)).Distinct().Take(EmojiRecentsMax));
    }

    public void SetPanel(KeyboardPanel panel)
    {
        if (Panel == panel) return;
        Panel = panel;
        PanelChanged?.Invoke();
        Changed?.Invoke();
    }

    /// <summary>New field: letters page, clear restore stack, recompute auto-cap.</summary>
    public void OnFieldStart(bool symbolsField = false)
    {
        _restore.Clear();
        _lastKeyWasSpace = false;
        if (Shift != ShiftState.CapsLock) Shift = ShiftState.Off;
        SetPanel(symbolsField ? KeyboardPanel.Symbols1 : KeyboardPanel.Letters);
        AfterChange();
    }

    /// <summary>The text changed outside the keyboard (physical keys, cursor move): clear the restore stack.</summary>
    public void OnExternalChange()
    {
        _restore.Clear();
        _lastKeyWasSpace = false;
        AfterChange();
    }

    public IReadOnlyList<string> AlternativesFor(KeyShape key)
        => KeyAlternatives.For(key, _accents, Options.LongPressDigits, Options.LongPressAccents, ShiftActive, Page);

    /// <summary>Key label (SPEC §3.6). <paramref name="firstSpace"/>: hex mode shows the language only on the first space.</summary>
    public string LabelFor(KeyShape key, bool firstSpace = true)
    {
        var strings = Shared.Strings.Default;
        return key.Code switch
        {
            "$shift" => Shift == ShiftState.CapsLock ? "⇪" : "⇧",
            "$back" => "⌫",
            "$space" => firstSpace ? Options.LanguageShort : "",
            "$enter" => "⏎",
            "$123" => strings.Get("keys.symbols", "123"),
            "$sym2" => strings.Get("keys.symbols2", "#+="),
            "$sym1" => strings.Get("keys.symbols", "123"),
            "$abc" => strings.Get("keys.letters", "ABC"),
            "$emoji" => "😊",
            "$nextkb" => "🌐",
            _ => ShiftActive && key.IsLetter ? TextAlgorithms.UpperCaseKey(key.Code) : key.Code,
        };
    }

    // ---- IGestureSink -----------------------------------------------------------------------------------

    public void OnPress(KeyShape key)
    {
        _pressed.Add(key);
        _host.Feedback(KeyboardFeedback.KeyDown);
        Changed?.Invoke();
    }

    public void OnRelease(KeyShape key)
    {
        if (_pressed.Remove(key)) Changed?.Invoke();
    }

    public void OnTap(KeyShape key)
    {
        long now = _clock.NowMs;
        switch (key.Code)
        {
            case "$shift":
                if (now - _lastShiftTap <= GestureThresholds.DoubleTap && Shift != ShiftState.CapsLock)
                    Shift = ShiftState.CapsLock;
                else if (Shift == ShiftState.Off)
                    Shift = ShiftState.OneShot;
                else
                    Shift = ShiftState.Off;
                _lastShiftTap = now;
                Changed?.Invoke();
                return;
            case "$space":
                _restore.Clear();
                if (Options.DoubleSpacePeriod && _lastKeyWasSpace && now - _lastSpaceAt < GestureThresholds.DoubleSpace
                    && TextAlgorithms.DoubleSpacePeriod(_host.TextBeforeCursor(64)))
                {
                    _host.DeleteBeforeCursor(" ");
                    _host.CommitText(". ");
                    _lastKeyWasSpace = false;
                }
                else
                {
                    _host.CommitText(" ");
                    _lastKeyWasSpace = true;
                    _lastSpaceAt = now;
                }
                if (Panel is KeyboardPanel.Symbols1 or KeyboardPanel.Symbols2) SetPanel(KeyboardPanel.Letters);
                AfterChange();
                return;
            case "$enter":
                _restore.Clear();
                _lastKeyWasSpace = false;
                _host.PerformEnter();
                if (Panel is KeyboardPanel.Symbols1 or KeyboardPanel.Symbols2) SetPanel(KeyboardPanel.Letters);
                AfterChange();
                return;
            case "$back":
                return; // handled on pointer down
            case "$123":
            case "$sym1":
                SetPanel(KeyboardPanel.Symbols1);
                return;
            case "$sym2":
                SetPanel(KeyboardPanel.Symbols2);
                return;
            case "$abc":
                SetPanel(KeyboardPanel.Letters);
                return;
            case "$emoji":
                SetPanel(KeyboardPanel.Emoji);
                return;
            case "$nextkb":
                return;
        }
        if (key.IsSpecial) return;
        var text = ShiftActive && key.IsLetter ? TextAlgorithms.UpperCaseKey(key.Code) : key.Code;
        TypeText(text, consumeShift: true);
    }

    public void OnUppercase(KeyShape key) => TypeText(TextAlgorithms.UpperCaseKey(key.Code), consumeShift: false);

    public void OnAlternative(string text) => TypeText(text, consumeShift: true);

    public void OnBackspace(bool word)
    {
        _restore.Clear();
        _lastKeyWasSpace = false;
        if (word) DeleteWord();
        else _host.DeleteBackward();
        AfterChange();
    }

    public void OnSwipeDelete()
    {
        _lastKeyWasSpace = false;
        var deleted = DeleteWord();
        if (!string.IsNullOrEmpty(deleted))
        {
            _restore.Push(deleted);
            TrimRestore();
        }
        AfterChange();
    }

    public void OnSwipeRestore()
    {
        _lastKeyWasSpace = false;
        if (_restore.Count > 0)
            _host.InsertRestored(_restore.Pop());
        else if (!_host.TryUndoCorrection())
            _host.Feedback(KeyboardFeedback.NoOp);
        AfterChange();
    }

    public void OnCursor(int delta)
    {
        _restore.Clear();
        _lastKeyWasSpace = false;
        _host.MoveCursor(delta);
        AfterChange();
    }

    public void OnShiftLongPress()
    {
        Shift = ShiftState.CapsLock;
        Changed?.Invoke();
    }

    public void OnPopupOpened(PopupState popup)
    {
        Popup = popup;
        _host.Feedback(KeyboardFeedback.PopupOpen);
        Changed?.Invoke();
    }

    public void OnPopupChanged(PopupState? popup)
    {
        Popup = popup;
        Changed?.Invoke();
    }

    // ---- emoji panel ------------------------------------------------------------------------------------

    public void TypeEmoji(string emoji)
    {
        if (string.IsNullOrEmpty(emoji)) return;
        TypeText(emoji, consumeShift: false);
        _recents.Remove(emoji);
        _recents.Insert(0, emoji);
        if (_recents.Count > EmojiRecentsMax) _recents.RemoveRange(EmojiRecentsMax, _recents.Count - EmojiRecentsMax);
        EmojiRecentsChanged?.Invoke(_recents);
    }

    public void EmojiBackspace() => OnBackspace(false);

    // ---- helpers -------------------------------------------------------------------------------------------

    private void TypeText(string text, bool consumeShift)
    {
        _restore.Clear();
        _lastKeyWasSpace = false;
        _host.CommitText(text);
        if (consumeShift && Shift is ShiftState.OneShot or ShiftState.OneShotAuto) Shift = ShiftState.Off;
        AfterChange();
    }

    /// <summary>Deletes the word before the cursor (§5.6) and returns it (null when the context is unknown).</summary>
    private string? DeleteWord()
    {
        var before = _host.TextBeforeCursor(400);
        var word = TextAlgorithms.WordDelete(before);
        if (word.Length > 0)
        {
            _host.DeleteBeforeCursor(word);
            return word;
        }
        if (!_host.IsContextKnown) _host.DeleteWordFallback();
        return null;
    }

    private void TrimRestore()
    {
        if (_restore.Count <= RestoreStackMax) return;
        var keep = _restore.Take(RestoreStackMax).Reverse().ToList();
        _restore.Clear();
        foreach (var k in keep) _restore.Push(k);
    }

    private void AfterChange()
    {
        UpdateAutoCap();
        Changed?.Invoke();
    }

    /// <summary>Auto-capitalisation (§4.2): never overrides a shift the user tapped or caps lock.</summary>
    public void UpdateAutoCap()
    {
        if (Shift == ShiftState.CapsLock || Shift == ShiftState.OneShot) return;
        bool allowed = Options.AutoCapitalize && !Options.Pinyin && Options.AutoCapAllowed;
        bool cap = false;
        if (allowed)
        {
            var before = _host.TextBeforeCursor(64);
            cap = (before.Length > 0 || _host.IsContextKnown) && TextAlgorithms.AutoCapitalize(before);
        }
        var next = cap ? ShiftState.OneShotAuto : ShiftState.Off;
        if (next != Shift)
        {
            Shift = next;
            Changed?.Invoke();
        }
    }
}
