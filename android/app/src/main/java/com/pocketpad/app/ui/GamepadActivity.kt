package com.pocketpad.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pocketpad.app.input.GyroController
import com.pocketpad.app.input.HapticsEngine
import com.pocketpad.app.model.GyroMode
import com.pocketpad.app.model.LayoutProfile
import com.pocketpad.app.model.ProfileRepository
import com.pocketpad.app.protocol.RumbleCommand
import com.pocketpad.app.transport.ConnectionManager
import com.pocketpad.app.transport.ConnectionState

class GamepadActivity : AppCompatActivity() {

    private lateinit var repo: ProfileRepository
    private lateinit var haptics: HapticsEngine
    private lateinit var gyro: GyroController
    private lateinit var surface: GamepadSurfaceView
    private lateinit var statusPill: TextView
    private lateinit var btnGyro: TextView
    private lateinit var btnRecenter: TextView

    private lateinit var activeProfile: LayoutProfile
    private var gyroActive = false

    private val connListener: (ConnectionState) -> Unit = { st ->
        runOnUiThread { updateStatusPill(st) }
    }

    private val rumbleListener: (RumbleCommand) -> Unit = { rumble ->
        haptics.applyGameRumble(rumble.largeMotor, rumble.smallMotor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        repo = ProfileRepository(this)
        haptics = HapticsEngine(this)
        activeProfile = repo.getActiveProfile()
        surface = GamepadSurfaceView(this, haptics)

        gyro = GyroController(this) { sx, sy ->
            surface.setGyroInput(gyroActive, sx, sy)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#090D1A"))
            addView(
                surface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // Translucent floating top mini HUD bar
        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA0F172A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        statusPill = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(6), dp(2), dp(8), dp(2))
        }
        hud.addView(statusPill)

        hud.addView(makeHudChip("Profils") { showQuickProfilePicker() })
        btnGyro = makeHudChip("Gyro: OFF") { toggleGyro() }
        hud.addView(btnGyro)
        btnRecenter = makeHudChip("Centrer") {
            gyro.recalibrate()
            Toast.makeText(this, "Centre gyroscope recalibré !", Toast.LENGTH_SHORT).show()
        }.apply { visibility = View.GONE }
        hud.addView(btnRecenter)
        hud.addView(makeHudChip("Éditer") { openEditor() })
        hud.addView(makeHudChip("Quitter", "#7F1D1D") { finish() })

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(6) }
        root.addView(hud, hudParams)

        setContentView(root)
        applyProfile(activeProfile)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        // Reload in case user edited profile in EditorActivity
        activeProfile = repo.getActiveProfile()
        applyProfile(activeProfile)
        ConnectionManager.addStateListener(connListener)
        ConnectionManager.addRumbleListener(rumbleListener)
        surface.startTurboLoop()
    }

    override fun onPause() {
        super.onPause()
        surface.stopTurboLoop()
        surface.releaseAllInputs()
        gyro.stop()
        haptics.stop()
        ConnectionManager.removeStateListener(connListener)
        ConnectionManager.removeRumbleListener(rumbleListener)
    }

    private fun applyProfile(profile: LayoutProfile) {
        activeProfile = profile
        surface.setProfile(profile)
        gyro.sensitivity = profile.gyroSensitivity
        gyroActive = profile.gyroMode != GyroMode.OFF
        updateGyroUi()
        ConnectionManager.notifyActiveProfile(profile.name)
    }

    private fun toggleGyro() {
        if (activeProfile.gyroMode == GyroMode.OFF) {
            // Enable steering wheel mode on the fly!
            activeProfile = activeProfile.copy(gyroMode = GyroMode.STEERING_LEFT_STICK)
            surface.setProfile(activeProfile)
            gyroActive = true
        } else {
            gyroActive = !gyroActive
        }
        updateGyroUi()
    }

    private fun updateGyroUi() {
        if (gyroActive) {
            gyro.start()
            btnGyro.text = "Gyro: ON"
            (btnGyro.background as? GradientDrawable)?.setColor(Color.parseColor("#0284C7"))
            btnRecenter.visibility = View.VISIBLE
        } else {
            gyro.stop()
            btnGyro.text = "Gyro: OFF"
            (btnGyro.background as? GradientDrawable)?.setColor(Color.parseColor("#1E293B"))
            btnRecenter.visibility = View.GONE
        }
    }

    private fun updateStatusPill(st: ConnectionState) {
        statusPill.text = if (st.connected) {
            val pingPart = if (st.pingMs >= 0) " · ${st.pingMs}ms" else ""
            "● ${st.transportLabel}$pingPart"
        } else {
            "○ Mode aperçu (hors-ligne)"
        }
        statusPill.setTextColor(
            Color.parseColor(if (st.connected) "#4ADE80" else "#FBBF24")
        )
    }

    private fun showQuickProfilePicker() {
        val profiles = repo.getAllProfiles()
        val names = profiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Changer de manette")
            .setItems(names) { _, which ->
                val chosen = profiles[which]
                repo.setActiveProfileId(chosen.id)
                applyProfile(chosen)
            }
            .show()
    }

    private fun openEditor() {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_PROFILE_ID, activeProfile.id)
        startActivity(intent)
    }

    private fun makeHudChip(label: String, bgHex: String = "#1E293B", onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(5) }
            setOnClickListener { onClick() }
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
