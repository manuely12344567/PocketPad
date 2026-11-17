using System.Buffers.Binary;
using System.Text;
using System.Text.Json;

namespace PocketPad.Core.Protocol;

/// <summary>
/// Zero-overhead Little-Endian framed binary encoder/decoder matching Android's PacketCodec.kt 1:1.
/// </summary>
public static class PacketCodec
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    public static byte[] EncodeFrame(PacketType type, ReadOnlySpan<byte> payload)
    {
        if (payload.Length > 255)
            throw new ArgumentOutOfRangeException(nameof(payload), "Payload max length is 255 bytes.");

        var frame = new byte[4 + payload.Length];
        frame[0] = PocketPadConstants.Magic0;
        frame[1] = PocketPadConstants.Magic1;
        frame[2] = (byte)type;
        frame[3] = (byte)payload.Length;
        payload.CopyTo(frame.AsSpan(4));
        return frame;
    }

    public static byte[] EncodeGamepadState(in GamepadState state)
    {
        Span<byte> payload = stackalloc byte[12];
        BinaryPrimitives.WriteUInt16LittleEndian(payload.Slice(0, 2), (ushort)state.Buttons);
        payload[2] = state.LeftTrigger;
        payload[3] = state.RightTrigger;
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(4, 2), state.LeftStickX);
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(6, 2), state.LeftStickY);
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(8, 2), state.RightStickX);
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(10, 2), state.RightStickY);
        return EncodeFrame(PacketType.GamepadState, payload);
    }

    public static bool TryDecodeGamepadState(ReadOnlySpan<byte> payload, out GamepadState state)
    {
        if (payload.Length < 12)
        {
            state = GamepadState.Neutral;
            return false;
        }

        state = new GamepadState(
            Buttons: (GamepadButtons)BinaryPrimitives.ReadUInt16LittleEndian(payload.Slice(0, 2)),
            LeftTrigger: payload[2],
            RightTrigger: payload[3],
            LeftStickX: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(4, 2)),
            LeftStickY: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(6, 2)),
            RightStickX: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(8, 2)),
            RightStickY: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(10, 2))
        );
        return true;
    }

    public static byte[] EncodeKeyboardCommand(in KeyboardCommand cmd)
    {
        Span<byte> payload = stackalloc byte[3];
        BinaryPrimitives.WriteUInt16LittleEndian(payload.Slice(0, 2), cmd.VirtualKeyCode);
        payload[2] = (byte)cmd.Action;
        return EncodeFrame(PacketType.KeyboardEvent, payload);
    }

    public static bool TryDecodeKeyboardCommand(ReadOnlySpan<byte> payload, out KeyboardCommand cmd)
    {
        if (payload.Length < 3)
        {
            cmd = default;
            return false;
        }

        cmd = new KeyboardCommand(
            VirtualKeyCode: BinaryPrimitives.ReadUInt16LittleEndian(payload.Slice(0, 2)),
            Action: (KeyboardAction)payload[2]
        );
        return true;
    }

    public static byte[] EncodeMouseCommand(in MouseCommand cmd)
    {
        Span<byte> payload = stackalloc byte[7];
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(0, 2), cmd.DeltaX);
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(2, 2), cmd.DeltaY);
        BinaryPrimitives.WriteInt16LittleEndian(payload.Slice(4, 2), cmd.WheelDelta);
        payload[6] = (byte)cmd.ButtonAction;
        return EncodeFrame(PacketType.MouseEvent, payload);
    }

    public static bool TryDecodeMouseCommand(ReadOnlySpan<byte> payload, out MouseCommand cmd)
    {
        if (payload.Length < 7)
        {
            cmd = default;
            return false;
        }

        cmd = new MouseCommand(
            DeltaX: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(0, 2)),
            DeltaY: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(2, 2)),
            WheelDelta: BinaryPrimitives.ReadInt16LittleEndian(payload.Slice(4, 2)),
            ButtonAction: (MouseButtonAction)payload[6]
        );
        return true;
    }

    public static byte[] EncodePing(long timestampMs)
    {
        Span<byte> payload = stackalloc byte[8];
        BinaryPrimitives.WriteInt64LittleEndian(payload, timestampMs);
        return EncodeFrame(PacketType.Ping, payload);
    }

    public static byte[] EncodePong(long timestampMs)
    {
        Span<byte> payload = stackalloc byte[8];
        BinaryPrimitives.WriteInt64LittleEndian(payload, timestampMs);
        return EncodeFrame(PacketType.Pong, payload);
    }

    public static bool TryDecodeTimestamp(ReadOnlySpan<byte> payload, out long timestampMs)
    {
        if (payload.Length < 8)
        {
            timestampMs = 0;
            return false;
        }

        timestampMs = BinaryPrimitives.ReadInt64LittleEndian(payload.Slice(0, 8));
        return true;
    }

    public static byte[] EncodeRumble(byte largeMotor, byte smallMotor)
    {
        Span<byte> payload = stackalloc byte[2];
        payload[0] = largeMotor;
        payload[1] = smallMotor;
        return EncodeFrame(PacketType.Rumble, payload);
    }

    public static bool TryDecodeRumble(ReadOnlySpan<byte> payload, out RumbleCommand rumble)
    {
        if (payload.Length < 2)
        {
            rumble = default;
            return false;
        }

        rumble = new RumbleCommand(payload[0], payload[1]);
        return true;
    }

    public static byte[] EncodeServerInfo(ServerInfoPayload info)
    {
        var json = JsonSerializer.Serialize(info, JsonOptions);
        var bytes = Encoding.UTF8.GetBytes(json);
        if (bytes.Length > 248)
        {
            var truncated = info with
            {
                PcName = info.PcName.Length > 14 ? info.PcName[..14] : info.PcName,
                SplitPreset = info.SplitPreset.Length > 10 ? info.SplitPreset[..10] : info.SplitPreset,
            };
            bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(truncated, JsonOptions));
        }
        return EncodeFrame(PacketType.ServerInfo, bytes);
    }

    public static ClientHelloInfo DecodeHello(ReadOnlySpan<byte> payload)
    {
        try
        {
            var json = Encoding.UTF8.GetString(payload);
            var parsed = JsonSerializer.Deserialize<ClientHelloInfo>(json, JsonOptions);
            if (parsed is not null)
            {
                return new ClientHelloInfo(
                    string.IsNullOrWhiteSpace(parsed.Name) ? "Téléphone Android" : parsed.Name,
                    string.IsNullOrWhiteSpace(parsed.Profile) ? "Manette" : parsed.Profile,
                    string.IsNullOrWhiteSpace(parsed.Transport) ? "usb" : parsed.Transport,
                    string.IsNullOrWhiteSpace(parsed.ClientId) ? "" : parsed.ClientId.Trim(),
                    parsed.PreferredSlot is >= 1 and <= PocketPadConstants.MaxPlayers ? parsed.PreferredSlot : 0
                );
            }
        }
        catch
        {
            // Fallback safe defaults
        }

        return new ClientHelloInfo("Téléphone Android", "Manette", "usb");
    }

    public static byte[] EncodeResetInputs() => EncodeFrame(PacketType.ResetInputs, ReadOnlySpan<byte>.Empty);
}

