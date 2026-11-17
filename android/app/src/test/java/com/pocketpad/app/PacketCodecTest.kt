package com.pocketpad.app

import com.pocketpad.app.protocol.FrameStreamReader
import com.pocketpad.app.protocol.GamepadButtons
import com.pocketpad.app.protocol.GamepadState
import com.pocketpad.app.protocol.KeyboardAction
import com.pocketpad.app.protocol.MouseButtonAction
import com.pocketpad.app.protocol.PacketCodec
import com.pocketpad.app.protocol.PacketType
import com.pocketpad.app.protocol.PocketPadConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PacketCodecTest {

    @Test
    fun gamepadStateRoundTripPreservesSignedAxesAndBitmask() {
        val original = GamepadState(
            buttons = GamepadButtons.A or GamepadButtons.B or GamepadButtons.LEFT_SHOULDER or GamepadButtons.START,
            leftTrigger = 190,
            rightTrigger = 255,
            leftStickX = -32768,
            leftStickY = 32767,
            rightStickX = -4200,
            rightStickY = 12345,
        )

        val frame = PacketCodec.encodeGamepadState(original)
        assertEquals(16, frame.size)
        assertEquals('P'.code.toByte(), frame[0])
        assertEquals('P'.code.toByte(), frame[1])
        assertEquals(PacketType.GAMEPAD_STATE, frame[2])
        assertEquals(12.toByte(), frame[3])

        val reader = FrameStreamReader()
        var decodedState: GamepadState? = null
        reader.feed(frame, frame.size) { pkt ->
            assertEquals(PacketType.GAMEPAD_STATE, pkt.type)
            decodedState = PacketCodec.decodeGamepadState(pkt.payload)
        }

        assertEquals(original, decodedState)
    }

    @Test
    fun streamReaderResyncsAfterGarbageAndFragmentedInput() {
        val ping = PacketCodec.encodePing(1794820123456L)
        val kb = PacketCodec.encodeKeyboardEvent(0x57, KeyboardAction.KEY_DOWN)
        val mouse = PacketCodec.encodeMouseEvent(-12, 24, 120, MouseButtonAction.LEFT_CLICK)
        val combined = byteArrayOf(0x01, 0xFF.toByte(), 0x50, 0x00) + ping + kb + mouse

        val reader = FrameStreamReader()
        val types = mutableListOf<Byte>()
        var parsedTs: Long? = null

        for (b in combined) {
            reader.feed(byteArrayOf(b), 1) { pkt ->
                types.add(pkt.type)
                if (pkt.type == PacketType.PING) {
                    parsedTs = PacketCodec.decodeTimestamp(pkt.payload)
                }
            }
        }

        assertEquals(listOf(PacketType.PING, PacketType.KEYBOARD_EVENT, PacketType.MOUSE_EVENT), types)
        assertNotNull(parsedTs)
        assertEquals(1794820123456L, parsedTs)
    }

    @Test
    fun helloPacketIncludesClientIdAndPreferredSlotForMultiplayer() {
        val frame = PacketCodec.encodeHello(
            name = "Redmi Note",
            profile = "Xbox 360",
            transport = "bluetooth",
            clientId = "pp_abc123",
            preferredSlot = 2,
        )
        val reader = FrameStreamReader()
        var payloadText = ""
        reader.feed(frame, frame.size) { pkt ->
            assertEquals(PacketType.HELLO, pkt.type)
            payloadText = String(pkt.payload, Charsets.UTF_8)
        }
        assertEquals(true, payloadText.contains("\"clientId\":\"pp_abc123\""))
        assertEquals(true, payloadText.contains("\"preferredSlot\":2"))
    }

    @Test
    fun videoControlPacketMatchesWireSpec() {
        val pkt = PacketCodec.encodeVideoControl(cmd = 1, slot = 4, maxWidth = 854, maxHeight = 480, quality = 65)
        assertEquals(10, pkt.size)
        assertEquals(PocketPadConstants.VIDEO_MAGIC_0, pkt[0])
        assertEquals(PocketPadConstants.VIDEO_MAGIC_1, pkt[1])
        assertEquals(1.toByte(), pkt[2])
        assertEquals(1.toByte(), pkt[3])
        assertEquals(4.toByte(), pkt[4])
        assertEquals(65.toByte(), pkt[9])
    }
}
