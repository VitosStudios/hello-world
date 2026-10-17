using System;
using System.Linq;
using System.Windows.Controls;
using Tippli.Core.Engine;

namespace Tippli.Windows.UI;

/// <summary>
/// <see cref="ITextHost"/> over a WPF TextBox: the "Ausprobieren" field in the settings runs the real engine
/// locally (our own process is excluded from the global hook, so this is independent of it).
/// </summary>
internal sealed class TextBoxTextHost : ITextHost
{
    private readonly TextBox _box;
    private bool _engineEdit;
    private int _caret;

    public TextBoxTextHost(TextBox box)
    {
        _box = box;
        _box.TextChanged += OnTextChanged;
        _box.SelectionChanged += OnSelectionChanged;
        _caret = box.CaretIndex;
    }

    public CorrectionEngine? Engine { get; set; }

    public string TextBeforeCursor(int maxChars)
    {
        int caret = Math.Min(_box.CaretIndex, _box.Text.Length);
        int start = Math.Max(0, caret - maxChars);
        return _box.Text.Substring(start, caret - start);
    }

    public string TextAfterCursor(int maxChars)
    {
        int caret = Math.Min(_box.CaretIndex, _box.Text.Length);
        return _box.Text.Substring(caret, Math.Min(maxChars, _box.Text.Length - caret));
    }

    public string SelectedText() => _box.SelectedText;

    public void Delete(string text)
    {
        int caret = _box.CaretIndex;
        if (caret < text.Length || !string.Equals(_box.Text.Substring(caret - text.Length, text.Length), text, StringComparison.Ordinal)) return;
        Edit(() =>
        {
            _box.Text = _box.Text.Remove(caret - text.Length, text.Length);
            _box.CaretIndex = caret - text.Length;
        });
    }

    public void Insert(string text)
    {
        int caret = _box.CaretIndex;
        Edit(() =>
        {
            _box.Text = _box.Text.Insert(caret, text);
            _box.CaretIndex = caret + text.Length;
        });
    }

    public void Batch(Action block) => Edit(block);

    private void Edit(Action action)
    {
        bool outer = !_engineEdit;
        _engineEdit = true;
        try
        {
            action();
        }
        finally
        {
            if (outer)
            {
                _engineEdit = false;
                _caret = _box.CaretIndex;
            }
        }
    }

    private void OnTextChanged(object sender, TextChangedEventArgs e)
    {
        if (_engineEdit || Engine == null) return;
        var changes = e.Changes.ToList();
        if (changes.Count == 1 && changes[0].RemovedLength == 0 && changes[0].AddedLength > 0 && changes[0].Offset == _caret)
        {
            var added = _box.Text.Substring(changes[0].Offset, changes[0].AddedLength);
            _caret = changes[0].Offset + changes[0].AddedLength;
            Engine.OnTextTyped(added);
        }
        else
        {
            _caret = _box.CaretIndex;
            Engine.OnNonAppendChange();
        }
    }

    private void OnSelectionChanged(object sender, System.Windows.RoutedEventArgs e)
    {
        if (_engineEdit || Engine == null) return;
        // Deferred: typing raises SelectionChanged and TextChanged; only a caret that ends up somewhere other
        // than right after the typed text is a cursor move.
        _box.Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input, () =>
        {
            if (_engineEdit || Engine == null) return;
            if (_box.CaretIndex != _caret || _box.SelectionLength > 0)
            {
                _caret = _box.CaretIndex;
                Engine.OnNonAppendChange();
            }
        });
    }
}
