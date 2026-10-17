using System.Diagnostics;

namespace Tippli.Core.Engine;

/// <summary>Monotonic time source in milliseconds (injectable for tests).</summary>
public interface IClock
{
    long NowMs { get; }
}

/// <summary>Runs callbacks later on the owner's thread (UI dispatcher on Windows, virtual time in tests).</summary>
public interface IScheduler
{
    /// <summary>Schedules <paramref name="action"/> after <paramref name="delayMs"/>; dispose the result to cancel.</summary>
    IDisposable Schedule(int delayMs, Action action);
}

public static class SchedulerExtensions
{
    /// <summary>Runs <paramref name="action"/> as soon as possible, but not synchronously.</summary>
    public static IDisposable Post(this IScheduler scheduler, Action action) => scheduler.Schedule(0, action);

    /// <summary>A task that completes after <paramref name="delayMs"/> on the scheduler's timeline.</summary>
    public static Task Delay(this IScheduler scheduler, int delayMs)
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        scheduler.Schedule(delayMs, () => tcs.TrySetResult());
        return tcs.Task;
    }
}

public sealed class SystemClock : IClock
{
    private static readonly Stopwatch Watch = Stopwatch.StartNew();
    public static readonly SystemClock Instance = new();
    public long NowMs => Watch.ElapsedMilliseconds;
}

/// <summary>A disposable that runs an action once.</summary>
public sealed class ActionDisposable : IDisposable
{
    private Action? _action;
    public ActionDisposable(Action action) => _action = action;
    public static readonly IDisposable Empty = new ActionDisposable(() => { });
    public void Dispose() => Interlocked.Exchange(ref _action, null)?.Invoke();
}

/// <summary>
/// Scheduler that posts to a <see cref="SynchronizationContext"/> (falls back to the thread pool).
/// The Windows app uses its own dispatcher-based scheduler; this one is handy for hosts without UI.
/// </summary>
public sealed class SynchronizationContextScheduler : IScheduler
{
    private readonly SynchronizationContext? _context;

    public SynchronizationContextScheduler(SynchronizationContext? context = null)
        => _context = context ?? SynchronizationContext.Current;

    public IDisposable Schedule(int delayMs, Action action)
    {
        int cancelled = 0;
        void Run()
        {
            if (Volatile.Read(ref cancelled) != 0) return;
            if (_context != null) _context.Post(_ => { if (Volatile.Read(ref cancelled) == 0) action(); }, null);
            else action();
        }
        var timer = new Timer(_ => Run(), null, Math.Max(0, delayMs), Timeout.Infinite);
        return new ActionDisposable(() =>
        {
            Interlocked.Exchange(ref cancelled, 1);
            timer.Dispose();
        });
    }
}
