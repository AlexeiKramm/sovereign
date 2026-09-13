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
    val activeProfileId: String = "standard",
    val customProfiles: List<CleaningProfile> = emptyList(),
)

class ProfileStore(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)
    private val file = File(filesDir, "profiles.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val builtinProfiles = listOf(
        CleaningProfile(
            id = "standard",
            name = "Standard",
            description = "Removes fillers and stutters, adds punctuation.",
            rules = CleanupPrompt.Variant.STANDARD.rules,
            isBuiltin = true,
        ),
        CleaningProfile(
            id = "conservative",
            name = "Conservative",
            description = "Adds punctuation and capitals without changing words.",
            rules = CleanupPrompt.Variant.CONSERVATIVE.rules,
            isBuiltin = true,
        ),
        CleaningProfile(
            id = "repairing",
            name = "Repairing",
            description = "Fixes slips, misheard words, and removes fillers.",
            rules = CleanupPrompt.Variant.REPAIRING.rules,
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
        val newActive = if (current.activeProfileId == id) "standard" else current.activeProfileId
        save(current.copy(activeProfileId = newActive, customProfiles = updated))
    }

    private fun save(data: ProfileData) {
        try {
            file.writeText(json.encodeToString(data))
        } catch (_: Exception) {}
    }
}
