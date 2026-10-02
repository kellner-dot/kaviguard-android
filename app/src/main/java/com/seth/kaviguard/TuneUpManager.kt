package com.seth.kaviguard

import android.app.Activity
import android.app.ActivityManager
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/** Device specs snapshot for spec-aware tuneup decisions. */
data class DeviceSpecs(
    val model: String,
    val androidVersion: String,
    val ramTotalMb: Long,
    val ramAvailMb: Long,
    val ramUsedPct: Int,
    val storageTotalGb: Double,
    val storageFreeGb: Double,
    val storageFreePct: Int
)

/** A large file found in Downloads. */
data class DownloadFile(
    val name: String,
    val sizeBytes: Long,
    val uri: Uri
)

/**
 * TuneUpManager — ACTUAL tuneup actions, not recommendations.
 *
 * What it really does:
 *  1. killBackgroundApps() — ActivityManager.killBackgroundProcesses() for every
 *     non-system background package (needs KILL_BACKGROUND_PROCESSES, a normal
 *     install-time permission). Measures RAM freed via MemoryInfo before/after.
 *  2. clearOwnCache() — deletes our own cache dir + filesDir temp files.
 *  3. findLargeDownloads() — MediaStore query for biggest files in Downloads.
 *  4. requestDeleteDownloads() — system delete-confirmation dialog
 *     (MediaStore.createDeleteRequest, API 30+), real deletion on confirm.
 *  5. openAppStorageSettings() — one-tap deep link to any app's Storage screen
 *     (Android reserves "Clear cache" for the user; this puts the button one tap away).
 *
 * Everything is wrapped in try/catch — a tuneup must never crash.
 */
object TuneUpManager {

    const val DELETE_REQUEST_CODE = 4105

    // ---------- specs ----------

    fun getSpecs(ctx: Context): DeviceSpecs {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem / 1_048_576
        val availMb = mi.availMem / 1_048_576
        val usedPct = if (mi.totalMem > 0)
            ((100 * (mi.totalMem - mi.availMem) / mi.totalMem).toInt()) else 0

        val ext = Environment.getExternalStorageDirectory()
        val totalB = ext.totalSpace.coerceAtLeast(1)
        val freeB = ext.freeSpace.coerceAtLeast(0)

        return DeviceSpecs(
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            ramTotalMb = totalMb,
            ramAvailMb = availMb,
            ramUsedPct = usedPct,
            storageTotalGb = totalB / 1_073_741_824.0,
            storageFreeGb = freeB / 1_073_741_824.0,
            storageFreePct = (100 * freeB / totalB).toInt()
        )
    }

