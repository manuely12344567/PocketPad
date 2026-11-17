using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PocketPad.Core.Protocol;
using PocketPad.Core.Video;

namespace PocketPad.Windows.Controls;

/// <summary>
/// Interactive WPF live Split-Screen preview & custom crop editor.
/// Renders the live PC monitor preview with draggable/resizable colored player crop boxes (J1..J4).
/// </summary>
public sealed class SplitScreenEditorControl : FrameworkElement
{
    private static readonly Color[] SlotColors =
    {
        Color.FromRgb(0x38, 0xBD, 0xF8), // J1 Cyan
        Color.FromRgb(0xA8, 0x55, 0xF7), // J2 Violet
        Color.FromRgb(0x22, 0xC5, 0x5E), // J3 Vert
        Color.FromRgb(0xF9, 0x73, 0x16), // J4 Orange
    };

    private static readonly SolidColorBrush BgBrush = new(Color.FromRgb(0x09, 0x0D, 0x1A));
    private static readonly Pen FrameBorderPen = new(new SolidColorBrush(Color.FromRgb(0x26, 0x35, 0x56)), 1.2);
    private static readonly Typeface LabelTypeface = new(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.Bold, FontStretches.Normal);

    private BitmapSource? _previewImage;
    private SplitScreenPreset _preset = SplitScreenPreset.FullShared;
    private bool _fullMenuOverride;
    private NormalizedCropRect[] _rects =
    {
        NormalizedCropRect.Full,
        NormalizedCropRect.Full,
        NormalizedCropRect.Full,
        NormalizedCropRect.Full,
    };

    private Rect _imageDrawRect = new(0, 0, 1, 1);
    private int _dragSlot; // 1..4, 0 = none
    private bool _isResizing;
    private Point _dragStartMouse;
    private NormalizedCropRect _dragStartRect;

    public event Action<int, NormalizedCropRect>? CustomRectEdited;

    public SplitScreenEditorControl()
    {
        ClipToBounds = true;
        Cursor = Cursors.Arrow;
    }

    public void UpdatePreviewJpeg(byte[] jpegBytes)
    {
        try
        {
            using var ms = new MemoryStream(jpegBytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();
            _previewImage = bmp;
            InvalidateVisual();
        }
        catch
        {
            // Ignore transient decode issue
        }
    }

    public void UpdateLayoutState(SplitScreenPreset preset, bool fullMenuOverride, NormalizedCropRect[] rects)
    {
        _preset = preset;
        _fullMenuOverride = fullMenuOverride;
        if (rects.Length >= PocketPadConstants.MaxPlayers)
            _rects = rects.ToArray();
        InvalidateVisual();
    }

    private int VisibleBoxCount => _fullMenuOverride || _preset == SplitScreenPreset.FullShared
        ? 1
        : _preset switch
        {
            SplitScreenPreset.Split2Horizontal or SplitScreenPreset.Split2Vertical => 2,
            SplitScreenPreset.Split3TopWide => 3,
            _ => 4,
        };

    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);
        var w = ActualWidth;
        var h = ActualHeight;
        if (w < 20 || h < 20) return;

        dc.DrawRoundedRectangle(BgBrush, FrameBorderPen, new Rect(0, 0, w, h), 10, 10);

        // Compute centered 16:9 (or actual image aspect) preview viewport
        double aspect = _previewImage is not null && _previewImage.PixelHeight > 0
            ? (double)_previewImage.PixelWidth / _previewImage.PixelHeight
            : 16.0 / 9.0;

        double pad = 8;
        double availW = Math.Max(10, w - pad * 2);
        double availH = Math.Max(10, h - pad * 2);
        double drawW = availW;
        double drawH = drawW / aspect;
        if (drawH > availH)
        {
            drawH = availH;
            drawW = drawH * aspect;
        }
        double drawX = (w - drawW) / 2.0;
        double drawY = (h - drawH) / 2.0;
        _imageDrawRect = new Rect(drawX, drawY, drawW, drawH);

