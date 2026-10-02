package com.seth.kaviguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/** Battery health snapshot. */
data class BatteryInfo(
    val levelPercent: Int,
    val charging: Boolean,
    val health: String,
    val temperatureC: Float,
    val voltageMv: Int,
    val technology: String,
    val cycleCount: Int?   // API 34+ via BatteryManager, may be unavailable
)

/**
 * Battery monitor. Cycle count is only exposed on API 34+ and only on
 * devices whose HAL reports it (Pixel does; Motorola may not — shown
 * as "unavailable" in that case).
 */
object BatteryMonitor {

    fun snapshot(ctx: Context): BatteryInfo {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batt: Intent? = ctx.registerReceiver(null, filter)

        val healthInt = batt?.getIntExtra(BatteryManager.EXTRA_HEALTH,
            BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val health = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val cycles: Int? = if (Build.VERSION.SDK_INT >= 34) {
            // BATTERY_PROPERTY_CYCLE_COUNT requires BATTERY_STATS (signature-level)
            // Wrap in try-catch - return null if permission denied
            try {
                val propId = try {
                    BatteryManager::class.java.getField("BATTERY_PROPERTY_CYCLE_COUNT").getInt(null)
                } catch (e: Exception) { 7 }
                val c = bm.getIntProperty(propId)
                if (c == Int.MIN_VALUE || c < 0) null else c
            } catch (e: SecurityException) { null }
        } else null

        return BatteryInfo(
            levelPercent = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: SecurityException) { -1 },
            charging = bm.isCharging,
            health = health,
            temperatureC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            voltageMv = batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            technology = batt?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "?",
            cycleCount = cycles
        )
    }

    fun verdict(info: BatteryInfo): String = buildString {
        append("Level ${info.levelPercent}% ${if (info.charging) "(charging)" else "(on battery)"}\n")
        append("Health: ${info.health}\n")
        append("Temp: ${info.temperatureC}°C  |  Voltage: ${info.voltageMv} mV  |  ${info.technology}\n")
        append("Cycle count: ${info.cycleCount?.toString() ?: "unavailable on this device"}\n")
        if (info.temperatureC > 40) append("⚠ Running hot — let it cool before heavy use.\n")
        if (info.health != "Good" && info.health != "Unknown")
            append("⚠ Battery health flag: ${info.health}\n")
    }
}
