package com.pocketpad.app.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID

class ProfileRepository(context: Context) {

    private val prefs = context.getSharedPreferences("pocketpad_prefs", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun getAllProfiles(): List<LayoutProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw.isNullOrBlank()) {
            val defaults = DefaultProfiles.allBuiltIn()
            saveAllProfiles(defaults)
            return defaults
        }
        return try {
            val type = object : TypeToken<List<LayoutProfile>>() {}.type
            val loaded: List<LayoutProfile>? = gson.fromJson(raw, type)
            if (loaded.isNullOrEmpty()) DefaultProfiles.allBuiltIn() else loaded
        } catch (_: Exception) {
            DefaultProfiles.allBuiltIn()
        }
    }

    fun saveAllProfiles(profiles: List<LayoutProfile>) {
        prefs.edit().putString(KEY_PROFILES, gson.toJson(profiles)).apply()
    }

    fun getProfileById(id: String): LayoutProfile {
        val all = getAllProfiles()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun getActiveProfile(): LayoutProfile {
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE, null) ?: "builtin_xbox360"
        return getProfileById(activeId)
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
    }

    fun saveProfile(profile: LayoutProfile) {
        val list = getAllProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            list[idx] = profile
        } else {
            list.add(profile)
        }
        saveAllProfiles(list)
    }

    fun createNewProfile(name: String, base: LayoutProfile? = null): LayoutProfile {
        val newId = "custom_${UUID.randomUUID().toString().take(8)}"
        val created = if (base != null) {
            base.copy(
                id = newId,
                name = name,
                description = "Profil personnalisé basé sur ${base.name}",
                builtIn = false,
                elements = base.elements.map { it.copy(id = "${it.id}_${UUID.randomUUID().toString().take(4)}") }
            )
        } else {
            LayoutProfile(
                id = newId,
                name = name,
                description = "Manette personnalisée créée dans l’éditeur",
                builtIn = false,
                elements = listOf(
                    ControlElement("stick_l", ControlType.JOYSTICK_LEFT, "L", 0.20f, 0.58f, 0.20f, 0.36f),
                    ControlElement("btn_a", ControlType.BUTTON, "A", 0.82f, 0.62f, 0.10f, 0.18f, colorHex = "#22C55E"),
                )
            )
        }
        saveProfile(created)
        setActiveProfileId(created.id)
        return created
    }

    fun deleteProfile(id: String): Boolean {
        val list = getAllProfiles().toMutableList()
        if (list.size <= 1) return false
        val removed = list.removeAll { it.id == id }
        if (removed) {
            saveAllProfiles(list)
            if (prefs.getString(KEY_ACTIVE_PROFILE, null) == id) {
                setActiveProfileId(list.first().id)
            }
        }
        return removed
    }

    fun resetBuiltInProfile(id: String): LayoutProfile? {
        val original = DefaultProfiles.allBuiltIn().firstOrNull { it.id == id } ?: return null
        saveProfile(original)
        return original
    }

    fun exportProfileJson(profile: LayoutProfile): String = gson.toJson(profile)

    fun importProfileJson(json: String): LayoutProfile {
        val parsed = gson.fromJson(json, LayoutProfile::class.java)
            ?: throw IllegalArgumentException("JSON de profil invalide")
        require(parsed.name.isNotBlank() && parsed.elements.isNotEmpty()) {
            "Le fichier ne contient aucun bouton de manette valide."
        }
        val imported = parsed.copy(
            id = "import_${UUID.randomUUID().toString().take(8)}",
            builtIn = false,
        )
        saveProfile(imported)
        setActiveProfileId(imported.id)
        return imported
    }

    var lastIp: String
        get() = prefs.getString(KEY_LAST_IP, "192.168.1.10") ?: "192.168.1.10"
        set(value) = prefs.edit().putString(KEY_LAST_IP, value.trim()).apply()

    var lastBtAddress: String?
        get() = prefs.getString(KEY_LAST_BT, null)
        set(value) = prefs.edit().putString(KEY_LAST_BT, value).apply()

    val clientId: String
        get() {
            val existing = prefs.getString(KEY_CLIENT_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = "pp_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            prefs.edit().putString(KEY_CLIENT_ID, generated).apply()
            return generated
        }

    var preferredSlot: Int
        get() = prefs.getInt(KEY_PREFERRED_SLOT, 0).coerceIn(0, 4)
        set(value) = prefs.edit().putInt(KEY_PREFERRED_SLOT, value.coerceIn(0, 4)).apply()

    var videoEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_ENABLED, value).apply()

    var controlsOpacity: Float
        get() = prefs.getFloat(KEY_CONTROLS_OPACITY, 0.65f).coerceIn(0.15f, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_CONTROLS_OPACITY, value.coerceIn(0.15f, 1.0f)).apply()

    var videoFillScreen: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_FILL, false)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_FILL, value).apply()

    var videoQuality: Int
        get() = prefs.getInt(KEY_VIDEO_QUALITY, 62).coerceIn(35, 85)
        set(value) = prefs.edit().putInt(KEY_VIDEO_QUALITY, value.coerceIn(35, 85)).apply()

    companion object {
        private const val KEY_PROFILES = "profiles_v1"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        private const val KEY_LAST_IP = "last_tcp_ip"
        private const val KEY_LAST_BT = "last_bt_mac"
        private const val KEY_CLIENT_ID = "device_client_id"
        private const val KEY_PREFERRED_SLOT = "preferred_player_slot"
        private const val KEY_VIDEO_ENABLED = "switch_video_enabled"
        private const val KEY_CONTROLS_OPACITY = "switch_controls_opacity"
        private const val KEY_VIDEO_FILL = "switch_video_fill"
        private const val KEY_VIDEO_QUALITY = "switch_video_quality"
    }
}
