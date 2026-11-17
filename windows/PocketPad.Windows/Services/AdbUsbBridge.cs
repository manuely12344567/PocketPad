using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text.RegularExpressions;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

public sealed record AdbStatusSnapshot(
    bool AdbAvailable,
    bool HasAuthorizedDevice,
    bool HasUnauthorizedDevice,
    string StatusText,
    string DetailText,
    IReadOnlyList<string> ConnectedSerials
);

/// <summary>
/// Extracts bundled Google ADB binaries (adb.exe + DLLs) to %LOCALAPPDATA%\PocketPad\adb
/// and continuously watches for plugged-in Android phones to automatically set up
/// `adb reverse tcp:27183 tcp:27183` (sub-2ms USB tunnel, just like scrcpy).
/// </summary>
public sealed class AdbUsbBridge : IDisposable
{
    private readonly Action<string> _log;
    private readonly Action<AdbStatusSnapshot> _onStatusChanged;
    private readonly HashSet<string> _forwardedSerials = new(StringComparer.Ordinal);
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _scanLock = new(1, 1);

    private string? _adbPath;
    private Task? _watchTask;
    private AdbStatusSnapshot _lastSnapshot = new(
        AdbAvailable: false,
        HasAuthorizedDevice: false,
        HasUnauthorizedDevice: false,
        StatusText: "Initialisation USB (ADB)…",
        DetailText: "Recherche du pont ADB intégré",
        ConnectedSerials: Array.Empty<string>()
    );

    public AdbStatusSnapshot CurrentStatus => _lastSnapshot;

    public AdbUsbBridge(Action<string> log, Action<AdbStatusSnapshot> onStatusChanged)
    {
        _log = log;
        _onStatusChanged = onStatusChanged;
    }

    public void Start()
    {
        _adbPath = EnsureAdbBinary();
        if (_adbPath is null)
        {
            PublishStatus(new AdbStatusSnapshot(
                AdbAvailable: false,
                HasAuthorizedDevice: false,
                HasUnauthorizedDevice: false,
                StatusText: "ADB non trouvé (utilise Partage USB)",
                DetailText: "Le partage de connexion USB et le Bluetooth restent 100 % actifs.",
                ConnectedSerials: Array.Empty<string>()
            ));
            return;
        }

        _log($"Pont USB ADB prêt : {_adbPath}");
        _watchTask = Task.Run(() => WatchLoopAsync(_cts.Token));
    }

    public Task ForceRefreshAsync() => ScanAndForwardAsync(_cts.Token);

    private string? EnsureAdbBinary()
    {
        try
        {
            var asm = Assembly.GetExecutingAssembly();
            var targetDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "PocketPad",
                "adb"
            );
            var adbExeTarget = Path.Combine(targetDir, "adb.exe");

            var resources = new (string ResName, string FileName)[]
            {
                ("PocketPad.adb.adb.exe", "adb.exe"),
                ("PocketPad.adb.AdbWinApi.dll", "AdbWinApi.dll"),
                ("PocketPad.adb.AdbWinUsbApi.dll", "AdbWinUsbApi.dll"),
            };

            var hasEmbedded = asm.GetManifestResourceInfo("PocketPad.adb.adb.exe") is not null;
            if (hasEmbedded)
            {
                Directory.CreateDirectory(targetDir);
                foreach (var (resName, fileName) in resources)
                {
                    var dest = Path.Combine(targetDir, fileName);
                    if (File.Exists(dest) && new FileInfo(dest).Length > 0)
                        continue;

                    using var stream = asm.GetManifestResourceStream(resName);
                    if (stream is null) continue;
                    using var fs = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.Read);
                    stream.CopyTo(fs);
                }

                if (File.Exists(adbExeTarget))
                    return adbExeTarget;
            }

            // Fallback: check local Tools\adb folder next to exe
            var besideExe = Path.Combine(AppContext.BaseDirectory, "Tools", "adb", "adb.exe");
            if (File.Exists(besideExe))
                return besideExe;