/// <summary>
/// Stateful streaming reader for TCP and Bluetooth RFCOMM streams.
/// Handles arbitrary chunk boundaries and automatically resynchronizes on magic bytes 0x50 0x50.
/// </summary>
public sealed class FrameStreamReader
{
    private readonly byte[] _buffer = new byte[4096];
    private int _length;

    public void Feed(ReadOnlySpan<byte> incoming, Action<DecodedPacket> onPacket)
    {
        var offset = 0;
        while (offset < incoming.Length)
        {
            var space = _buffer.Length - _length;
            if (space <= 0)
            {
                // Buffer overflow protection: drop old data and resync
                _length = 0;
                space = _buffer.Length;
            }

            var toCopy = Math.Min(space, incoming.Length - offset);
            incoming.Slice(offset, toCopy).CopyTo(_buffer.AsSpan(_length, toCopy));
            _length += toCopy;
            offset += toCopy;

            DrainFrames(onPacket);
        }
    }

    private void DrainFrames(Action<DecodedPacket> onPacket)
    {
        var readPos = 0;
        while (_length - readPos >= 4)
        {
            // Resync to magic bytes 'P','P'
            if (_buffer[readPos] != PocketPadConstants.Magic0 ||
                _buffer[readPos + 1] != PocketPadConstants.Magic1)
            {
                readPos++;
                continue;
            }

            var typeByte = _buffer[readPos + 2];
            var payloadLen = _buffer[readPos + 3];
            var totalLen = 4 + payloadLen;

            if (_length - readPos < totalLen)
            {
                // Wait for remaining bytes
                break;
            }

            var payload = new byte[payloadLen];
            if (payloadLen > 0)
            {
                Buffer.BlockCopy(_buffer, readPos + 4, payload, 0, payloadLen);
            }

            onPacket(new DecodedPacket((PacketType)typeByte, payload));
            readPos += totalLen;
        }

        if (readPos > 0)
        {
            var remaining = _length - readPos;
            if (remaining > 0)
            {
                Buffer.BlockCopy(_buffer, readPos, _buffer, 0, remaining);
            }
            _length = remaining;
        }
    }
}
