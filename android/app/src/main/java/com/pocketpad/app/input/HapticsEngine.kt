package com.pocketpad.app.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticsEngine(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var enabled: Boolean = true

    fun tapTick() {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(12L, 70))
            }
        } catch (_: Exception) {
        }
    }

    fun applyGameRumble(largeMotor: Int, smallMotor: Int) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val strength = maxOf(largeMotor, (smallMotor * 0.75f).toInt()).coerceIn(0, 255)
            if (strength < 12) {
                v.cancel()
                return
            }
            val durationMs = if (largeMotor > 120) 240L else 140L
            val amplitude = if (v.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }
}
