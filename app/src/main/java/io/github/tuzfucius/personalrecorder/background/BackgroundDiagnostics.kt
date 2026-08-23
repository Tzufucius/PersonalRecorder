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
    private val vendorNames = setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "realme", "oneplus", "vivo", "meizu")

    fun read(context: Context): BatteryOemDiagnostics {
        val appContext = context.applicationContext
        val power = appContext.getSystemService(PowerManager::class.java)
        val manufacturer = Build.MANUFACTURER.trim().ifBlank { "未知" }
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
            model = Build.MODEL.trim().ifBlank { "未知设备" },
            vendorManagedBackground = vendorManaged,
            vendorGuidance = vendorGuidance(normalized),
        )
    }

    private fun vendorGuidance(manufacturer: String): String = when {
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            "请在电池设置中关闭本应用的省电优化，并在启动管理中允许自动管理或后台运行。"
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
            "请在应用信息中允许自启动，并将电池策略设为无限制；必要时锁定最近任务。"
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("vivo") ->
            "请在电池与后台活动设置中允许自启动、后台运行和无限制耗电。"
        else -> "请在系统电池设置中允许本应用后台活动，并确认通知访问权限仍然开启。"
    }
}
