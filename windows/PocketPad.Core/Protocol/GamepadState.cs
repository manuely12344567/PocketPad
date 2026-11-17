namespace PocketPad.Core.Protocol;

public static class PocketPadConstants
{
    public const string AppVersion = "1.0.0";
    public const int TcpPort = 27183;
    public const int UdpDiscoveryPort = 27184;
    public const string DiscoveryRequest = "POCKETPAD_DISCOVER_V1";
    public const string DiscoveryResponsePrefix = "POCKETPAD_SERVER_V1";

    /// <summary>
    /// Dedicated Bluetooth RFCOMM Service Class UUID (avoids conflicts with Windows COM SerialPort profiles).
    /// Ends in ASCII "pocket" (706f636b6574).
    /// </summary>
    public static readonly Guid BluetoothServiceUuid = new("e8f1a4b0-7c2d-4e9a-9f31-706f636b6574");

    public const byte Magic0 = 0x50; // 'P'
    public const byte Magic1 = 0x50; // 'P'
}

[Flags]
public enum GamepadButtons : ushort
{
    None = 0x0000,
    DpadUp = 0x0001,
    DpadDown = 0x0002,
    DpadLeft = 0x0004,
    DpadRight = 0x0008,
    Start = 0x0010,
    Back = 0x0020,
    LeftThumb = 0x0040,
    RightThumb = 0x0080,
    LeftShoulder = 0x0100,
    RightShoulder = 0x0200,
    Guide = 0x0400,
    A = 0x1000,
    B = 0x2000,
    X = 0x4000,
    Y = 0x8000,
}

public readonly record struct GamepadState(
    GamepadButtons Buttons,
    byte LeftTrigger,
    byte RightTrigger,
    short LeftStickX,
    short LeftStickY,
    short RightStickX,
    short RightStickY
)
{
    public static GamepadState Neutral => new(GamepadButtons.None, 0, 0, 0, 0, 0, 0);

    public bool HasButton(GamepadButtons button) => (Buttons & button) == button;
}
