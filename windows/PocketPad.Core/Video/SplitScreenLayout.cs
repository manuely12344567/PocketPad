using PocketPad.Core.Protocol;

namespace PocketPad.Core.Video;

public enum SplitScreenPreset
{
    FullShared,
    Split2Horizontal,
    Split2Vertical,
    Split3TopWide,
    Split4Grid,
    Custom,
}

public readonly record struct NormalizedCropRect(float X, float Y, float Width, float Height)
{
    public static readonly NormalizedCropRect Full = new(0f, 0f, 1f, 1f);

    public NormalizedCropRect Clamped(float minSize = 0.15f)
    {
        var w = Math.Clamp(Width, minSize, 1f);
        var h = Math.Clamp(Height, minSize, 1f);
        var x = Math.Clamp(X, 0f, 1f - w);
        var y = Math.Clamp(Y, 0f, 1f - h);
        return new NormalizedCropRect(x, y, w, h);
    }

    public (int X, int Y, int Width, int Height) ToPixelRect(int screenWidth, int screenHeight)
    {
        var c = Clamped();
        var px = Math.Clamp((int)MathF.Round(c.X * screenWidth), 0, Math.Max(0, screenWidth - 2));
        var py = Math.Clamp((int)MathF.Round(c.Y * screenHeight), 0, Math.Max(0, screenHeight - 2));
        var pw = Math.Clamp((int)MathF.Round(c.Width * screenWidth), 2, screenWidth - px);
        var ph = Math.Clamp((int)MathF.Round(c.Height * screenHeight), 2, screenHeight - py);
        return (px, py, pw, ph);
    }
}

/// <summary>
/// Thread-safe split-screen layout engine mapping each player slot (1..4) to their dedicated
/// normalized screen crop rectangle — supporting 1-click presets, custom draggable boxes,
/// instant slot view swapping, Menu ↔ Split-Screen toggle, and lightweight divider detection.
/// </summary>
public sealed class SplitScreenConfiguration
{
    private readonly object _sync = new();
    private SplitScreenPreset _preset = SplitScreenPreset.FullShared;
    private bool _forceFullMenuView;
    private bool _autoDetectMenuVsSplit;
    private readonly NormalizedCropRect[] _customRects =
    {
        new(0f, 0f, 0.5f, 0.5f),
        new(0.5f, 0f, 0.5f, 0.5f),
        new(0f, 0.5f, 0.5f, 0.5f),
        new(0.5f, 0.5f, 0.5f, 0.5f),
    };

    // Maps player slot index (0..3) -> preset zone index (0..3) so players can swap views anytime
    private readonly int[] _zoneMapping = { 0, 1, 2, 3 };

    public SplitScreenPreset Preset
    {
        get { lock (_sync) return _preset; }
    }

    public bool ForceFullMenuView
    {
        get { lock (_sync) return _forceFullMenuView; }
        set { lock (_sync) _forceFullMenuView = value; }
    }

    public bool AutoDetectMenuVsSplit
    {
        get { lock (_sync) return _autoDetectMenuVsSplit; }
        set { lock (_sync) _autoDetectMenuVsSplit = value; }
    }

    public void SetPreset(SplitScreenPreset preset)
    {
        lock (_sync)
        {
            _preset = preset;
            if (preset != SplitScreenPreset.Custom)
            {
                for (int i = 0; i < PocketPadConstants.MaxPlayers; i++)
                {
                    _customRects[i] = ComputePresetBaseRect(preset, _zoneMapping[i]);
                }
            }
        }
    }

    public void SetCustomRect(int slot, NormalizedCropRect rect)
    {
        if (slot < 1 || slot > PocketPadConstants.MaxPlayers) return;
        lock (_sync)
        {
            _preset = SplitScreenPreset.Custom;
            _customRects[slot - 1] = rect.Clamped();
        }
    }

    public void ResetZoneMapping()
    {
        lock (_sync)
        {
            for (int i = 0; i < PocketPadConstants.MaxPlayers; i++)
                _zoneMapping[i] = i;
            if (_preset != SplitScreenPreset.Custom)
            {
                for (int i = 0; i < PocketPadConstants.MaxPlayers; i++)
                    _customRects[i] = ComputePresetBaseRect(_preset, _zoneMapping[i]);
            }
        }
    }

    public void SwapSlotViews(int slotA, int slotB)
    {
        if (slotA < 1 || slotA > PocketPadConstants.MaxPlayers ||
            slotB < 1 || slotB > PocketPadConstants.MaxPlayers ||
            slotA == slotB) return;

        lock (_sync)
        {
            (_zoneMapping[slotA - 1], _zoneMapping[slotB - 1]) = (_zoneMapping[slotB - 1], _zoneMapping[slotA - 1]);
            (_customRects[slotA - 1], _customRects[slotB - 1]) = (_customRects[slotB - 1], _customRects[slotA - 1]);
        }
    }

    public void RotateSlotViews(int activeCount = 4)
    {
        var count = Math.Clamp(activeCount, 2, PocketPadConstants.MaxPlayers);
        lock (_sync)
        {
            var firstMap = _zoneMapping[0];
            var firstRect = _customRects[0];
            for (int i = 0; i < count - 1; i++)
            {
                _zoneMapping[i] = _zoneMapping[i + 1];
                _customRects[i] = _customRects[i + 1];
            }
            _zoneMapping[count - 1] = firstMap;
            _customRects[count - 1] = firstRect;
        }
    }

