using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using PocketPad.Core.Protocol;
using PocketPad.Core.Video;

namespace PocketPad.Windows.Services;

public sealed record VideoTelemetrySnapshot(
    bool StreamingEnabled,
    string BackendName,
    int ActiveViewers,
    int ActualFps,
    int BandwidthKbps,
    bool DetectedSplitActive,
    SplitScreenPreset ActivePreset,
    bool FullMenuOverride
);

/// <summary>
/// Dedicated low-latency TCP video streaming server (port 27185) for PocketPad's Nintendo Switch mode.
/// Each player's phone subscribes to its own slot (1..4) and receives its exact split-screen crop
/// via a zero-queue latest-frame mailbox so latency stays ultra-low even on busy Wi-Fi.
/// </summary>
public sealed class VideoStreamServer : IDisposable
{
    private sealed class VideoClientSession
    {
        public long Id { get; }
        public TcpClient Tcp { get; }
        public NetworkStream Stream { get; }
        public CancellationTokenSource Cts { get; }

        public volatile int Slot = 1;
        public volatile int MaxWidth = 854;
        public volatile int MaxHeight = 480;
        public volatile int Quality = 60;
        public volatile bool Paused;

        private readonly object _mailLock = new();
        private byte[]? _pendingFrame;
        private readonly SemaphoreSlim _frameReady = new(0, 1);

        public VideoClientSession(long id, TcpClient tcp, NetworkStream stream, CancellationTokenSource cts)
        {
            Id = id;
            Tcp = tcp;
            Stream = stream;
            Cts = cts;
        }

        /// <summary>
        /// Replaces any unsent older frame immediately (drop-oldest policy -> zero latency queue buildup).
        /// </summary>
        public void EnqueueLatestFrame(byte[] framedPacket)
        {
            if (Paused || Cts.IsCancellationRequested) return;
            bool shouldSignal;
            lock (_mailLock)
            {
                shouldSignal = _pendingFrame is null;
                _pendingFrame = framedPacket;
            }
            if (shouldSignal)
            {
                try { _frameReady.Release(); } catch (SemaphoreFullException) { }
            }
        }

        public async Task<byte[]?> WaitNextFrameAsync(CancellationToken ct)
        {
            await _frameReady.WaitAsync(ct);
            lock (_mailLock)
            {
                var frame = _pendingFrame;
                _pendingFrame = null;
                return frame;
            }
        }

        public void DisposeResources()
        {
            try { Cts.Cancel(); } catch { }
            try { Stream.Dispose(); } catch { }
            try { Tcp.Dispose(); } catch { }
            try { Cts.Dispose(); } catch { }
            try { _frameReady.Dispose(); } catch { }
        }
    }

    private readonly Action<string> _log;
    private readonly ScreenCaptureService _capture;
    private readonly SplitScreenConfiguration _splitConfig = new();
    private readonly ConcurrentDictionary<long, VideoClientSession> _clients = new();
    private readonly CancellationTokenSource _cts = new();

    private TcpListener? _listener;
    private Task? _acceptTask;
    private Task? _captureTask;
    private long _nextClientId;

    private volatile bool _streamingEnabled = true;
    private volatile bool _previewRequested;
    private volatile int _targetFps = 28;
    private volatile int _qualityCap = 65;
    private volatile int _maxWidthCap = 854;
    private volatile int _maxHeightCap = 480;

    private long _bytesSentWindow;
    private int _framesCapturedWindow;
    private bool _detectedSplitActive = true;
    private int _seamStreak;

    public SplitScreenConfiguration SplitConfig => _splitConfig;
    public ScreenCaptureService CaptureService => _capture;

    public bool StreamingEnabled
    {
        get => _streamingEnabled;
        set => _streamingEnabled = value;
    }

    public bool PreviewRequested
    {
        get => _previewRequested;
        set => _previewRequested = value;
    }

    public int TargetFps
    {
        get => _targetFps;
        set => _targetFps = Math.Clamp(value, 10, 45);
    }

    public int QualityCap
    {
        get => _qualityCap;
        set => _qualityCap = Math.Clamp(value, 35, 85);
    }

    public int MaxWidthCap
    {
        get => _maxWidthCap;
        set => _maxWidthCap = Math.Clamp(value, 480, 1280);
    }

    public int MaxHeightCap
    {
        get => _maxHeightCap;
        set => _maxHeightCap = Math.Clamp(value, 270, 720);
    }

    public event Action<byte[]>? PreviewJpegReady;
    public event Action<VideoTelemetrySnapshot>? TelemetryUpdated;

    public VideoStreamServer(Action<string> log)
    {
        _log = log;
        _capture = new ScreenCaptureService(log);
    }

