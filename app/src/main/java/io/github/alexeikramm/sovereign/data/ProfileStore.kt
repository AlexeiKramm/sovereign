package io.github.alexeikramm.sovereign.data

import android.content.Context
import io.github.alexeikramm.sovereign.cleanup.CleanupPrompt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CleaningProfile(
    val id: String,
    val name: String,
    val description: String,
    val rules: String,
    val isBuiltin: Boolean = false,
)

@Serializable
data class ProfileData(
    val activeProfileId: String = "smart_clean",
    val customProfiles: List<CleaningProfile> = emptyList(),
)

class ProfileStore(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)
    private val file = File(filesDir, "profiles.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val builtinProfiles = listOf(
        CleaningProfile(
            id = "smart_clean",
            name = "Smart Clean",
            description = "Rewrites into clean, natural written prose: restructures sentences and wording, keeps the meaning.",
            rules = CleanupPrompt.SMART_CLEAN_RULES,
            isBuiltin = true,
        ),
        CleaningProfile(
            id = "conservative",
            name = "Conservative",
            description = "Preserves original words and structure. Removes fillers, repairs self-corrections, formats lists and numbers, punctuates.",
            rules = CleanupPrompt.CONSERVATIVE_RULES,
            isBuiltin = true,
        ),
    )

    @Synchronized
    fun load(): ProfileData {
        if (!file.exists()) return ProfileData()
        return try {
            json.decodeFromString<ProfileData>(file.readText())
        } catch (_: Exception) {
            ProfileData()
        }
    }

    @Synchronized
    fun allProfiles(): List<CleaningProfile> {
        val data = load()
        return builtinProfiles + data.customProfiles
    }

    @Synchronized
    fun activeProfile(): CleaningProfile {
        val id = load().activeProfileId
        if (id == "rewrite") return builtinProfiles.first { it.id == "smart_clean" }
        return allProfiles().firstOrNull { it.id == id } ?: builtinProfiles.first()
    }

    @Synchronized
    fun setActiveProfile(id: String) {
        val current = load()
        save(current.copy(activeProfileId = id))
    }

    @Synchronized
    fun saveCustomProfile(profile: CleaningProfile) {
        val current = load()
        val updated = current.customProfiles.filter { it.id != profile.id } + profile
        save(current.copy(customProfiles = updated))
    }

    @Synchronized
    fun deleteCustomProfile(id: String) {
        val current = load()
        val updated = current.customProfiles.filter { it.id != id }
        val newActive = if (current.activeProfileId == id) "smart_clean" else current.activeProfileId
        save(current.copy(activeProfileId = newActive, customProfiles = updated))
    }

    /**
     * Atomically restores custom profiles and active profile id. Used for backup restoration.
     */
    @Synchronized
    fun restoreProfiles(customProfiles: List<CleaningProfile>, activeProfileId: String) {
        val sanitizedProfiles = customProfiles
            .filterNot { it.isBuiltin }
            .map { it.copy(name = it.name.trim(), rules = it.rules.trim()) }
            .filter { it.name.isNotBlank() && it.rules.isNotBlank() }

        val validActiveId = if (allProfiles().any { it.id == activeProfileId } || sanitizedProfiles.any { it.id == activeProfileId }) {
            activeProfileId
        } else if (activeProfileId == "rewrite") {
            "smart_clean"
        } else {
            builtinProfiles.first().id
        }

        save(ProfileData(activeProfileId = validActiveId, customProfiles = sanitizedProfiles))
    }

    private fun save(data: ProfileData) {
        try {
            file.writeText(json.encodeToString(data))
        } catch (_: Exception) {}
    }
}
