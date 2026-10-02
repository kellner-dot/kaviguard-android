package com.seth.kaviguard

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * KaviGuard Android v1.0.5 — security & health utility for Seth's Razr.
 *
 * v1.0.5 adds REAL tuneup (TuneUpManager):
 *  - one-tap TUNE UP: kills background processes (real RAM freed, measured),
 *    clears our own cache, spec-aware (RAM >80% / storage <10% drive priority)
 *  - top cache hogs as one-tap buttons opening each app's Storage settings
 *  - large Downloads finder with system delete-confirmation dialog
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var dynamicBox: LinearLayout

    companion object {
        private const val REQ_MEDIA = 5101
    }

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
            text = "KaviGuard Android v1.0.5"
            textSize = 22f
        })
        layout.addView(TextView(this).apply {
            text = "Security & health for this device. No root needed."
            textSize = 14f
        })

        // Prominent one-tap tuneup
        layout.addView(Button(this).apply {
            text = "⚡ TUNE UP NOW"
            textSize = 18f
            setPadding(0, 24, 0, 24)
            setOnClickListener { runTuneUp() }
        })

        fun btn(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        btn("Top Cache Hogs (tap one to open its storage)") { runAsync { showHogButtons() } }
        btn("Large Downloads (find & delete)") { showDownloads() }
        btn("Run Full Audit") { runFullAudit() }
        btn("Permission Audit") { runAsync { showPermissionAudit() } }
        btn("Privacy Audit (sensors)") { runAsync { showPrivacyAudit() } }
        btn("Sketchy App Detector") { runAsync { showSketchy() } }
        btn("Storage Analyzer") { runAsync { showStorage() } }
        btn("Battery Health") { runAsync { showBattery() } }

        output = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(output)

        dynamicBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        layout.addView(dynamicBox)
    }

    // ---------- tuneup ----------

    private fun runTuneUp() {
        dynamicBox.removeAllViews()
        say("Tuning up…")
        thread {
            val report = try {
                TuneUpManager.tuneUp(this) { p -> runOnUiThread { say("$p…") } }
            } catch (e: Exception) {
                "Tune-up error: ${e.message}"
            }
            runOnUiThread { say(report) }
        }
    }

    /** Top cache hogs rendered as real one-tap buttons. */
    private fun showHogButtons(): String {
        val hogs = try {
            StorageAnalyzer.analyze(this, 15).filter { it.cacheBytes > 0 }
        } catch (e: Exception) {
            return "Could not analyze storage: ${e.message}"
        }
        runOnUiThread {
            dynamicBox.removeAllViews()
            dynamicBox.addView(TextView(this).apply {
                text = "Tap an app to open its Storage settings, then tap 'Clear cache':"
                textSize = 13f
            })
            for (h in hogs) {
                dynamicBox.addView(Button(this).apply {
                    text = "${h.label} — ${StorageAnalyzer.formatBytes(h.cacheBytes)}"
                    textSize = 12f
                    setOnClickListener {
                        TuneUpManager.openAppStorageSettings(this@MainActivity, h.packageName)
                    }
                })
            }
        }
        val total = StorageAnalyzer.totalCache(hogs)
        return "Top-${hogs.size} caches hold ${StorageAnalyzer.formatBytes(total)}.\n" +
                "Buttons below open each app's Storage screen — one tap each."
    }

    // ---------- large downloads ----------

    private fun showDownloads() {
        dynamicBox.removeAllViews()
        if (!hasMediaPermission()) {
            requestMediaPermission()
            say("Need storage permission to list Downloads — granting it will load the list.")
            return
        }
        loadDownloads()
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermission() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, REQ_MEDIA)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                loadDownloads()
            } else {
                say("Permission denied — can't list Downloads.")
            }
        }
    }

    private fun loadDownloads() {
        say("Scanning Downloads…")
        thread {
            val files = try {
                TuneUpManager.findLargeDownloads(this@MainActivity, 20)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                dynamicBox.removeAllViews()
                if (files.isEmpty()) {
                    say("No large downloads found (or none readable).")
                    return@runOnUiThread
                }
                val total = files.sumOf { it.sizeBytes }
                say("${files.size} largest downloads " +
                        "(${StorageAnalyzer.formatBytes(total)} total):\n" +
                        files.joinToString("\n") {
                            "• ${it.name} — ${StorageAnalyzer.formatBytes(it.sizeBytes)}"
                        })
                dynamicBox.addView(Button(this).apply {
                    text = "🗑 Delete ALL listed (system will confirm)"
                    setOnClickListener {
                        val ok = TuneUpManager.requestDeleteDownloads(
                            this@MainActivity, files)
                        if (!ok) say("Delete not supported here — remove files manually.")
                    }
                })
                dynamicBox.addView(TextView(this).apply {
                    text = "Android shows a system confirmation dialog — nothing is " +
                            "deleted until you approve it there."
                    textSize = 12f
                })
            }
        }
    }

    @Deprecated("legacy")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TuneUpManager.DELETE_REQUEST_CODE) {
            val msg = if (resultCode == RESULT_OK) "Deleted." else "Delete cancelled."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            say(msg + " Tap 'Large Downloads' to rescan.")
            dynamicBox.removeAllViews()
        }
    }

    // ---------- existing reports ----------

    private fun runAsync(block: () -> String) {
        dynamicBox.removeAllViews()
        say("Working…")
        thread {
            val result = try { block() } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread { say(result) }
        }
    }

    private fun say(t: String) { output.text = t }

    private fun runFullAudit(): String {
        dynamicBox.removeAllViews()
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
        sb.append("Top cache hogs:\n")
        for (a in apps.take(15)) {
            sb.append("• ${a.label}: cache ${StorageAnalyzer.formatBytes(a.cacheBytes)} " +
                    "| data ${StorageAnalyzer.formatBytes(a.dataBytes)}\n")
        }
        sb.append("Top-50 cache total: ${StorageAnalyzer.formatBytes(StorageAnalyzer.totalCache(apps))}\n")
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
