package com.pocketpad.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.pocketpad.app.model.ControlElement
import com.pocketpad.app.model.ControlShape
import com.pocketpad.app.model.ControlType
import kotlin.math.min

data class ControlVisualState(
    val pressed: Boolean = false,
    val toggledOn: Boolean = false,
    val stickX: Float = 0f,     // -1..1
    val stickY: Float = 0f,     // -1..1 (screen coords: + is down)
    val dpadMask: Int = 0,      // UP/DOWN/LEFT/RIGHT bits
)

/**
 * Shared high-contrast neon-dark Canvas renderer used identically by both GamepadSurfaceView and EditorCanvasView.
 */
object GamepadRenderer {
    private val bgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C2742")
        strokeWidth = 1.5f
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    fun computeRect(el: ControlElement, viewW: Float, viewH: Float): RectF {
        val cx = el.x * viewW
        val cy = el.y * viewH
        return if (el.shape == ControlShape.CIRCLE || el.type == ControlType.JOYSTICK_LEFT || el.type == ControlType.JOYSTICK_RIGHT) {
            val diameter = min(el.width * viewW, el.height * viewH).coerceAtLeast(52f)
            val half = diameter / 2f
            RectF(cx - half, cy - half, cx + half, cy + half)
        } else {
            val w = (el.width * viewW).coerceAtLeast(60f)
            val h = (el.height * viewH).coerceAtLeast(44f)
            RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }
    }

    fun drawGrid(canvas: Canvas, viewW: Float, viewH: Float, stepFraction: Float = 0.05f) {
        var fx = stepFraction
        while (fx < 1f) {
            canvas.drawLine(fx * viewW, 0f, fx * viewW, viewH, gridPaint)
            fx += stepFraction
        }
        var fy = stepFraction
        while (fy < 1f) {
            canvas.drawLine(0f, fy * viewH, viewW, fy * viewH, gridPaint)
            fy += stepFraction
        }
    }

