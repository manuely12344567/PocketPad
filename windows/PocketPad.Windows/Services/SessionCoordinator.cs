using System.IO;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

public enum InputRoutingMode
{
    Auto,
    Xbox360Only,
    KeyboardMouseOnly,
}

public sealed record ActiveSessionSnapshot(
    bool IsConnected,
    string DeviceName,
    string ProfileName,
    string TransportLabel,
    string RemoteEndpoint,
    int PacketsPerSecond,
    GamepadState GamepadState
);

/// <summary>
/// Central hub coordinating USB (ADB + Tethering), Bluetooth RFCOMM, ViGEm Xbox 360 driver,
/// Keyboard/Mouse Win32 injection, rumble feedback, and live UI telemetry.
/// </summary>
public sealed class SessionCoordinator : IDisposable
{
    private readonly Action<string> _log;
    private readonly VirtualXboxDriver _xboxDriver;
    private readonly KeyboardMouseInjector _kbMouse;
    private readonly AdbUsbBridge _adbBridge;
    private readonly TcpUsbServer _tcpServer;
    private readonly BluetoothServer _bluetoothServer;

    private readonly object _clientLock = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    private Stream? _activeStream;
    private CancellationTokenSource? _activeClientCts;
    private string _deviceName = "Aucun téléphone connecté";
    private string _profileName = "—";
    private string _transportLabel = "—";
    private string _remoteEndpoint = "—";
    private GamepadState _currentGamepad = GamepadState.Neutral;
    private int _packetsCounter;
    private int _packetsPerSecond;
    private Timer? _statsTimer;

    public InputRoutingMode Mode { get; set; } = InputRoutingMode.Auto;

    public VirtualXboxDriver XboxDriver => _xboxDriver;
    public AdbUsbBridge AdbBridge => _adbBridge;
    public BluetoothServer BluetoothServer => _bluetoothServer;

    public event Action<ActiveSessionSnapshot>? SessionUpdated;
    public event Action<AdbStatusSnapshot>? AdbStatusUpdated;
    public event Action<BluetoothStatusSnapshot>? BluetoothStatusUpdated;

    public SessionCoordinator(Action<string> log)
    {
        _log = log;
        _xboxDriver = new VirtualXboxDriver(log);
        _kbMouse = new KeyboardMouseInjector();
        _adbBridge = new AdbUsbBridge(log, snap => AdbStatusUpdated?.Invoke(snap));
        _tcpServer = new TcpUsbServer(log, () => _xboxDriver.IsAvailable, HandleIncomingClientAsync);
        _bluetoothServer = new BluetoothServer(log, snap => BluetoothStatusUpdated?.Invoke(snap), HandleIncomingClientAsync);

        _xboxDriver.RumbleReceived += OnXboxRumbleReceived;
    }

    public void StartAll()
    {
        _xboxDriver.TryInitialize();
        _tcpServer.Start();
        _adbBridge.Start();
        _bluetoothServer.Start();

        _statsTimer = new Timer(_ =>
        {
            _packetsPerSecond = Interlocked.Exchange(ref _packetsCounter, 0);
            EmitSnapshot();
        }, null, 1000, 1000);
    }

    public async Task SendTestRumbleAsync()
    {
        await WriteFrameToActiveClientAsync(PacketCodec.EncodeRumble(220, 180));
        await Task.Delay(320);
        await WriteFrameToActiveClientAsync(PacketCodec.EncodeRumble(0, 0));
    }

    public async Task NotifyServerInfoAsync()
    {
        var payload = new ServerInfoPayload(
            Environment.MachineName,
            _xboxDriver.IsAvailable,
            Mode.ToString().ToLowerInvariant(),
            PocketPadConstants.AppVersion
        );
        await WriteFrameToActiveClientAsync(PacketCodec.EncodeServerInfo(payload));
    }

    public void DisconnectCurrentClient()
    {
        lock (_clientLock)
        {
            try { _activeClientCts?.Cancel(); } catch { }
            _activeStream = null;
        }
        ResetAllInputs();
        EmitSnapshot();
    }

