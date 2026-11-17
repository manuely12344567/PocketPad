package com.pocketpad.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.pocketpad.app.input.HapticsEngine
import com.pocketpad.app.model.ControlElement
import com.pocketpad.app.model.ControlType
import com.pocketpad.app.model.GyroMode
import com.pocketpad.app.model.LayoutProfile
import com.pocketpad.app.protocol.GamepadButtons
import com.pocketpad.app.protocol.GamepadState
import com.pocketpad.app.protocol.KeyboardAction
import com.pocketpad.app.protocol.MouseButtonAction
import com.pocketpad.app.transport.ConnectionManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Ultra-low-latency multi-touch gaming surface (supports up to 10 simultaneous fingers).
 * Emits binary Xbox 360 state packets, keyboard key up/down events, and mouse trackpad deltas in real time.
 */
class GamepadSurfaceView(
    context: Context,
    private val haptics: HapticsEngine,
) : View(context) {

    private var profile: LayoutProfile = LayoutProfile("empty", "Vide", "")

    private data class ActiveTouch(
        val elementId: String,
        val downTimeMs: Long,
        var lastX: Float,
        var lastY: Float,
        var movedDist: Float = 0f,
    )

    private val pointerMap = HashMap<Int, ActiveTouch>()
    private val toggledElementIds = HashSet<String>()
    private val visualStates = HashMap<String, ControlVisualState>()
    private val activeWasdKeys = HashSet<Int>()

    // Trackpad 2-finger scroll state
    private var lastScrollY: Float? = null

    // Gyroscope overlay
    private var gyroEnabled: Boolean = false
    private var gyroX: Float = 0f
    private var gyroY: Float = 0f

    // Nintendo Switch live PC screen backdrop + adjustable control opacity
    @Volatile private var videoBitmap: Bitmap? = null
    private var videoVisible: Boolean = true
    private var videoFillScreen: Boolean = false
    private var controlsOpacity: Float = 0.65f
    private val videoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val videoSrcRect = Rect()
    private val videoDstRect = RectF()

    // Turbo auto-fire pulse
    private val turboHandler = Handler(Looper.getMainLooper())
    private var turboTickHigh = true
    private val turboRunnable = object : Runnable {
        override fun run() {
            turboTickHigh = !turboTickHigh
            if (hasAnyActiveTurbo()) {
                syncAndSendGamepadState()
            }
            turboHandler.postDelayed(this, 60L)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.parseColor("#090D1A"))
    }

    fun setProfile(newProfile: LayoutProfile) {
        releaseAllInputs()
        profile = newProfile
        haptics.enabled = newProfile.hapticEnabled
        gyroEnabled = newProfile.gyroMode != GyroMode.OFF
        invalidate()
    }

    fun setGyroInput(enabled: Boolean, x: Float, y: Float) {
        gyroEnabled = enabled
        gyroX = if (enabled) x else 0f
        gyroY = if (enabled) y else 0f

        if (enabled && profile.gyroMode == GyroMode.AIM_MOUSE) {
            val dx = (gyroX * 14f).roundToInt()
            val dy = (gyroY * 14f).roundToInt()
            if (dx != 0 || dy != 0) {
                ConnectionManager.sendMouseEvent(dx, dy, 0, MouseButtonAction.MOVE_ONLY)
            }
        } else {
            syncAndSendGamepadState()
        }
    }

    fun startTurboLoop() {
        turboHandler.removeCallbacks(turboRunnable)
        turboHandler.postDelayed(turboRunnable, 60L)
    }

    fun stopTurboLoop() {
        turboHandler.removeCallbacks(turboRunnable)
    }

    fun releaseAllInputs() {
        pointerMap.clear()
        toggledElementIds.clear()
        visualStates.clear()
        for (vk in activeWasdKeys.toList()) {
            ConnectionManager.sendKeyboardEvent(vk, KeyboardAction.KEY_UP)
        }
        activeWasdKeys.clear()
        ConnectionManager.sendResetInputs()
        invalidate()
    }

    fun setVideoFrame(bmp: Bitmap?) {
        videoBitmap = bmp
        postInvalidateOnAnimation()
    }

    fun setVideoDisplayOptions(visible: Boolean, opacity: Float, fillScreen: Boolean) {
        videoVisible = visible
        controlsOpacity = opacity.coerceIn(0.15f, 1.0f)
        videoFillScreen = fillScreen
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw live PC game video full-bleed behind the gamepad controls (Nintendo Switch mode)
        val bmp = if (videoVisible) videoBitmap else null
        val showingVideo = bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0
        if (showingVideo && bmp != null) {
            drawVideoBackdrop(canvas, bmp, w, h)
        }

        val opacityScale = if (showingVideo) controlsOpacity else 1.0f

        // Draw trackpads behind buttons so overlapping layouts stay clickable & visible
        val ordered = profile.elements.sortedBy { if (it.type == ControlType.TRACKPAD) 0 else 1 }
        for (el in ordered) {
            val st = visualStates[el.id] ?: ControlVisualState(toggledOn = toggledElementIds.contains(el.id))
            GamepadRenderer.drawControl(canvas, el, w, h, st, selectedInEditor = false, globalOpacityScale = opacityScale)
        }
    }

    private fun drawVideoBackdrop(canvas: Canvas, bmp: Bitmap, viewW: Float, viewH: Float) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = if (videoFillScreen) {
            maxOf(viewW / bw, viewH / bh)
        } else {
            minOf(viewW / bw, viewH / bh)
        }
        val drawW = bw * scale
        val drawH = bh * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        videoSrcRect.set(0, 0, bmp.width, bmp.height)
        videoDstRect.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bmp, videoSrcRect, videoDstRect, videoPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pid = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                handlePointerDown(pid, x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                handlePointersMove(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(actionIndex)
                handlePointerUp(pid)
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseAllInputs()
            }
        }

        invalidate()
        return true
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        val hit = hitTestElement(x, y) ?: return
        pointerMap[pointerId] = ActiveTouch(hit.id, System.currentTimeMillis(), x, y)

        when (hit.type) {
            ControlType.JOYSTICK_LEFT, ControlType.JOYSTICK_RIGHT -> {
                updateJoystickVisual(hit, x, y)
                haptics.tapTick()
                syncAndSendGamepadState()
            }

            ControlType.DPAD -> {
                updateDpadVisual(hit, x, y)
                haptics.tapTick()
                syncAndSendGamepadState()
            }

            ControlType.TRACKPAD -> {
                visualStates[hit.id] = ControlVisualState(pressed = true)
            }

            ControlType.BUTTON, ControlType.TRIGGER_LEFT, ControlType.TRIGGER_RIGHT -> {
                haptics.tapTick()
                if (hit.toggleMode) {
                    if (!toggledElementIds.add(hit.id)) {
                        toggledElementIds.remove(hit.id)
                    }
                    visualStates[hit.id] = ControlVisualState(pressed = true, toggledOn = toggledElementIds.contains(hit.id))
                } else {
                    visualStates[hit.id] = ControlVisualState(pressed = true)
                }
                syncAndSendGamepadState()
            }

            ControlType.KEYBOARD_KEY -> {
                haptics.tapTick()
                if (hit.toggleMode) {
                    val nowOn = toggledElementIds.add(hit.id)
                    if (!nowOn) toggledElementIds.remove(hit.id)
                    visualStates[hit.id] = ControlVisualState(pressed = true, toggledOn = nowOn)
                    ConnectionManager.sendKeyboardEvent(
                        hit.vkCode,
                        if (nowOn) KeyboardAction.KEY_DOWN else KeyboardAction.KEY_UP
                    )
                } else {
                    visualStates[hit.id] = ControlVisualState(pressed = true)
                    ConnectionManager.sendKeyboardEvent(hit.vkCode, KeyboardAction.KEY_DOWN)
                }
            }

            ControlType.MOUSE_BUTTON -> {
                haptics.tapTick()
                if (hit.mouseButton == 4) {
                    visualStates[hit.id] = ControlVisualState(pressed = true)
                    ConnectionManager.sendMouseEvent(0, 0, 120, MouseButtonAction.MOVE_ONLY)
                } else if (hit.mouseButton == 5) {
                    visualStates[hit.id] = ControlVisualState(pressed = true)
                    ConnectionManager.sendMouseEvent(0, 0, -120, MouseButtonAction.MOVE_ONLY)
                } else if (hit.toggleMode) {
                    val nowOn = toggledElementIds.add(hit.id)
                    if (!nowOn) toggledElementIds.remove(hit.id)
                    visualStates[hit.id] = ControlVisualState(pressed = true, toggledOn = nowOn)
                    sendMouseButtonState(hit.mouseButton, down = nowOn)
                } else {
                    visualStates[hit.id] = ControlVisualState(pressed = true)
                    sendMouseButtonState(hit.mouseButton, down = true)
                }
            }
        }
    }

    private fun handlePointersMove(event: MotionEvent) {
        var gamepadDirty = false

        // Detect 2 fingers simultaneously on a TRACKPAD for smooth vertical scrolling
        val trackpadPointers = mutableListOf<Pair<Int, Float>>()
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val touch = pointerMap[pid] ?: continue
            val el = profile.elements.firstOrNull { it.id == touch.elementId } ?: continue
            if (el.type == ControlType.TRACKPAD) {
                trackpadPointers.add(pid to event.getY(i))
            }
        }

        if (trackpadPointers.size >= 2) {
            val avgY = trackpadPointers.map { it.second }.average().toFloat()
            val prev = lastScrollY
            if (prev != null) {
                val dy = avgY - prev
                if (abs(dy) >= 6f) {
                    val wheel = (-dy * 4f).roundToInt().coerceIn(-240, 240)
                    ConnectionManager.sendMouseEvent(0, 0, wheel, MouseButtonAction.MOVE_ONLY)
                    lastScrollY = avgY
                }
            } else {
                lastScrollY = avgY
            }
            // Update pointer last positions without sending cursor move
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                pointerMap[pid]?.let {
                    it.lastX = event.getX(i)
                    it.lastY = event.getY(i)
                    it.movedDist += 10f
                }
            }
            return
        } else {
            lastScrollY = null
        }

        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val touch = pointerMap[pid] ?: continue
            val el = profile.elements.firstOrNull { it.id == touch.elementId } ?: continue
            val x = event.getX(i)
            val y = event.getY(i)

            when (el.type) {
                ControlType.JOYSTICK_LEFT, ControlType.JOYSTICK_RIGHT -> {
                    updateJoystickVisual(el, x, y)
                    gamepadDirty = true
                }

                ControlType.DPAD -> {
                    updateDpadVisual(el, x, y)
                    gamepadDirty = true
                }

                ControlType.TRACKPAD -> {
                    val dx = x - touch.lastX
                    val dy = y - touch.lastY
                    touch.movedDist += hypot(dx, dy)
                    touch.lastX = x
                    touch.lastY = y
                    val mx = (dx * 1.65f).roundToInt()
                    val my = (dy * 1.65f).roundToInt()
                    if (mx != 0 || my != 0) {
                        ConnectionManager.sendMouseEvent(mx, my, 0, MouseButtonAction.MOVE_ONLY)
                    }
                }

                else -> Unit
            }
        }

        if (gamepadDirty) {
            syncAndSendGamepadState()
        }
    }

    private fun handlePointerUp(pointerId: Int) {
        val touch = pointerMap.remove(pointerId) ?: return
        val el = profile.elements.firstOrNull { it.id == touch.elementId } ?: return
        val stillHeldByAnotherPointer = pointerMap.values.any { it.elementId == el.id }
        if (stillHeldByAnotherPointer) return

        when (el.type) {
            ControlType.JOYSTICK_LEFT, ControlType.JOYSTICK_RIGHT -> {
                visualStates[el.id] = ControlVisualState()
                if (el.joystickAsWasd) updateWasdFromStick(0f, 0f, el.wasdAzerty)
                syncAndSendGamepadState()
            }

            ControlType.DPAD -> {
                visualStates[el.id] = ControlVisualState()
                syncAndSendGamepadState()
            }

            ControlType.TRACKPAD -> {
                visualStates[el.id] = ControlVisualState()
                // Quick light tap on trackpad => Left Click!
                val elapsed = System.currentTimeMillis() - touch.downTimeMs
                if (elapsed < 220L && touch.movedDist < 18f) {
                    haptics.tapTick()
                    ConnectionManager.sendMouseEvent(0, 0, 0, MouseButtonAction.LEFT_CLICK)
                }
            }

            ControlType.BUTTON, ControlType.TRIGGER_LEFT, ControlType.TRIGGER_RIGHT -> {
                visualStates[el.id] = ControlVisualState(pressed = false, toggledOn = toggledElementIds.contains(el.id))
                syncAndSendGamepadState()
            }

            ControlType.KEYBOARD_KEY -> {
                if (!el.toggleMode) {
                    visualStates[el.id] = ControlVisualState(pressed = false)
                    ConnectionManager.sendKeyboardEvent(el.vkCode, KeyboardAction.KEY_UP)
                } else {
                    visualStates[el.id] = ControlVisualState(pressed = false, toggledOn = toggledElementIds.contains(el.id))
                }
            }

            ControlType.MOUSE_BUTTON -> {
                if (!el.toggleMode) {
                    visualStates[el.id] = ControlVisualState(pressed = false)
                    if (el.mouseButton in 1..3) {
                        sendMouseButtonState(el.mouseButton, down = false)
                    }
                } else {
                    visualStates[el.id] = ControlVisualState(pressed = false, toggledOn = toggledElementIds.contains(el.id))
                }
            }
        }
    }

    private fun updateJoystickVisual(el: ControlElement, x: Float, y: Float) {
        val rect = GamepadRenderer.computeRect(el, width.toFloat(), height.toFloat())
        val radius = (rect.width() / 2f).coerceAtLeast(24f)
        var dx = (x - rect.centerX()) / radius
        var dy = (y - rect.centerY()) / radius
        val mag = hypot(dx, dy)
        if (mag > 1f) {
            dx /= mag
            dy /= mag
        }
        // Subtle 7% center deadzone
        if (mag < 0.07f) {
            dx = 0f
            dy = 0f
        }
        visualStates[el.id] = ControlVisualState(pressed = true, stickX = dx, stickY = dy)
        if (el.joystickAsWasd) {
            updateWasdFromStick(dx, dy, el.wasdAzerty)
        }
    }

    private fun updateWasdFromStick(dx: Float, dy: Float, azerty: Boolean) {
        val vkUp = if (azerty) 0x5A else 0x57    // Z or W
        val vkLeft = if (azerty) 0x51 else 0x41  // Q or A
        val vkDown = 0x53                        // S
        val vkRight = 0x44                       // D

        val desired = HashSet<Int>()
        if (dy < -0.32f) desired.add(vkUp)
        if (dy > 0.32f) desired.add(vkDown)
        if (dx < -0.32f) desired.add(vkLeft)
        if (dx > 0.32f) desired.add(vkRight)

        for (old in activeWasdKeys.toList()) {
            if (!desired.contains(old)) {
                activeWasdKeys.remove(old)
                ConnectionManager.sendKeyboardEvent(old, KeyboardAction.KEY_UP)
            }
        }
        for (key in desired) {
            if (activeWasdKeys.add(key)) {
                ConnectionManager.sendKeyboardEvent(key, KeyboardAction.KEY_DOWN)
            }
        }
    }

    private fun updateDpadVisual(el: ControlElement, x: Float, y: Float) {
        val rect = GamepadRenderer.computeRect(el, width.toFloat(), height.toFloat())
        val dx = (x - rect.centerX()) / (rect.width() / 2f)
        val dy = (y - rect.centerY()) / (rect.height() / 2f)
        var mask = 0
        if (dy < -0.28f) mask = mask or GamepadButtons.DPAD_UP
        if (dy > 0.28f) mask = mask or GamepadButtons.DPAD_DOWN
        if (dx < -0.28f) mask = mask or GamepadButtons.DPAD_LEFT
        if (dx > 0.28f) mask = mask or GamepadButtons.DPAD_RIGHT
        visualStates[el.id] = ControlVisualState(pressed = true, dpadMask = mask)
    }

    private fun sendMouseButtonState(mouseBtn: Int, down: Boolean) {
        val action = when (mouseBtn) {
            1 -> if (down) MouseButtonAction.LEFT_DOWN else MouseButtonAction.LEFT_UP
            2 -> if (down) MouseButtonAction.RIGHT_DOWN else MouseButtonAction.RIGHT_UP
            3 -> if (down) MouseButtonAction.MIDDLE_DOWN else MouseButtonAction.MIDDLE_UP
            else -> MouseButtonAction.MOVE_ONLY
        }
        if (action != MouseButtonAction.MOVE_ONLY) {
            ConnectionManager.sendMouseEvent(0, 0, 0, action)
        }
    }

    private fun hasAnyActiveTurbo(): Boolean = profile.elements.any { el ->
        el.turboHz > 0 && ((visualStates[el.id]?.pressed == true) || toggledElementIds.contains(el.id))
    }

    private fun syncAndSendGamepadState() {
        var buttons = 0
        var lt = 0
        var rt = 0
        var lx = 0f
        var ly = 0f
        var rx = 0f
        var ry = 0f

        for (el in profile.elements) {
            val st = visualStates[el.id]
            val latched = toggledElementIds.contains(el.id)
            val isPressed = (st?.pressed == true) || latched
            val turboPass = el.turboHz <= 0 || turboTickHigh

            when (el.type) {
                ControlType.BUTTON -> {
                    if (isPressed && turboPass) {
                        buttons = buttons or el.xboxButtonBit
                    }
                }

                ControlType.TRIGGER_LEFT -> {
                    if (isPressed && turboPass) lt = 255
                }

                ControlType.TRIGGER_RIGHT -> {
                    if (isPressed && turboPass) rt = 255
                }

                ControlType.DPAD -> {
                    if (st != null) {
                        buttons = buttons or st.dpadMask
                    }
                }

                ControlType.JOYSTICK_LEFT -> {
                    if (!el.joystickAsWasd && st != null) {
                        lx = st.stickX
                        ly = -st.stickY // Invert screen Y so UP = +32767 (XInput standard!)
                    }
                }

                ControlType.JOYSTICK_RIGHT -> {
                    if (st != null) {
                        rx = st.stickX
                        ry = -st.stickY
                    }
                }

                else -> Unit
            }
        }

        // Mix Gyroscope steering / aiming when active
        if (gyroEnabled) {
            when (profile.gyroMode) {
                GyroMode.STEERING_LEFT_STICK -> {
                    if (abs(lx) < 0.05f) lx = gyroX
                }
                GyroMode.AIM_RIGHT_STICK -> {
                    rx = (rx + gyroX).coerceIn(-1f, 1f)
                    ry = (ry - gyroY).coerceIn(-1f, 1f)
                }
                else -> Unit
            }
        }

        val state = GamepadState(
            buttons = buttons,
            leftTrigger = lt,
            rightTrigger = rt,
            leftStickX = (lx * 32767f).roundToInt().coerceIn(-32768, 32767),
            leftStickY = (ly * 32767f).roundToInt().coerceIn(-32768, 32767),
            rightStickX = (rx * 32767f).roundToInt().coerceIn(-32768, 32767),
            rightStickY = (ry * 32767f).roundToInt().coerceIn(-32768, 32767),
        )
        ConnectionManager.sendGamepadState(state)
    }

    private fun hitTestElement(x: Float, y: Float): ControlElement? {
        val w = width.toFloat()
        val h = height.toFloat()
        // Test non-trackpad controls first (higher z-order), with generous touch padding for comfort
        val ordered = profile.elements.sortedByDescending { if (it.type == ControlType.TRACKPAD) 0 else 1 }
        for (el in ordered) {
            val rect = GamepadRenderer.computeRect(el, w, h)
            val pad = if (el.type == ControlType.TRACKPAD) 0f else 12f
            if (x >= rect.left - pad && x <= rect.right + pad && y >= rect.top - pad && y <= rect.bottom + pad) {
                return el
            }
        }
        return null
    }
}
