package com.pocketpad.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pocketpad.app.model.ControlElement
import com.pocketpad.app.model.ControlShape
import com.pocketpad.app.model.ControlType
import com.pocketpad.app.model.GyroMode
import com.pocketpad.app.model.InputCatalog
import com.pocketpad.app.model.LayoutProfile
import com.pocketpad.app.model.ProfileRepository
import com.pocketpad.app.protocol.GamepadButtons
import java.util.UUID

class EditorActivity : AppCompatActivity() {

    private lateinit var repo: ProfileRepository
    private lateinit var profile: LayoutProfile
    private lateinit var canvasView: EditorCanvasView

    private lateinit var txtSelectedHint: TextView
    private lateinit var btnProps: TextView
    private lateinit var btnDuplicate: TextView
    private lateinit var btnDelete: TextView
    private lateinit var btnGrid: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()

        repo = ProfileRepository(this)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        profile = if (profileId != null) repo.getProfileById(profileId) else repo.getActiveProfile()

        canvasView = EditorCanvasView(
            context = this,
            onSelectionChanged = { sel -> updateSelectionToolbar(sel) },
            onDoubleTapSelected = { sel -> showElementPropertiesDialog(sel) },
        )
        canvasView.elements = profile.elements.toMutableList()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#090D1A"))
            addView(
                canvasView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // Top floating editor toolbar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D90F172A"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        bar.addView(makeBarBtn("+ Ajouter", "#0284C7") { showAddControlPalette() })
        btnProps = makeBarBtn("Propriétés", "#1E293B") {
            canvasView.getSelected()?.let { showElementPropertiesDialog(it) }
        }
        bar.addView(btnProps)

        btnDuplicate = makeBarBtn("Dupliquer", "#1E293B") { duplicateSelected() }
        bar.addView(btnDuplicate)

        btnDelete = makeBarBtn("Supprimer", "#9F1239") { deleteSelected() }
        bar.addView(btnDelete)

        btnGrid = makeBarBtn("Grille: ON", "#1D4ED8") { toggleGrid() }
        bar.addView(btnGrid)

        bar.addView(makeBarBtn("Profil", "#334155") { showProfileSettingsDialog() })
        bar.addView(makeBarBtn("Sauvegarder", "#15803D") { saveAndExit() })
        bar.addView(makeBarBtn("Annuler", "#475569") { finish() })

        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(6) }
        root.addView(bar, barParams)