    private async Task HandleIncomingClientAsync(Stream stream, string transportLabel, string remoteEndpoint, CancellationToken serverCt)
    {
        CancellationTokenSource clientCts;
        lock (_clientLock)
        {
            // Gracefully kick previous client if a new one connects
            try { _activeClientCts?.Cancel(); } catch { }
            clientCts = CancellationTokenSource.CreateLinkedTokenSource(serverCt);
            _activeClientCts = clientCts;
            _activeStream = stream;
            _transportLabel = transportLabel;
            _remoteEndpoint = remoteEndpoint;
            _deviceName = transportLabel == "Bluetooth" ? remoteEndpoint : "Téléphone Android";
            _profileName = "Connexion en cours…";
        }

        _log($"Téléphone connecté via {transportLabel} ({remoteEndpoint})");
        EmitSnapshot();
        await NotifyServerInfoAsync();

        var reader = new FrameStreamReader();
        var buffer = new byte[4096];

        try
        {
            while (!clientCts.Token.IsCancellationRequested)
            {
                var read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), clientCts.Token);
                if (read <= 0) break;

                reader.Feed(buffer.AsSpan(0, read), packet => ProcessPacket(packet));
            }
        }
        catch (OperationCanceledException)
        {
            // Normal disconnect / replaced by newer connection
        }
        catch (IOException)
        {
            // Cable unplugged or Bluetooth out of range
        }
        catch (Exception ex)
        {
            _log($"Déconnexion ({transportLabel}) : {ex.Message}");
        }
        finally
        {
            bool wasActive;
            lock (_clientLock)
            {
                wasActive = ReferenceEquals(_activeStream, stream);
                if (wasActive)
                {
                    _activeStream = null;
                    _activeClientCts = null;
                    _deviceName = "Aucun téléphone connecté";
                    _profileName = "—";
                    _transportLabel = "—";
                    _remoteEndpoint = "—";
                    _packetsPerSecond = 0;
                }
            }

            if (wasActive)
            {
                ResetAllInputs();
                _log($"Téléphone déconnecté ({transportLabel}). Toutes les touches/axes ont été relâchés.");
                EmitSnapshot();
            }

            clientCts.Dispose();
        }
    }

    private void ProcessPacket(DecodedPacket packet)
    {
        Interlocked.Increment(ref _packetsCounter);

        switch (packet.Type)
        {
            case PacketType.GamepadState:
                if (PacketCodec.TryDecodeGamepadState(packet.Payload, out var state))
                {
                    _currentGamepad = state;
                    if (Mode is InputRoutingMode.Auto or InputRoutingMode.Xbox360Only)
                    {
                        _xboxDriver.ApplyState(state);
                    }
                    EmitSnapshot();
                }
                break;

            case PacketType.KeyboardEvent:
                if (Mode is InputRoutingMode.Auto or InputRoutingMode.KeyboardMouseOnly &&
                    PacketCodec.TryDecodeKeyboardCommand(packet.Payload, out var kbCmd))
                {
                    _kbMouse.HandleKeyboard(kbCmd);
                }
                break;

            case PacketType.MouseEvent:
                if (Mode is InputRoutingMode.Auto or InputRoutingMode.KeyboardMouseOnly &&
                    PacketCodec.TryDecodeMouseCommand(packet.Payload, out var mouseCmd))
                {
                    _kbMouse.HandleMouse(mouseCmd);
                }
                break;

            case PacketType.Hello:
                var hello = PacketCodec.DecodeHello(packet.Payload);
                lock (_clientLock)
                {
                    _deviceName = hello.Name;
                    _profileName = hello.Profile;
                    if (hello.Transport.Equals("bluetooth", StringComparison.OrdinalIgnoreCase))
                        _transportLabel = "Bluetooth";
                    else if (hello.Transport.Equals("usb", StringComparison.OrdinalIgnoreCase) && _transportLabel.StartsWith("Wi-Fi"))
                        _transportLabel = "USB";
                }
                _log($"Profil actif sur {hello.Name} : {hello.Profile}");
                _ = NotifyServerInfoAsync();
                EmitSnapshot();
                break;

            case PacketType.Ping:
                if (PacketCodec.TryDecodeTimestamp(packet.Payload, out var ts))
                {
                    _ = WriteFrameToActiveClientAsync(PacketCodec.EncodePong(ts));
                }
                break;

            case PacketType.ResetInputs:
                ResetAllInputs();
                EmitSnapshot();
                break;
        }
    }

    private void OnXboxRumbleReceived(byte largeMotor, byte smallMotor)
    {
        _ = WriteFrameToActiveClientAsync(PacketCodec.EncodeRumble(largeMotor, smallMotor));
    }

    private async Task WriteFrameToActiveClientAsync(byte[] frame)
    {
        Stream? target;
        lock (_clientLock)
        {
            target = _activeStream;
        }
        if (target is null) return;

        await _writeLock.WaitAsync();
        try
        {
            if (target.CanWrite)
            {
                await target.WriteAsync(frame);
                await target.FlushAsync();
            }
        }
        catch
        {
            // Ignore transient write errors on closing stream
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private void ResetAllInputs()
    {
        _currentGamepad = GamepadState.Neutral;
        _xboxDriver.ResetToNeutral();
        _kbMouse.ReleaseAll();
    }

    private void EmitSnapshot()
    {
        ActiveSessionSnapshot snap;
        lock (_clientLock)
        {
            snap = new ActiveSessionSnapshot(
                IsConnected: _activeStream is not null,
                DeviceName: _deviceName,
                ProfileName: _profileName,
                TransportLabel: _transportLabel,
                RemoteEndpoint: _remoteEndpoint,
                PacketsPerSecond: _packetsPerSecond,
                GamepadState: _currentGamepad
            );
        }
        SessionUpdated?.Invoke(snap);
    }

    public void Dispose()
    {
        _statsTimer?.Dispose();
        _xboxDriver.RumbleReceived -= OnXboxRumbleReceived;
        DisconnectCurrentClient();
        _adbBridge.Dispose();
        _tcpServer.Dispose();
        _bluetoothServer.Dispose();
        _xboxDriver.Dispose();
        _writeLock.Dispose();
    }
}
