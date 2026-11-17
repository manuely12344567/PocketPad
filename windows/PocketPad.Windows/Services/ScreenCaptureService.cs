using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using PocketPad.Core.Video;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace PocketPad.Windows.Services;

public sealed record MonitorDescriptor(
    int Index,
    uint AdapterIndex,
    uint OutputIndex,
    string DisplayName,
    int Left,
    int Top,
    int Width,
    int Height
);

/// <summary>
/// High-speed Windows screen capture engine using GPU DXGI Desktop Duplication (Vortice.Direct3D11 / DXGI)
/// with automatic seamless fallback to GDI CopyFromScreen (e.g. UAC / RDP / restricted GPU).
/// Captures the full monitor ONCE per frame, supports center-line split-screen seam detection,
/// and crops/downscales/encodes fast JPEG slices per player slot on demand.
/// </summary>
public sealed class ScreenCaptureService : IDisposable
{
    private const int SmCxScreen = 0;
    private const int SmCyScreen = 1;

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    private readonly Action<string> _log;
    private readonly ImageCodecInfo? _jpegCodec;
    private readonly object _sync = new();

    private ID3D11Device? _device;
    private ID3D11DeviceContext? _context;
    private IDXGIOutputDuplication? _duplication;
    private ID3D11Texture2D? _stagingTexture;
    private Bitmap? _desktopBitmap;
    private byte[]? _rowTransferBuffer;

    private int _selectedMonitorIndex;
    private MonitorDescriptor _activeMonitor;
    private bool _dxgiActive;
    private DateTime _nextDxgiRetryUtc = DateTime.MinValue;

    public string BackendLabel => _dxgiActive ? "DXGI GPU" : "GDI";
    public MonitorDescriptor ActiveMonitor => _activeMonitor;

    public ScreenCaptureService(Action<string> log)
    {
        _log = log;
        _jpegCodec = ImageCodecInfo.GetImageEncoders()
            .FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);

