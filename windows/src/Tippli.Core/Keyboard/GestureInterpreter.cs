using Tippli.Core.Engine;

namespace Tippli.Core.Keyboard;

/// <summary>Thresholds of SPEC §4.1 (dp/pt/DIP; multiplied by the display density).</summary>
public static class GestureThresholds
{
    public const double TapSlop = 12;
    public const double SwipeUpMin = 28;
    public const double SwipeHMin = 40;
    public const double SwipeHStep = 40;
    public const double CursorStep = 10;
    public const int RepeatDelay = 400;
    public const int RepeatInterval = 55;
    public const int WordRepeatAfter = 12;
    public const int WordRepeatInterval = 220;
    public const int DoubleTap = 300;
    public const int DoubleSpace = 400;
}

/// <summary>Receives the interpreted gestures (implemented by <see cref="KeyboardController"/>).</summary>
public interface IGestureSink
{
    /// <summary>Pointer went down on a key: pressed state + haptic/sound.</summary>
    void OnPress(KeyShape key);
    /// <summary>The pressed state of the key ends.</summary>
    void OnRelease(KeyShape key);
    /// <summary>A tap on a key (character or special key).</summary>
    void OnTap(KeyShape key);
    /// <summary>Swipe up on a letter key: type the uppercase letter.</summary>
    void OnUppercase(KeyShape key);
    /// <summary>Backspace action (down / auto-repeat). <paramref name="word"/> = word-repeat mode.</summary>
    void OnBackspace(bool word);
    /// <summary>Swipe left: delete one word (called again for every further step).</summary>
    void OnSwipeDelete();
    /// <summary>Swipe right: restore (once per gesture).</summary>
    void OnSwipeRestore();
    /// <summary>Space-bar trackpad: move the cursor by <paramref name="delta"/> characters.</summary>
    void OnCursor(int delta);
    /// <summary>Long press on shift: caps lock.</summary>
    void OnShiftLongPress();
    /// <summary>The long-press popup opened.</summary>
    void OnPopupOpened(PopupState popup);
    /// <summary>The popup selection changed or the popup closed (null).</summary>
    void OnPopupChanged(PopupState? popup);
    /// <summary>A popup alternative was chosen.</summary>
    void OnAlternative(string text);
}

/// <summary>The open long-press popup.</summary>
public sealed class PopupState
{
    public PopupState(int pointerId, KeyShape key, IReadOnlyList<string> items, PopupLayout layout)
    {
        PointerId = pointerId;
        Key = key;
        Items = items;
        Layout = layout;
    }

    public int PointerId { get; }
    public KeyShape Key { get; }
    public IReadOnlyList<string> Items { get; }
    public PopupLayout Layout { get; }
    /// <summary>Selected index, -1 = cancelled (pointer far below the key).</summary>
    public int Selected { get; internal set; }
}

/// <summary>
/// Platform independent multi-pointer gesture interpreter (SPEC §4.1–4.6). Feed it pointer events in keyboard
/// coordinates; it calls <see cref="IGestureSink"/>. Timers use the injected <see cref="IScheduler"/>.
/// </summary>
public sealed class GestureInterpreter
{
    private sealed class Track
    {
        public required int Id;
        public required KeyShape Key;
        public required double DownX, DownY;
        public double LastX, LastY;
        public bool Moved;
        public bool Consumed;
        public bool Done; // committed early (fast typing rule)
        public bool CursorMode;
        public double CursorAnchorX;
        public bool Swiping;
        public bool SwipeLeft;
        public int WordsDeleted;
        public int Deleted;
        public IDisposable? LongPress;
        public IDisposable? Repeat;
        public PopupState? Popup;
        public double PopupAnchorX, PopupAnchorY;
        public bool PopupFollowing;
    }

    private readonly Dictionary<int, Track> _tracks = new();
    private readonly IGestureSink _sink;
    private readonly IScheduler _scheduler;
    private PopupState? _popup;

    public GestureInterpreter(IGestureSink sink, IScheduler scheduler)
    {
        _sink = sink;
        _scheduler = scheduler;
    }

