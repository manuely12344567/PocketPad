using PocketPad.Core.Multiplayer;
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
            new ServerInfoPayload("GAMING-PC", true, "auto", PocketPadConstants.AppVersion, PlayerSlot: 2, MaxPlayers: 4, ConnectedPlayers: 3, SlotColor: "#A855F7")
        );
        var reader = new FrameStreamReader();
        DecodedPacket? pkt = null;
        reader.Feed(serverFrame, p => pkt = p);

        Assert.NotNull(pkt);
        Assert.Equal(PacketType.ServerInfo, pkt.Value.Type);
        var json = System.Text.Encoding.UTF8.GetString(pkt.Value.Payload);
        Assert.Contains("GAMING-PC", json);
        Assert.Contains("\"vigemReady\":true", json, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("\"playerSlot\":2", json, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("\"connectedPlayers\":3", json, StringComparison.OrdinalIgnoreCase);

        var helloBytes = System.Text.Encoding.UTF8.GetBytes(
            """{"name":"Galaxy S24","profile":"Xbox 360","transport":"bluetooth","clientId":"dev-xyz","preferredSlot":3}"""
        );
        var hello = PacketCodec.DecodeHello(helloBytes);
        Assert.Equal("Galaxy S24", hello.Name);
        Assert.Equal("dev-xyz", hello.ClientId);
        Assert.Equal(3, hello.PreferredSlot);
    }

    private sealed class FakeSession
    {
        public string ClientId { get; set; } = "";
    }

    [Fact]
    public void PlayerSlotRegistry_Supports4SimultaneousPlayers_ReconnectAndSwap()
    {
        var reg = new PlayerSlotRegistry<FakeSession>(4);

        var p1 = new FakeSession { ClientId = "phone-usb" };
        var p2 = new FakeSession { ClientId = "phone-bt" };
        var p3 = new FakeSession { ClientId = "phone-wifi" };
        var p4 = new FakeSession { ClientId = "phone-usb2" };
        var p5 = new FakeSession { ClientId = "phone-extra" };

        Assert.Equal(1, reg.AllocateInitialSlot(p1));
        Assert.Equal(2, reg.AllocateInitialSlot(p2));
        Assert.Equal(3, reg.AllocateInitialSlot(p3));
        Assert.Equal(4, reg.AllocateInitialSlot(p4));
        Assert.Equal(0, reg.AllocateInitialSlot(p5)); // 5th rejected cleanly when room full
        Assert.Equal(4, reg.ConnectedCount);

        // Register identities
        Assert.Equal(1, reg.ApplyIdentity(p1, p1.ClientId, 0, s => s.ClientId, out var ev1));
        Assert.Null(ev1);
        Assert.Equal(2, reg.ApplyIdentity(p2, p2.ClientId, 0, s => s.ClientId, out var ev2));
        Assert.Null(ev2);

        // Player 2 drops and reconnects -> reclaims slot 2 cleanly!
        Assert.Equal(2, reg.Release(p2));
        Assert.Equal(3, reg.ConnectedCount);

        var p2Reconnect = new FakeSession { ClientId = "phone-bt" };
        var initial = reg.AllocateInitialSlot(p2Reconnect);
        Assert.Equal(2, initial);
        var finalSlot = reg.ApplyIdentity(p2Reconnect, p2Reconnect.ClientId, 0, s => s.ClientId, out var evRec);
        Assert.Equal(2, finalSlot);
        Assert.Null(evRec);

        // Swap Player 1 <-> Player 3
        Assert.True(reg.TryMoveOrSwap(1, 3, out var at1, out var at3));
        Assert.Same(p3, at1);
        Assert.Same(p1, at3);
        Assert.Equal(3, reg.GetSlotOf(p1));
        Assert.Equal(1, reg.GetSlotOf(p3));
    }

    [Fact]
    public void PlayerSlotRegistry_PreferredSlotAndDuplicateClientEviction()
    {
        var reg = new PlayerSlotRegistry<FakeSession>(4);

        var p1 = new FakeSession { ClientId = "phone-a" };
        Assert.Equal(1, reg.AllocateInitialSlot(p1));
        // Phone A explicitly requests slot 3 -> moves from 1 to 3
        Assert.Equal(3, reg.ApplyIdentity(p1, p1.ClientId, preferredSlot: 3, s => s.ClientId, out var ev0));
        Assert.Null(ev0);
        Assert.Null(reg.GetAtSlot(1));
        Assert.Same(p1, reg.GetAtSlot(3));

        // Same phone reconnects before old TCP timeout -> evicts stale session and keeps slot 3!
        var p1Fresh = new FakeSession { ClientId = "phone-a" };
        Assert.Equal(1, reg.AllocateInitialSlot(p1Fresh));
        var assigned = reg.ApplyIdentity(p1Fresh, p1Fresh.ClientId, preferredSlot: 0, s => s.ClientId, out var evicted);
        Assert.Equal(3, assigned);
        Assert.Same(p1, evicted);
        Assert.Null(reg.GetAtSlot(1));
        Assert.Same(p1Fresh, reg.GetAtSlot(3));
    }
}
