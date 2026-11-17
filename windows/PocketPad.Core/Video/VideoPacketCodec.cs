using System.Buffers.Binary;
using PocketPad.Core.Protocol;

namespace PocketPad.Core.Video;

public enum VideoClientCommand : byte
{
    Subscribe = 0x01,
    Pause = 0x02,
    Resume = 0x03,
}

public readonly record struct VideoSubscribePacket(
    VideoClientCommand Command,
    int Slot,
    ushort MaxWidth,
    ushort MaxHeight,
    byte Quality
);

/// <summary>
/// Zero-overhead binary wire codec for PocketPad's dedicated video channel (TCP port 27185).
/// Keeps live screen frames completely separate from gamepad inputs (TCP port 27183).
/// </summary>
public static class VideoPacketCodec
{
    public const int ClientPacketSize = 10;
    public const int ServerHeaderSize = 12;

    public const byte FlagFullMenuView = 0x01;
    public const byte FlagAutoSplitActive = 0x02;

    public static bool TryDecodeClientPacket(ReadOnlySpan<byte> bytes, out VideoSubscribePacket packet)
    {
        packet = default;
        if (bytes.Length < ClientPacketSize) return false;
        if (bytes[0] != PocketPadConstants.VideoMagic0 || bytes[1] != PocketPadConstants.VideoMagic1) return false;
        if (bytes[2] != 0x01) return false;

        var cmd = (VideoClientCommand)bytes[3];
        var slot = Math.Clamp((int)bytes[4], 1, PocketPadConstants.MaxPlayers);
        var maxW = Math.Clamp(BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(5, 2)), (ushort)240, (ushort)1280);
        var maxH = Math.Clamp(BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(7, 2)), (ushort)136, (ushort)720);
        var quality = Math.Clamp(bytes[9], (byte)30, (byte)85);

        packet = new VideoSubscribePacket(cmd, slot, maxW, maxH, quality);
        return true;
    }

    public static byte[] EncodeClientPacket(VideoSubscribePacket packet)
    {
        var buf = new byte[ClientPacketSize];
        buf[0] = PocketPadConstants.VideoMagic0;
        buf[1] = PocketPadConstants.VideoMagic1;
        buf[2] = 0x01;
        buf[3] = (byte)packet.Command;
        buf[4] = (byte)Math.Clamp(packet.Slot, 1, PocketPadConstants.MaxPlayers);
        BinaryPrimitives.WriteUInt16LittleEndian(buf.AsSpan(5, 2), packet.MaxWidth);
        BinaryPrimitives.WriteUInt16LittleEndian(buf.AsSpan(7, 2), packet.MaxHeight);
        buf[9] = Math.Clamp(packet.Quality, (byte)30, (byte)85);
        return buf;
    }

    public static void WriteFrameHeader(
        Span<byte> header,
        int slot,
        byte flags,
        ushort width,
        ushort height,
        int jpegPayloadLength)
    {
        header[0] = PocketPadConstants.VideoMagic0;
        header[1] = PocketPadConstants.VideoMagic1;
        header[2] = (byte)Math.Clamp(slot, 1, PocketPadConstants.MaxPlayers);
        header[3] = flags;
        BinaryPrimitives.WriteUInt16LittleEndian(header.Slice(4, 2), width);
        BinaryPrimitives.WriteUInt16LittleEndian(header.Slice(6, 2), height);
        BinaryPrimitives.WriteUInt32LittleEndian(header.Slice(8, 4), (uint)jpegPayloadLength);
    }
}
