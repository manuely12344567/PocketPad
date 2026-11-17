package com.pocketpad.app.model

import com.pocketpad.app.protocol.GamepadButtons

object DefaultProfiles {

    fun allBuiltIn(): List<LayoutProfile> = listOf(
        xboxClassic(),
        playStationStyle(),
        racingGyro(),
        fpsKeyboardMouse(),
        pcTrackpadRemote(),
    )

    fun xboxClassic(): LayoutProfile = LayoutProfile(
        id = "builtin_xbox360",
        name = "Xbox 360 Classique",
        description = "Manette complète double joystick, croix 8 directions, LT/RT, LB/RB, A/B/X/Y",
        builtIn = true,
        gyroMode = GyroMode.OFF,
        elements = listOf(
            // Triggers & Bumpers
            ControlElement("lt", ControlType.TRIGGER_LEFT, "LT", 0.11f, 0.14f, 0.13f, 0.12f, ControlShape.PILL, "#38BDF8"),
            ControlElement("lb", ControlType.BUTTON, "LB", 0.25f, 0.14f, 0.11f, 0.11f, ControlShape.PILL, "#38BDF8", xboxButtonBit = GamepadButtons.LEFT_SHOULDER),
            ControlElement("rb", ControlType.BUTTON, "RB", 0.75f, 0.14f, 0.11f, 0.11f, ControlShape.PILL, "#8B5CF6", xboxButtonBit = GamepadButtons.RIGHT_SHOULDER),
            ControlElement("rt", ControlType.TRIGGER_RIGHT, "RT", 0.89f, 0.14f, 0.13f, 0.12f, ControlShape.PILL, "#8B5CF6"),

            // Center system buttons
            ControlElement("back", ControlType.BUTTON, "SEL", 0.41f, 0.22f, 0.08f, 0.09f, ControlShape.PILL, "#94A3B8", xboxButtonBit = GamepadButtons.BACK),
            ControlElement("guide", ControlType.BUTTON, "⚡", 0.50f, 0.22f, 0.07f, 0.12f, ControlShape.CIRCLE, "#38BDF8", xboxButtonBit = GamepadButtons.GUIDE),
            ControlElement("start", ControlType.BUTTON, "START", 0.59f, 0.22f, 0.08f, 0.09f, ControlShape.PILL, "#94A3B8", xboxButtonBit = GamepadButtons.START),

            // Left Joystick & D-Pad
            ControlElement("lstick", ControlType.JOYSTICK_LEFT, "L", 0.16f, 0.49f, 0.20f, 0.36f, ControlShape.CIRCLE, "#38BDF8"),
            ControlElement("dpad", ControlType.DPAD, "D-PAD", 0.33f, 0.78f, 0.17f, 0.31f, ControlShape.ROUND_RECT, "#94A3B8"),
            ControlElement("l3", ControlType.BUTTON, "L3", 0.07f, 0.85f, 0.07f, 0.11f, ControlShape.CIRCLE, "#64748B", xboxButtonBit = GamepadButtons.LEFT_THUMB),

            // Right Joystick & ABXY
            ControlElement("rstick", ControlType.JOYSTICK_RIGHT, "R", 0.67f, 0.76f, 0.19f, 0.34f, ControlShape.CIRCLE, "#8B5CF6"),
            ControlElement("r3", ControlType.BUTTON, "R3", 0.93f, 0.85f, 0.07f, 0.11f, ControlShape.CIRCLE, "#64748B", xboxButtonBit = GamepadButtons.RIGHT_THUMB),

            ControlElement("btn_y", ControlType.BUTTON, "Y", 0.84f, 0.36f, 0.09f, 0.16f, ControlShape.CIRCLE, "#EAB308", xboxButtonBit = GamepadButtons.Y),
            ControlElement("btn_x", ControlType.BUTTON, "X", 0.76f, 0.51f, 0.09f, 0.16f, ControlShape.CIRCLE, "#38BDF8", xboxButtonBit = GamepadButtons.X),
            ControlElement("btn_b", ControlType.BUTTON, "B", 0.92f, 0.51f, 0.09f, 0.16f, ControlShape.CIRCLE, "#F43F5E", xboxButtonBit = GamepadButtons.B),
            ControlElement("btn_a", ControlType.BUTTON, "A", 0.84f, 0.66f, 0.09f, 0.16f, ControlShape.CIRCLE, "#22C55E", xboxButtonBit = GamepadButtons.A),
        )
    )

