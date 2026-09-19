package fi.ak.smartvoice.data

import android.content.Context
import fi.ak.smartvoice.cleanup.CleanupPrompt
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
            description = "Removes fillers, repairs self-corrections, formats lists and numbers, punctuates.",
            rules = CleanupPrompt.Variant.SMART_CLEAN.rules,
            isBuiltin = true,
        ),
        CleaningProfile(
            id = "rewrite",
            name = "Rewrite",
            description = "Rewrites into clean written prose: restructures sentences and wording, keeps the meaning.",
            rules = CleanupPrompt.Variant.REWRITE.rules,
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

    private fun save(data: ProfileData) {
        try {
            file.writeText(json.encodeToString(data))
        } catch (_: Exception) {}
    }
}
