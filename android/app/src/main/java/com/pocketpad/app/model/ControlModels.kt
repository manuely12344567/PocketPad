package com.pocketpad.app.model

import com.pocketpad.app.protocol.GamepadButtons

enum class ControlType {
    BUTTON,
    JOYSTICK_LEFT,
    JOYSTICK_RIGHT,
    DPAD,
    TRIGGER_LEFT,
    TRIGGER_RIGHT,
    TRACKPAD,
    KEYBOARD_KEY,
    MOUSE_BUTTON,
}

enum class ControlShape {
    CIRCLE,
    PILL,
    ROUND_RECT,
}

enum class GyroMode {
    OFF,
    STEERING_LEFT_STICK,
    AIM_RIGHT_STICK,
    AIM_MOUSE,
}

data class ControlElement(
    val id: String,
    val type: ControlType,
    val label: String,
    val x: Float,              // Center X normalized 0f..1f
    val y: Float,              // Center Y normalized 0f..1f
    val width: Float,          // Normalized width relative to screen width (0.04f..0.45f)
    val height: Float,         // Normalized height relative to screen height (0.06f..0.60f)
    val shape: ControlShape = ControlShape.CIRCLE,
    val colorHex: String = "#38BDF8",
    val opacity: Float = 0.85f,
    val xboxButtonBit: Int = GamepadButtons.A,
    val vkCode: Int = 0x20,    // Win32 Virtual-Key code (default Space)
    val mouseButton: Int = 1,  // 1=Left, 2=Right, 3=Middle, 4=ScrollUp, 5=ScrollDown
    val joystickAsWasd: Boolean = false,
    val wasdAzerty: Boolean = false, // true => ZQSD, false => WASD
    val toggleMode: Boolean = false,
    val turboHz: Int = 0,
)

data class LayoutProfile(
    val id: String,
    val name: String,
    val description: String,
    val builtIn: Boolean = false,
    val gyroMode: GyroMode = GyroMode.OFF,
    val gyroSensitivity: Float = 1.4f,
    val hapticEnabled: Boolean = true,
    val elements: List<ControlElement> = emptyList(),
)

data class KeyOption(val label: String, val vkCode: Int)

object InputCatalog {
    val xboxButtons: List<Pair<String, Int>> = listOf(
        "A (Croix ✕)" to GamepadButtons.A,
        "B (Rond ○)" to GamepadButtons.B,
        "X (Carré □)" to GamepadButtons.X,
        "Y (Triangle △)" to GamepadButtons.Y,
        "LB (L1)" to GamepadButtons.LEFT_SHOULDER,
        "RB (R1)" to GamepadButtons.RIGHT_SHOULDER,
        "Start (Options)" to GamepadButtons.START,
        "Back / Select (Share)" to GamepadButtons.BACK,
        "Guide / Xbox" to GamepadButtons.GUIDE,
        "L3 (Clic Joystick G)" to GamepadButtons.LEFT_THUMB,
        "R3 (Clic Joystick D)" to GamepadButtons.RIGHT_THUMB,
        "D-Pad Haut" to GamepadButtons.DPAD_UP,
        "D-Pad Bas" to GamepadButtons.DPAD_DOWN,
        "D-Pad Gauche" to GamepadButtons.DPAD_LEFT,
        "D-Pad Droite" to GamepadButtons.DPAD_RIGHT,
    )

    val keyboardKeys: List<KeyOption> = listOf(
        KeyOption("Espace (Space)", 0x20),
        KeyOption("Entrée (Enter)", 0x0D),
        KeyOption("Échap (Esc)", 0x1B),
        KeyOption("Shift Gauche (Sprint)", 0xA0),
        KeyOption("Ctrl Gauche (Accroupir)", 0xA2),
        KeyOption("Alt Gauche", 0xA4),
        KeyOption("Tab", 0x09),
        KeyOption("Retour arrière (Backspace)", 0x08),
        KeyOption("Flèche Haut", 0x26),
        KeyOption("Flèche Bas", 0x28),
        KeyOption("Flèche Gauche", 0x25),
        KeyOption("Flèche Droite", 0x27),
        KeyOption("W (Avancer QWERTY)", 0x57),
        KeyOption("A (Gauche QWERTY)", 0x41),
        KeyOption("S (Reculer)", 0x53),
        KeyOption("D (Droite)", 0x44),
        KeyOption("Z (Avancer AZERTY)", 0x5A),
        KeyOption("Q (Gauche AZERTY)", 0x51),
        KeyOption("E (Interagir)", 0x45),
        KeyOption("R (Recharger)", 0x52),
        KeyOption("F (Action)", 0x46),
        KeyOption("G (Grenade)", 0x47),
        KeyOption("C (Accroupir)", 0x43),
        KeyOption("V (Mêlée)", 0x56),
        KeyOption("X", 0x58),
        KeyOption("M (Carte)", 0x4D),
        KeyOption("I (Inventaire)", 0x49),
        KeyOption("Touche 1", 0x31),
        KeyOption("Touche 2", 0x32),
        KeyOption("Touche 3", 0x33),
        KeyOption("Touche 4", 0x34),
        KeyOption("F1", 0x70),
        KeyOption("F5", 0x74),
        KeyOption("F11 (Plein écran)", 0x7A),
        KeyOption("Volume +", 0xAF),
        KeyOption("Volume -", 0xAE),
        KeyOption("Play / Pause", 0xB3),
    )

    val mouseButtons: List<Pair<String, Int>> = listOf(
        "Clic Gauche (Tir)" to 1,
        "Clic Droit (Viser)" to 2,
        "Clic Milieu" to 3,
        "Molette Haut" to 4,
        "Molette Bas" to 5,
    )

    val colorPalette: List<Pair<String, String>> = listOf(
        "Cyan Néon" to "#38BDF8",
        "Vert Xbox" to "#22C55E",
        "Rouge / Rose" to "#F43F5E",
        "Jaune Or" to "#EAB308",
        "Violet Électrique" to "#8B5CF6",
        "Orange" to "#F97316",
        "Bleu Roi" to "#3B82F6",
        "Gris Titane" to "#94A3B8",
    )

    fun xboxButtonName(bit: Int): String =
        xboxButtons.firstOrNull { it.second == bit }?.first ?: "Bouton"

    fun keyName(vk: Int): String =
        keyboardKeys.firstOrNull { it.vkCode == vk }?.label ?: "Touche 0x${vk.toString(16).uppercase()}"

    fun mouseName(action: Int): String =
        mouseButtons.firstOrNull { it.second == action }?.first ?: "Souris"
}