        if (_previewImage is not null)
        {
            dc.DrawImage(_previewImage, _imageDrawRect);
        }
        else
        {
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0x0E, 0x16, 0x2B)), null, _imageDrawRect);
            var hint = MakeText("Aperçu en direct de l’écran PC…", 12, Color.FromRgb(0x94, 0xA3, 0xB8));
            dc.DrawText(hint, new Point(drawX + (drawW - hint.Width) / 2, drawY + (drawH - hint.Height) / 2));
        }

        var count = VisibleBoxCount;
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;

        if (_fullMenuOverride || _preset == SplitScreenPreset.FullShared)
        {
            var pen = new Pen(new SolidColorBrush(SlotColors[0]), 2.2);
            dc.DrawRectangle(new SolidColorBrush(Color.FromArgb(24, 0x38, 0xBD, 0xF8)), pen, _imageDrawRect);
            var tagText = _fullMenuOverride
                ? "VUE MENU · TOUT L’ÉCRAN (J1–J4)"
                : "ÉCRAN PARTAGÉ · J1–J4";
            DrawBadge(dc, tagText, SlotColors[0], _imageDrawRect.X + 8, _imageDrawRect.Y + 8, dpi);
            return;
        }

        // Draw colored interactive crop rectangles for active split players
        for (int i = 0; i < count; i++)
        {
            var r = ToCanvasRect(_rects[i]);
            var col = SlotColors[i];
            var fill = new SolidColorBrush(Color.FromArgb(_dragSlot == i + 1 ? (byte)55 : (byte)28, col.R, col.G, col.B));
            var border = new Pen(new SolidColorBrush(col), _dragSlot == i + 1 ? 2.6 : 1.8);

            dc.DrawRectangle(fill, border, r);
            DrawBadge(dc, $"J{i + 1}", col, r.X + 6, r.Y + 6, dpi);

            // Resize grip handle in bottom-right corner
            var grip = new Rect(r.Right - 11, r.Bottom - 11, 11, 11);
            dc.DrawRectangle(new SolidColorBrush(col), null, grip);
        }
    }

    private Rect ToCanvasRect(NormalizedCropRect norm)
    {
        return new Rect(
            _imageDrawRect.X + norm.X * _imageDrawRect.Width,
            _imageDrawRect.Y + norm.Y * _imageDrawRect.Height,
            norm.Width * _imageDrawRect.Width,
            norm.Height * _imageDrawRect.Height
        );
    }

    private void DrawBadge(DrawingContext dc, string text, Color bgColor, double x, double y, double dpi)
    {
        var ft = new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            LabelTypeface,
            11,
            new SolidColorBrush(Color.FromRgb(0x09, 0x0D, 0x1A)),
            dpi
        );
        var pill = new Rect(x, y, ft.Width + 12, ft.Height + 4);
        dc.DrawRoundedRectangle(new SolidColorBrush(bgColor), null, pill, 5, 5);
        dc.DrawText(ft, new Point(x + 6, y + 2));
    }

    private FormattedText MakeText(string text, double size, Color color)
    {
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        return new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            LabelTypeface,
            size,
            new SolidColorBrush(color),
            dpi
        );
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonDown(e);
        if (_fullMenuOverride || _preset == SplitScreenPreset.FullShared) return;

        var pos = e.GetPosition(this);
        var count = VisibleBoxCount;

        // Check in reverse order so top-most drawn box responds first
        for (int i = count - 1; i >= 0; i--)
        {
            var r = ToCanvasRect(_rects[i]);
            var grip = new Rect(r.Right - 16, r.Bottom - 16, 18, 18);
            if (grip.Contains(pos))
            {
                _dragSlot = i + 1;
                _isResizing = true;
                _dragStartMouse = pos;
                _dragStartRect = _rects[i];
                CaptureMouse();
                e.Handled = true;
                return;
            }
            if (r.Contains(pos))
            {
                _dragSlot = i + 1;
                _isResizing = false;
                _dragStartMouse = pos;
                _dragStartRect = _rects[i];
                CaptureMouse();
                e.Handled = true;
                return;
            }
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (_dragSlot == 0 || !IsMouseCaptured || _imageDrawRect.Width < 20 || _imageDrawRect.Height < 20)
            return;

        var pos = e.GetPosition(this);
        var dx = (float)((pos.X - _dragStartMouse.X) / _imageDrawRect.Width);
        var dy = (float)((pos.Y - _dragStartMouse.Y) / _imageDrawRect.Height);

        NormalizedCropRect updated;
        if (_isResizing)
        {
            updated = new NormalizedCropRect(
                _dragStartRect.X,
                _dragStartRect.Y,
                Math.Clamp(_dragStartRect.Width + dx, 0.15f, 1f - _dragStartRect.X),
                Math.Clamp(_dragStartRect.Height + dy, 0.15f, 1f - _dragStartRect.Y)
            ).Clamped();
        }
        else
        {
            updated = new NormalizedCropRect(
                Math.Clamp(_dragStartRect.X + dx, 0f, 1f - _dragStartRect.Width),
                Math.Clamp(_dragStartRect.Y + dy, 0f, 1f - _dragStartRect.Height),
                _dragStartRect.Width,
                _dragStartRect.Height
            ).Clamped();
        }

        _rects[_dragSlot - 1] = updated;
        _preset = SplitScreenPreset.Custom;
        CustomRectEdited?.Invoke(_dragSlot, updated);
        InvalidateVisual();
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        if (_dragSlot != 0)
        {
            _dragSlot = 0;
            _isResizing = false;
            ReleaseMouseCapture();
            InvalidateVisual();
        }
    }
}