    public NormalizedCropRect GetConfiguredRectForSlot(int slot)
    {
        if (slot < 1 || slot > PocketPadConstants.MaxPlayers)
            return NormalizedCropRect.Full;

        lock (_sync)
        {
            if (_preset == SplitScreenPreset.Custom)
                return _customRects[slot - 1];
            return ComputePresetBaseRect(_preset, _zoneMapping[slot - 1]);
        }
    }

    public NormalizedCropRect GetEffectiveRectForSlot(int slot, bool detectedSplitActive)
    {
        lock (_sync)
        {
            if (_forceFullMenuView || _preset == SplitScreenPreset.FullShared)
                return NormalizedCropRect.Full;

            if (_autoDetectMenuVsSplit && !detectedSplitActive)
                return NormalizedCropRect.Full;

            return _preset == SplitScreenPreset.Custom
                ? _customRects[Math.Clamp(slot, 1, PocketPadConstants.MaxPlayers) - 1]
                : ComputePresetBaseRect(_preset, _zoneMapping[Math.Clamp(slot, 1, PocketPadConstants.MaxPlayers) - 1]);
        }
    }

    public NormalizedCropRect[] GetAllConfiguredRects()
    {
        lock (_sync)
        {
            var result = new NormalizedCropRect[PocketPadConstants.MaxPlayers];
            for (int i = 0; i < PocketPadConstants.MaxPlayers; i++)
            {
                result[i] = _preset == SplitScreenPreset.Custom
                    ? _customRects[i]
                    : ComputePresetBaseRect(_preset, _zoneMapping[i]);
            }
            return result;
        }
    }

    public static NormalizedCropRect ComputePresetBaseRect(SplitScreenPreset preset, int zoneIndex)
    {
        return preset switch
        {
            SplitScreenPreset.FullShared => NormalizedCropRect.Full,
            SplitScreenPreset.Split2Horizontal => (zoneIndex % 2) switch
            {
                0 => new NormalizedCropRect(0f, 0f, 1f, 0.5f),
                _ => new NormalizedCropRect(0f, 0.5f, 1f, 0.5f),
            },
            SplitScreenPreset.Split2Vertical => (zoneIndex % 2) switch
            {
                0 => new NormalizedCropRect(0f, 0f, 0.5f, 1f),
                _ => new NormalizedCropRect(0.5f, 0f, 0.5f, 1f),
            },
            SplitScreenPreset.Split3TopWide => (zoneIndex % 3) switch
            {
                0 => new NormalizedCropRect(0f, 0f, 1f, 0.5f),
                1 => new NormalizedCropRect(0f, 0.5f, 0.5f, 0.5f),
                _ => new NormalizedCropRect(0.5f, 0.5f, 0.5f, 0.5f),
            },
            SplitScreenPreset.Split4Grid => (zoneIndex % 4) switch
            {
                0 => new NormalizedCropRect(0f, 0f, 0.5f, 0.5f),
                1 => new NormalizedCropRect(0.5f, 0f, 0.5f, 0.5f),
                2 => new NormalizedCropRect(0f, 0.5f, 0.5f, 0.5f),
                _ => new NormalizedCropRect(0.5f, 0.5f, 0.5f, 0.5f),
            },
            _ => NormalizedCropRect.Full,
        };
    }

    /// <summary>
    /// Fast seam detector for Auto-Split (Menu ↔ Match):
    /// inspects a 1D luminance sample along the center line (horizontal or vertical) and checks
    /// if the center seam is a clean dark/uniform split border (low variance + distinct from neighbors).
    /// </summary>
    public static bool HasCleanSplitSeam(ReadOnlySpan<byte> seamLuma, ReadOnlySpan<byte> neighborLumaA, ReadOnlySpan<byte> neighborLumaB)
    {
        if (seamLuma.Length < 16 || neighborLumaA.Length != seamLuma.Length || neighborLumaB.Length != seamLuma.Length)
            return false;

        double sum = 0;
        for (int i = 0; i < seamLuma.Length; i++)
            sum += seamLuma[i];
        double mean = sum / seamLuma.Length;

        double variance = 0;
        int contrastHits = 0;
        for (int i = 0; i < seamLuma.Length; i++)
        {
            var diff = seamLuma[i] - mean;
            variance += diff * diff;

            var deltaA = Math.Abs(seamLuma[i] - neighborLumaA[i]);
            var deltaB = Math.Abs(seamLuma[i] - neighborLumaB[i]);
            if (deltaA >= 18 || deltaB >= 18)
                contrastHits++;
        }
        variance /= seamLuma.Length;

        // A clean game split bar is uniform along its length (low variance) and contrasts with the gameplay beside it
        var uniformLine = variance < 220.0;
        var darkOrDistinct = mean < 55.0 || contrastHits >= (seamLuma.Length * 0.55);
        return uniformLine && darkOrDistinct && contrastHits >= (seamLuma.Length * 0.40);
    }
}
