package io.github.tuzfucius.personalrecorder.background

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import io.github.tuzfucius.personalrecorder.R

data class BatteryOemDiagnostics(
    val batteryOptimizationIgnored: Boolean,
    val manufacturer: String,
    val model: String,
    val vendorManagedBackground: Boolean,
    @StringRes val vendorGuidance: Int?,
)

object BackgroundDiagnostics {
    private val vendorNames = setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "realme", "oneplus", "vivo", "meizu")

    fun read(context: Context): BatteryOemDiagnostics {
        val appContext = context.applicationContext
        val power = appContext.getSystemService(PowerManager::class.java)
        val manufacturer = Build.MANUFACTURER.trim().ifBlank { appContext.getString(R.string.unknown_manufacturer) }
        val normalized = manufacturer.lowercase()
        val vendorManaged = vendorNames.any { normalized.contains(it) }
        val batteryOptimizationIgnored = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            runCatching { power?.isIgnoringBatteryOptimizations(appContext.packageName) == true }
                .getOrDefault(false)
        }
        return BatteryOemDiagnostics(
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            manufacturer = manufacturer,
            model = Build.MODEL.trim().ifBlank { appContext.getString(R.string.unknown_device) },
            vendorManagedBackground = vendorManaged,
            vendorGuidance = vendorGuidance(normalized),
        )
    }

    @StringRes
    private fun vendorGuidance(manufacturer: String): Int = when {
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            R.string.vendor_guidance_huawei
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
            R.string.vendor_guidance_xiaomi
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("vivo") ->
            R.string.vendor_guidance_oppo
        else -> R.string.vendor_guidance_generic
    }
}
