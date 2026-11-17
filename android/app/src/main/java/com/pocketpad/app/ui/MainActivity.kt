package com.pocketpad.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pocketpad.app.model.LayoutProfile
import com.pocketpad.app.model.ProfileRepository
import com.pocketpad.app.protocol.PocketPadConstants
import com.pocketpad.app.transport.ConnectionManager
import com.pocketpad.app.transport.ConnectionState

class MainActivity : AppCompatActivity() {

    private lateinit var repo: ProfileRepository
    private lateinit var txtStatusTitle: TextView
    private lateinit var txtStatusDetail: TextView
    private lateinit var txtPingBadge: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var btnDisconnect: TextView
    private lateinit var profilesContainer: LinearLayout
    private val slotChips = mutableListOf<TextView>()

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            openBluetoothPicker()
        } else {
            Toast.makeText(this, "Autorisation Bluetooth requise pour détecter ton PC.", Toast.LENGTH_LONG).show()
        }
    }

    private val exportProfileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val active = repo.getActiveProfile()
                val json = repo.exportProfileJson(active)
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Profil « ${active.name} » exporté !", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                Toast.makeText(this, "Erreur export : ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importProfileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val imported = repo.importProfileJson(json)
                refreshProfilesList()
                Toast.makeText(this, "Profil « ${imported.name} » importé !", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                Toast.makeText(this, "Fichier invalide : ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val connListener: (ConnectionState) -> Unit = { st ->
        runOnUiThread { renderConnectionState(st) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ProfileRepository(this)
        ConnectionManager.configureIdentity(repo.clientId, repo.preferredSlot)
        setContentView(buildUi())
        refreshSlotChips()
    }

    override fun onResume() {
        super.onResume()
        refreshProfilesList()
        ConnectionManager.addStateListener(connListener)
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.removeStateListener(connListener)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090D1A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "PocketPad"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        })
        titleCol.addView(TextView(this).apply {
            text = "Manette Multijoueur (1–4J) · USB, Bluetooth & Wi-Fi · v${PocketPadConstants.APP_VERSION}"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        header.addView(titleCol)

        txtPingBadge = TextView(this).apply {
            text = "Hors-ligne"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundRect("#18223A", "#2D3E63", 14)
        }
        header.addView(txtPingBadge)
        root.addView(header)

        // Live Connection Status Card
        statusCard = makeCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val statusTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        txtStatusTitle = TextView(this).apply {
            text = "Non connecté au PC"
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnDisconnect = makePillButton("Déconnecter", "#9F1239") {
            ConnectionManager.disconnect()
        }.apply { visibility = View.GONE }
        statusTop.addView(txtStatusTitle)
        statusTop.addView(btnDisconnect)
        statusCard.addView(statusTop)

        txtStatusDetail = TextView(this).apply {
            text = "Lance PocketPad.exe sur Windows puis choisis ton mode de connexion ci-dessous."
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(txtStatusDetail)
        root.addView(statusCard)

        // Connection Mode Buttons Card
        val connCard = makeCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        connCard.addView(makeSectionTitle("1. CONNEXION AU PC WINDOWS"))

        // Primary USB Button
        connCard.addView(makeBigActionButton(
            title = "⚡ Connexion Câble USB (Recommandé · < 2 ms)",
            subtitle = "Détecte automatiquement ADB USB ou Partage de connexion USB",
            bgHex = "#0284C7",
            strokeHex = "#38BDF8",
        ) {
            ConnectionManager.connectUsbAuto(repo.getActiveProfile().name) { ok, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }
        })

        // Secondary Row: Bluetooth + Wi-Fi
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val btnBt = makeBigActionButton(
            title = "Bluetooth",
            subtitle = "PC appairé (sans fil)",
            bgHex = "#4C1D95",
            strokeHex = "#8B5CF6",
        ) { requestBluetoothAndConnect() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(6) }
        }

        val btnWifi = makeBigActionButton(
            title = "Wi-Fi / IP",
            subtitle = "Auto-scan ou IP",
            bgHex = "#1E293B",
            strokeHex = "#334155",
        ) { showWifiDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) }
        }

        row2.addView(btnBt)
        row2.addView(btnWifi)
        connCard.addView(row2)

        // Multiplayer Slot Picker (Auto / J1 / J2 / J3 / J4)
        val slotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        slotRow.addView(TextView(this).apply {
            text = "Place joueur :"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        })
        slotChips.clear()
        val slotLabels = listOf("Auto", "J1", "J2", "J3", "J4")
        slotLabels.forEachIndexed { idx, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(5)
                }
                setOnClickListener {
                    repo.preferredSlot = idx
                    ConnectionManager.requestPreferredSlot(idx)
                    refreshSlotChips()
                    val msg = if (idx == 0) "Attribution automatique du joueur activée" else "Place souhaitée : Joueur $idx"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            slotChips.add(chip)
            slotRow.addView(chip)
        }
        connCard.addView(slotRow)
        root.addView(connCard)

        // Play & Edit Primary CTA Card
        val launchCard = makeCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        launchCard.addView(makeSectionTitle("2. JOUER OU PERSONNALISER TA MANETTE"))

        val ctaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnPlay = makeBigActionButton(
            title = "🎮 JOUER (Plein écran)",
            subtitle = "Manette active · Multi-touch",
            bgHex = "#15803D",
            strokeHex = "#22C55E",
        ) {
            startActivity(Intent(this, GamepadActivity::class.java))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
                .apply { rightMargin = dp(6) }
        }

        val btnEdit = makeBigActionButton(
            title = "✏️ Éditeur",
            subtitle = "Déplacer / Remapper",
            bgHex = "#1E293B",
            strokeHex = "#38BDF8",
        ) {
            val active = repo.getActiveProfile()
            val intent = Intent(this, EditorActivity::class.java)
            intent.putExtra(EditorActivity.EXTRA_PROFILE_ID, active.id)
            startActivity(intent)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
                .apply { leftMargin = dp(6) }
        }
        ctaRow.addView(btnPlay)
        ctaRow.addView(btnEdit)
        launchCard.addView(ctaRow)
        root.addView(launchCard)

        // Profiles Manager Card
        val profCard = makeCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val profHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        profHead.addView(TextView(this).apply {
            text = "PROFILS DE MANETTE"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        profHead.addView(makePillButton("+ Nouveau", "#0284C7") { showCreateProfileDialog() })
        profHead.addView(makePillButton("Import", "#1E293B") {
            importProfileLauncher.launch(arrayOf("application/json", "*/*"))
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
        })
        profHead.addView(makePillButton("Export", "#1E293B") {
            val name = repo.getActiveProfile().name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            exportProfileLauncher.launch("PocketPad_${name}.json")
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
        })
        profCard.addView(profHead)

        profilesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        profCard.addView(profilesContainer)
        root.addView(profCard)

        // Help card
        val helpCard = makeCard()
        helpCard.addView(makeSectionTitle("GUIDE RAPIDE MULTIJOUEUR (USB, BLUETOOTH & WI-FI)"))
        helpCard.addView(TextView(this).apply {
            text = "• Multijoueur jusqu’à 4 joueurs simultanés : chaque ami installe PocketPad sur son téléphone et se connecte au même PC (en mixant librement Câble USB, Bluetooth et Wi-Fi !).\n" +
                "• Câble USB (ADB · < 2 ms) : active « Débogage USB » sur Android, branche le câble et appuie sur « Connexion Câble USB » (plusieurs câbles USB en même temps sont supportés !).\n" +
                "• Bluetooth : associe le téléphone au PC dans Windows Bluetooth, puis clique sur « Bluetooth » ici.\n" +
                "• Wi-Fi : sur le même Wi-Fi ou point d’accès, clique sur « Wi-Fi / IP → Auto-Scan réseau »."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        root.addView(helpCard)

        return scroll
    }

    private fun refreshProfilesList() {
        profilesContainer.removeAllViews()
        val all = repo.getAllProfiles()
        val activeId = repo.getActiveProfile().id

        for (p in all) {
            val isSelected = p.id == activeId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = roundRect(
                    if (isSelected) "#172A4A" else "#0D1426",
                    if (isSelected) "#38BDF8" else "#1E2B48",
                    12
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    repo.setActiveProfileId(p.id)
                    ConnectionManager.notifyActiveProfile(p.name)
                    refreshProfilesList()
                }
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = (if (isSelected) "● " else "") + p.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(if (isSelected) "#38BDF8" else "#F1F5F9"))
            })
            info.addView(TextView(this).apply {
                text = "${p.elements.size} contrôles · ${p.description}"
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
            })
            row.addView(info)

            row.addView(makePillButton("Éditer", "#1E293B") {
                repo.setActiveProfileId(p.id)
                val i = Intent(this, EditorActivity::class.java)
                i.putExtra(EditorActivity.EXTRA_PROFILE_ID, p.id)
                startActivity(i)
            })

            row.addView(makePillButton("⋮", "#1E293B") {
                showProfileMenu(p)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(5) }
            })

            profilesContainer.addView(row)
        }
    }

    private fun showProfileMenu(profile: LayoutProfile) {
        val actions = mutableListOf("Dupliquer ce profil", "Ouvrir dans l’éditeur")
        if (!profile.builtIn) actions.add("Supprimer ce profil")
        else actions.add("Réinitialiser d’origine")

        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val copy = repo.createNewProfile("${profile.name} (Copie)", profile)
                        refreshProfilesList()
                        Toast.makeText(this, "Profil « ${copy.name} » créé !", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        repo.setActiveProfileId(profile.id)
                        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROFILE_ID, profile.id))
                    }
                    2 -> {
                        if (!profile.builtIn) {
                            repo.deleteProfile(profile.id)
                            refreshProfilesList()
                            Toast.makeText(this, "Profil supprimé.", Toast.LENGTH_SHORT).show()
                        } else {
                            repo.resetBuiltInProfile(profile.id)
                            refreshProfilesList()
                            Toast.makeText(this, "Profil restauré d’origine.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showCreateProfileDialog() {
        val edt = EditText(this).apply {
            hint = "Ex : Ma manette FIFA, GTA V, Racing…"
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Créer une nouvelle manette")
            .setMessage("Pars d’une copie du profil actuel pour la personnaliser librement dans l’éditeur.")
            .setView(edt)
            .setPositiveButton("Créer & Éditer") { _, _ ->
                val name = edt.text.toString().trim().ifBlank { "Ma Manette Perso" }
                val created = repo.createNewProfile(name, repo.getActiveProfile())
                refreshProfilesList()
                startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROFILE_ID, created.id))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestBluetoothAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (need.isNotEmpty()) {
                btPermissionLauncher.launch(need.toTypedArray())
                return
            }
        }
        openBluetoothPicker()
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothPicker() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null) {
            Toast.makeText(this, "Cet appareil ne dispose pas de Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Active d’abord le Bluetooth sur ton téléphone.", Toast.LENGTH_LONG).show()
            return
        }

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        if (bonded.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Aucun PC appairé")
                .setMessage("Associe d’abord ton téléphone avec ton PC dans Paramètres → Bluetooth, puis réessaie.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = bonded.map { "${it.name ?: "Appareil"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choisis ton PC Bluetooth")
            .setItems(labels) { _, which ->
                val dev = bonded[which]
                repo.lastBtAddress = dev.address
                ConnectionManager.connectBluetooth(adapter, dev, repo.getActiveProfile().name) { ok, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showWifiDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(6))
        }
        val edtIp = EditText(this).apply {
            setText(repo.lastIp)
            hint = "Ex : 192.168.1.25"
        }
        box.addView(TextView(this).apply {
            text = "Adresse IP affichée dans PocketPad.exe sur ton PC :"
            textSize = 12.5f
            setPadding(0, 0, 0, dp(6))
        })
        box.addView(edtIp)

        AlertDialog.Builder(this)
            .setTitle("Connexion Wi-Fi / IP")
            .setView(box)
            .setPositiveButton("Connecter") { _, _ ->
                val ip = edtIp.text.toString().trim()
                if (ip.isNotBlank()) {
                    repo.lastIp = ip
                    ConnectionManager.connectTcp(ip, PocketPadConstants.TCP_PORT, repo.getActiveProfile().name) { ok, msg ->
                        runOnUiThread {
                            Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNeutralButton("Auto-Scan réseau") { _, _ ->
                Toast.makeText(this, "Recherche des PC PocketPad sur le réseau…", Toast.LENGTH_SHORT).show()
                Thread {
                    val servers = ConnectionManager.discoverServersBlocking(1400)
                    runOnUiThread {
                        if (servers.isEmpty()) {
                            Toast.makeText(this, "Aucun PC trouvé automatiquement. Saisis l’IP manuellement.", Toast.LENGTH_LONG).show()
                        } else {
                            val labels = servers.map {
                                "${it.pcName} (${it.ip}) · ${it.connectedPlayers}/${it.maxPlayers} joueurs"
                            }.toTypedArray()
                            AlertDialog.Builder(this)
                                .setTitle("PC détectés")
                                .setItems(labels) { _, idx ->
                                    val srv = servers[idx]
                                    repo.lastIp = srv.ip
                                    ConnectionManager.connectTcp(srv.ip, srv.port, repo.getActiveProfile().name)
                                }
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun renderConnectionState(st: ConnectionState) {
        btnDisconnect.visibility = if (st.connected || st.connecting) View.VISIBLE else View.GONE
        txtStatusDetail.text = st.statusDetail

        when {
            st.connected -> {
                val slotNum = st.playerSlot.coerceIn(1, PocketPadConstants.MAX_PLAYERS)
                val slotHex = st.slotColorHex.ifBlank { PocketPadConstants.slotColorHex(slotNum) }
                txtStatusTitle.text = "● JOUEUR $slotNum · ${st.pcName.ifBlank { "PC Windows" }}"
                txtStatusTitle.setTextColor(Color.parseColor(slotHex))
                statusCard.background = roundRect("#0B1F2E", slotHex, 14)
                val pingPart = if (st.pingMs >= 0) " · ${st.pingMs} ms" else ""
                txtPingBadge.text = "J$slotNum · ${st.connectedPlayers}/${st.maxPlayers}J$pingPart"
                txtPingBadge.setTextColor(Color.parseColor(slotHex))
                txtPingBadge.background = roundRect("#0F1D33", slotHex, 14)
            }
            st.connecting -> {
                txtStatusTitle.text = "◌ ${st.transportLabel}"
                txtStatusTitle.setTextColor(Color.parseColor("#38BDF8"))
                statusCard.background = roundRect("#0D1D36", "#38BDF8", 14)
                txtPingBadge.text = "Connexion…"
                txtPingBadge.setTextColor(Color.parseColor("#38BDF8"))
                txtPingBadge.background = roundRect("#18223A", "#38BDF8", 14)
            }
            else -> {
                txtStatusTitle.text = "○ Non connecté au PC"
                txtStatusTitle.setTextColor(Color.parseColor("#F1F5F9"))
                statusCard.background = roundRect("#11182B", "#243150", 14)
                txtPingBadge.text = "Hors-ligne"
                txtPingBadge.setTextColor(Color.parseColor("#94A3B8"))
                txtPingBadge.background = roundRect("#18223A", "#2D3E63", 14)
            }
        }
    }

    private fun refreshSlotChips() {
        val selected = repo.preferredSlot
        slotChips.forEachIndexed { idx, chip ->
            val active = idx == selected
            val accent = if (idx == 0) "#38BDF8" else PocketPadConstants.slotColorHex(idx)
            chip.background = roundRect(
                if (active) accent else "#0D1426",
                if (active) accent else "#243150",
                10
            )
            chip.setTextColor(
                if (active) Color.parseColor("#090D1A") else Color.parseColor("#CBD5E1")
            )
        }
    }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = roundRect("#11182B", "#243150", 14)
    }

    private fun makeSectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#38BDF8"))
        setPadding(0, 0, 0, dp(10))
    }

    private fun makeBigActionButton(
        title: String,
        subtitle: String,
        bgHex: String,
        strokeHex: String,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect(bgHex, strokeHex, 12)
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun makePillButton(label: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundRect(bgHex, bgHex, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun roundRect(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