    public void Start()
    {
        try
        {
            _listener = new TcpListener(IPAddress.Any, PocketPadConstants.VideoTcpPort);
            _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _listener.Start();
            _log($"Serveur Vidéo Mode Switch actif sur le port TCP {PocketPadConstants.VideoTcpPort}");
            _acceptTask = Task.Run(() => AcceptLoopAsync(_cts.Token));
        }
        catch (Exception ex)
        {
            _log($"Erreur démarrage serveur vidéo TCP {PocketPadConstants.VideoTcpPort} : {ex.Message}");
        }

        _captureTask = Task.Run(() => CaptureAndBroadcastLoopAsync(_cts.Token));
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener is not null)
        {
            try
            {
                var tcp = await _listener.AcceptTcpClientAsync(ct);
                tcp.NoDelay = true;
                tcp.SendBufferSize = 131072;
                tcp.ReceiveBufferSize = 4096;

                var id = Interlocked.Increment(ref _nextClientId);
                var clientCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                var session = new VideoClientSession(id, tcp, tcp.GetStream(), clientCts);
                _clients[id] = session;

                var ep = tcp.Client.RemoteEndPoint?.ToString() ?? "client";
                _log($"Écran téléphone connecté sur le flux vidéo ({ep})");

                _ = Task.Run(() => RunClientSessionAsync(session), ct);
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
                    _log($"Avertissement serveur vidéo : {ex.Message}");
            }
        }
    }

    private async Task RunClientSessionAsync(VideoClientSession session)
    {
        var token = session.Cts.Token;

        // Task A: Read 10-byte client subscribe/pause/resume packets
        var readTask = Task.Run(async () =>
        {
            var buf = new byte[VideoPacketCodec.ClientPacketSize];
            try
            {
                while (!token.IsCancellationRequested)
                {
                    var offset = 0;
                    while (offset < buf.Length)
                    {
                        var n = await session.Stream.ReadAsync(buf.AsMemory(offset, buf.Length - offset), token);
                        if (n <= 0) return;
                        offset += n;
                    }

                    if (VideoPacketCodec.TryDecodeClientPacket(buf, out var sub))
                    {
                        session.Slot = sub.Slot;
                        session.MaxWidth = sub.MaxWidth;
                        session.MaxHeight = sub.MaxHeight;
                        session.Quality = sub.Quality;
                        session.Paused = sub.Command == VideoClientCommand.Pause;
                    }
                }
            }
            catch
            {
                // Disconnected
            }
            finally
            {
                try { session.Cts.Cancel(); } catch { }
            }
        }, token);

        // Task B: Write latest JPEG frames from single-slot mailbox
        var writeTask = Task.Run(async () =>
        {
            try
            {
                while (!token.IsCancellationRequested)
                {
                    var packet = await session.WaitNextFrameAsync(token);
                    if (packet is null) continue;
                    await session.Stream.WriteAsync(packet, token);
                    Interlocked.Add(ref _bytesSentWindow, packet.Length);
                }
            }
            catch
            {
                // Disconnected
            }
            finally
            {
                try { session.Cts.Cancel(); } catch { }
            }
        }, token);

        await Task.WhenAll(readTask, writeTask);
        _clients.TryRemove(session.Id, out _);
        session.DisposeResources();
    }

    private async Task CaptureAndBroadcastLoopAsync(CancellationToken ct)
    {
        var sw = Stopwatch.StartNew();
        var lastStatsTime = sw.ElapsedMilliseconds;
        var lastPreviewTime = 0L;

        while (!ct.IsCancellationRequested)
        {
            var frameStartMs = sw.ElapsedMilliseconds;

            var activeViewers = _streamingEnabled
                ? _clients.Values.Where(c => !c.Paused && !c.Cts.IsCancellationRequested).ToList()
                : new List<VideoClientSession>();

            var needPreview = _previewRequested && (frameStartMs - lastPreviewTime >= 180);

            if (activeViewers.Count == 0 && !needPreview)
            {
                EmitTelemetryIfDue(sw.ElapsedMilliseconds, ref lastStatsTime, 0);
                await Task.Delay(80, ct);
                continue;
            }

            if (_capture.TryCaptureFrame(out var fullFrame) && fullFrame is not null)
            {
                Interlocked.Increment(ref _framesCapturedWindow);

                // Optional Auto-Split seam detection (with hysteresis to avoid flickering)
                if (_splitConfig.AutoDetectMenuVsSplit &&
                    _splitConfig.Preset is not SplitScreenPreset.FullShared and not SplitScreenPreset.Custom)
                {
                    var seamNow = _capture.DetectSplitScreenSeam(fullFrame, _splitConfig.Preset);
                    if (seamNow)
                    {
                        _seamStreak = Math.Min(_seamStreak + 1, 6);
                        if (_seamStreak >= 4) _detectedSplitActive = true;
                    }
                    else
                    {
                        _seamStreak = Math.Max(_seamStreak - 1, -6);
                        if (_seamStreak <= -4) _detectedSplitActive = false;
                    }
                }
                else
                {
                    _detectedSplitActive = true;
                }

                // Cache encoded frames by (cropRect, targetW, targetH, quality) so shared views encode ONCE!
                var frameCache = new Dictionary<(NormalizedCropRect Rect, int W, int H, int Q), (byte[] Jpeg, ushort W, ushort H)>();

                byte flags = 0;
                if (_splitConfig.ForceFullMenuView) flags |= VideoPacketCodec.FlagFullMenuView;
                if (_splitConfig.AutoDetectMenuVsSplit && !_detectedSplitActive) flags |= VideoPacketCodec.FlagFullMenuView;
                if (_splitConfig.AutoDetectMenuVsSplit && _detectedSplitActive) flags |= VideoPacketCodec.FlagAutoSplitActive;

                foreach (var viewer in activeViewers)
                {
                    var slot = viewer.Slot;
                    var crop = _splitConfig.GetEffectiveRectForSlot(slot, _detectedSplitActive);
                    var targetW = Math.Min(viewer.MaxWidth, _maxWidthCap);
                    var targetH = Math.Min(viewer.MaxHeight, _maxHeightCap);
                    var quality = Math.Min(viewer.Quality, _qualityCap);

                    var cacheKey = (crop, targetW, targetH, quality);
                    if (!frameCache.TryGetValue(cacheKey, out var encoded))
                    {
                        var res = _capture.EncodeCroppedJpeg(fullFrame, crop, targetW, targetH, quality);
                        if (res is null) continue;
                        encoded = res.Value;
                        frameCache[cacheKey] = encoded;
                    }

                    var wirePacket = new byte[VideoPacketCodec.ServerHeaderSize + encoded.Jpeg.Length];
                    VideoPacketCodec.WriteFrameHeader(
                        wirePacket.AsSpan(0, VideoPacketCodec.ServerHeaderSize),
                        slot,
                        flags,
                        encoded.W,
                        encoded.H,
                        encoded.Jpeg.Length
                    );
                    Buffer.BlockCopy(encoded.Jpeg, 0, wirePacket, VideoPacketCodec.ServerHeaderSize, encoded.Jpeg.Length);
                    viewer.EnqueueLatestFrame(wirePacket);
                }

                if (needPreview)
                {
                    lastPreviewTime = frameStartMs;
                    var preview = _capture.EncodeCroppedJpeg(fullFrame, NormalizedCropRect.Full, 560, 315, 58);
                    if (preview is not null)
                    {
                        PreviewJpegReady?.Invoke(preview.Value.JpegBytes);
                    }
                }
            }

            EmitTelemetryIfDue(sw.ElapsedMilliseconds, ref lastStatsTime, activeViewers.Count);

            var intervalMs = Math.Max(18, 1000 / Math.Max(10, _targetFps));
            var spentMs = (int)(sw.ElapsedMilliseconds - frameStartMs);
            var sleepMs = intervalMs - spentMs;
            if (sleepMs > 2)
            {
                try { await Task.Delay(sleepMs, ct); } catch (OperationCanceledException) { break; }
            }
        }
    }

    private void EmitTelemetryIfDue(long nowMs, ref long lastStatsMs, int activeViewers)
    {
        if (nowMs - lastStatsMs < 1000) return;
        var elapsedSec = Math.Max(0.25, (nowMs - lastStatsMs) / 1000.0);
        lastStatsMs = nowMs;

        var fps = (int)Math.Round(Interlocked.Exchange(ref _framesCapturedWindow, 0) / elapsedSec);
        var bytes = Interlocked.Exchange(ref _bytesSentWindow, 0);
        var kbps = (int)Math.Round((bytes * 8.0) / 1000.0 / elapsedSec);

        TelemetryUpdated?.Invoke(new VideoTelemetrySnapshot(
            StreamingEnabled: _streamingEnabled,
            BackendName: _capture.BackendLabel,
            ActiveViewers: activeViewers,
            ActualFps: activeViewers > 0 ? fps : 0,
            BandwidthKbps: kbps,
            DetectedSplitActive: _detectedSplitActive,
            ActivePreset: _splitConfig.Preset,
            FullMenuOverride: _splitConfig.ForceFullMenuView
        ));
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _listener?.Stop(); } catch { }
        foreach (var c in _clients.Values)
            c.DisposeResources();
        _clients.Clear();
        try { _captureTask?.Wait(600); } catch { }
        _capture.Dispose();
        _cts.Dispose();
    }
}
