using System;
using System.Collections.Generic;
using System.IO;
using System.Media;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Threading;
using Microsoft.Win32;
using Tippli.Core.Engine;
using Tippli.Core.Settings;

namespace Tippli.Windows.Services;

/// <summary>Minimal file log in %APPDATA%\Tippli\tippli.log. Never logs typed text.</summary>
internal static class Log
{
    private static readonly object Lock = new();
    private static string? _path;

    public static void Init(string directory)
    {
        try
        {
            Directory.CreateDirectory(directory);
            _path = Path.Combine(directory, "tippli.log");
            if (File.Exists(_path) && new FileInfo(_path).Length > 1_000_000)
                File.Move(_path, _path + ".old", overwrite: true);
        }
        catch
        {
            _path = null;
        }
    }

    public static void Info(string message) => Write("INFO", message);

    public static void Error(string message, Exception? ex = null)
        => Write("ERROR", ex == null ? message : $"{message}: {ex.GetType().Name}: {ex.Message}\n{ex.StackTrace}");

    private static void Write(string level, string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {level} {message}";
        System.Diagnostics.Debug.WriteLine(line);
        if (_path == null) return;
        lock (Lock)
        {
            try { File.AppendAllText(_path, line + Environment.NewLine); }
            catch { /* ignore */ }
        }
    }
}

/// <summary><see cref="IScheduler"/> on the WPF dispatcher (UI thread).</summary>
internal sealed class DispatcherScheduler : IScheduler
{
    private readonly Dispatcher _dispatcher;

    public DispatcherScheduler(Dispatcher dispatcher) => _dispatcher = dispatcher;

    public IDisposable Schedule(int delayMs, Action action)
    {
        if (delayMs <= 0)
        {
            var op = _dispatcher.BeginInvoke(DispatcherPriority.Normal, action);
            return new ActionDisposable(() => op.Abort());
        }
        var timer = new DispatcherTimer(DispatcherPriority.Normal, _dispatcher) { Interval = TimeSpan.FromMilliseconds(delayMs) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            action();
        };
        timer.Start();
        return new ActionDisposable(timer.Stop);
    }
}

/// <summary>API keys encrypted with DPAPI (CurrentUser) in %APPDATA%\Tippli\keys.dat (SPEC §8).</summary>
internal sealed class DpapiSecretStore : ISecretStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("VitosStudios.Tippli.keys.v1");
    private readonly string _path;
    private readonly object _lock = new();
    private readonly Dictionary<string, string> _values;

    public DpapiSecretStore(string path)
    {
        _path = path;
        _values = Load();
    }

    public static string DefaultPath => Path.Combine(SettingsStore.DefaultDirectory, "keys.dat");

    public string? Get(string key)
    {
        lock (_lock) return _values.TryGetValue(key, out var v) ? v : null;
    }

    public void Set(string key, string? value)
    {
        lock (_lock)
        {
            if (string.IsNullOrEmpty(value)) _values.Remove(key);
            else _values[key] = value.Trim();
            Save();
        }
    }

    private Dictionary<string, string> Load()
    {
        try
        {
            if (!File.Exists(_path)) return new Dictionary<string, string>();
            var protectedBytes = File.ReadAllBytes(_path);
            var plain = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            try
            {
                return JsonSerializer.Deserialize<Dictionary<string, string>>(plain) ?? new Dictionary<string, string>();
            }
            finally
            {
                Array.Clear(plain);
            }
        }
        catch (Exception ex)
        {
            Log.Error("keys.dat could not be read", ex);
            return new Dictionary<string, string>();
        }
    }

    private void Save()
    {
        byte[] plain = JsonSerializer.SerializeToUtf8Bytes(_values);
        try
        {
            var protectedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            var tmp = _path + ".tmp";
            File.WriteAllBytes(tmp, protectedBytes);
            File.Move(tmp, _path, overwrite: true);
        }
        catch (Exception ex)
        {
            Log.Error("keys.dat could not be written", ex);
        }
        finally
        {
            Array.Clear(plain);
        }
    }
}

/// <summary>Launch at login via HKCU\Software\Microsoft\Windows\CurrentVersion\Run (SPEC §9.4).</summary>
internal static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "Tippli";

    public static string Command => $"\"{Environment.ProcessPath}\" --autostart";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
        catch
        {
            return false;
        }
    }

    public static void Apply(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (enabled)
            {
                if (key.GetValue(ValueName) as string != Command) key.SetValue(ValueName, Command);
            }
            else if (key.GetValue(ValueName) != null)
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch (Exception ex)
        {
            Log.Error("autostart", ex);
        }
    }
}

/// <summary>Windows-only UI state (kept out of settings.json, whose keys must match settings-defaults.json).</summary>
internal sealed class AppState
{
    public double? KeyboardLeft { get; set; }
    public double? KeyboardTop { get; set; }
    public double? KeyboardWidth { get; set; }
    public double? KeyboardHeight { get; set; }
    public bool FirstRunDone { get; set; }

    private string? _path;

    public static AppState Load(string path)
    {
        AppState state;
        try
        {
            state = File.Exists(path) ? JsonSerializer.Deserialize<AppState>(File.ReadAllText(path)) ?? new AppState() : new AppState();
        }
        catch
        {
            state = new AppState();
        }
        state._path = path;
        return state;
    }

    public void Save()
    {
        if (_path == null) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception ex)
        {
            Log.Error("state.json", ex);
        }
    }
}

/// <summary>Soft key click for the on-screen keyboard (setting <c>sound</c>), synthesised once in memory.</summary>
internal static class KeySound
{
    private static SoundPlayer? _player;

    public static void Play()
    {
        try
        {
            _player ??= CreatePlayer();
            _player.Play();
        }
        catch
        {
            // no audio device
        }
    }

    private static SoundPlayer CreatePlayer()
    {
        const int rate = 22050;
        int samples = rate / 60; // ~17 ms
        var ms = new MemoryStream();
        var w = new BinaryWriter(ms);
        w.Write(Encoding.ASCII.GetBytes("RIFF"));
        w.Write(36 + samples * 2);
        w.Write(Encoding.ASCII.GetBytes("WAVEfmt "));
        w.Write(16);
        w.Write((short)1);
        w.Write((short)1);
        w.Write(rate);
        w.Write(rate * 2);
        w.Write((short)2);
        w.Write((short)16);
        w.Write(Encoding.ASCII.GetBytes("data"));
        w.Write(samples * 2);
        var rnd = new Random(7);
        for (int i = 0; i < samples; i++)
        {
            double env = Math.Exp(-i / (samples / 6.0));
            double v = (Math.Sin(i * 2 * Math.PI * 1800 / rate) * 0.6 + (rnd.NextDouble() - 0.5) * 0.4) * env;
            w.Write((short)(v * 6000));
        }
        w.Flush();
        ms.Position = 0;
        var player = new SoundPlayer(ms);
        player.Load();
        return player;
    }
}