        // Bottom hint pill
        txtSelectedHint = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C00F172A"))
                cornerRadius = dp(14).toFloat()
            }
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(6) }
        root.addView(txtSelectedHint, hintParams)

        setContentView(root)
        updateSelectionToolbar(null)
    }

    private fun updateSelectionToolbar(selected: ControlElement?) {
        val enabled = selected != null
        btnProps.alpha = if (enabled) 1f else 0.4f
        btnProps.isEnabled = enabled
        btnDuplicate.alpha = if (enabled) 1f else 0.4f
        btnDuplicate.isEnabled = enabled
        btnDelete.alpha = if (enabled) 1f else 0.4f
        btnDelete.isEnabled = enabled

        txtSelectedHint.text = if (selected == null) {
            "Éditeur · ${profile.name} — Touche un bouton pour déplacer/redimensionner · Double-tap pour modifier"
        } else {
            val actionDesc = when (selected.type) {
                ControlType.BUTTON -> InputCatalog.xboxButtonName(selected.xboxButtonBit)
                ControlType.KEYBOARD_KEY -> InputCatalog.keyName(selected.vkCode)
                ControlType.MOUSE_BUTTON -> InputCatalog.mouseName(selected.mouseButton)
                ControlType.JOYSTICK_LEFT -> if (selected.joystickAsWasd) "Joystick → ${if (selected.wasdAzerty) "ZQSD" else "WASD"}" else "Joystick Gauche"
                ControlType.JOYSTICK_RIGHT -> "Joystick Droit"
                ControlType.DPAD -> "Croix D-Pad 8 directions"
                ControlType.TRIGGER_LEFT -> "Gâchette LT"
                ControlType.TRIGGER_RIGHT -> "Gâchette RT"
                ControlType.TRACKPAD -> "Pavé tactile souris"
            }
            "Sélection : « ${selected.label} » ($actionDesc) — Glisse pour placer · Poignée ⤡ pour redimensionner"
        }
    }

    private fun toggleGrid() {
        canvasView.snapToGrid = !canvasView.snapToGrid
        btnGrid.text = if (canvasView.snapToGrid) "Grille: ON" else "Grille: OFF"
        (btnGrid.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (canvasView.snapToGrid) "#1D4ED8" else "#1E293B")
        )
        canvasView.invalidate()
    }

    private fun duplicateSelected() {
        val sel = canvasView.getSelected() ?: return
        val copy = sel.copy(
            id = "el_${UUID.randomUUID().toString().take(6)}",
            x = (sel.x + 0.06f).coerceAtMost(0.92f),
            y = (sel.y + 0.06f).coerceAtMost(0.90f),
        )
        canvasView.elements.add(copy)
        canvasView.selectById(copy.id)
    }

    private fun deleteSelected() {
        val sel = canvasView.getSelected() ?: return
        canvasView.elements.removeAll { it.id == sel.id }
        canvasView.selectById(null)
    }

    private fun showAddControlPalette() {
        val items = arrayOf(
            "Bouton Manette Xbox (A, B, X, Y, LB, RB…)",
            "Joystick Analogique Gauche (ou WASD/ZQSD)",
            "Joystick Analogique Droit",
            "Croix Directionnelle (D-Pad 8 directions)",
            "Gâchette Gauche (LT / L2)",
            "Gâchette Droite (RT / R2)",
            "Touche Clavier PC (Espace, Shift, R, E…)",
            "Bouton Souris / Molette (Tir, Viser…)",
            "Pavé Tactile Souris (Trackpad)",
        )

        AlertDialog.Builder(this)
            .setTitle("Ajouter un contrôle")
            .setItems(items) { _, which ->
                val uid = "el_${UUID.randomUUID().toString().take(6)}"
                val created = when (which) {
                    0 -> ControlElement(uid, ControlType.BUTTON, "A", 0.50f, 0.50f, 0.09f, 0.16f, ControlShape.CIRCLE, "#22C55E", xboxButtonBit = GamepadButtons.A)
                    1 -> ControlElement(uid, ControlType.JOYSTICK_LEFT, "L", 0.25f, 0.55f, 0.20f, 0.36f, ControlShape.CIRCLE, "#38BDF8")
                    2 -> ControlElement(uid, ControlType.JOYSTICK_RIGHT, "R", 0.72f, 0.60f, 0.20f, 0.36f, ControlShape.CIRCLE, "#8B5CF6")
                    3 -> ControlElement(uid, ControlType.DPAD, "D-PAD", 0.30f, 0.55f, 0.18f, 0.32f, ControlShape.ROUND_RECT, "#94A3B8")
                    4 -> ControlElement(uid, ControlType.TRIGGER_LEFT, "LT", 0.20f, 0.25f, 0.14f, 0.13f, ControlShape.PILL, "#38BDF8")
                    5 -> ControlElement(uid, ControlType.TRIGGER_RIGHT, "RT", 0.80f, 0.25f, 0.14f, 0.13f, ControlShape.PILL, "#8B5CF6")
                    6 -> ControlElement(uid, ControlType.KEYBOARD_KEY, "ESPACE", 0.50f, 0.55f, 0.13f, 0.13f, ControlShape.PILL, "#22C55E", vkCode = 0x20)
                    7 -> ControlElement(uid, ControlType.MOUSE_BUTTON, "TIR", 0.78f, 0.50f, 0.11f, 0.19f, ControlShape.CIRCLE, "#F43F5E", mouseButton = 1)
                    else -> ControlElement(uid, ControlType.TRACKPAD, "TRACKPAD", 0.55f, 0.52f, 0.36f, 0.52f, ControlShape.ROUND_RECT, "#38BDF8", opacity = 0.65f)
                }
                canvasView.elements.add(created)
                canvasView.selectById(created.id)
                showElementPropertiesDialog(created)
            }
            .show()
    }

    private fun showElementPropertiesDialog(el: ControlElement) {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        scroll.addView(box)

        // Label
        box.addView(makeFieldLabel("Nom affiché sur le bouton"))
        val edtLabel = EditText(this).apply {
            setText(el.label)
            setTextColor(Color.WHITE)
        }
        box.addView(edtLabel)

        // Action selector depending on control type
        var spinnerXbox: Spinner? = null
        var spinnerKb: Spinner? = null
        var spinnerMouse: Spinner? = null
        var cbWasd: CheckBox? = null
        var cbAzerty: CheckBox? = null

        when (el.type) {
            ControlType.BUTTON -> {
                box.addView(makeFieldLabel("Action Manette Xbox 360"))
                val labels = InputCatalog.xboxButtons.map { it.first }
                spinnerXbox = Spinner(this).apply {
                    adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                    val idx = InputCatalog.xboxButtons.indexOfFirst { it.second == el.xboxButtonBit }.coerceAtLeast(0)
                    setSelection(idx)
                }
                box.addView(spinnerXbox)
            }

            ControlType.KEYBOARD_KEY -> {
                box.addView(makeFieldLabel("Touche Clavier PC"))
                val labels = InputCatalog.keyboardKeys.map { it.label }
                spinnerKb = Spinner(this).apply {
                    adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                    val idx = InputCatalog.keyboardKeys.indexOfFirst { it.vkCode == el.vkCode }.coerceAtLeast(0)
                    setSelection(idx)
                }
                box.addView(spinnerKb)
            }

            ControlType.MOUSE_BUTTON -> {
                box.addView(makeFieldLabel("Action Souris PC"))
                val labels = InputCatalog.mouseButtons.map { it.first }
                spinnerMouse = Spinner(this).apply {
                    adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                    val idx = InputCatalog.mouseButtons.indexOfFirst { it.second == el.mouseButton }.coerceAtLeast(0)
                    setSelection(idx)
                }
                box.addView(spinnerMouse)
            }

            ControlType.JOYSTICK_LEFT -> {
                cbWasd = CheckBox(this).apply {
                    text = "Envoyer les touches clavier (WASD / ZQSD) au lieu du stick Xbox"
                    isChecked = el.joystickAsWasd
                }
                cbAzerty = CheckBox(this).apply {
                    text = "Disposition clavier français AZERTY (Z-Q-S-D)"
                    isChecked = el.wasdAzerty
                }
                box.addView(cbWasd)
                box.addView(cbAzerty)
            }

            else -> Unit
        }

        // Shape selector
        box.addView(makeFieldLabel("Forme"))
        val shapes = listOf(ControlShape.CIRCLE to "Cercle", ControlShape.PILL to "Pilule", ControlShape.ROUND_RECT to "Rectangle arrondi")
        val spinnerShape = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, shapes.map { it.second })
            setSelection(shapes.indexOfFirst { it.first == el.shape }.coerceAtLeast(0))
        }
        box.addView(spinnerShape)

        // Color selector
        box.addView(makeFieldLabel("Couleur d’accent"))
        val colorNames = InputCatalog.colorPalette.map { it.first }
        val spinnerColor = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, colorNames)
            val idx = InputCatalog.colorPalette.indexOfFirst { it.second.equals(el.colorHex, ignoreCase = true) }.coerceAtLeast(0)
            setSelection(idx)
        }
        box.addView(spinnerColor)

        // Size & Opacity sliders
        box.addView(makeFieldLabel("Taille (Largeur)"))
        val seekW = SeekBar(this).apply {
            max = 60
            progress = ((el.width - 0.05f) * 100f).toInt().coerceIn(0, 60)
        }
        box.addView(seekW)

        box.addView(makeFieldLabel("Hauteur"))
        val seekH = SeekBar(this).apply {
            max = 65
            progress = ((el.height - 0.07f) * 100f).toInt().coerceIn(0, 65)
        }
        box.addView(seekH)

        box.addView(makeFieldLabel("Opacité"))
        val seekOpacity = SeekBar(this).apply {
            max = 75
            progress = ((el.opacity - 0.25f) * 100f).toInt().coerceIn(0, 75)
        }
        box.addView(seekOpacity)

        // Toggle & Turbo options for button-like controls
        val cbToggle = CheckBox(this).apply {
            text = "Mode maintien (Toggle : 1 tap verrouille / déverrouille)"
            isChecked = el.toggleMode
        }
        val cbTurbo = CheckBox(this).apply {
            text = "Mode Turbo (appui rapide automatique)"
            isChecked = el.turboHz > 0
        }
        if (el.type in setOf(ControlType.BUTTON, ControlType.TRIGGER_LEFT, ControlType.TRIGGER_RIGHT, ControlType.KEYBOARD_KEY, ControlType.MOUSE_BUTTON)) {
            box.addView(cbToggle)
            if (el.type in setOf(ControlType.BUTTON, ControlType.TRIGGER_LEFT, ControlType.TRIGGER_RIGHT)) {
                box.addView(cbTurbo)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Propriétés du contrôle")
            .setView(scroll)
            .setPositiveButton("Appliquer") { _, _ ->
                val newLabel = edtLabel.text.toString().trim().ifBlank { el.label }
                val newXbox = spinnerXbox?.let { InputCatalog.xboxButtons[it.selectedItemPosition].second } ?: el.xboxButtonBit
                val newVk = spinnerKb?.let { InputCatalog.keyboardKeys[it.selectedItemPosition].vkCode } ?: el.vkCode
                val newMouse = spinnerMouse?.let { InputCatalog.mouseButtons[it.selectedItemPosition].second } ?: el.mouseButton
                val newShape = shapes[spinnerShape.selectedItemPosition].first
                val newColor = InputCatalog.colorPalette[spinnerColor.selectedItemPosition].second
                val newW = (0.05f + seekW.progress / 100f).coerceIn(0.05f, 0.70f)
                val newH = (0.07f + seekH.progress / 100f).coerceIn(0.07f, 0.75f)
                val newOp = (0.25f + seekOpacity.progress / 100f).coerceIn(0.25f, 1.0f)

                canvasView.updateSelected { cur ->
                    cur.copy(
                        label = newLabel,
                        xboxButtonBit = newXbox,
                        vkCode = newVk,
                        mouseButton = newMouse,
                        joystickAsWasd = cbWasd?.isChecked ?: cur.joystickAsWasd,
                        wasdAzerty = cbAzerty?.isChecked ?: cur.wasdAzerty,
                        shape = newShape,
                        colorHex = newColor,
                        width = newW,
                        height = newH,
                        opacity = newOp,
                        toggleMode = cbToggle.isChecked,
                        turboHz = if (cbTurbo.isChecked) 15 else 0,
                    )
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showProfileSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        box.addView(makeFieldLabel("Nom du profil de manette"))
        val edtName = EditText(this).apply { setText(profile.name) }
        box.addView(edtName)

        box.addView(makeFieldLabel("Mode Gyroscope"))
        val gyroOptions = listOf(
            GyroMode.OFF to "Désactivé",
            GyroMode.STEERING_LEFT_STICK to "Volant de course (Stick Gauche)",
            GyroMode.AIM_RIGHT_STICK to "Visée Gyroscope (Stick Droit)",
            GyroMode.AIM_MOUSE to "Visée Gyroscope (Souris PC)",
        )
        val spGyro = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, gyroOptions.map { it.second })
            setSelection(gyroOptions.indexOfFirst { it.first == profile.gyroMode }.coerceAtLeast(0))
        }
        box.addView(spGyro)

        box.addView(makeFieldLabel("Sensibilité Gyroscope"))
        val seekSens = SeekBar(this).apply {
            max = 25
            progress = ((profile.gyroSensitivity - 0.5f) * 10f).toInt().coerceIn(0, 25)
        }
        box.addView(seekSens)

        val cbHaptic = CheckBox(this).apply {
            text = "Vibrations haptiques & retour de force des jeux"
            isChecked = profile.hapticEnabled
        }
        box.addView(cbHaptic)

        val builder = AlertDialog.Builder(this)
            .setTitle("Paramètres du profil")
            .setView(box)
            .setPositiveButton("Valider") { _, _ ->
                profile = profile.copy(
                    name = edtName.text.toString().trim().ifBlank { profile.name },
                    gyroMode = gyroOptions[spGyro.selectedItemPosition].first,
                    gyroSensitivity = 0.5f + seekSens.progress / 10f,
                    hapticEnabled = cbHaptic.isChecked,
                )
                updateSelectionToolbar(canvasView.getSelected())
            }
            .setNegativeButton("Fermer", null)

        if (profile.builtIn) {
            builder.setNeutralButton("Réinitialiser d’origine") { _, _ ->
                val reset = repo.resetBuiltInProfile(profile.id)
                if (reset != null) {
                    profile = reset
                    canvasView.elements = reset.elements.toMutableList()
                    canvasView.selectById(null)
                    Toast.makeText(this, "Disposition d’origine restaurée !", Toast.LENGTH_SHORT).show()
                }
            }
        }

        builder.show()
    }

    private fun saveAndExit() {
        val updated = profile.copy(elements = canvasView.elements.toList())
        repo.saveProfile(updated)
        repo.setActiveProfileId(updated.id)
        Toast.makeText(this, "Manette « ${updated.name} » enregistrée !", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun makeBarBtn(label: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
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

    private fun makeFieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#94A3B8"))
        setPadding(0, dp(10), 0, dp(3))
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
