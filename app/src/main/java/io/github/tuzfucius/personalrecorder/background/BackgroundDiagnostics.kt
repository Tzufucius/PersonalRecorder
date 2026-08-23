package io.github.tuzfucius.personalrecorder.background

import android.content.Context
import android.os.Build
import android.os.PowerManager

data class BatteryOemDiagnostics(
    val batteryOptimizationIgnored: Boolean,
    val manufacturer: String,
    val model: String,
    val vendorManagedBackground: Boolean,
    val vendorGuidance: String?,
)

object BackgroundDiagnostics {
    private val vendorNames = setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "oneplus", "vivo", "meizu")

    fun read(context: Context): BatteryOemDiagnostics {
        val appContext = context.applicationContext
        val power = appContext.getSystemService(PowerManager::class.java)
        val manufacturer = Build.MANUFACTURER.trim().ifBlank { "未知" }
        val normalized = manufacturer.lowercase()
        val vendorManaged = vendorNames.any { normalized.contains(it) }
        return BatteryOemDiagnostics(
            batteryOptimizationIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                power?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            manufacturer = manufacturer,
            model = Build.MODEL.trim().ifBlank { "未知设备" },
            vendorManagedBackground = vendorManaged,
            vendorGuidance = if (vendorManaged) "该厂商可能额外限制自启动或后台活动，请在应用信息中允许后台活动。" else null,
        )
    }
}