    fun playStationStyle(): LayoutProfile = LayoutProfile(
        id = "builtin_playstation",
        name = "PlayStation Style",
        description = "Symboles ✕ ○ □ △, L1/L2/R1/R2 et disposition symétrique DualShock",
        builtIn = true,
        gyroMode = GyroMode.OFF,
        elements = listOf(
            ControlElement("l2", ControlType.TRIGGER_LEFT, "L2", 0.11f, 0.14f, 0.13f, 0.12f, ControlShape.PILL, "#3B82F6"),
            ControlElement("l1", ControlType.BUTTON, "L1", 0.25f, 0.14f, 0.11f, 0.11f, ControlShape.PILL, "#3B82F6", xboxButtonBit = GamepadButtons.LEFT_SHOULDER),
            ControlElement("r1", ControlType.BUTTON, "R1", 0.75f, 0.14f, 0.11f, 0.11f, ControlShape.PILL, "#8B5CF6", xboxButtonBit = GamepadButtons.RIGHT_SHOULDER),
            ControlElement("r2", ControlType.TRIGGER_RIGHT, "R2", 0.89f, 0.14f, 0.13f, 0.12f, ControlShape.PILL, "#8B5CF6"),

            ControlElement("share", ControlType.BUTTON, "SHARE", 0.41f, 0.22f, 0.085f, 0.09f, ControlShape.PILL, "#94A3B8", xboxButtonBit = GamepadButtons.BACK),
            ControlElement("ps", ControlType.BUTTON, "PS", 0.50f, 0.22f, 0.07f, 0.12f, ControlShape.CIRCLE, "#3B82F6", xboxButtonBit = GamepadButtons.GUIDE),
            ControlElement("opt", ControlType.BUTTON, "OPT", 0.59f, 0.22f, 0.085f, 0.09f, ControlShape.PILL, "#94A3B8", xboxButtonBit = GamepadButtons.START),

            ControlElement("dpad", ControlType.DPAD, "D-PAD", 0.15f, 0.48f, 0.18f, 0.32f, ControlShape.ROUND_RECT, "#94A3B8"),
            ControlElement("lstick", ControlType.JOYSTICK_LEFT, "L", 0.34f, 0.77f, 0.19f, 0.34f, ControlShape.CIRCLE, "#3B82F6"),
            ControlElement("rstick", ControlType.JOYSTICK_RIGHT, "R", 0.66f, 0.77f, 0.19f, 0.34f, ControlShape.CIRCLE, "#8B5CF6"),

            ControlElement("tri", ControlType.BUTTON, "△", 0.85f, 0.36f, 0.09f, 0.16f, ControlShape.CIRCLE, "#22C55E", xboxButtonBit = GamepadButtons.Y),
            ControlElement("sq", ControlType.BUTTON, "□", 0.77f, 0.51f, 0.09f, 0.16f, ControlShape.CIRCLE, "#EC4899", xboxButtonBit = GamepadButtons.X),
            ControlElement("cir", ControlType.BUTTON, "○", 0.93f, 0.51f, 0.09f, 0.16f, ControlShape.CIRCLE, "#F43F5E", xboxButtonBit = GamepadButtons.B),
            ControlElement("crs", ControlType.BUTTON, "✕", 0.85f, 0.66f, 0.09f, 0.16f, ControlShape.CIRCLE, "#38BDF8", xboxButtonBit = GamepadButtons.A),
        )
    )