    public KeyboardGeometry? Geometry { get; set; }
    public Func<KeyShape, IReadOnlyList<string>>? AlternativesProvider { get; set; }
    public int LongPressMs { get; set; } = 350;
    public double Density { get; set; } = 1;
    public PopupState? Popup => _popup;
    public bool HasActivePointers => _tracks.Count > 0;

    private static bool IsCharacterKey(KeyShape key) => key.IsCharacter || key.Code == "$space";

    public void PointerDown(int id, double x, double y)
    {
        if (Geometry == null) return;
        if (_tracks.ContainsKey(id)) PointerCancel(id);
        var key = Geometry.HitTest(x, y);
        if (key == null) return;

        // Fast typing rule: commit older, still pressed, unmoved character pointers immediately.
        foreach (var other in _tracks.Values.ToList())
        {
            if (!other.Done && !other.Consumed && !other.Moved && other.Popup == null && IsCharacterKey(other.Key))
            {
                other.Done = true;
                CancelTimers(other);
                _sink.OnTap(other.Key);
                _sink.OnRelease(other.Key);
            }
        }

        var t = new Track { Id = id, Key = key, DownX = x, DownY = y, LastX = x, LastY = y };
        _tracks[id] = t;
        _sink.OnPress(key);

        if (key.Code == "$back")
        {
            _sink.OnBackspace(false);
            t.Deleted = 1;
            t.Repeat = _scheduler.Schedule(GestureThresholds.RepeatDelay, () => RepeatTick(t));
        }
        else if (key.Code == "$shift" || key.IsCharacter)
        {
            t.LongPress = _scheduler.Schedule(Math.Max(50, LongPressMs), () => LongPressFired(t));
        }
    }

    public void PointerMove(int id, double x, double y)
    {
        if (!_tracks.TryGetValue(id, out var t) || t.Done) return;
        Move(t, x, y);
    }

    public void PointerUp(int id, double x, double y)
    {
        if (!_tracks.Remove(id, out var t)) return;
        if (t.Done)
        {
            CancelTimers(t);
            return;
        }
        Move(t, x, y);
        CancelTimers(t);

        if (t.Popup != null)
        {
            var popup = t.Popup;
            t.Popup = null;
            if (_popup == popup) _popup = null;
            _sink.OnPopupChanged(null);
            if (popup.Selected >= 0 && popup.Selected < popup.Items.Count) _sink.OnAlternative(popup.Items[popup.Selected]);
        }
        else if (t.Consumed || t.Key.Code == "$back")
        {
            // swipe / cursor / shift long press / backspace already acted
        }
        else
        {
            double dx = x - t.DownX, dy = y - t.DownY;
            if (dy <= -GestureThresholds.SwipeUpMin * Density && Math.Abs(dy) > Math.Abs(dx) && t.Key.IsLetter)
                _sink.OnUppercase(t.Key);
            else
                _sink.OnTap(t.Key); // forgiving: the key where the pointer went down
        }
        _sink.OnRelease(t.Key);
    }

    public void PointerCancel(int id)
    {
        if (!_tracks.Remove(id, out var t)) return;
        CancelTimers(t);
        if (t.Popup != null)
        {
            if (_popup == t.Popup) _popup = null;
            t.Popup = null;
            _sink.OnPopupChanged(null);
        }
        if (!t.Done) _sink.OnRelease(t.Key);
    }

    public void CancelAll()
    {
        foreach (var id in _tracks.Keys.ToList()) PointerCancel(id);
    }

