using System.IO;
using PocketPad.Core.Multiplayer;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Services;

public enum InputRoutingMode
{
    Auto,
    Xbox360Only,
    KeyboardMouseOnly,
}

public sealed record PlayerSlotSnapshot(
    int Slot,
    string ColorHex,
    bool IsConnected,
    string DeviceName,
    string ProfileName,
    string TransportLabel,
    string RemoteEndpoint,
    int PacketsPerSecond,
    GamepadState GamepadState
);

public sealed record MultiplayerSnapshot(
    int ConnectedCount,
    int MaxPlayers,
    IReadOnlyList<PlayerSlotSnapshot> Slots
);

/// <summary>
/// Full 4-player multiplayer hub coordinating simultaneous USB (ADB &amp; Tethering), Bluetooth RFCOMM,
/// and Wi-Fi connections — any mix of transports, up to 4 independent Virtual Xbox 360 controllers
/// with per-player rumble, slot swapping, and isolated Keyboard/Mouse ownership.
/// </summary>
public sealed class SessionCoordinator : IDisposable
{
    private sealed class PlayerSession
    {
        public long SessionId { get; }
        public Stream Stream { get; }
        public CancellationTokenSource Cts { get; }
        public SemaphoreSlim WriteLock { get; } = new(1, 1);
        public string ClientId { get; set; } = "";
        public string DeviceName { get; set; }
        public string ProfileName { get; set; } = "Connexion…";
        public string TransportLabel { get; set; }
        public string RemoteEndpoint { get; }
        public GamepadState CurrentGamepad { get; set; } = GamepadState.Neutral;
        public int PacketsCounter;
        public int PacketsPerSecond;

        public PlayerSession(long sessionId, Stream stream, CancellationTokenSource cts, string transportLabel, string remoteEndpoint)
        {
            SessionId = sessionId;
            Stream = stream;
            Cts = cts;
            TransportLabel = transportLabel;
            RemoteEndpoint = remoteEndpoint;
            DeviceName = transportLabel == "Bluetooth" ? remoteEndpoint : "Téléphone Android";
        }

        public async Task WriteFrameAsync(byte[] frame)
        {
            if (Cts.IsCancellationRequested) return;
            try
            {
                await WriteLock.WaitAsync(Cts.Token);
                try
                {
                    if (Stream.CanWrite && !Cts.IsCancellationRequested)
                    {
                        await Stream.WriteAsync(frame, Cts.Token);
                        await Stream.FlushAsync(Cts.Token);
                    }
                }
                finally
                {
                    WriteLock.Release();
                }
            }
            catch
            {
                // Ignore transient errors on closing connection
            }
        }
    }

    private readonly Action<string> _log;
    private readonly VirtualXboxDriver _xboxDriver;
    private readonly KeyboardMouseInjector _kbMouse;
    private readonly AdbUsbBridge _adbBridge;
    private readonly TcpUsbServer _tcpServer;
    private readonly BluetoothServer _bluetoothServer;
    private readonly PlayerSlotRegistry<PlayerSession> _registry = new(PocketPadConstants.MaxPlayers);

    private long _nextSessionId;
    private Timer? _statsTimer;

    public InputRoutingMode Mode { get; set; } = InputRoutingMode.Auto;
    public bool KeyboardMousePlayer1Only { get; set; } = false;

    public VirtualXboxDriver XboxDriver => _xboxDriver;
    public AdbUsbBridge AdbBridge => _adbBridge;
    public BluetoothServer BluetoothServer => _bluetoothServer;

    public event Action<MultiplayerSnapshot>? MultiplayerUpdated;
    public event Action<AdbStatusSnapshot>? AdbStatusUpdated;
    public event Action<BluetoothStatusSnapshot>? BluetoothStatusUpdated;