    fun racingGyro(): LayoutProfile = LayoutProfile(
        id = "builtin_racing",
        name = "Course · Volant Gyroscope",
        description = "Penche le téléphone comme un volant ! Grandes pédales Gaz/Frein, Nitro, Vitesses",
        builtIn = true,
        gyroMode = GyroMode.STEERING_LEFT_STICK,
        gyroSensitivity = 1.6f,
        elements = listOf(
            ControlElement("brake", ControlType.TRIGGER_LEFT, "FREIN", 0.13f, 0.66f, 0.18f, 0.42f, ControlShape.ROUND_RECT, "#F43F5E"),
            ControlElement("gas", ControlType.TRIGGER_RIGHT, "GAZ", 0.87f, 0.66f, 0.18f, 0.42f, ControlShape.ROUND_RECT, "#22C55E"),

            ControlElement("gear_down", ControlType.BUTTON, "V -", 0.12f, 0.25f, 0.13f, 0.16f, ControlShape.PILL, "#F97316", xboxButtonBit = GamepadButtons.LEFT_SHOULDER),
            ControlElement("gear_up", ControlType.BUTTON, "V +", 0.88f, 0.25f, 0.13f, 0.16f, ControlShape.PILL, "#38BDF8", xboxButtonBit = GamepadButtons.RIGHT_SHOULDER),

            ControlElement("nitro", ControlType.BUTTON, "NITRO", 0.70f, 0.72f, 0.11f, 0.20f, ControlShape.CIRCLE, "#38BDF8", xboxButtonBit = GamepadButtons.A),
            ControlElement("handbrake", ControlType.BUTTON, "DRIFT", 0.30f, 0.72f, 0.11f, 0.20f, ControlShape.CIRCLE, "#EAB308", xboxButtonBit = GamepadButtons.B),

            ControlElement("cam", ControlType.BUTTON, "CAM", 0.42f, 0.22f, 0.10f, 0.11f, ControlShape.PILL, "#8B5CF6", xboxButtonBit = GamepadButtons.Y),
            ControlElement("pause", ControlType.BUTTON, "PAUSE", 0.58f, 0.22f, 0.10f, 0.11f, ControlShape.PILL, "#94A3B8", xboxButtonBit = GamepadButtons.START),
            ControlElement("lstick_backup", ControlType.JOYSTICK_LEFT, "DIR", 0.50f, 0.68f, 0.18f, 0.32f, ControlShape.CIRCLE, "#38BDF8", opacity = 0.65f),
        )
    )

    fun fpsKeyboardMouse(): LayoutProfile = LayoutProfile(
        id = "builtin_fps_kb",
        name = "FPS · Clavier + Souris",
        description = "Marche sans aucun pilote ! Stick WASD + Trackpad visée souris + Tir / Saut / Sprint",
        builtIn = true,
        gyroMode = GyroMode.OFF,
        elements = listOf(
            ControlElement("move_wasd", ControlType.JOYSTICK_LEFT, "WASD", 0.16f, 0.62f, 0.22f, 0.40f, ControlShape.CIRCLE, "#38BDF8", joystickAsWasd = true),
            ControlElement("aim_pad", ControlType.TRACKPAD, "VISÉE SOURIS", 0.64f, 0.58f, 0.36f, 0.58f, ControlShape.ROUND_RECT, "#8B5CF6", opacity = 0.60f),

            ControlElement("fire", ControlType.MOUSE_BUTTON, "TIR", 0.90f, 0.42f, 0.13f, 0.24f, ControlShape.CIRCLE, "#F43F5E", mouseButton = 1),
            ControlElement("aim", ControlType.MOUSE_BUTTON, "VISER", 0.90f, 0.70f, 0.12f, 0.20f, ControlShape.CIRCLE, "#38BDF8", mouseButton = 2, toggleMode = true),

            ControlElement("sprint", ControlType.KEYBOARD_KEY, "SPRINT", 0.12f, 0.24f, 0.12f, 0.12f, ControlShape.PILL, "#22C55E", vkCode = 0xA0, toggleMode = true),
            ControlElement("crouch", ControlType.KEYBOARD_KEY, "CTRL", 0.26f, 0.24f, 0.11f, 0.12f, ControlShape.PILL, "#F97316", vkCode = 0xA2, toggleMode = true),
            ControlElement("reload", ControlType.KEYBOARD_KEY, "R", 0.39f, 0.24f, 0.08f, 0.14f, ControlShape.CIRCLE, "#EAB308", vkCode = 0x52),
            ControlElement("interact", ControlType.KEYBOARD_KEY, "E", 0.49f, 0.24f, 0.08f, 0.14f, ControlShape.CIRCLE, "#38BDF8", vkCode = 0x45),
            ControlElement("jump", ControlType.KEYBOARD_KEY, "SAUT", 0.36f, 0.84f, 0.14f, 0.14f, ControlShape.PILL, "#22C55E", vkCode = 0x20),
            ControlElement("esc", ControlType.KEYBOARD_KEY, "ESC", 0.90f, 0.16f, 0.10f, 0.10f, ControlShape.PILL, "#94A3B8", vkCode = 0x1B),
        )
    )

