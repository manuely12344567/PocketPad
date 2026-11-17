package com.pocketpad.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object PocketPadConstants {
    const val APP_VERSION = "1.0.0"
    const val TCP_PORT = 27183
    const val UDP_DISCOVERY_PORT = 27184
    const val DISCOVERY_REQUEST = "POCKETPAD_DISCOVER_V1"
    const val DISCOVERY_RESPONSE_PREFIX = "POCKETPAD_SERVER_V1"

    val BLUETOOTH_SERVICE_UUID: UUID = UUID.fromString("e8f1a4b0-7c2d-4e9a-9f31-706f636b6574")

    const val MAGIC_0: Byte = 0x50 // 'P'
    const val MAGIC_1: Byte = 0x50 // 'P'
}

object GamepadButtons {
    const val NONE = 0x0000
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val GUIDE = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

object PacketType {
    const val GAMEPAD_STATE: Byte = 0x01
    const val KEYBOARD_EVENT: Byte = 0x02
    const val MOUSE_EVENT: Byte = 0x03
    const val HELLO: Byte = 0x04
    const val PING: Byte = 0x05
    const val PONG: Byte = 0x06
    const val RUMBLE: Byte = 0x07
    const val SERVER_INFO: Byte = 0x08
    const val RESET_INPUTS: Byte = 0x09
}

object KeyboardAction {
    const val KEY_UP: Byte = 0
    const val KEY_DOWN: Byte = 1
    const val TAP: Byte = 2
}

object MouseButtonAction {
    const val MOVE_ONLY: Byte = 0
    const val LEFT_DOWN: Byte = 1
    const val LEFT_UP: Byte = 2
    const val RIGHT_DOWN: Byte = 3
    const val RIGHT_UP: Byte = 4
    const val MIDDLE_DOWN: Byte = 5
    const val MIDDLE_UP: Byte = 6
    const val LEFT_CLICK: Byte = 7
    const val RIGHT_CLICK: Byte = 8
}

data class GamepadState(
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
) {
    companion object {
        val NEUTRAL = GamepadState()
    }
}

data class DecodedPacket(
    val type: Byte,
    val payload: ByteArray,
)

data class RumbleCommand(
    val largeMotor: Int,
    val smallMotor: Int,
)

object PacketCodec {
    fun encodeFrame(type: Byte, payload: ByteArray): ByteArray {
        require(payload.size <= 255) { "Payload max length is 255 bytes" }
        val frame = ByteArray(4 + payload.size)
        frame[0] = PocketPadConstants.MAGIC_0
        frame[1] = PocketPadConstants.MAGIC_1
        frame[2] = type
        frame[3] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    fun encodeGamepadState(state: GamepadState): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort((state.buttons and 0xFFFF).toShort())
        buf.put(state.leftTrigger.coerceIn(0, 255).toByte())
        buf.put(state.rightTrigger.coerceIn(0, 255).toByte())
        buf.putShort(state.leftStickX.coerceIn(-32768, 32767).toShort())
        buf.putShort(state.leftStickY.coerceIn(-32768, 32767).toShort())
        buf.putShort(state.rightStickX.coerceIn(-32768, 32767).toShort())
        buf.putShort(state.rightStickY.coerceIn(-32768, 32767).toShort())
        return encodeFrame(PacketType.GAMEPAD_STATE, buf.array())
    }

    fun decodeGamepadState(payload: ByteArray): GamepadState? {
        if (payload.size < 12) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return GamepadState(
            buttons = buf.short.toInt() and 0xFFFF,
            leftTrigger = buf.get().toInt() and 0xFF,
            rightTrigger = buf.get().toInt() and 0xFF,
            leftStickX = buf.short.toInt(),
            leftStickY = buf.short.toInt(),
            rightStickX = buf.short.toInt(),
            rightStickY = buf.short.toInt(),
        )
    }

    fun encodeKeyboardEvent(vkCode: Int, action: Byte): ByteArray {
        val buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort((vkCode and 0xFFFF).toShort())
        buf.put(action)
        return encodeFrame(PacketType.KEYBOARD_EVENT, buf.array())
    }

    fun encodeMouseEvent(dx: Int, dy: Int, wheel: Int, buttonAction: Byte): ByteArray {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(dx.coerceIn(-32768, 32767).toShort())
        buf.putShort(dy.coerceIn(-32768, 32767).toShort())
        buf.putShort(wheel.coerceIn(-32768, 32767).toShort())
        buf.put(buttonAction)
        return encodeFrame(PacketType.MOUSE_EVENT, buf.array())
    }

    fun encodePing(timestampMs: Long): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(timestampMs)
        return encodeFrame(PacketType.PING, buf.array())
    }

    fun decodeTimestamp(payload: ByteArray): Long? {
        if (payload.size < 8) return null
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun decodeRumble(payload: ByteArray): RumbleCommand? {
        if (payload.size < 2) return null
        return RumbleCommand(
            largeMotor = payload[0].toInt() and 0xFF,
            smallMotor = payload[1].toInt() and 0xFF,
        )
    }

    fun encodeHello(name: String, profile: String, transport: String): ByteArray {
        val safeName = name.replace("\"", "'").take(40)
        val safeProfile = profile.replace("\"", "'").take(40)
        val safeTransport = transport.replace("\"", "'").take(20)
        val json = """{"name":"$safeName","profile":"$safeProfile","transport":"$safeTransport"}"""
        return encodeFrame(PacketType.HELLO, json.toByteArray(Charsets.UTF_8))
    }

    fun encodeResetInputs(): ByteArray = encodeFrame(PacketType.RESET_INPUTS, ByteArray(0))
}

class FrameStreamReader {
    private val buffer = ByteArray(4096)
    private var length = 0

    fun feed(data: ByteArray, count: Int, onPacket: (DecodedPacket) -> Unit) {
        var offset = 0
        while (offset < count) {
            var space = buffer.size - length
            if (space <= 0) {
                length = 0
                space = buffer.size
            }
            val toCopy = minOf(space, count - offset)
            System.arraycopy(data, offset, buffer, length, toCopy)
            length += toCopy
            offset += toCopy
            drain(onPacket)
        }
    }

    private fun drain(onPacket: (DecodedPacket) -> Unit) {
        var readPos = 0
        while (length - readPos >= 4) {
            if (buffer[readPos] != PocketPadConstants.MAGIC_0 ||
                buffer[readPos + 1] != PocketPadConstants.MAGIC_1
            ) {
                readPos++
                continue
            }
            val type = buffer[readPos + 2]
            val payloadLen = buffer[readPos + 3].toInt() and 0xFF
            val totalLen = 4 + payloadLen
            if (length - readPos < totalLen) break

            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                System.arraycopy(buffer, readPos + 4, payload, 0, payloadLen)
            }
            onPacket(DecodedPacket(type, payload))
            readPos += totalLen
        }

        if (readPos > 0) {
            val remaining = length - readPos
            if (remaining > 0) {
                System.arraycopy(buffer, readPos, buffer, 0, remaining)
            }
            length = remaining
        }
    }
}
