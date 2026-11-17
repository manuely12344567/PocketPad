namespace PocketPad.Core.Protocol;

public enum PacketType : byte
{
    GamepadState = 0x01,
    KeyboardEvent = 0x02,
    MouseEvent = 0x03,
    Hello = 0x04,
    Ping = 0x05,
    Pong = 0x06,
    Rumble = 0x07,
    ServerInfo = 0x08,
    ResetInputs = 0x09,
}

public enum KeyboardAction : byte
{
    KeyUp = 0,
    KeyDown = 1,
    Tap = 2,
}

public enum MouseButtonAction : byte
{
    MoveOnly = 0,
    LeftDown = 1,
    LeftUp = 2,
    RightDown = 3,
    RightUp = 4,
    MiddleDown = 5,
    MiddleUp = 6,
    LeftClick = 7,
    RightClick = 8,
}

public readonly record struct KeyboardCommand(ushort VirtualKeyCode, KeyboardAction Action);

public readonly record struct MouseCommand(short DeltaX, short DeltaY, short WheelDelta, MouseButtonAction ButtonAction);

public readonly record struct RumbleCommand(byte LargeMotor, byte SmallMotor);

public sealed record ClientHelloInfo(
    string Name,
    string Profile,
    string Transport,
    string ClientId = "",
    int PreferredSlot = 0
);

public sealed record ServerInfoPayload(
    string PcName,
    bool ViGemReady,
    string Mode,
    string Version,
    int PlayerSlot = 1,
    int MaxPlayers = PocketPadConstants.MaxPlayers,
    int ConnectedPlayers = 1,
    string SlotColor = "#38BDF8",
    int VideoPort = PocketPadConstants.VideoTcpPort,
    bool VideoEnabled = true,
    string SplitPreset = "FullShared",
    string LanIp = ""
);

public readonly record struct DecodedPacket(PacketType Type, byte[] Payload);