        var monitors = EnumerateMonitors();
        _activeMonitor = monitors.FirstOrDefault() ?? CreatePrimaryFallbackMonitor();
    }

    public static IReadOnlyList<MonitorDescriptor> EnumerateMonitors()
    {
        var list = new List<MonitorDescriptor>();
        try
        {
            using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
            for (uint a = 0; factory.EnumAdapters1(a, out var adapter).Success; a++)
            {
                using (adapter)
                {
                    for (uint o = 0; adapter.EnumOutputs(o, out var output).Success; o++)
                    {
                        using (output)
                        {
                            var desc = output.Description;
                            if (!desc.AttachedToDesktop) continue;
                            var rect = desc.DesktopCoordinates;
                            var w = rect.Right - rect.Left;
                            var h = rect.Bottom - rect.Top;
                            if (w <= 0 || h <= 0) continue;

                            var idx = list.Count;
                            list.Add(new MonitorDescriptor(
                                Index: idx,
                                AdapterIndex: a,
                                OutputIndex: o,
                                DisplayName: $"Écran {idx + 1} ({w}×{h})",
                                Left: rect.Left,
                                Top: rect.Top,
                                Width: w,
                                Height: h
                            ));
                        }
                    }
                }
            }
        }
        catch
        {
            // Fallback below
        }

        if (list.Count == 0)
            list.Add(CreatePrimaryFallbackMonitor());

        return list;
    }

    private static MonitorDescriptor CreatePrimaryFallbackMonitor()
    {
        var w = Math.Max(640, GetSystemMetrics(SmCxScreen));
        var h = Math.Max(360, GetSystemMetrics(SmCyScreen));
        return new MonitorDescriptor(0, 0, 0, $"Écran principal ({w}×{h})", 0, 0, w, h);
    }

    public void SelectMonitor(int monitorIndex)
    {
        lock (_sync)
        {
            var monitors = EnumerateMonitors();
            _selectedMonitorIndex = Math.Clamp(monitorIndex, 0, Math.Max(0, monitors.Count - 1));
            _activeMonitor = monitors[_selectedMonitorIndex];
            DisposeDxgiLocked();
            _nextDxgiRetryUtc = DateTime.MinValue;
            _log($"Capture vidéo basculée sur {_activeMonitor.DisplayName}");
        }
    }

    /// <summary>
    /// Captures or updates the latest desktop bitmap (DXGI GPU preferred, GDI fallback).
    /// Returns true if a valid frame is available in the internal buffer.
    /// </summary>
    public bool TryCaptureFrame(out Bitmap? frameBitmap)
    {
        lock (_sync)
        {
            if (!_dxgiActive && DateTime.UtcNow >= _nextDxgiRetryUtc)
            {
                TryInitializeDxgiLocked();
            }

            if (_dxgiActive && TryCaptureDxgiLocked())
            {
                frameBitmap = _desktopBitmap;
                return frameBitmap is not null;
            }

            // Fallback: GDI CopyFromScreen (always works, even over RDP/protected desktops)
            if (TryCaptureGdiLocked())
            {
                frameBitmap = _desktopBitmap;
                return frameBitmap is not null;
            }

            frameBitmap = null;
            return false;
        }
    }

    /// <summary>
    /// Crops a normalized rectangle from the captured screen bitmap, scales it to fit maxWidth x maxHeight
    /// while preserving aspect ratio, and encodes it to JPEG bytes.
    /// </summary>
    public (byte[] JpegBytes, ushort Width, ushort Height)? EncodeCroppedJpeg(
        Bitmap source,
        NormalizedCropRect cropRect,
        int maxWidth,
        int maxHeight,
        int jpegQuality)
    {
        if (_jpegCodec is null) return null;

        var (sx, sy, sw, sh) = cropRect.ToPixelRect(source.Width, source.Height);
        if (sw < 4 || sh < 4) return null;

        var scale = Math.Min((double)maxWidth / sw, (double)maxHeight / sh);
        if (scale > 1.0) scale = 1.0;

        var dw = Math.Max(64, ((int)Math.Round(sw * scale)) & ~1);
        var dh = Math.Max(64, ((int)Math.Round(sh * scale)) & ~1);

        using var target = new Bitmap(dw, dh, PixelFormat.Format24bppRgb);
        using (var g = Graphics.FromImage(target))
        {
            g.CompositingMode = CompositingMode.SourceCopy;
            g.CompositingQuality = CompositingQuality.HighSpeed;
            g.InterpolationMode = InterpolationMode.Bilinear;
            g.SmoothingMode = SmoothingMode.None;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            g.DrawImage(source, new Rectangle(0, 0, dw, dh), new Rectangle(sx, sy, sw, sh), GraphicsUnit.Pixel);
        }

        using var ms = new MemoryStream(32 * 1024);
        using var encParams = new EncoderParameters(1);
        encParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)Math.Clamp(jpegQuality, 30, 85));
        target.Save(ms, _jpegCodec, encParams);

        return (ms.ToArray(), (ushort)dw, (ushort)dh);
    }

    /// <summary>
    /// Fast lightweight split-screen seam detector for Auto-Split (Menu ↔ Match).
    /// Samples 64 points along the horizontal and/or vertical center line directly from locked bitmap memory.
    /// </summary>
    public bool DetectSplitScreenSeam(Bitmap frame, SplitScreenPreset preset)
    {
        if (preset is SplitScreenPreset.FullShared or SplitScreenPreset.Custom)
            return true;

        var w = frame.Width;
        var h = frame.Height;
        if (w < 64 || h < 64) return false;

        var data = frame.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
        try
        {
            bool hasHorizontal = false;
            bool hasVertical = false;

            if (preset is SplitScreenPreset.Split2Horizontal or SplitScreenPreset.Split3TopWide or SplitScreenPreset.Split4Grid)
            {
                hasHorizontal = CheckHorizontalCenterSeam(data, w, h);
            }

            if (preset is SplitScreenPreset.Split2Vertical or SplitScreenPreset.Split4Grid)
            {
                hasVertical = CheckVerticalCenterSeam(data, w, h);
            }

            return preset switch
            {
                SplitScreenPreset.Split2Horizontal => hasHorizontal,
                SplitScreenPreset.Split2Vertical => hasVertical,
                SplitScreenPreset.Split3TopWide => hasHorizontal,
                SplitScreenPreset.Split4Grid => hasHorizontal || hasVertical,
                _ => true,
            };
        }
        finally
        {
            frame.UnlockBits(data);
        }
    }

    private static bool CheckHorizontalCenterSeam(BitmapData data, int w, int h)
    {
        const int samples = 64;
        Span<byte> seam = stackalloc byte[samples];
        Span<byte> above = stackalloc byte[samples];
        Span<byte> below = stackalloc byte[samples];

        int midY = h / 2;
        int offsetY = Math.Max(6, h / 60);
        int yA = Math.Max(0, midY - offsetY);
        int yB = Math.Min(h - 1, midY + offsetY);

        for (int i = 0; i < samples; i++)
        {
            int x = (int)((i + 0.5) * w / samples);
            seam[i] = SampleLuma32(data, x, midY);
            above[i] = SampleLuma32(data, x, yA);
            below[i] = SampleLuma32(data, x, yB);
        }

        return SplitScreenConfiguration.HasCleanSplitSeam(seam, above, below);
    }

    private static bool CheckVerticalCenterSeam(BitmapData data, int w, int h)
    {
        const int samples = 64;
        Span<byte> seam = stackalloc byte[samples];
        Span<byte> left = stackalloc byte[samples];
        Span<byte> right = stackalloc byte[samples];

        int midX = w / 2;
        int offsetX = Math.Max(6, w / 60);
        int xA = Math.Max(0, midX - offsetX);
        int xB = Math.Min(w - 1, midX + offsetX);

        for (int i = 0; i < samples; i++)
        {
            int y = (int)((i + 0.5) * h / samples);
            seam[i] = SampleLuma32(data, midX, y);
            left[i] = SampleLuma32(data, xA, y);
            right[i] = SampleLuma32(data, xB, y);
        }

        return SplitScreenConfiguration.HasCleanSplitSeam(seam, left, right);
    }

    private static byte SampleLuma32(BitmapData data, int x, int y)
    {
        var ptr = IntPtr.Add(data.Scan0, y * data.Stride + x * 4);
        int bgra = Marshal.ReadInt32(ptr);
        int b = bgra & 0xFF;
        int g = (bgra >> 8) & 0xFF;
        int r = (bgra >> 16) & 0xFF;
        return (byte)((r * 77 + g * 150 + b * 29) >> 8);
    }

    private void TryInitializeDxgiLocked()
    {
        DisposeDxgiLocked();
        try
        {
            using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
            if (!factory.EnumAdapters1(_activeMonitor.AdapterIndex, out var adapter).Success)
            {
                _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(5);
                return;
            }

            using (adapter)
            {
                if (!adapter.EnumOutputs(_activeMonitor.OutputIndex, out var output).Success)
                {
                    _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(5);
                    return;
                }

                using (output)
                {
                    var desc = output.Description;
                    var bounds = desc.DesktopCoordinates;
                    var w = bounds.Right - bounds.Left;
                    var h = bounds.Bottom - bounds.Top;
                    if (w <= 0 || h <= 0)
                    {
                        _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(5);
                        return;
                    }

                    var levels = new[]
                    {
                        FeatureLevel.Level_11_1,
                        FeatureLevel.Level_11_0,
                        FeatureLevel.Level_10_1,
                        FeatureLevel.Level_10_0,
                    };

                    var devRes = D3D11.D3D11CreateDevice(
                        adapter,
                        DriverType.Unknown,
                        DeviceCreationFlags.BgraSupport,
                        levels,
                        out var device,
                        out var context
                    );

                    if (devRes.Failure)
                    {
                        _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(5);
                        return;
                    }

                    _device = device;
                    _context = context;

                    using var output1 = output.QueryInterface<IDXGIOutput1>();
                    _duplication = output1.DuplicateOutput(_device);

                    var stagingDesc = new Texture2DDescription(
                        Format.B8G8R8A8_UNorm,
                        (uint)w,
                        (uint)h,
                        arraySize: 1,
                        mipLevels: 1,
                        bindFlags: BindFlags.None,
                        usage: ResourceUsage.Staging,
                        cpuAccessFlags: CpuAccessFlags.Read
                    );
                    _stagingTexture = _device.CreateTexture2D(stagingDesc);

                    EnsureDesktopBitmapLocked(w, h);
                    _dxgiActive = true;
                    _log($"Capture écran GPU DXGI active sur {_activeMonitor.DisplayName}");
                }
            }
        }
        catch (Exception ex)
        {
            DisposeDxgiLocked();
            _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(6);
            _log($"DXGI indisponible ({ex.Message}) — bascule automatique sur GDI.");
        }
    }

    private bool TryCaptureDxgiLocked()
    {
        if (_duplication is null || _context is null || _stagingTexture is null || _desktopBitmap is null)
            return false;

        try
        {
            var res = _duplication.AcquireNextFrame(16u, out _, out var desktopResource);
            if (res == Vortice.DXGI.ResultCode.WaitTimeout)
            {
                // Desktop did not change in 16ms -> reuse the latest cached frame cleanly
                return true;
            }

            if (res.Failure || desktopResource is null)
            {
                DisposeDxgiLocked();
                _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(2);
                return false;
            }

            try
            {
                using (desktopResource)
                using (var tex = desktopResource.QueryInterface<ID3D11Texture2D>())
                {
                    _context.CopyResource(_stagingTexture, tex);
                }
            }
            finally
            {
                try { _duplication.ReleaseFrame(); } catch { }
            }

            var mapRes = _context.Map(_stagingTexture, 0, MapMode.Read, Vortice.Direct3D11.MapFlags.None, out var mapped);
            if (mapRes.Failure)
                return false;

            try
            {
                var w = _desktopBitmap.Width;
                var h = _desktopBitmap.Height;
                var rowBytes = w * 4;
                if (_rowTransferBuffer is null || _rowTransferBuffer.Length < rowBytes)
                    _rowTransferBuffer = new byte[rowBytes];

                var bmpData = _desktopBitmap.LockBits(
                    new Rectangle(0, 0, w, h),
                    ImageLockMode.WriteOnly,
                    PixelFormat.Format32bppRgb
                );
                try
                {
                    var srcBase = mapped.DataPointer;
                    var dstBase = bmpData.Scan0;
                    var srcPitch = (int)mapped.RowPitch;
                    var dstStride = bmpData.Stride;

                    for (int y = 0; y < h; y++)
                    {
                        Marshal.Copy(IntPtr.Add(srcBase, y * srcPitch), _rowTransferBuffer, 0, rowBytes);
                        Marshal.Copy(_rowTransferBuffer, 0, IntPtr.Add(dstBase, y * dstStride), rowBytes);
                    }
                }
                finally
                {
                    _desktopBitmap.UnlockBits(bmpData);
                }
            }
            finally
            {
                _context.Unmap(_stagingTexture, 0);
            }

            return true;
        }
        catch
        {
            DisposeDxgiLocked();
            _nextDxgiRetryUtc = DateTime.UtcNow.AddSeconds(2);
            return false;
        }
    }

    private bool TryCaptureGdiLocked()
    {
        try
        {
            var m = _activeMonitor;
            EnsureDesktopBitmapLocked(m.Width, m.Height);
            if (_desktopBitmap is null) return false;

            using var g = Graphics.FromImage(_desktopBitmap);
            g.CopyFromScreen(m.Left, m.Top, 0, 0, new Size(m.Width, m.Height), CopyPixelOperation.SourceCopy);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private void EnsureDesktopBitmapLocked(int width, int height)
    {
        if (_desktopBitmap is not null && _desktopBitmap.Width == width && _desktopBitmap.Height == height)
            return;

        _desktopBitmap?.Dispose();
        _desktopBitmap = new Bitmap(width, height, PixelFormat.Format32bppRgb);
    }

    private void DisposeDxgiLocked()
    {
        _dxgiActive = false;
        try { _duplication?.Dispose(); } catch { }
        try { _stagingTexture?.Dispose(); } catch { }
        try { _context?.Dispose(); } catch { }
        try { _device?.Dispose(); } catch { }
        _duplication = null;
        _stagingTexture = null;
        _context = null;
        _device = null;
    }

    public void Dispose()
    {
        lock (_sync)
        {
            DisposeDxgiLocked();
            _desktopBitmap?.Dispose();
            _desktopBitmap = null;
        }
    }
}