    fun drawControl(
        canvas: Canvas,
        el: ControlElement,
        viewW: Float,
        viewH: Float,
        state: ControlVisualState = ControlVisualState(),
        selectedInEditor: Boolean = false,
        globalOpacityScale: Float = 1f,
    ) {
        val rect = computeRect(el, viewW, viewH)
        val accent = parseColorSafe(el.colorHex)
        val opScale = globalOpacityScale.coerceIn(0.15f, 1f)
        val alphaBase = (el.opacity.coerceIn(0.25f, 1f) * opScale * 255).toInt().coerceIn(28, 255)
        val labelAlpha = (150 + (opScale * 105f)).toInt().coerceIn(155, 255)
        val active = state.pressed || state.toggledOn

        when (el.type) {
            ControlType.JOYSTICK_LEFT, ControlType.JOYSTICK_RIGHT -> {
                val radius = rect.width() / 2f
                val cx = rect.centerX()
                val cy = rect.centerY()

                // Outer base well
                bgFill.color = withAlpha(Color.parseColor("#111A2E"), (alphaBase * 0.88f).toInt())
                canvas.drawCircle(cx, cy, radius, bgFill)

                strokePaint.color = withAlpha(accent, if (active) 255 else (alphaBase * 0.72f).toInt())
                strokePaint.strokeWidth = if (active) 5f else 3f
                canvas.drawCircle(cx, cy, radius, strokePaint)

                // Subtle crosshair lines
                strokePaint.color = withAlpha(Color.parseColor("#334155"), 120)
                strokePaint.strokeWidth = 2f
                canvas.drawLine(cx - radius * 0.65f, cy, cx + radius * 0.65f, cy, strokePaint)
                canvas.drawLine(cx, cy - radius * 0.65f, cx, cy + radius * 0.65f, strokePaint)

                // Movable thumbstick knob
                val knobR = radius * 0.42f
                val maxTravel = radius - knobR * 0.75f
                val kx = cx + state.stickX.coerceIn(-1f, 1f) * maxTravel
                val ky = cy + state.stickY.coerceIn(-1f, 1f) * maxTravel

                bgFill.color = withAlpha(if (active) accent else Color.parseColor("#1E2C4A"), (if (active) 220 else (alphaBase * 0.95f).toInt()).coerceIn(60, 240))
                canvas.drawCircle(kx, ky, knobR, bgFill)
                strokePaint.color = withAlpha(accent, labelAlpha)
                strokePaint.strokeWidth = 4f
                canvas.drawCircle(kx, ky, knobR, strokePaint)

                textPaint.textSize = (knobR * 0.72f).coerceIn(16f, 36f)
                textPaint.color = withAlpha(Color.WHITE, labelAlpha)
                drawCenteredText(canvas, el.label, kx, ky, textPaint)
            }

            ControlType.DPAD -> {
                val cx = rect.centerX()
                val cy = rect.centerY()
                val armW = rect.width() * 0.34f
                val armH = rect.height() * 0.34f

                // Soft backing circle
                bgFill.color = withAlpha(Color.parseColor("#0F172A"), (alphaBase * 0.75f).toInt())
                canvas.drawCircle(cx, cy, min(rect.width(), rect.height()) * 0.52f, bgFill)

                val upRect = RectF(cx - armW / 2f, rect.top, cx + armW / 2f, cy - armH / 2f + 4f)
                val dnRect = RectF(cx - armW / 2f, cy + armH / 2f - 4f, cx + armW / 2f, rect.bottom)
                val lfRect = RectF(rect.left, cy - armH / 2f, cx - armW / 2f + 4f, cy + armH / 2f)
                val rtRect = RectF(cx + armW / 2f - 4f, cy - armH / 2f, rect.right, cy + armH / 2f)
                val centerRect = RectF(cx - armW / 2f, cy - armH / 2f, cx + armW / 2f, cy + armH / 2f)

                drawDpadArm(canvas, upRect, "▲", (state.dpadMask and 0x0001) != 0, accent, alphaBase)
                drawDpadArm(canvas, dnRect, "▼", (state.dpadMask and 0x0002) != 0, accent, alphaBase)
                drawDpadArm(canvas, lfRect, "◀", (state.dpadMask and 0x0004) != 0, accent, alphaBase)
                drawDpadArm(canvas, rtRect, "▶", (state.dpadMask and 0x0008) != 0, accent, alphaBase)

                bgFill.color = withAlpha(Color.parseColor("#18223A"), alphaBase)
                canvas.drawRect(centerRect, bgFill)
            }

            ControlType.TRACKPAD -> {
                val r = 24f
                bgFill.color = withAlpha(if (active) Color.parseColor("#18294A") else Color.parseColor("#10192D"), (alphaBase * 0.85f).toInt())
                canvas.drawRoundRect(rect, r, r, bgFill)

                strokePaint.color = withAlpha(accent, if (active) 255 else (alphaBase * 0.8f).toInt())
                strokePaint.strokeWidth = if (active) 4.5f else 2.5f
                canvas.drawRoundRect(rect, r, r, strokePaint)

                textPaint.textSize = (min(rect.width(), rect.height()) * 0.11f).coerceIn(18f, 34f)
                textPaint.color = withAlpha(Color.WHITE, 190)
                drawCenteredText(canvas, el.label, rect.centerX(), rect.centerY() - 12f, textPaint)

                subTextPaint.textSize = (textPaint.textSize * 0.65f).coerceIn(13f, 22f)
                drawCenteredText(canvas, "Glisser : souris · Tap : clic · 2 doigts : molette", rect.centerX(), rect.centerY() + 24f, subTextPaint)
            }

            else -> {
                val cornerRadius = when (el.shape) {
                    ControlShape.CIRCLE -> rect.width() / 2f
                    ControlShape.PILL -> min(rect.width(), rect.height()) / 2f
                    ControlShape.ROUND_RECT -> 20f
                }

                // Glow halo when pressed or latched
                if (active) {
                    bgFill.color = withAlpha(accent, 90)
                    val halo = RectF(rect.left - 7f, rect.top - 7f, rect.right + 7f, rect.bottom + 7f)
                    canvas.drawRoundRect(halo, cornerRadius + 7f, cornerRadius + 7f, bgFill)
                }

                bgFill.color = if (active) {
                    withAlpha(accent, 230)
                } else {
                    withAlpha(Color.parseColor("#162138"), alphaBase)
                }
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgFill)

                strokePaint.color = if (state.toggledOn) {
                    Color.parseColor("#FACC15")
                } else {
                    withAlpha(accent, if (active) 255 else (alphaBase * 0.9f).toInt())
                }
                strokePaint.strokeWidth = if (active || state.toggledOn) 5f else 3f
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

                textPaint.textSize = (min(rect.width(), rect.height()) * 0.36f).coerceIn(16f, 42f)
                textPaint.color = if (active) Color.WHITE else withAlpha(Color.parseColor("#F1F5F9"), labelAlpha)
                drawCenteredText(canvas, el.label, rect.centerX(), rect.centerY(), textPaint)

                // Small indicator badges for Toggle / Turbo
                if (el.toggleMode || el.turboHz > 0) {
                    subTextPaint.textSize = 13f
                    subTextPaint.color = if (state.toggledOn) Color.parseColor("#FACC15") else Color.parseColor("#94A3B8")
                    val badge = buildString {
                        if (el.toggleMode) append("LOCK")
                        if (el.toggleMode && el.turboHz > 0) append("·")
                        if (el.turboHz > 0) append("T")
                    }
                    drawCenteredText(canvas, badge, rect.centerX(), rect.bottom - 12f, subTextPaint)
                }
            }
        }

        // Editor selection box + corner resize handle
        if (selectedInEditor) {
            val sel = RectF(rect.left - 8f, rect.top - 8f, rect.right + 8f, rect.bottom + 8f)
            canvas.drawRoundRect(sel, 16f, 16f, selectionPaint)

            // Bottom-right resize knob
            val hx = sel.right
            val hy = sel.bottom
            bgFill.color = Color.parseColor("#38BDF8")
            canvas.drawCircle(hx, hy, 18f, bgFill)
            textPaint.textSize = 20f
            textPaint.color = Color.parseColor("#090D1A")
            drawCenteredText(canvas, "⤡", hx, hy, textPaint)
        }
    }

    private fun drawDpadArm(canvas: Canvas, rect: RectF, arrow: String, pressed: Boolean, accent: Int, alpha: Int) {
        bgFill.color = if (pressed) withAlpha(accent, 235) else withAlpha(Color.parseColor("#1D2A45"), alpha)
        canvas.drawRoundRect(rect, 10f, 10f, bgFill)
        strokePaint.color = withAlpha(if (pressed) Color.WHITE else accent, if (pressed) 255 else 150)
        strokePaint.strokeWidth = 2.5f
        canvas.drawRoundRect(rect, 10f, 10f, strokePaint)
        textPaint.textSize = (min(rect.width(), rect.height()) * 0.45f).coerceIn(14f, 28f)
        textPaint.color = if (pressed) Color.WHITE else Color.parseColor("#CBD5E1")
        drawCenteredText(canvas, arrow, rect.centerX(), rect.centerY(), textPaint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val fm = paint.fontMetrics
        val baseline = y - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    fun parseColorSafe(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        Color.parseColor("#38BDF8")
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
