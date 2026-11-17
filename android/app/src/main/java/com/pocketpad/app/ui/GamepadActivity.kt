package com.pocketpad.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
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
import com.pocketpad.app.protocol.PocketPadConstants
import com.pocketpad.app.protocol.RumbleCommand
import com.pocketpad.app.transport.ConnectionManager
import com.pocketpad.app.transport.ConnectionState
import com.pocketpad.app.transport.VideoStreamClient
import com.pocketpad.app.transport.VideoStreamSnapshot
import kotlin.math.roundToInt

class GamepadActivity : AppCompatActivity() {

    private lateinit var repo: ProfileRepository
    private lateinit var haptics: HapticsEngine
    private lateinit var gyro: GyroController
    private lateinit var surface: GamepadSurfaceView
    private lateinit var playerBadge: TextView
    private lateinit var statusPill: TextView
    private lateinit var btnScreen: TextView
    private lateinit var btnOpacity: TextView
    private lateinit var btnGyro: TextView
    private lateinit var btnRecenter: TextView
    private lateinit var topAccentStrip: View

    private lateinit var activeProfile: LayoutProfile
    private var gyroActive = false
    private var latestConnState: ConnectionState = ConnectionManager.currentState
    private var latestVideoSnap: VideoStreamSnapshot = VideoStreamClient.currentSnapshot

    private val connListener: (ConnectionState) -> Unit = { st ->
        latestConnState = st
        syncVideoStreamWithConnection(st)
        runOnUiThread { updateStatusPill(st, latestVideoSnap) }
    }

    private val videoFrameListener: (Bitmap, VideoStreamSnapshot) -> Unit = { bmp, snap ->
        latestVideoSnap = snap
        surface.setVideoFrame(bmp)
    }

    private val videoStatusListener: (VideoStreamSnapshot) -> Unit = { snap ->
        latestVideoSnap = snap
        runOnUiThread { updateStatusPill(latestConnState, snap) }
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
        surface.setVideoDisplayOptions(repo.videoEnabled, repo.controlsOpacity, repo.videoFillScreen)

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

        // Subtle player color glow strip at very top of screen
        topAccentStrip = View(this).apply {
            setBackgroundColor(Color.parseColor("#38BDF8"))
        }
        root.addView(
            topAccentStrip,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP)
        )