    private void Move(Track t, double x, double y)
    {
        double dx = x - t.DownX, dy = y - t.DownY;
        double slop = GestureThresholds.TapSlop * Density;
        t.LastX = x;
        t.LastY = y;
        if (!t.Moved && dx * dx + dy * dy > slop * slop)
        {
            t.Moved = true;
            t.LongPress?.Dispose();
            t.LongPress = null;
        }

        if (t.Popup != null)
        {
            UpdatePopup(t, x, y);
            return;
        }
        if (!t.Moved) return;
        if (t.Consumed && !t.Swiping && !t.CursorMode) return;

        if (t.Key.Code == "$space")
        {
            if (!t.CursorMode && Math.Abs(dx) > slop)
            {
                t.CursorMode = true;
                t.Consumed = true;
                t.CursorAnchorX = t.DownX;
            }
            if (t.CursorMode)
            {
                double step = GestureThresholds.CursorStep * Density;
                while (x - t.CursorAnchorX >= step) { _sink.OnCursor(1); t.CursorAnchorX += step; }
                while (t.CursorAnchorX - x >= step) { _sink.OnCursor(-1); t.CursorAnchorX -= step; }
            }
            return;
        }

        double hMin = GestureThresholds.SwipeHMin * Density;
        if (!t.Swiping && Math.Abs(dx) >= hMin && Math.Abs(dx) > 1.5 * Math.Abs(dy))
        {
            t.Swiping = true;
            t.Consumed = true;
            t.SwipeLeft = dx < 0;
            t.Repeat?.Dispose();
            t.Repeat = null;
            if (t.SwipeLeft)
            {
                _sink.OnSwipeDelete();
                t.WordsDeleted = 1;
            }
            else
            {
                _sink.OnSwipeRestore();
            }
        }
        if (t.Swiping && t.SwipeLeft)
        {
            double step = GestureThresholds.SwipeHStep * Density;
            while (-dx >= hMin + t.WordsDeleted * step)
            {
                _sink.OnSwipeDelete();
                t.WordsDeleted++;
            }
        }
    }

    private void UpdatePopup(Track t, double x, double y)
    {
        var p = t.Popup!;
        // The first alternative stays pre-selected until the pointer really moves (no jump on jitter or release).
        if (!t.PopupFollowing)
        {
            double half = GestureThresholds.TapSlop * Density / 2;
            if (Math.Abs(x - t.PopupAnchorX) <= half && Math.Abs(y - t.PopupAnchorY) <= half) return;
            t.PopupFollowing = true;
        }
        double limit = t.Key.Bottom + 1.5 * (Geometry?.H ?? 50);
        int sel = y > limit ? -1 : p.Layout.IndexAt(x, y);
        if (sel != p.Selected)
        {
            p.Selected = sel;
            _sink.OnPopupChanged(p);
        }
    }

    private void LongPressFired(Track t)
    {
        t.LongPress = null;
        if (!_tracks.TryGetValue(t.Id, out var cur) || cur != t || t.Done || t.Moved || t.Consumed) return;
        if (t.Key.Code == "$shift")
        {
            t.Consumed = true;
            _sink.OnShiftLongPress();
            return;
        }
        if (_popup != null || Geometry == null) return;
        var items = AlternativesProvider?.Invoke(t.Key) ?? Array.Empty<string>();
        if (items.Count == 0) return; // behaves like a slow tap
        var layout = Geometry.LayoutPopup(t.Key, items.Count);
        var popup = new PopupState(t.Id, t.Key, items, layout) { Selected = 0 };
        t.Popup = popup;
        t.PopupAnchorX = t.LastX;
        t.PopupAnchorY = t.LastY;
        t.PopupFollowing = false;
        _popup = popup;
        _sink.OnPopupOpened(popup);
    }

    private void RepeatTick(Track t)
    {
        t.Repeat = null;
        if (!_tracks.TryGetValue(t.Id, out var cur) || cur != t || t.Done || t.Consumed) return;
        if (t.Deleted >= GestureThresholds.WordRepeatAfter)
        {
            _sink.OnBackspace(true);
            t.Repeat = _scheduler.Schedule(GestureThresholds.WordRepeatInterval, () => RepeatTick(t));
        }
        else
        {
            _sink.OnBackspace(false);
            t.Deleted++;
            t.Repeat = _scheduler.Schedule(GestureThresholds.RepeatInterval, () => RepeatTick(t));
        }
    }

    private static void CancelTimers(Track t)
    {
        t.LongPress?.Dispose();
        t.LongPress = null;
        t.Repeat?.Dispose();
        t.Repeat = null;
    }
}