            // Fallback: check system PATH for adb.exe
            var pathVar = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
            foreach (var dir in pathVar.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                try
                {
                    var candidate = Path.Combine(dir.Trim(), "adb.exe");
                    if (File.Exists(candidate))
                        return candidate;
                }
                catch
                {
                    // ignore invalid PATH entry
                }
            }
        }
        catch (Exception ex)
        {
            _log($"Avertissement initialisation ADB : {ex.Message}");
        }

        return null;
    }

    private async Task WatchLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await ScanAndForwardAsync(ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                _log($"Erreur veille USB ADB : {ex.Message}");
            }

            try
            {
                await Task.Delay(2500, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task ScanAndForwardAsync(CancellationToken ct)
    {
        if (_adbPath is null) return;
        await _scanLock.WaitAsync(ct);
        try
        {
            var (exitCode, stdout, stderr) = await RunAdbAsync("devices -l", ct);
            if (exitCode != 0)
            {
                PublishStatus(new AdbStatusSnapshot(
                    AdbAvailable: true,
                    HasAuthorizedDevice: false,
                    HasUnauthorizedDevice: false,
                    StatusText: "Service USB ADB en attente",
                    DetailText: string.IsNullOrWhiteSpace(stderr) ? "Branche ton téléphone en USB." : stderr.Trim(),
                    ConnectedSerials: Array.Empty<string>()
                ));
                return;
            }

            var authorized = new List<(string Serial, string Model)>();
            var unauthorizedCount = 0;

            foreach (var rawLine in stdout.Split('\n', StringSplitOptions.RemoveEmptyEntries))
            {
                var line = rawLine.Trim();
                if (line.Length == 0 || line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase) || line.StartsWith("*"))
                    continue;

                var parts = Regex.Split(line, @"\s+");
                if (parts.Length < 2) continue;

                var serial = parts[0];
                var state = parts[1];

                if (string.Equals(state, "device", StringComparison.OrdinalIgnoreCase))
                {
                    var modelMatch = Regex.Match(line, @"model:([^\s]+)");
                    var model = modelMatch.Success ? modelMatch.Groups[1].Value.Replace('_', ' ') : serial;
                    authorized.Add((serial, model));
                }
                else if (string.Equals(state, "unauthorized", StringComparison.OrdinalIgnoreCase))
                {
                    unauthorizedCount++;
                }
            }

            // Drop serials that were unplugged
            var activeSet = authorized.Select(a => a.Serial).ToHashSet(StringComparer.Ordinal);
            _forwardedSerials.RemoveWhere(s => !activeSet.Contains(s));

            // Ensure reverse TCP tunnel on every plugged-in authorized Android device
            foreach (var (serial, model) in authorized)
            {
                var (revCode, _, revErr) = await RunAdbAsync(
                    $"-s \"{serial}\" reverse tcp:{PocketPadConstants.TcpPort} tcp:{PocketPadConstants.TcpPort}",
                    ct
                );

                if (revCode == 0 && _forwardedSerials.Add(serial))
                {
                    _log($"Tunnel USB activé automatiquement sur {model} ({serial}) → port {PocketPadConstants.TcpPort}");
                }
                else if (revCode != 0)
                {
                    _log($"Échec tunnel USB sur {serial} : {revErr.Trim()}");
                }
            }

            if (authorized.Count > 0)
            {
                var models = string.Join(", ", authorized.Select(a => a.Model));
                PublishStatus(new AdbStatusSnapshot(
                    AdbAvailable: true,
                    HasAuthorizedDevice: true,
                    HasUnauthorizedDevice: unauthorizedCount > 0,
                    StatusText: $"Câble USB prêt : {models}",
                    DetailText: $"Tunnel direct ADB actif (port {PocketPadConstants.TcpPort}, < 2 ms). Appuie sur « Câble USB » sur ton téléphone !",
                    ConnectedSerials: authorized.Select(a => a.Serial).ToList()
                ));
            }
            else if (unauthorizedCount > 0)
            {
                PublishStatus(new AdbStatusSnapshot(
                    AdbAvailable: true,
                    HasAuthorizedDevice: false,
                    HasUnauthorizedDevice: true,
                    StatusText: "Autorisation USB requise sur le téléphone",
                    DetailText: "Déverrouille ton téléphone et accepte « Autoriser le débogage USB » (coche « Toujours autoriser »).",
                    ConnectedSerials: Array.Empty<string>()
                ));
            }
            else
            {
                PublishStatus(new AdbStatusSnapshot(
                    AdbAvailable: true,
                    HasAuthorizedDevice: false,
                    HasUnauthorizedDevice: false,
                    StatusText: "En attente d’un téléphone USB",
                    DetailText: "Branche le câble USB (Débogage USB activé) OU active « Partage de connexion USB ».",
                    ConnectedSerials: Array.Empty<string>()
                ));
            }
        }
        finally
        {
            _scanLock.Release();
        }
    }

    private async Task<(int ExitCode, string StdOut, string StdErr)> RunAdbAsync(string arguments, CancellationToken ct)
    {
        var psi = new ProcessStartInfo
        {
            FileName = _adbPath!,
            Arguments = arguments,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        using var proc = new Process { StartInfo = psi };
        proc.Start();

        var outTask = proc.StandardOutput.ReadToEndAsync(ct);
        var errTask = proc.StandardError.ReadToEndAsync(ct);

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(8));

        try
        {
            await proc.WaitForExitAsync(timeoutCts.Token);
        }
        catch (OperationCanceledException)
        {
            try { proc.Kill(entireProcessTree: true); } catch { }
            return (-1, string.Empty, "Timeout ADB");
        }

        return (proc.ExitCode, await outTask, await errTask);
    }

    private void PublishStatus(AdbStatusSnapshot snapshot)
    {
        var changed =
            _lastSnapshot.StatusText != snapshot.StatusText ||
            _lastSnapshot.DetailText != snapshot.DetailText ||
            _lastSnapshot.HasAuthorizedDevice != snapshot.HasAuthorizedDevice ||
            _lastSnapshot.HasUnauthorizedDevice != snapshot.HasUnauthorizedDevice;

        _lastSnapshot = snapshot;
        if (changed)
        {
            _onStatusChanged(snapshot);
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _watchTask?.Wait(500); } catch { }
        _cts.Dispose();
        _scanLock.Dispose();
    }
}
