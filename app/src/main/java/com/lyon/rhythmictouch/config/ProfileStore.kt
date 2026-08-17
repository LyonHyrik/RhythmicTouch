package com.lyon.rhythmictouch.config

import android.content.Context
import com.lyon.rhythmictouch.RhythmicConstants
import org.json.JSONArray

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(RhythmicConstants.PREF_PROFILES, Context.MODE_PRIVATE)

    fun readProfiles(): List<VibrationProfile> {
        val customs = VibrationProfile.fromJsonList(prefs.getString(RhythmicConstants.KEY_PROFILES_JSON, null))
            .filter { !it.isDefault }
            .distinctBy { it.id }
        return listOf(VibrationProfile.defaultProfile()) + customs
    }

    fun readActiveId(): String =
        prefs.getString(RhythmicConstants.KEY_ACTIVE_PROFILE_ID, VibrationProfile.DEFAULT_ID)
            ?: VibrationProfile.DEFAULT_ID

    fun getActive(): VibrationProfile {
        val id = readActiveId()
        return readProfiles().firstOrNull { it.id == id } ?: VibrationProfile.defaultProfile()
    }

    fun createDraft(): VibrationProfile = VibrationProfile(
        id = "p${System.currentTimeMillis()}",
        name = "未命名配置",
        params = VibrationProfile.defaultProfile().params,
    )

    fun addProfile(name: String): VibrationProfile {
        val trimmed = name.trim()
        val safeName = if (trimmed.isEmpty()) "未命名配置" else trimmed
        val profile = VibrationProfile(
            id = "p${System.currentTimeMillis()}",
            name = safeName,
            params = VibrationProfile.defaultProfile().params,
        )
        saveProfiles(readProfiles() + profile)
        return profile
    }

    fun addOrUpdate(profile: VibrationProfile) {
        if (profile.isDefault) return
        val existing = readProfiles()
        val saved = if (existing.any { it.id == profile.id }) {
            existing.map { if (it.id == profile.id) profile else it }
        } else {
            existing + profile
        }
        saveProfiles(saved)
    }

    fun importProfile(profile: VibrationProfile): VibrationProfile {
        if (profile.isDefault) return profile
        val imported = if (readProfiles().any { it.id == profile.id }) {
            profile.copy(id = "p${System.currentTimeMillis()}")
        } else {
            profile
        }
        saveProfiles(readProfiles() + imported)
        return imported
    }

    fun updateProfile(profile: VibrationProfile) {
        if (profile.isDefault) return
        saveProfiles(readProfiles().map { if (it.id == profile.id) profile else it })
    }

    fun deleteProfile(id: String): Boolean {
        if (id == VibrationProfile.DEFAULT_ID) return false
        val profiles = readProfiles().filter { it.id != id }
        saveProfiles(profiles)
        if (readActiveId() == id) {
            prefs.edit().putString(RhythmicConstants.KEY_ACTIVE_PROFILE_ID, VibrationProfile.DEFAULT_ID).apply()
        }
        return true
    }

    fun setActive(id: String) {
        if (readProfiles().none { it.id == id }) return
        prefs.edit().putString(RhythmicConstants.KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    private fun saveProfiles(profiles: List<VibrationProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            if (!p.isDefault) arr.put(p.toJson())
        }
        prefs.edit().putString(RhythmicConstants.KEY_PROFILES_JSON, arr.toString()).apply()
    }
}
