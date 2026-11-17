using System.Windows.Controls;
using System.Windows.Media;
using PocketPad.Core.Protocol;

namespace PocketPad.Windows.Controls;

public partial class GamepadVisualizer : UserControl
{
    private static readonly SolidColorBrush IdleBg = new(Color.FromRgb(0x1B, 0x27, 0x42));
    private static readonly SolidColorBrush ActiveCyan = new(Color.FromRgb(0x0E, 0xA5, 0xE9));
    private static readonly SolidColorBrush ActiveGreen = new(Color.FromRgb(0x16, 0xA3, 0x4A));
    private static readonly SolidColorBrush ActiveRose = new(Color.FromRgb(0xE1, 0x1D, 0x48));
    private static readonly SolidColorBrush ActiveYellow = new(Color.FromRgb(0xCA, 0x8A, 0x04));
    private static readonly SolidColorBrush ActiveViolet = new(Color.FromRgb(0x7C, 0x3A, 0xED));
    private static readonly SolidColorBrush IdleKnob = new(Color.FromRgb(0x25, 0x36, 0x5A));

    public GamepadVisualizer()
    {
        InitializeComponent();
    }

    public void UpdateState(in GamepadState state)
    {
        SetActive(BtnA, state.HasButton(GamepadButtons.A), ActiveGreen);
        SetActive(BtnB, state.HasButton(GamepadButtons.B), ActiveRose);
        SetActive(BtnX, state.HasButton(GamepadButtons.X), ActiveCyan);
        SetActive(BtnY, state.HasButton(GamepadButtons.Y), ActiveYellow);

        SetActive(BtnLb, state.HasButton(GamepadButtons.LeftShoulder), ActiveCyan);
        SetActive(BtnRb, state.HasButton(GamepadButtons.RightShoulder), ActiveViolet);
        SetActive(BtnBack, state.HasButton(GamepadButtons.Back), ActiveCyan);
        SetActive(BtnStart, state.HasButton(GamepadButtons.Start), ActiveCyan);
        SetActive(BtnGuide, state.HasButton(GamepadButtons.Guide), ActiveCyan);

        SetActive(BtnDpadUp, state.HasButton(GamepadButtons.DpadUp), ActiveCyan);
        SetActive(BtnDpadDown, state.HasButton(GamepadButtons.DpadDown), ActiveCyan);
        SetActive(BtnDpadLeft, state.HasButton(GamepadButtons.DpadLeft), ActiveCyan);
        SetActive(BtnDpadRight, state.HasButton(GamepadButtons.DpadRight), ActiveCyan);

        // Triggers (max inner width = 82)
        LtFill.Width = (state.LeftTrigger / 255.0) * 82.0;
        RtFill.Width = (state.RightTrigger / 255.0) * 82.0;

        // Left stick center = (88, 102), travel radius = 20px
        var lx = (state.LeftStickX / 32767.0) * 20.0;
        var ly = -(state.LeftStickY / 32767.0) * 20.0;
        Canvas.SetLeft(LeftStickKnob, 88.0 + lx);
        Canvas.SetTop(LeftStickKnob, 102.0 + ly);
        LeftStickKnob.Fill = state.HasButton(GamepadButtons.LeftThumb) ? ActiveCyan : IdleKnob;

        // Right stick center = (272, 156), travel radius = 20px
        var rx = (state.RightStickX / 32767.0) * 20.0;
        var ry = -(state.RightStickY / 32767.0) * 20.0;
        Canvas.SetLeft(RightStickKnob, 272.0 + rx);
        Canvas.SetTop(RightStickKnob, 156.0 + ry);
        RightStickKnob.Fill = state.HasButton(GamepadButtons.RightThumb) ? ActiveViolet : IdleKnob;
    }

    private static void SetActive(Border border, bool active, Brush activeBrush)
    {
        border.Background = active ? activeBrush : IdleBg;
    }
}