    public SessionCoordinator(Action<string> log)
    {
        _log = log;
        _xboxDriver = new VirtualXboxDriver(log);
        _kbMouse = new KeyboardMouseInjector();
        _adbBridge = new AdbUsbBridge(log, snap => AdbStatusUpdated?.Invoke(snap));
        _tcpServer = new TcpUsbServer(
            log,
            () => _xboxDriver.IsAvailable,
            () => _registry.ConnectedCount,
            HandleIncomingClientAsync
        );
        _bluetoothServer = new BluetoothServer(
            log,
            snap => BluetoothStatusUpdated?.Invoke(snap),
            HandleIncomingClientAsync
        );

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
            foreach (var (_, sess) in _registry.GetAllActive())
            {
                sess.PacketsPerSecond = Interlocked.Exchange(ref sess.PacketsCounter, 0);
            }
            EmitSnapshot();
        }, null, 1000, 1000);

        EmitSnapshot();
    }

    public async Task SendTestRumbleAsync(int slot)
    {
        var sess = _registry.GetAtSlot(slot);
        if (sess is null) return;
        await sess.WriteFrameAsync(PacketCodec.EncodeRumble(220, 180));
        await Task.Delay(320);
        await sess.WriteFrameAsync(PacketCodec.EncodeRumble(0, 0));
    }

    public async Task BroadcastServerInfoAsync()
    {
        var active = _registry.GetAllActive();
        var count = active.Count;
        foreach (var (slot, sess) in active)
        {
            var payload = new ServerInfoPayload(
                PcName: Environment.MachineName,
                ViGemReady: _xboxDriver.IsAvailable,
                Mode: Mode.ToString().ToLowerInvariant(),
                Version: PocketPadConstants.AppVersion,
                PlayerSlot: slot,
                MaxPlayers: PocketPadConstants.MaxPlayers,
                ConnectedPlayers: count,
                SlotColor: PocketPadConstants.GetSlotColor(slot)
            );
            await sess.WriteFrameAsync(PacketCodec.EncodeServerInfo(payload));
        }
    }

    public void EnsureAllActiveXboxSlotsConnected()
    {
        foreach (var (slot, _) in _registry.GetAllActive())
        {
            _xboxDriver.EnsureSlotConnected(slot);
        }
    }

    public void DisconnectSlot(int slot)
    {
        var sess = _registry.GetAtSlot(slot);
        if (sess is null) return;
        try { sess.Cts.Cancel(); } catch { }
    }

    public void DisconnectAll()
    {
        foreach (var (_, sess) in _registry.GetAllActive())
        {
            try { sess.Cts.Cancel(); } catch { }
        }
    }

    public async Task SwapSlotsAsync(int slotA, int slotB)
    {
        if (!_registry.TryMoveOrSwap(slotA, slotB, out var nowAtA, out var nowAtB))
            return;

        // Reconcile virtual Xbox 360 controllers cleanly for both slots
        if (nowAtA is not null)
        {
            _xboxDriver.EnsureSlotConnected(slotA);
            _xboxDriver.ApplyState(slotA, nowAtA.CurrentGamepad);
        }
        else
        {
            _xboxDriver.DisconnectSlot(slotA);
        }

        if (nowAtB is not null)
        {
            _xboxDriver.EnsureSlotConnected(slotB);
            _xboxDriver.ApplyState(slotB, nowAtB.CurrentGamepad);
        }
        else
        {
            _xboxDriver.DisconnectSlot(slotB);
        }

        _log($"Slots échangés : J{slotA} ⇄ J{slotB}");
        await BroadcastServerInfoAsync();
        EmitSnapshot();
    }

    private async Task HandleIncomingClientAsync(Stream stream, string transportLabel, string remoteEndpoint, CancellationToken serverCt)
    {
        var clientCts = CancellationTokenSource.CreateLinkedTokenSource(serverCt);
        var sessionId = Interlocked.Increment(ref _nextSessionId);
        var session = new PlayerSession(sessionId, stream, clientCts, transportLabel, remoteEndpoint);

        var assignedSlot = _registry.AllocateInitialSlot(session);
        if (assignedSlot == 0)
        {
            _log($"Connexion refusée ({transportLabel}, {remoteEndpoint}) : les {PocketPadConstants.MaxPlayers} places multijoueur sont déjà occupées.");
            clientCts.Dispose();
            return;
        }

        _xboxDriver.EnsureSlotConnected(assignedSlot);
        _log($"Joueur {assignedSlot} connecté via {transportLabel} ({remoteEndpoint}) — {_registry.ConnectedCount}/{PocketPadConstants.MaxPlayers} joueurs");
        EmitSnapshot();
        await BroadcastServerInfoAsync();

        var reader = new FrameStreamReader();
        var buffer = new byte[4096];

        try
        {
            while (!clientCts.Token.IsCancellationRequested)
            {
                var read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), clientCts.Token);
                if (read <= 0) break;

                reader.Feed(buffer.AsSpan(0, read), packet => ProcessPacket(session, packet));
            }
        }
        catch (OperationCanceledException)
        {
            // Disconnected intentionally or replaced by reconnect
        }
        catch (IOException)
        {
            // Cable unplugged / Bluetooth or Wi-Fi dropped
        }
        catch (Exception ex)
        {
            _log($"Déconnexion J{_registry.GetSlotOf(session)} ({transportLabel}) : {ex.Message}");
        }
        finally
        {
            var releasedSlot = _registry.Release(session);
            _kbMouse.ReleaseOwner(session.SessionId);

            if (releasedSlot > 0)
            {
                _xboxDriver.DisconnectSlot(releasedSlot);
                _log($"Joueur {releasedSlot} ({session.DeviceName}, {session.TransportLabel}) déconnecté. Restants : {_registry.ConnectedCount}/{PocketPadConstants.MaxPlayers}");
                _ = BroadcastServerInfoAsync();
                EmitSnapshot();
            }

            clientCts.Dispose();
            session.WriteLock.Dispose();
        }
    }

    private void ProcessPacket(PlayerSession session, DecodedPacket packet)
    {
        Interlocked.Increment(ref session.PacketsCounter);
        var slot = _registry.GetSlotOf(session);
        if (slot <= 0) return;

        switch (packet.Type)
        {
            case PacketType.GamepadState:
                if (PacketCodec.TryDecodeGamepadState(packet.Payload, out var state))
                {
                    session.CurrentGamepad = state;
                    if (Mode is InputRoutingMode.Auto or InputRoutingMode.Xbox360Only)
                    {
                        _xboxDriver.ApplyState(slot, state);
                    }
                    EmitSnapshot();
                }
                break;

            case PacketType.KeyboardEvent:
                if (Mode is InputRoutingMode.Auto or InputRoutingMode.KeyboardMouseOnly &&
                    (!KeyboardMousePlayer1Only || slot == 1) &&
                    PacketCodec.TryDecodeKeyboardCommand(packet.Payload, out var kbCmd))
                {
                    _kbMouse.HandleKeyboard(session.SessionId, kbCmd);
                }
                break;

            case PacketType.MouseEvent:
                if (Mode is InputRoutingMode.Auto or InputRoutingMode.KeyboardMouseOnly &&
                    (!KeyboardMousePlayer1Only || slot == 1) &&
                    PacketCodec.TryDecodeMouseCommand(packet.Payload, out var mouseCmd))
                {
                    _kbMouse.HandleMouse(session.SessionId, mouseCmd);
                }
                break;

            case PacketType.Hello:
                var hello = PacketCodec.DecodeHello(packet.Payload);
                var oldSlot = slot;
                session.DeviceName = hello.Name;
                session.ProfileName = hello.Profile;
                session.ClientId = hello.ClientId;
                if (hello.Transport.Equals("bluetooth", StringComparison.OrdinalIgnoreCase))
                    session.TransportLabel = "Bluetooth";
                else if (hello.Transport.Equals("usb", StringComparison.OrdinalIgnoreCase) && session.TransportLabel.StartsWith("Wi-Fi"))
                    session.TransportLabel = "USB";

                var newSlot = _registry.ApplyIdentity(
                    session,
                    hello.ClientId,
                    hello.PreferredSlot,
                    s => s.ClientId,
                    out var evicted
                );

                if (evicted is not null)
                {
                    _kbMouse.ReleaseOwner(evicted.SessionId);
                    try { evicted.Cts.Cancel(); } catch { }
                    _log($"Reconnexion rapide détectée pour {hello.Name} → reprise du slot J{newSlot}");
                }

                if (newSlot > 0 && newSlot != oldSlot)
                {
                    _xboxDriver.DisconnectSlot(oldSlot);
                    _xboxDriver.EnsureSlotConnected(newSlot);
                    _log($"{hello.Name} déplacé : J{oldSlot} → Joueur {newSlot}");
                }
                else
                {
                    _log($"Joueur {newSlot} : {hello.Name} — Profil « {hello.Profile} » ({session.TransportLabel})");
                }

                _ = BroadcastServerInfoAsync();
                EmitSnapshot();
                break;

            case PacketType.Ping:
                if (PacketCodec.TryDecodeTimestamp(packet.Payload, out var ts))
                {
                    _ = session.WriteFrameAsync(PacketCodec.EncodePong(ts));
                }
                break;

            case PacketType.ResetInputs:
                session.CurrentGamepad = GamepadState.Neutral;
                _xboxDriver.ResetSlotToNeutral(slot);
                _kbMouse.ReleaseOwner(session.SessionId);
                EmitSnapshot();
                break;
        }
    }

    private void OnXboxRumbleReceived(int slot, byte largeMotor, byte smallMotor)
    {
        var sess = _registry.GetAtSlot(slot);
        if (sess is not null)
        {
            _ = sess.WriteFrameAsync(PacketCodec.EncodeRumble(largeMotor, smallMotor));
        }
    }

    private void EmitSnapshot()
    {
        var slots = new List<PlayerSlotSnapshot>(PocketPadConstants.MaxPlayers);
        int connected = 0;
        for (int s = 1; s <= PocketPadConstants.MaxPlayers; s++)
        {
            var sess = _registry.GetAtSlot(s);
            if (sess is not null)
            {
                connected++;
                slots.Add(new PlayerSlotSnapshot(
                    Slot: s,
                    ColorHex: PocketPadConstants.GetSlotColor(s),
                    IsConnected: true,
                    DeviceName: sess.DeviceName,
                    ProfileName: sess.ProfileName,
                    TransportLabel: sess.TransportLabel,
                    RemoteEndpoint: sess.RemoteEndpoint,
                    PacketsPerSecond: sess.PacketsPerSecond,
                    GamepadState: sess.CurrentGamepad
                ));
            }
            else
            {
                slots.Add(new PlayerSlotSnapshot(
                    Slot: s,
                    ColorHex: PocketPadConstants.GetSlotColor(s),
                    IsConnected: false,
                    DeviceName: "Libre",
                    ProfileName: "En attente…",
                    TransportLabel: "—",
                    RemoteEndpoint: "—",
                    PacketsPerSecond: 0,
                    GamepadState: GamepadState.Neutral
                ));
            }
        }

        MultiplayerUpdated?.Invoke(new MultiplayerSnapshot(connected, PocketPadConstants.MaxPlayers, slots));
    }

    public void Dispose()
    {
        _statsTimer?.Dispose();
        _xboxDriver.RumbleReceived -= OnXboxRumbleReceived;
        DisconnectAll();
        _kbMouse.ReleaseAll();
        _adbBridge.Dispose();
        _tcpServer.Dispose();
        _bluetoothServer.Dispose();
        _xboxDriver.Dispose();
    }
}
