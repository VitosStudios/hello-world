using System;
using System.Threading;
using System.Windows.Automation;
using System.Windows.Threading;
using Tippli.Windows.Services;

namespace Tippli.Windows.Interop;

/// <summary>
/// Inspects the focused element with UI Automation on a background MTA thread (never on the hook/UI thread,
/// UIA calls are cross-process and can be slow): is it a password field (SPEC §9.4), and what is its value
/// (used to know whether the buffer starts at the field start). Requests are coalesced and throttled.
/// </summary>
internal sealed class FieldInspector : IDisposable
{
    public sealed record Result(int Generation, bool IsPassword, string? Value, bool Failed);

    private readonly Dispatcher _dispatcher;
    private readonly AutoResetEvent _signal = new(false);
    private readonly Thread _thread;
    private readonly int _ownPid = Environment.ProcessId;
    private volatile bool _disposed;
    private int _generation;

    public FieldInspector(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
        _thread = new Thread(Run) { IsBackground = true, Name = "Tippli field inspector" };
        _thread.SetApartmentState(ApartmentState.MTA);
        _thread.Start();
    }

    /// <summary>The newest request; older results are ignored.</summary>
    public int Generation => Volatile.Read(ref _generation);

    /// <summary>Raised on the UI thread.</summary>
    public event Action<Result>? Completed;

    public int Request()
    {
        int gen = Interlocked.Increment(ref _generation);
        _signal.Set();
        return gen;
    }

    private void Run()
    {
        while (!_disposed)
        {
            _signal.WaitOne();
            if (_disposed) return;
            Thread.Sleep(60); // let focus settle and coalesce bursts of focus events
            int gen = Generation;
            var result = Inspect(gen);
            if (_disposed) return;
            _dispatcher.BeginInvoke(() =>
            {
                try { Completed?.Invoke(result); }
                catch (Exception ex) { Log.Error("field inspector", ex); }
            });
        }
    }

    private Result Inspect(int gen)
    {
        try
        {
            var element = AutomationElement.FocusedElement;
            if (element == null) return new Result(gen, false, null, true);
            var current = element.Current;
            if (current.ProcessId == _ownPid) return new Result(gen, false, null, false);
            bool password = current.IsPassword;
            string? value = null;
            if (!password && element.TryGetCurrentPattern(ValuePattern.Pattern, out var pattern) && pattern is ValuePattern vp)
            {
                var v = vp.Current;
                if (!v.IsReadOnly && v.Value is { Length: <= 20000 } s) value = s;
            }
            return new Result(gen, password, value, false);
        }
        catch (Exception)
        {
            return new Result(gen, false, null, true);
        }
    }

    public void Dispose()
    {
        _disposed = true;
        _signal.Set();
    }
}