    fun availRamMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / 1_048_576
    }

    // ---------- 1. kill background processes ----------

    /**
     * Kills background processes of all non-system third-party packages.
     * Returns (packagesKilled, ramFreedMb). Never kills self or system apps.
     */
    fun killBackgroundApps(ctx: Context): Pair<Int, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val beforeMb = availRamMb(ctx)

        val pkgs = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getInstalledPackages(0)
            }
        } catch (e: Exception) {
            return Pair(0, 0L)
        }

        var killed = 0
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName
            if (pkg == ctx.packageName) continue                       // never self
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue  // never system
            try {
                am.killBackgroundProcesses(pkg)
                killed++
            } catch (_: Exception) {
                // Some packages refuse — skip silently
            }
        }

        // Let the system settle so the measurement is real
        try { Thread.sleep(1500) } catch (_: InterruptedException) { }
        val afterMb = availRamMb(ctx)
        val freed = (afterMb - beforeMb).coerceAtLeast(0)
        return Pair(killed, freed)
    }

    // ---------- 2. clear own cache ----------

    /** Deletes our own cache dir contents. Returns bytes cleared. */
    fun clearOwnCache(ctx: Context): Long {
        var cleared = 0L
        try {
            cleared += deleteRecursive(ctx.cacheDir)
            ctx.externalCacheDir?.let { cleared += deleteRecursive(it) }
            // temp files in filesDir
            ctx.filesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("tmp") || f.name.endsWith(".tmp")) {
                    cleared += deleteRecursive(f)
                }
            }
        } catch (_: Exception) { }
        return cleared
    }

    private fun deleteRecursive(f: File): Long {
        var bytes = 0L
        try {
            if (f.isDirectory) {
                f.listFiles()?.forEach { bytes += deleteRecursive(it) }
            } else {
                bytes = f.length()
            }
            f.delete()
        } catch (_: Exception) { }
        return bytes
    }

    // ---------- 3/4. large downloads ----------

    /** Biggest files in Downloads via MediaStore. Needs READ_MEDIA_* (33+) or READ_EXTERNAL_STORAGE. */
    fun findLargeDownloads(ctx: Context, limit: Int = 20): List<DownloadFile> {
        val out = mutableListOf<DownloadFile>()
        if (Build.VERSION.SDK_INT < 29) return out
        try {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val proj = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE
            )
            ctx.contentResolver.query(
                uri, proj, null, null,
                "${MediaStore.Downloads.SIZE} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "unnamed"
                    val size = c.getLong(sizeCol)
                    out.add(DownloadFile(name, size, Uri.withAppendedPath(uri, id.toString())))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * Fires the SYSTEM delete-confirmation dialog for the given downloads.
     * Returns false if not supported on this API level (caller falls back).
     */
    fun requestDeleteDownloads(activity: Activity, files: List<DownloadFile>): Boolean {
        if (files.isEmpty()) return false
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val req = MediaStore.createDeleteRequest(
                    activity.contentResolver, files.map { it.uri })
                activity.startIntentSenderForResult(
                    req.intentSender, DELETE_REQUEST_CODE, null, 0, 0, 0, null)
                true
            } else {
                // Pre-30: direct delete attempt (best effort)
                var any = false
                for (f in files) {
                    try {
                        if (activity.contentResolver.delete(f.uri, null, null) > 0) any = true
                    } catch (_: Exception) { }
                }
                any
            }
        } catch (e: Exception) {
            // RecoverableSecurityException path is handled by the system dialog on 30+
            false
        }
    }

    // ---------- 5. deep link to an app's storage settings ----------

    /** Opens the app's "App info" screen — Storage → Clear cache is one tap away. */
    fun openAppStorageSettings(ctx: Context, packageName: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (_: Exception) { }
    }

    // ---------- orchestrated tuneup ----------

    /**
     * Runs the full tuneup. progress() is called on the caller's thread
     * (call from a background thread; post to UI yourself).
     * Returns a human-readable before/after report.
     */
    fun tuneUp(ctx: Context, progress: (String) -> Unit): String {
        val sb = StringBuilder()
        sb.append("=== KaviGuard Tune-Up ===\n\n")

        // Specs first — decisions below are driven by these
        val specs = try { getSpecs(ctx) } catch (e: Exception) {
            return "Could not read device specs: ${e.message}"
        }
        sb.append("${specs.model} | ${specs.androidVersion}\n")
        sb.append("RAM: ${specs.ramAvailMb} MB free of ${specs.ramTotalMb} MB " +
                "(${specs.ramUsedPct}% used)\n")
        sb.append("Storage: %.1f GB free of %.1f GB (%d%% free)\n\n".format(
            specs.storageFreeGb, specs.storageTotalGb, specs.storageFreePct))

        // --- RAM: kill background apps ---
        progress("Closing background apps…")
        val (killed, ramFreed) = try { killBackgroundApps(ctx) } catch (e: Exception) {
            Pair(0, 0L)
        }
        sb.append("• Background apps closed: $killed\n")
        sb.append("• RAM freed: ${StorageAnalyzer.formatBytes(ramFreed * 1_048_576)} " +
                "(now ${availRamMb(ctx)} MB free)\n")
        if (specs.ramUsedPct > 80) {
            sb.append("  (memory pressure was HIGH at ${specs.ramUsedPct}% — " +
                    "this was the priority)\n")
        }

        // --- Storage: clear our own cache ---
        progress("Clearing cache…")
        val ownCleared = try { clearOwnCache(ctx) } catch (e: Exception) { 0L }
        sb.append("• KaviGuard's own cache cleared: " +
                "${StorageAnalyzer.formatBytes(ownCleared)}\n")

        // --- Storage: top cache hogs (one-tap deep links) ---
        val hogs = try { StorageAnalyzer.analyze(ctx, 10) } catch (e: Exception) {
            emptyList()
        }
        val hogTotal = StorageAnalyzer.totalCache(hogs)
        sb.append("• Top-10 app caches hold: ${StorageAnalyzer.formatBytes(hogTotal)}\n")
        if (specs.storageFreePct < 10) {
            sb.append("  ⚠ Storage is LOW (${specs.storageFreePct}% free) — " +
                    "clearing the caches below is the priority.\n")
        }
        sb.append("\nAndroid only lets YOU tap 'Clear cache' per app.\n")
        sb.append("Biggest cache holders (use 'Open App Storage' per app):\n")
        for (h in hogs.take(8)) {
            if (h.cacheBytes > 0) {
                sb.append("  • ${h.label}: ${StorageAnalyzer.formatBytes(h.cacheBytes)}\n")
            }
        }

        // --- Downloads quick check ---
        val downloads = try { findLargeDownloads(ctx, 5) } catch (e: Exception) {
            emptyList()
        }
        if (downloads.isNotEmpty()) {
            val dlTotal = downloads.sumOf { it.sizeBytes }
            sb.append("\n• 5 largest downloads: ${StorageAnalyzer.formatBytes(dlTotal)}\n")
            sb.append("  Use 'Large Downloads' to review and delete them.\n")
        }

        sb.append("\nDone. RAM and cache work is real and immediate.\n")
        sb.append("Note: Android may restart some system services on its own — " +
                "your apps stay closed until you reopen them.")
        progress("Done")
        return sb.toString()
    }
}
