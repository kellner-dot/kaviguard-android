package com.seth.kaviguard

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.util.UUID

/** Per-app storage breakdown. */
data class AppStorage(
    val packageName: String,
    val label: String,
    val cacheBytes: Long,
    val dataBytes: Long,
    val apkBytes: Long
)

/**
 * Storage analysis via StorageStatsManager (no root needed).
 * Cache clearing of OTHER apps is not possible without root or system
 * privileges — the report shows sizes and deep-links to each app's
 * Storage settings page where Seth can tap "Clear cache" manually.
 * We CAN clear our own cache.
 */
object StorageAnalyzer {

    fun analyze(ctx: Context, limit: Int = 50): List<AppStorage> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid: UUID = sm.getUuidForPath(ctx.filesDir) ?: StorageManager.UUID_DEFAULT

        val pkgs = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }

        val out = mutableListOf<AppStorage>()
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            try {
                val stats = ssm.queryStatsForPackage(uuid, pi.packageName, android.os.Process.myUserHandle())
                out.add(AppStorage(
                    packageName = pi.packageName,
                    label = ai.loadLabel(pm).toString(),
                    cacheBytes = stats.cacheBytes,
                    dataBytes = stats.dataBytes,
                    apkBytes = stats.appBytes
                ))
            } catch (_: Exception) {
                // Package not queryable (rare) — skip
            }
        }
        return out.sortedByDescending { it.cacheBytes }.take(limit)
    }

    fun totalCache(apps: List<AppStorage>): Long = apps.sumOf { it.cacheBytes }

    fun formatBytes(b: Long): String = when {
        b >= 1_073_741_824 -> "%.1f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun deviceStorage(): String {
        val ext = Environment.getExternalStorageDirectory()
        val total = ext.totalSpace
        val free = ext.freeSpace
        return "Used ${formatBytes(total - free)} of ${formatBytes(total)} " +
                "(${(100 * free / total.coerceAtLeast(1))}% free)"
    }
}
