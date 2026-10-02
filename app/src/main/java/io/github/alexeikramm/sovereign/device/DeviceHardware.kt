package io.github.alexeikramm.sovereign.device

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Hardware profile detected on the current Android device.
 */
data class DeviceHardwareProfile(
    val socModel: String,
    val chipBrand: String,
    val boardPlatform: String,
    val hardware: String,
    val isQualcommSnapdragon: Boolean,
    val isMediaTekDimensity: Boolean,
    val isGoogleTensor: Boolean,
    val hasOpenClGpu: Boolean,
)

object DeviceHardware {

    fun getProfile(context: Context): DeviceHardwareProfile {
        val soc = getSocModel()
        val platform = getSystemProperty("ro.board.platform", Build.BOARD)
        val hw = Build.HARDWARE.lowercase()
        val isQcom = hw.contains("qcom") || soc.startsWith("sm", ignoreCase = true) || platform.contains("canoe", ignoreCase = true)
        val isMtk = hw.contains("mtk") || hw.contains("mediatek") || soc.startsWith("mt", ignoreCase = true)
        val isTensor = hw.contains("tensor") || platform.contains("zuma", ignoreCase = true)

        val brand = resolveChipBrand(soc, platform, isQcom, isMtk, isTensor)

        // Check OpenCL GPU libraries
        val hasOpenCl = checkOpenClAvailability()

        return DeviceHardwareProfile(
            socModel = soc,
            chipBrand = brand,
            boardPlatform = platform,
            hardware = Build.HARDWARE,
            isQualcommSnapdragon = isQcom,
            isMediaTekDimensity = isMtk,
            isGoogleTensor = isTensor,
            hasOpenClGpu = hasOpenCl,
        )
    }

    private fun getSocModel(): String {
        // Build.SOC_MODEL is available in API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) {
                return soc
            }
        }
        val prop = getSystemProperty("ro.soc.model", "")
        if (prop.isNotBlank()) return prop

        val platform = getSystemProperty("ro.board.platform", "")
        if (platform.isNotBlank()) return platform

        return Build.HARDWARE
    }

    private fun resolveChipBrand(
        soc: String,
        platform: String,
        isQcom: Boolean,
        isMtk: Boolean,
        isTensor: Boolean,
    ): String = when {
        soc.equals("SM8850", ignoreCase = true) || platform.equals("canoe", ignoreCase = true) ->
            "Qualcomm Snapdragon 8 Elite Gen 5 (SM8850)"
        soc.equals("SM8750", ignoreCase = true) || platform.equals("sun", ignoreCase = true) ->
            "Qualcomm Snapdragon 8 Elite (SM8750)"
        soc.equals("SM8650", ignoreCase = true) || platform.equals("pineapple", ignoreCase = true) ->
            "Qualcomm Snapdragon 8 Gen 3 (SM8650)"
        soc.equals("SM8550", ignoreCase = true) || platform.equals("kalama", ignoreCase = true) ->
            "Qualcomm Snapdragon 8 Gen 2 (SM8550)"
        soc.startsWith("SM8", ignoreCase = true) ->
            "Qualcomm Snapdragon 8-Series ($soc)"
        soc.startsWith("SM7", ignoreCase = true) ->
            "Qualcomm Snapdragon 7-Series ($soc)"
        isQcom -> "Qualcomm Snapdragon ($soc)"
        isMtk -> "MediaTek Dimensity ($soc)"
        isTensor -> "Google Tensor ($soc)"
        else -> "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} $soc"
    }

    private fun checkOpenClAvailability(): Boolean {
        val paths = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL_adreno.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
        )
        return paths.any { File(it).exists() }
    }

    private fun getSystemProperty(key: String, defaultValue: String): String {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, defaultValue) as? String ?: defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }
}
