import android.app.Activity
package com.seth.kaviguard

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * KaviGuard Android v1.0.0 — security & health utility for Seth's Razr.
 *
 * Sections:
 *  - Permission audit (risk-scored app list)
 *  - Privacy audit (location / camera / mic / contacts / SMS by app)
 *  - Sketchy app detector (sideloaded, debuggable, non-store)
 *  - Storage analyzer (per-app cache sizes)
 *  - Battery monitor
 *
 * No root required. Actions that Android reserves for the user
 * (revoking permissions, clearing another app's cache, uninstalling)
 * deep-link to the right Settings page instead.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(layout)
        setContentView(scroll)

        layout.addView(TextView(this).apply {
            text = "KaviGuard Android v1.0.0"
            textSize = 22f
        })
        layout.addView(TextView(this).apply {
            text = "Security & health for this device. No root needed."
            textSize = 14f
        })

        fun btn(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        output = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        btn("Run Full Audit") { runFullAudit() }
        btn("Permission Audit") { runAsync { showPermissionAudit() } }
        btn("Privacy Audit (sensors)") { runAsync { showPrivacyAudit() } }
        btn("Sketchy App Detector") { runAsync { showSketchy() } }
        btn("Storage Analyzer") { runAsync { showStorage() } }
        btn("Battery Health") { runAsync { showBattery() } }

        layout.addView(output)
    }

    private fun runAsync(block: () -> String) {
        say("Working…")
        thread {
            val result = try { block() } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread { say(result) }
        }
    }

    private fun say(t: String) { output.text = t }

    // ---------- reports ----------

    private fun runFullAudit(): String {
        say("Working…")
        thread {
            val sb = StringBuilder()
            sb.append(showBattery()).append("\n\n")
            sb.append(showSketchy()).append("\n\n")
            sb.append(showStorage()).append("\n\n")
            sb.append(showPrivacyAudit()).append("\n\n")
            sb.append(showPermissionAudit())
            runOnUiThread { say(sb.toString()) }
        }
        return "Working…"
    }

    private fun showBattery(): String {
        val info = BatteryMonitor.snapshot(this)
        return "=== Battery ===\n" + BatteryMonitor.verdict(info)
    }

    private fun showSketchy(): String {
        val audits = PermissionAuditor.audit(this)
        val sketchy = audits.filter {
            !it.isSystem && (it.flags.any { f -> f.contains("SIDELOADED") || f.contains("DEBUGGABLE") }
                    || it.riskScore >= 50)
        }
        val sb = StringBuilder("=== Sketchy App Detector ===\n")
        if (sketchy.isEmpty()) sb.append("Nothing sketchy found. Nice.\n")
        for (a in sketchy) {
            sb.append("• ${a.label} (${a.packageName})\n")
            sb.append("  risk ${a.riskScore}/100 | ${a.flags.joinToString(", ")}\n")
            sb.append("  installer: ${a.installer ?: "unknown"}\n")
        }
        return sb.toString()
    }

    private fun showStorage(): String {
        val apps = StorageAnalyzer.analyze(this)
        val sb = StringBuilder("=== Storage ===\n")
        sb.append(StorageAnalyzer.deviceStorage()).append("\n")
        sb.append("Top cache hogs (tap app name in list below to open its Settings):\n")
        var total = 0L
        for (a in apps.take(15)) {
            total += a.cacheBytes
            sb.append("• ${a.label}: cache ${StorageAnalyzer.formatBytes(a.cacheBytes)} " +
                    "| data ${StorageAnalyzer.formatBytes(a.dataBytes)}\n")
        }
        sb.append("Top-50 cache total: ${StorageAnalyzer.formatBytes(StorageAnalyzer.totalCache(apps))}\n")
        sb.append("\nNote: Android only lets YOU clear another app's cache.\n" +
                "Tap a row below to open that app's Storage settings.")
        return sb.toString()
    }

    private fun showPrivacyAudit(): String {
        val reports = PrivacyAudit.audit(this)
        val sb = StringBuilder("=== Privacy Audit ===\n")
        for (r in reports) {
            sb.append("\n${r.sensor} (${r.apps.size} apps):\n")
            for (a in r.apps.take(20)) sb.append("• ${a.label}\n")
            if (r.apps.size > 20) sb.append("…and ${r.apps.size - 20} more\n")
        }
        return sb.toString()
    }

    private fun showPermissionAudit(): String {
        val audits = PermissionAuditor.audit(this).filter { !it.isSystem && it.riskScore > 0 }
        val sb = StringBuilder("=== Permission Audit (top risk) ===\n")
        for (a in audits.take(30)) {
            sb.append("• ${a.label} — risk ${a.riskScore}/100\n")
            if (a.flags.isNotEmpty()) sb.append("  ${a.flags.joinToString(", ")}\n")
            val perms = a.dangerousPermissions.take(6).joinToString(", ") {
                it.substringAfterLast('.')
            }
            sb.append("  perms: $perms\n")
        }
        return sb.toString()
    }
}
