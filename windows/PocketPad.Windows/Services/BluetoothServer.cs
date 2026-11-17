using System.IO;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

public sealed record BluetoothStatusSnapshot(bool Available, bool Listening, string StatusText, string DetailText);

/// <summary>
/// Native Windows Bluetooth Classic RFCOMM Server via 32feet.NET (InTheHand.Net.Bluetooth).
/// Uses PocketPad's dedicated Service UUID so Android connects directly without manual COM ports.
/// Gracefully degrades if Bluetooth hardware is disabled or missing.
/// </summary>
public sealed class BluetoothServer : IDisposable
{
    private readonly Action<string> _log;
    private readonly Action<BluetoothStatusSnapshot> _onStatusChanged;
    private readonly Func<Stream, string, string, CancellationToken, Task> _onClientConnected;
    private readonly CancellationTokenSource _cts = new();

    private BluetoothListener? _listener;
    private Task? _acceptTask;

    public BluetoothStatusSnapshot CurrentStatus { get; private set; } = new(
        Available: false,
        Listening: false,
        StatusText: "Initialisation Bluetooth…",
        DetailText: "Vérification de l’adaptateur Bluetooth Windows"
    );

    public BluetoothServer(
        Action<string> log,
        Action<BluetoothStatusSnapshot> onStatusChanged,
        Func<Stream, string, string, CancellationToken, Task> onClientConnected)
    {
        _log = log;
        _onStatusChanged = onStatusChanged;
        _onClientConnected = onClientConnected;
    }

    public void Start()
    {
        Task.Run(() => StartInternal());
    }

    public void Restart()
    {
        StopListener();
        Task.Run(() => StartInternal());
    }

    private void StartInternal()
    {
        try
        {
            string radioName = Environment.MachineName;
            try
            {
                var radio = BluetoothRadio.Default;
                if (radio is not null && !string.IsNullOrWhiteSpace(radio.Name))
                {
                    radioName = radio.Name;
                }
            }
            catch
            {
                // Some Windows 11 builds throw on BluetoothRadio.Default while BluetoothListener still works cleanly
            }

            _listener = new BluetoothListener(PocketPadConstants.BluetoothServiceUuid)
            {
                ServiceName = "PocketPad Controller"
            };
            _listener.Start();

            PublishStatus(new BluetoothStatusSnapshot(
                Available: true,
                Listening: true,
                StatusText: $"Bluetooth en écoute ({radioName})",
                DetailText: "Associe ton téléphone à ce PC dans Windows Bluetooth, puis choisis ce PC dans l’app Android."
            ));
            _log($"Serveur Bluetooth RFCOMM actif (PC : {radioName}, UUID PocketPad)");

            _acceptTask = Task.Run(() => AcceptLoopAsync(_cts.Token));
        }
        catch (Exception ex)
        {
            PublishStatus(new BluetoothStatusSnapshot(
                Available: false,
                Listening: false,
                StatusText: "Bluetooth désactivé ou indisponible",
                DetailText: $"Active le Bluetooth Windows puis clique sur Réessayer ({ex.Message}). Le câble USB reste 100 % actif."
            ));
            _log($"Bluetooth inactif : {ex.Message}");
        }
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener is not null)
        {
            try
            {
                var btClient = await Task.Run(() => _listener.AcceptBluetoothClient(), ct);
                var remoteName = string.IsNullOrWhiteSpace(btClient.RemoteMachineName)
                    ? "Bluetooth Android"
                    : btClient.RemoteMachineName;

                _ = Task.Run(async () =>
                {
                    using (btClient)
                    using (var stream = btClient.GetStream())
                    {
                        await _onClientConnected(stream, "Bluetooth", remoteName, ct);
                    }
                }, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                if (!ct.IsCancellationRequested)
                {
                    _log($"Avertissement boucle Bluetooth : {ex.Message}");
                    await Task.Delay(1500, ct).ConfigureAwait(false);
                }
            }
        }
    }

    private void PublishStatus(BluetoothStatusSnapshot snapshot)
    {
        CurrentStatus = snapshot;
        _onStatusChanged(snapshot);
    }

    private void StopListener()
    {
        try { _listener?.Stop(); } catch { }
        try { _listener?.Dispose(); } catch { }
        _listener = null;
    }

    public void Dispose()
    {
        _cts.Cancel();
        StopListener();
        _cts.Dispose();
    }
}
