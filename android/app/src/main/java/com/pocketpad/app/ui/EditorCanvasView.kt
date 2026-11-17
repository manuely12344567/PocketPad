package com.pocketpad.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import com.pocketpad.app.model.ControlElement
import com.pocketpad.app.model.ControlType
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Interactive drag-and-drop & resize Canvas for customizing any PocketPad controller layout.
 */
class EditorCanvasView(
    context: Context,
    private val onSelectionChanged: (ControlElement?) -> Unit,
    private val onDoubleTapSelected: (ControlElement) -> Unit,
) : View(context) {

    var elements: MutableList<ControlElement> = mutableListOf()
    var selectedId: String? = null
        private set
    var snapToGrid: Boolean = true

    private var draggingId: String? = null
    private var resizingId: String? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var resizeStartW = 0f
    private var resizeStartH = 0f
    private var resizeStartTouchX = 0f
    private var resizeStartTouchY = 0f
    private var lastTapTime = 0L
    private var lastTapId: String? = null

    init {
        setBackgroundColor(Color.parseColor("#090D1A"))
    }

    fun selectById(id: String?) {
        selectedId = id
        onSelectionChanged(getSelected())
        invalidate()
    }

    fun getSelected(): ControlElement? = elements.firstOrNull { it.id == selectedId }

    fun updateSelected(transform: (ControlElement) -> ControlElement) {
        val idx = elements.indexOfFirst { it.id == selectedId }
        if (idx >= 0) {
            elements[idx] = transform(elements[idx])
            onSelectionChanged(elements[idx])
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (snapToGrid) {
            GamepadRenderer.drawGrid(canvas, w, h, 0.05f)
        }

        val ordered = elements.sortedBy {
            when {
                it.id == selectedId -> 2
                it.type == ControlType.TRACKPAD -> 0
                else -> 1
            }
        }
        for (el in ordered) {
            GamepadRenderer.drawControl(
                canvas,
                el,
                w,
                h,
                state = ControlVisualState(),
                selectedInEditor = el.id == selectedId,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 1. Check resize handle of currently selected control first
                val curSel = getSelected()
                if (curSel != null) {
                    val rect = GamepadRenderer.computeRect(curSel, w, h)
                    val hx = rect.right + 8f
                    val hy = rect.bottom + 8f
                    if (hypot(x - hx, y - hy) <= 44f) {
                        resizingId = curSel.id
                        resizeStartW = curSel.width
                        resizeStartH = curSel.height
                        resizeStartTouchX = x
                        resizeStartTouchY = y
                        return true
                    }
                }

                // 2. Hit-test controls (non-trackpad priority, then trackpad)
                val hit = hitTest(x, y, w, h)
                if (hit != null) {
                    val now = System.currentTimeMillis()
                    val isDoubleTap = (hit.id == lastTapId) && (now - lastTapTime < 340L)
                    lastTapId = hit.id
                    lastTapTime = now

                    selectedId = hit.id
                    onSelectionChanged(hit)
                    draggingId = hit.id
                    dragOffsetX = x - (hit.x * w)
                    dragOffsetY = y - (hit.y * h)
                    invalidate()

                    if (isDoubleTap) {
                        draggingId = null
                        onDoubleTapSelected(hit)
                    }
                } else {
                    selectedId = null
                    draggingId = null
                    resizingId = null
                    onSelectionChanged(null)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val rId = resizingId
                if (rId != null) {
                    val idx = elements.indexOfFirst { it.id == rId }
                    if (idx >= 0) {
                        val el = elements[idx]
                        val dxNorm = ((x - resizeStartTouchX) * 2f) / w
                        val dyNorm = ((y - resizeStartTouchY) * 2f) / h
                        val newW = (resizeStartW + dxNorm).coerceIn(0.05f, 0.70f)
                        val newH = (resizeStartH + dyNorm).coerceIn(0.07f, 0.75f)
                        elements[idx] = el.copy(width = newW, height = newH)
                        invalidate()
                    }
                    return true
                }

                val dId = draggingId
                if (dId != null) {
                    val idx = elements.indexOfFirst { it.id == dId }
                    if (idx >= 0) {
                        val el = elements[idx]
                        var nx = ((x - dragOffsetX) / w).coerceIn(0.04f, 0.96f)
                        var ny = ((y - dragOffsetY) / h).coerceIn(0.08f, 0.94f)
                        if (snapToGrid) {
                            nx = snap(nx, 0.025f)
                            ny = snap(ny, 0.025f)
                        }
                        elements[idx] = el.copy(x = nx, y = ny)
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingId = null
                resizingId = null
                onSelectionChanged(getSelected())
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float, w: Float, h: Float): ControlElement? {
        val ordered = elements.sortedByDescending {
            when {
                it.id == selectedId -> 3
                it.type == ControlType.TRACKPAD -> 0
                else -> 1
            }
        }
        for (el in ordered) {
            val rect = GamepadRenderer.computeRect(el, w, h)
            if (x >= rect.left - 12f && x <= rect.right + 12f && y >= rect.top - 12f && y <= rect.bottom + 12f) {
                return el
            }
        }
        return null
    }

    private fun snap(value: Float, step: Float): Float =
        ((value / step).roundToInt() * step).coerceIn(0.04f, 0.96f)
}
