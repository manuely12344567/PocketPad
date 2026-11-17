using PocketPad.Core.Protocol;
using Xunit;

namespace PocketPad.Tests;

public class PacketCodecTests
{
    [Fact]
    public void GamepadState_RoundTrip_PreservesAllAxesTriggersAndButtons()
    {
        var original = new GamepadState(
            Buttons: GamepadButtons.A | GamepadButtons.RightShoulder | GamepadButtons.DpadUp | GamepadButtons.Start,
            LeftTrigger: 185,
            RightTrigger: 255,
            LeftStickX: -32768,
            LeftStickY: 32767,
            RightStickX: -12345,
            RightStickY: 9876
        );

        var frame = PacketCodec.EncodeGamepadState(original);
        Assert.Equal(16, frame.Length);
        Assert.Equal((byte)'P', frame[0]);
        Assert.Equal((byte)'P', frame[1]);
        Assert.Equal((byte)PacketType.GamepadState, frame[2]);
        Assert.Equal(12, frame[3]);

        var reader = new FrameStreamReader();
        DecodedPacket? decoded = null;
        reader.Feed(frame, p => decoded = p);

        Assert.NotNull(decoded);
        Assert.Equal(PacketType.GamepadState, decoded.Value.Type);
        Assert.True(PacketCodec.TryDecodeGamepadState(decoded.Value.Payload, out var parsed));
        Assert.Equal(original, parsed);
    }

    [Fact]
    public void FrameStreamReader_HandlesFragmentedChunksAndGarbagePrefix()
    {
        var frame1 = PacketCodec.EncodeKeyboardCommand(new KeyboardCommand(0x57, KeyboardAction.KeyDown)); // 'W'
        var frame2 = PacketCodec.EncodeMouseCommand(new MouseCommand(-24, 15, -120, MouseButtonAction.LeftDown));
        var frame3 = PacketCodec.EncodeRumble(210, 95);

        var stream = new List<byte> { 0x00, 0xFF, 0x12, 0x50, 0x99 }; // noisy bytes before real frame
        stream.AddRange(frame1);
        stream.AddRange(frame2);
        stream.AddRange(frame3);

        var reader = new FrameStreamReader();
        var received = new List<DecodedPacket>();

        // Feed byte by byte to verify arbitrary split handling
        var raw = stream.ToArray();
        for (int i = 0; i < raw.Length; i++)
        {
            reader.Feed(raw.AsSpan(i, 1), p => received.Add(p));
        }

        Assert.Equal(3, received.Count);

        Assert.True(PacketCodec.TryDecodeKeyboardCommand(received[0].Payload, out var kb));
        Assert.Equal((ushort)0x57, kb.VirtualKeyCode);
        Assert.Equal(KeyboardAction.KeyDown, kb.Action);

        Assert.True(PacketCodec.TryDecodeMouseCommand(received[1].Payload, out var mouse));
        Assert.Equal((short)-24, mouse.DeltaX);
        Assert.Equal((short)15, mouse.DeltaY);
        Assert.Equal((short)-120, mouse.WheelDelta);
        Assert.Equal(MouseButtonAction.LeftDown, mouse.ButtonAction);

        Assert.True(PacketCodec.TryDecodeRumble(received[2].Payload, out var rumble));
        Assert.Equal((byte)210, rumble.LargeMotor);
        Assert.Equal((byte)95, rumble.SmallMotor);
    }

    [Fact]
    public void PingPong_RoundTrip_PreservesInt64Timestamp()
    {
        const long ts = 1794820123456L;
        var pong = PacketCodec.EncodePong(ts);
        var reader = new FrameStreamReader();
        long echoed = 0;
        reader.Feed(pong, p =>
        {
            Assert.Equal(PacketType.Pong, p.Type);
            Assert.True(PacketCodec.TryDecodeTimestamp(p.Payload, out echoed));
        });

        Assert.Equal(ts, echoed);
    }

    [Fact]
    public void HelloAndServerInfo_EncodeDecodeProperly()
    {
        var serverFrame = PacketCodec.EncodeServerInfo(
            new ServerInfoPayload("GAMING-PC", true, "auto", PocketPadConstants.AppVersion)
        );
        var reader = new FrameStreamReader();
        DecodedPacket? pkt = null;
        reader.Feed(serverFrame, p => pkt = p);

        Assert.NotNull(pkt);
        Assert.Equal(PacketType.ServerInfo, pkt.Value.Type);
        var json = System.Text.Encoding.UTF8.GetString(pkt.Value.Payload);
        Assert.Contains("GAMING-PC", json);
        Assert.Contains("\"vigemReady\":true", json, StringComparison.OrdinalIgnoreCase);
    }
}
