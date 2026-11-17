# PocketPad Binary Protocol v1

Ultra-low-latency framed binary protocol used identically over **USB (ADB Reverse / USB Tethering TCP)**, **Bluetooth RFCOMM SPP**, and **Wi-Fi LAN TCP**.

## Constants
- **Protocol Version**: `1.2` (backwards compatible with `1.0` & `1.1`)
- **Max Simultaneous Players**: `4` (`J1` `#38BDF8`, `J2` `#A855F7`, `J3` `#22C55E`, `J4` `#F97316`)
- **Gamepad / Input TCP Port**: `27183` (`TCP_NODELAY = true`)
- **Live Switch Screen Video TCP Port**: `27185` (`TCP_NODELAY = true`, independent from inputs; automatically forwarded over USB via `adb reverse tcp:27185 tcp:27185`)
- **UDP Discovery Port**: `27184` (Broadcast payload: `POCKETPAD_DISCOVER_V1`, Response: `POCKETPAD_SERVER_V1|<PC_NAME>|<PORT>|<VIGEM_READY>|<CONNECTED_PLAYERS>|<MAX_PLAYERS>|<VIDEO_PORT>`)
- **Bluetooth RFCOMM Service UUID**: `e8f1a4b0-7c2d-4e9a-9f31-706f636b6574`

## Video Stream Channel (TCP Port `27185`)
- **Client → Server Subscribe/Control (10 bytes)**: `0x50 0x56` (`"PV"`) | `ver=1` | `cmd (1=Subscribe, 2=Pause, 3=Resume)` | `slot (1..4)` | `maxWidth (uint16 LE)` | `maxHeight (uint16 LE)` | `quality (30..85)`
- **Server → Client Frame (12-byte header + JPEG)**: `0x50 0x56` (`"PV"`) | `slot (1..4)` | `flags (bit0=FullMenu, bit1=AutoSplit)` | `width (uint16 LE)` | `height (uint16 LE)` | `len (uint32 LE)` | `jpegBytes[len]`

## Frame Header (4 bytes)
| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 1 | `Magic0` | `0x50` (`'P'`) |
| 1 | 1 | `Magic1` | `0x50` (`'P'`) |
| 2 | 1 | `Type` | Packet Type (`0x01..0x09`) |
| 3 | 1 | `Length` | Payload length in bytes (`0..255`) |
| 4 | `Length` | `Payload` | Little-Endian binary or UTF-8 JSON |

## Packet Types

### `0x01` — `GAMEPAD_STATE` (Android -> PC, 12 bytes)
| Offset | Type | Field | Range |
|---|---|---|---|
| 0..1 | `uint16` | `buttons` | XInput 16-bit bitmask |
| 2 | `uint8` | `leftTrigger` | `0..255` |
| 3 | `uint8` | `rightTrigger` | `0..255` |
| 4..5 | `int16` | `leftStickX` | `-32768..32767` |
| 6..7 | `int16` | `leftStickY` | `-32768..32767` (`+` = Up) |
| 8..9 | `int16` | `rightStickX` | `-32768..32767` |
| 10..11 | `int16` | `rightStickY` | `-32768..32767` (`+` = Up) |

**Button Bitmask (`buttons`):**
- `0x0001` D-Pad Up | `0x0002` D-Pad Down | `0x0004` D-Pad Left | `0x0008` D-Pad Right
- `0x0010` Start | `0x0020` Back/Select | `0x0040` L3 (`LeftThumb`) | `0x0080` R3 (`RightThumb`)
- `0x0100` LB (`LeftShoulder`) | `0x0200` RB (`RightShoulder`) | `0x0400` Guide/Home
- `0x1000` A | `0x2000` B | `0x4000` X | `0x8000` Y

### `0x02` — `KEYBOARD_EVENT` (Android -> PC, 3 bytes)
| Offset | Type | Field | Values |
|---|---|---|---|
| 0..1 | `uint16` | `vkCode` | Win32 Virtual-Key code |
| 2 | `uint8` | `action` | `0` = KeyUp, `1` = KeyDown, `2` = Tap |

### `0x03` — `MOUSE_EVENT` (Android -> PC, 7 bytes)
| Offset | Type | Field | Values |
|---|---|---|---|
| 0..1 | `int16` | `dx` | Relative delta X |
| 2..3 | `int16` | `dy` | Relative delta Y |
| 4..5 | `int16` | `wheel` | Scroll delta (`+120` up / `-120` down) |
| 6 | `uint8` | `buttonAction` | `0`=None, `1`=LDown, `2`=LUp, `3`=RDown, `4`=RUp, `5`=MDown, `6`=MUp, `7`=LClick, `8`=RClick |

### `0x04` — `HELLO` (Android -> PC, UTF-8 JSON)
`{"name":"Phone","profile":"Xbox 360","transport":"usb"}`

### `0x05` / `0x06` — `PING` / `PONG` (8 bytes)
- `int64` `timestampMs` (echoed verbatim by PC)

### `0x07` — `RUMBLE` (PC -> Android, 2 bytes)
- `[0]` `uint8` `largeMotor` (`0..255`)
- `[1]` `uint8` `smallMotor` (`0..255`)

### `0x08` — `SERVER_INFO` (PC -> Android, UTF-8 JSON)
`{"pc":"MY-PC","vigem":true,"mode":"auto"}`

### `0x09` — `RESET_INPUTS` (Android -> PC, 0 bytes)
Releases all buttons, axes, keyboard keys, and mouse buttons cleanly.
