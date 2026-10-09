package org.andashi.updater.ui

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.andashi.updater.R
import org.andashi.updater.UpdaterApp
import org.andashi.updater.store.Store
import org.andashi.updater.work.Schedule

/**
 * The status page (design §14): per app the state, the lock's age, the last
 * check, a Check-now button, and the history. Plain views, no framework -
 * every dependency is attack surface in a reproducible build - and nothing
 * here can change policy, which comes from the host.
 */
class StatusActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val button = Button(this).apply {
            text = getString(R.string.check_now)
            setOnClickListener { Schedule.checkNow(this@StatusActivity, "status-page"); postDelayed({ render() }, 1500) }
        }
        text = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; movementMethod = ScrollingMovementMethod(); gravity = Gravity.START }
        column.addView(button)
        column.addView(text)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val s = UpdaterApp.store(this).state()
        if (s.config.sha256 == null) { text.text = getString(R.string.no_config); return }
        text.text = buildString {
            appendLine("zone ${s.config.zone}   updater ${s.updaterVersionCode}   exemption ${s.exemption}")
            appendLine("lock ${s.lock.generated ?: "-"}  ${s.lock.freshness}${s.lock.freshnessReason?.let { " ($it)" } ?: ""}")
            s.lock.lastError?.let { appendLine("lock error: $it") }
            appendLine("last check ${s.lastCheck ?: "-"}   last ok ${s.lastCheckOk ?: "-"}")
            s.lastCheckError?.let { appendLine("error: $it") }
            appendLine()
            for (a in s.apps.sortedWith(compareBy({ order(it) }, { it.label }))) {
                appendLine("${a.state.padEnd(18)} ${a.label}  ${a.installed ?: "-"}${if (a.locked != null && a.locked != a.installed) " -> ${a.locked}" else ""}")
                a.reason?.let { appendLine("    $it") }
                a.lastError?.let { appendLine("    $it") }
            }
            if (s.foreign.isNotEmpty()) {
                appendLine(); appendLine("not managed here:")
                for (f in s.foreign.sortedBy { it.pkg }) appendLine("    ${f.pkg}  (${f.installer ?: "no installer"})")
            }
            if (s.checks.netFalseViolations.isNotEmpty()) {
                appendLine(); appendLine("net:false but holds INTERNET:")
                for (p in s.checks.netFalseViolations) appendLine("    $p")
            }
            appendLine(); appendLine("history:")
            for (h in s.history.takeLast(40).reversed()) appendLine("${h.at.take(19)} ${h.pkg} ${h.event}${h.from?.let { " $it" } ?: ""}${h.to?.let { " -> $it" } ?: ""}${h.detail?.let { ": $it" } ?: ""}")
        }
    }

    private fun order(a: Store.AppRecord): Int = when (a.state) {
        "user-action" -> 0; "failed", "unverifiable" -> 1; "behind", "downloading", "verifying", "installing", "waiting-constraints" -> 2
        "not-owner", "missing", "ahead" -> 3; else -> 4
    }
}