    fun pcTrackpadRemote(): LayoutProfile = LayoutProfile(
        id = "builtin_pc_remote",
        name = "PC Souris & Clavier",
        description = "Contrôle directement ton PC : grand pavé tactile, clics, molette, flèches et média",
        builtIn = true,
        gyroMode = GyroMode.OFF,
        elements = listOf(
            ControlElement("main_pad", ControlType.TRACKPAD, "PAVÉ TACTILE SOURIS", 0.42f, 0.48f, 0.56f, 0.62f, ControlShape.ROUND_RECT, "#38BDF8", opacity = 0.72f),
            ControlElement("lclick", ControlType.MOUSE_BUTTON, "CLIC GAUCHE", 0.26f, 0.88f, 0.24f, 0.15f, ControlShape.PILL, "#38BDF8", mouseButton = 1),
            ControlElement("rclick", ControlType.MOUSE_BUTTON, "CLIC DROIT", 0.56f, 0.88f, 0.24f, 0.15f, ControlShape.PILL, "#8B5CF6", mouseButton = 2),

            ControlElement("scroll_up", ControlType.MOUSE_BUTTON, "▲", 0.76f, 0.32f, 0.08f, 0.16f, ControlShape.ROUND_RECT, "#38BDF8", mouseButton = 4),
            ControlElement("scroll_dn", ControlType.MOUSE_BUTTON, "▼", 0.76f, 0.56f, 0.08f, 0.16f, ControlShape.ROUND_RECT, "#38BDF8", mouseButton = 5),

            ControlElement("esc", ControlType.KEYBOARD_KEY, "ESC", 0.89f, 0.20f, 0.11f, 0.11f, ControlShape.PILL, "#F43F5E", vkCode = 0x1B),
            ControlElement("enter", ControlType.KEYBOARD_KEY, "ENTRÉE", 0.89f, 0.38f, 0.11f, 0.13f, ControlShape.PILL, "#22C55E", vkCode = 0x0D),
            ControlElement("space", ControlType.KEYBOARD_KEY, "ESPACE", 0.89f, 0.56f, 0.11f, 0.13f, ControlShape.PILL, "#38BDF8", vkCode = 0x20),
            ControlElement("backsp", ControlType.KEYBOARD_KEY, "⌫", 0.89f, 0.74f, 0.11f, 0.13f, ControlShape.PILL, "#F97316", vkCode = 0x08),
            ControlElement("play", ControlType.KEYBOARD_KEY, "⏯", 0.89f, 0.89f, 0.11f, 0.11f, ControlShape.PILL, "#8B5CF6", vkCode = 0xB3),
        )
    )
}
