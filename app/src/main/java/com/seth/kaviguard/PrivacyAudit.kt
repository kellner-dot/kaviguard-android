package com.seth.kaviguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Privacy audit: which installed apps hold location / camera / microphone
 * permissions. Reuses PermissionAuditor, groups by sensor.
 */
object PrivacyAudit {

    data class SensorReport(
        val sensor: String,
        val apps: List<AppAudit>
    )

    fun audit(ctx: Context): List<SensorReport> {
        val all = PermissionAuditor.audit(ctx).filter { !it.isSystem }
        fun hasAny(a: AppAudit, vararg perms: String) =
            a.dangerousPermissions.any { p -> perms.any { p == it } }

        return listOf(
            SensorReport("Location", all.filter {
                hasAny(it, android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }),
            SensorReport("Camera", all.filter {
                hasAny(it, android.Manifest.permission.CAMERA)
            }),
            SensorReport("Microphone", all.filter {
                hasAny(it, android.Manifest.permission.RECORD_AUDIO)
            }),
            SensorReport("Contacts", all.filter {
                hasAny(it, android.Manifest.permission.READ_CONTACTS)
            }),
            SensorReport("SMS / Call log", all.filter { a ->
                a.dangerousPermissions.any { it.contains("SMS") || it.contains("CALL_LOG") }
            })
        )
    }

    /** Deep-link to an app's permission settings page. */
    fun openAppSettings(ctx: Context, packageName: String) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
