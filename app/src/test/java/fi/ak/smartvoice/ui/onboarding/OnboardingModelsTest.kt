package fi.ak.smartvoice.ui.onboarding

import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import fi.ak.smartvoice.data.AppSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingModelsTest {

    @Test
    fun `catalog offers three presets with unique ids`() {
        val ids = OnboardingModelPresets.ALL.map { it.id }
        assertEquals(3, OnboardingModelPresets.ALL.size)
        assertEquals(ids.size, ids.toSet().size)
        OnboardingModelPresets.ALL.forEach { preset ->
            assertTrue(preset.title.isNotBlank())
            assertTrue(preset.description.isNotBlank())
            assertTrue("preset ${preset.id} has no bytes", preset.totalBytes > 0L)
        }
    }

    @Test
    fun `presets pair the agreed models`() {
        val streaming = OnboardingModelPresets.STREAMING
        assertEquals(AsrModels.NEMOTRON, streaming.asrModel)
        assertEquals(LiteRtLmModels.GEMMA_4_E2B, streaming.llmModel)

        val highAccuracy = OnboardingModelPresets.HIGH_ACCURACY
        assertEquals(AsrModels.PARAKEET_TDT_V3, highAccuracy.asrModel)
        assertEquals(LiteRtLmModels.GEMMA_4_E2B, highAccuracy.llmModel)

        val compact = OnboardingModelPresets.COMPACT
        assertEquals(AsrModels.WHISPER_BASE, compact.asrModel)
        assertEquals(null, compact.llmModel)
    }

    @Test
    fun `total bytes is the sum of every stage in the preset`() {
        OnboardingModelPresets.ALL.forEach { preset ->
            assertEquals(
                preset.asrModel.totalBytes + (preset.llmModel?.approximateBytes ?: 0L),
                preset.totalBytes,
            )
        }
        assertEquals(AsrModels.WHISPER_BASE.totalBytes, OnboardingModelPresets.COMPACT.totalBytes)
    }

    @Test
    fun `byId falls back to the first preset`() {
        assertEquals(OnboardingModelPresets.STREAMING, OnboardingModelPresets.byId(null))
        assertEquals(OnboardingModelPresets.STREAMING, OnboardingModelPresets.byId("nonsense"))
        assertEquals(OnboardingModelPresets.HIGH_ACCURACY, OnboardingModelPresets.byId("high_accuracy"))
        assertEquals(OnboardingModelPresets.COMPACT, OnboardingModelPresets.byId("compact"))
    }

    @Test
    fun `sizes render in MB and GB`() {
        assertEquals("682 MB", formatModelSize(682_000_000L))
        assertEquals("161 MB", formatModelSize(160_600_000L))
        assertEquals("3.3 GB", formatModelSize(3_270_000_000L))
    }

    @Test
    fun `sizes never use a locale decimal comma`() {
        val original = Locale.getDefault()
        try {
            // A German device would render "1,5 GB" if the format were not pinned.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 GB", formatModelSize(1_500_000_000L))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `onboarding defaults to not completed`() {
        assertFalse(AppSettings.DEFAULT_ONBOARDING_COMPLETED)
    }
}
