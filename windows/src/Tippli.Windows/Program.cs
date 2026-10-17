using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Threading;
using Tippli.Core.Settings;
using Tippli.Core.Shared;
using Tippli.Windows.Interop;
using Tippli.Windows.Services;
using Tippli.Windows.UI;

namespace Tippli.Windows;

public static class Program
{
    public const string AppId = "VitosStudios.Tippli";
    private const string MutexName = "Local\\" + AppId + ".SingleInstance";
    private const string ShowEventName = "Local\\" + AppId + ".Show";

    [STAThread]
    public static int Main(string[] args)
    {
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool createdNew);
        if (!createdNew)
        {
            // Already running: ask the running instance to open its settings and quit.
            try
            {
                if (EventWaitHandle.TryOpenExisting(ShowEventName, out var show)) show.Set();
            }
            catch
            {
                // ignore
            }
            return 0;
        }

        NativeMethods.SetCurrentProcessExplicitAppUserModelID(AppId);
        Log.Init(SettingsStore.DefaultDirectory);
        Log.Info($"Tippli start {typeof(Program).Assembly.GetName().Version} {Environment.OSVersion}");

        using var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        var app = new TippliApp(autostart: args.Any(a => string.Equals(a, "--autostart", StringComparison.OrdinalIgnoreCase)), showEvent);
        return app.Run();
    }
}

/// <summary>The WPF application: composition root, tray, first run, global error handling.</summary>
internal sealed class TippliApp : Application
{
    private readonly bool _autostart;
    private readonly EventWaitHandle _showEvent;
    private DesktopController? _controller;
    private TrayIcon? _tray;
    private RegisteredWaitHandle? _showWait;

    public TippliApp(bool autostart, EventWaitHandle showEvent)
    {
        _autostart = autostart;
        _showEvent = showEvent;
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Error("unhandled", e.ExceptionObject as Exception);
        TaskSchedulerUnobserved();
    }

    private static void TaskSchedulerUnobserved()
        => System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Log.Error("unobserved task", e.Exception);
            e.SetObserved();
        };

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var data = SharedData.Default;
        var settings = new SettingsStore(data, SettingsStore.DefaultFilePath);
        settings.Load();
        var state = AppState.Load(Path.Combine(SettingsStore.DefaultDirectory, "state.json"));
        ISecretStore secrets;
        try
        {
            secrets = new DpapiSecretStore(DpapiSecretStore.DefaultPath);
        }
        catch (Exception ex)
        {
            Log.Error("DPAPI unavailable - keys kept in memory only", ex);
            secrets = new InMemorySecretStore();
        }

        var theme = new ThemeManager(this, data, settings);
        theme.LoadStyles();
        theme.Apply();

        _controller = new DesktopController(Dispatcher, data, settings, secrets, state, theme);
        _tray = new TrayIcon(_controller, Quit);
        _controller.Start();

        _showWait = ThreadPool.RegisterWaitForSingleObject(_showEvent,
            (_, _) => Dispatcher.BeginInvoke(() => _controller?.OpenSettings()), null, Timeout.Infinite, executeOnlyOnce: false);

        bool firstRun = !state.FirstRunDone || !settings.FileExisted;
        if (firstRun)
        {
            settings.Save(); // writes settings.json with all default keys
            _controller.OpenSettings(welcome: true);
        }
        else if (!_autostart && !_controller.Ai.HasKey)
        {
            _controller.OpenSettings(page: "provider");
        }
        if (settings.DesktopShowKeyboardOnStart) _controller.ShowKeyboard();
    }

    private void Quit()
    {
        try
        {
            _controller?.CloseAllWindows();
        }
        catch (Exception ex)
        {
            Log.Error("shutdown", ex);
        }
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try
        {
            _showWait?.Unregister(null);
            _tray?.Dispose();
            _controller?.Dispose();
        }
        catch (Exception ex)
        {
            Log.Error("exit", ex);
        }
        _tray = null;
        _controller = null;
        base.OnExit(e);
    }

    private static void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        // A background keyboard app must never die because of one failed UI action.
        Log.Error("dispatcher", e.Exception);
        e.Handled = true;
    }
}
