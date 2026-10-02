package com.seth.kaviguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Result of a permission audit for one app. */
data class AppAudit(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val installer: String?,
    val dangerousPermissions: List<String>,
    val riskScore: Int,          // 0-100
    val flags: List<String>      // e.g. "SIDELOADED", "DEBUGGABLE", "LOCATION", ...
)

/**
 * Audits installed apps: dangerous permissions, installer source,
 * debuggable/system flags. Produces a risk score per app.
 *
 * Honest limits (no root): we can LIST permissions declared in manifests
 * and granted at runtime, but cannot revoke other apps' permissions —
 * the report deep-links to each app's Settings page for manual action.
 */
object PermissionAuditor {

    /** Permissions we consider privacy/security sensitive. */
    val DANGEROUS: Map<String, Int> = mapOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION to 15,
        android.Manifest.permission.ACCESS_COARSE_LOCATION to 10,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION to 25,
        android.Manifest.permission.CAMERA to 15,
        android.Manifest.permission.RECORD_AUDIO to 15,
        android.Manifest.permission.READ_CONTACTS to 10,
        android.Manifest.permission.WRITE_CONTACTS to 10,
        android.Manifest.permission.READ_SMS to 20,
        android.Manifest.permission.RECEIVE_SMS to 20,
        android.Manifest.permission.SEND_SMS to 20,
        android.Manifest.permission.READ_CALL_LOG to 15,
        android.Manifest.permission.READ_PHONE_STATE to 10,
        android.Manifest.permission.READ_EXTERNAL_STORAGE to 5,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE to 5,
        android.Manifest.permission.READ_MEDIA_IMAGES to 5,
        android.Manifest.permission.READ_MEDIA_VIDEO to 5,
        android.Manifest.permission.READ_MEDIA_AUDIO to 5,
        android.Manifest.permission.BODY_SENSORS to 15,
        android.Manifest.permission.ACTIVITY_RECOGNITION to 10,
        android.Manifest.permission.POST_NOTIFICATIONS to 3,
        android.Manifest.permission.SYSTEM_ALERT_WINDOW to 15,
        android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE to 20,
        android.Manifest.permission.REQUEST_INSTALL_PACKAGES to 15,
    )

    fun audit(ctx: Context): List<AppAudit> {
        val pm = ctx.packageManager
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(
                PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        return pkgs.mapNotNull { pi ->
            val ai: ApplicationInfo = pi.applicationInfo ?: return@mapNotNull null
            val label = ai.loadLabel(pm).toString()
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val installer: String? = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    pm.getInstallSourceInfo(pi.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(pi.packageName)
                }
            } catch (_: Exception) { null }

            val declared = pi.requestedPermissions?.toList() ?: emptyList()
            val grantedFlags = pi.requestedPermissionsFlags ?: IntArray(0)
            val dangerous = declared.filterIndexed { idx, perm ->
                DANGEROUS.containsKey(perm) &&
                    idx < grantedFlags.size &&
                    (grantedFlags[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }

            val flags = mutableListOf<String>()
            var score = dangerous.sumOf { DANGEROUS[it] ?: 0 }

            val fromStore = installer == "com.android.vending" || installer == "com.amazon.venezia"
            if (!isSystem && installer == null) {
                flags.add("SIDELOADED (unknown installer)")
                score += 25
            } else if (!isSystem && !fromStore) {
                flags.add("NON-STORE installer: $installer")
                score += 10
            }
            if ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                flags.add("DEBUGGABLE build")
                score += 20
            }
            if (dangerous.contains(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                flags.add("BACKGROUND LOCATION")
            if (dangerous.contains(android.Manifest.permission.CAMERA)) flags.add("CAMERA")
            if (dangerous.contains(android.Manifest.permission.RECORD_AUDIO)) flags.add("MICROPHONE")
            if (dangerous.any { it.contains("SMS") }) flags.add("SMS ACCESS")

            AppAudit(
                packageName = pi.packageName,
                label = label,
                isSystem = isSystem,
                installer = installer,
                dangerousPermissions = dangerous,
                riskScore = score.coerceAtMost(100),
                flags = flags
            )
        }.sortedByDescending { it.riskScore }
    }
}