        // Translucent floating top mini HUD bar
        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#BB0F172A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        playerBadge = TextView(this).apply {
            text = "J1"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#090D1A"))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { showQuickSlotPicker() }
        }
        hud.addView(playerBadge)

        statusPill = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(7), dp(2), dp(6), dp(2))
        }
        hud.addView(statusPill)

        btnScreen = makeHudChip("Écran: ON") { toggleVideoScreen() }
        hud.addView(btnScreen)

        btnOpacity = makeHudChip("Opacité") { showDisplaySettingsDialog() }
        hud.addView(btnOpacity)

        hud.addView(makeHudChip("Profils") { showQuickProfilePicker() })
        btnGyro = makeHudChip("Gyro") { toggleGyro() }
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
        updateScreenButtonUi()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        activeProfile = repo.getActiveProfile()
        applyProfile(activeProfile)
        surface.setVideoDisplayOptions(repo.videoEnabled, repo.controlsOpacity, repo.videoFillScreen)
        updateScreenButtonUi()

        ConnectionManager.addStateListener(connListener)
        ConnectionManager.addRumbleListener(rumbleListener)
        VideoStreamClient.addFrameListener(videoFrameListener)
        VideoStreamClient.addStatusListener(videoStatusListener)
        syncVideoStreamWithConnection(ConnectionManager.currentState)
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
        VideoStreamClient.removeFrameListener(videoFrameListener)
        VideoStreamClient.removeStatusListener(videoStatusListener)
        VideoStreamClient.updateSubscription(latestConnState.playerSlot.coerceAtLeast(1), repo.videoQuality, isPaused = true)
    }

    private fun syncVideoStreamWithConnection(st: ConnectionState) {
        if (!repo.videoEnabled) {
            VideoStreamClient.stop("Écran coupé")
            surface.setVideoFrame(null)
            return
        }

        if (!st.connected) {
            VideoStreamClient.stop("Hors-ligne")
            surface.setVideoFrame(null)
            return
        }

        if (!st.serverVideoEnabled) {
            VideoStreamClient.stop("Vidéo en pause sur PC")
            surface.setVideoFrame(null)
            return
        }

        val host = st.videoHost
        if (host.isBlank()) {
            VideoStreamClient.stop("Vidéo requiert USB ou Wi-Fi")
            return
        }

        VideoStreamClient.start(
            host = host,
            port = st.videoPort,
            slot = st.playerSlot.coerceIn(1, PocketPadConstants.MAX_PLAYERS),
            jpegQuality = repo.videoQuality
        )
    }

    private fun toggleVideoScreen() {
        repo.videoEnabled = !repo.videoEnabled
        surface.setVideoDisplayOptions(repo.videoEnabled, repo.controlsOpacity, repo.videoFillScreen)
        updateScreenButtonUi()
        syncVideoStreamWithConnection(latestConnState)
        Toast.makeText(
            this,
            if (repo.videoEnabled) "Écran de jeu activé sur le téléphone" else "Écran coupé (mode manette seule)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateScreenButtonUi() {
        if (repo.videoEnabled) {
            btnScreen.text = "Écran: ON"
            (btnScreen.background as? GradientDrawable)?.setColor(Color.parseColor("#0284C7"))
        } else {
            btnScreen.text = "Écran: OFF"
            (btnScreen.background as? GradientDrawable)?.setColor(Color.parseColor("#1E293B"))
        }
    }

    private fun showDisplaySettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val opacityLabel = TextView(this).apply {
            text = "Opacité des boutons sur la vidéo : ${(repo.controlsOpacity * 100).roundToInt()} %"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(opacityLabel)

        val seekOpacity = SeekBar(this).apply {
            max = 85 // maps 0..85 => 15%..100%
            progress = ((repo.controlsOpacity * 100f).roundToInt() - 15).coerceIn(0, 85)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val pct = p + 15
                    repo.controlsOpacity = pct / 100f
                    opacityLabel.text = "Opacité des boutons sur la vidéo : $pct %"
                    surface.setVideoDisplayOptions(repo.videoEnabled, repo.controlsOpacity, repo.videoFillScreen)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seekOpacity)

        val switchFill = Switch(this).apply {
            text = "Remplir tout l’écran (zoom sans bandes noires)"
            isChecked = repo.videoFillScreen
            setPadding(0, dp(14), 0, dp(14))
            setOnCheckedChangeListener { _, checked ->
                repo.videoFillScreen = checked
                surface.setVideoDisplayOptions(repo.videoEnabled, repo.controlsOpacity, repo.videoFillScreen)
            }
        }
        box.addView(switchFill)

        val qualityLabel = TextView(this).apply {
            text = "Qualité vidéo demandée : ${repo.videoQuality} %"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(qualityLabel)

        val seekQuality = SeekBar(this).apply {
            max = 50 // maps 0..50 => 35..85
            progress = (repo.videoQuality - 35).coerceIn(0, 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val q = p + 35
                    repo.videoQuality = q
                    qualityLabel.text = "Qualité vidéo demandée : $q %"
                    if (repo.videoEnabled && latestConnState.connected) {
                        VideoStreamClient.updateSubscription(
                            latestConnState.playerSlot.coerceIn(1, PocketPadConstants.MAX_PLAYERS),
                            q,
                            isPaused = false
                        )
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seekQuality)

        AlertDialog.Builder(this)
            .setTitle("Affichage Mode Switch")
            .setView(box)
            .setPositiveButton("OK", null)
            .show()
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
            btnGyro.text = "Gyro"
            (btnGyro.background as? GradientDrawable)?.setColor(Color.parseColor("#1E293B"))
            btnRecenter.visibility = View.GONE
        }
    }

    private fun updateStatusPill(st: ConnectionState, vid: VideoStreamSnapshot) {
        if (st.connected) {
            val slot = st.playerSlot.coerceIn(1, PocketPadConstants.MAX_PLAYERS)
            val colorHex = st.slotColorHex.ifBlank { PocketPadConstants.slotColorHex(slot) }
            val slotColor = Color.parseColor(colorHex)
            playerBadge.visibility = View.VISIBLE
            playerBadge.text = "J$slot"
            (playerBadge.background as? GradientDrawable)?.setColor(slotColor)
            topAccentStrip.setBackgroundColor(slotColor)

            val pingPart = if (st.pingMs >= 0) "${st.pingMs}ms" else st.transportLabel
            val fpsPart = if (repo.videoEnabled && vid.connected && vid.activeFps > 0) " · ${vid.activeFps}fps" else ""
            val menuPart = if (repo.videoEnabled && vid.connected && vid.isFullMenuView) " · Menu" else ""
            val mpPart = if (st.connectedPlayers > 1) " (${st.connectedPlayers}/${st.maxPlayers}J)" else ""
            statusPill.text = "● $pingPart$fpsPart$menuPart$mpPart"
            statusPill.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            playerBadge.visibility = View.GONE
            topAccentStrip.setBackgroundColor(Color.parseColor("#334155"))
            statusPill.text = "○ Hors-ligne"
            statusPill.setTextColor(Color.parseColor("#FBBF24"))
        }
    }

    private fun showQuickSlotPicker() {
        val options = arrayOf(
            "Attribution automatique",
            "Joueur 1 (Cyan)",
            "Joueur 2 (Violet)",
            "Joueur 3 (Vert)",
            "Joueur 4 (Orange)",
        )
        AlertDialog.Builder(this)
            .setTitle("Changer de place multijoueur")
            .setItems(options) { _, which ->
                repo.preferredSlot = which
                ConnectionManager.requestPreferredSlot(which)
                val label = if (which == 0) "Mode automatique" else "Demande envoyée pour Joueur $which (si libre)"
                Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            }
            .show()
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
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(4) }
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
