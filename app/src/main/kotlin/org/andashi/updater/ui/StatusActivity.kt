package org.andashi.updater.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.andashi.updater.R
import org.andashi.updater.UpdaterApp
import org.andashi.updater.store.Store
import org.andashi.updater.work.Schedule
import java.time.Duration
import java.time.Instant

/**
 * The status page (design §14): what the zone runs against what the lock
 * names, in one glance, and the history underneath. Plain views and no UI
 * framework - every dependency is attack surface in a reproducible build -
 * and nothing here can change policy, which comes from the host. The one
 * action is "Check now".
 */
class StatusActivity : Activity() {
    private lateinit var content: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (32 * dp).toInt())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val s = UpdaterApp.store(this).state()
        val now = Instant.now()

        content.addView(text("Andashi Updater", 22f, INK, bold = true))
        if (s.config.sha256 == null) {
            content.addView(text(getString(R.string.no_config), 15f, MUTED).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })
            content.addView(checkButton())
            return
        }
        val problems = s.apps.count { it.state in ATTENTION } + s.checks.netFalseViolations.size
        val headline = when {
            s.exemption == "missing" -> "Cannot update in the background" to RED
            s.lock.freshness == "stale" -> "The lock is stale" to AMBER
            s.lastCheckError != null && s.lock.sha256 == null -> "No lock yet" to AMBER
            problems > 0 -> "$problems need${if (problems == 1) "s" else ""} attention" to AMBER
            s.apps.any { it.state in IN_FLIGHT } -> "Updating" to CYAN
            else -> "Everything is current" to GREEN
        }
        content.addView(text(headline.first, 17f, headline.second, bold = true).apply { setPadding(0, (4 * dp).toInt(), 0, 0) })
        content.addView(text(buildString {
            append("zone ${s.config.zone ?: "-"}")
            append("  ·  lock ${s.lock.generated?.take(10) ?: "none"}")
            s.lock.freshnessReason?.let { append("  ·  $it") }
            append("\nlast check ${ago(s.lastCheck, now)}")
            s.lastCheckError?.let { append("\n$it") }
            if (s.exemption == "missing") append("\nbattery exemption missing: the host must grant it")
        }, 13f, MUTED).apply { setPadding(0, (6 * dp).toInt(), 0, (12 * dp).toInt()) })
        content.addView(checkButton())

        content.addView(section("Apps"))
        for (a in s.apps.sortedWith(compareBy({ order(it.state) }, { it.label.lowercase() }))) content.addView(appRow(a))

        if (s.checks.netFalseViolations.isNotEmpty()) {
            content.addView(section("Holds INTERNET although the catalog says no"))
            for (p in s.checks.netFalseViolations) content.addView(text(p, 14f, RED))
        }
        if (s.foreign.isNotEmpty()) {
            content.addView(section("Not managed here"))
            for (f in s.foreign.sortedBy { it.pkg }) content.addView(text("${f.pkg}  ·  ${f.installer ?: "no installer of record"}", 13f, MUTED))
        }
        content.addView(section("History"))
        for (h in s.history.takeLast(60).reversed()) {
            val line = buildString {
                append(h.at.take(16).replace('T', ' ')); append("  ")
                if (h.pkg != "-") append("${h.pkg.substringAfterLast('.')}  ")
                append(h.event)
                if (h.from != null && h.to != null) append("  ${h.from} → ${h.to}") else h.to?.let { append("  → $it") }
                h.detail?.let { append("  ·  $it") }
            }
            content.addView(text(line, 12f, if (h.event in setOf("failed", "lock-refused", "updater.json-refused", "lock.json-refused")) RED else MUTED))
        }
    }

    private fun appRow(a: Store.AppRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = 14 * dp }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(chip(a.state))
        head.addView(text(a.label.ifEmpty { a.pkg }, 16f, INK, bold = true).apply {
            setPadding((10 * dp).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val version = when {
            a.installed == null -> "not installed"
            a.locked != null && a.locked != a.installed -> "${a.installed} → ${a.locked}"
            else -> "${a.installed}"
        }
        head.addView(text(version, 13f, MUTED))
        row.addView(head)
        val detail = listOfNotNull(a.reason, a.lastError, a.nextTry?.takeIf { a.state == "failed" }?.let { "next try ${ago(it, Instant.now(), future = true)}" }).joinToString("\n")
        if (detail.isNotEmpty()) row.addView(text(detail, 13f, if (a.state in ATTENTION) AMBER else MUTED).apply { setPadding(0, (6 * dp).toInt(), 0, 0) })
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, (8 * dp).toInt()) }
        wrap.addView(row)
        return wrap
    }

    private fun chip(state: String): View {
        val color = when (state) {
            "current" -> GREEN
            "behind", "held", "downloading", "verifying", "installing", "waiting-constraints" -> CYAN
            "user-action", "failed", "unverifiable" -> RED
            else -> AMBER
        }
        return TextView(this).apply {
            text = state
            textSize = 11f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            background = GradientDrawable().apply { setColor(color); cornerRadius = 20 * dp }
        }
    }

    private fun checkButton(): View = Button(this).apply {
        text = getString(R.string.check_now)
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply { setColor(GOLD); cornerRadius = 24 * dp }
        setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
        setOnClickListener {
            Schedule.checkNow(this@StatusActivity, "status-page")
            text = "Checking…"
            postDelayed({ render() }, 2500)
        }
    }

    private fun section(title: String): View = text(title.uppercase(), 12f, MUTED, bold = true).apply {
        setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
        letterSpacing = 0.08f
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun ago(iso: String?, now: Instant, future: Boolean = false): String {
        val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "never"
        val d = if (future) Duration.between(now, t) else Duration.between(t, now)
        val m = d.toMinutes()
        return when {
            m < 1 -> if (future) "now" else "just now"
            m < 60 -> "$m min${if (future) "" else " ago"}"
            m < 60 * 48 -> "${m / 60} h${if (future) "" else " ago"}"
            else -> "${m / 60 / 24} d${if (future) "" else " ago"}"
        }
    }

    private fun order(state: String): Int = when (state) {
        "user-action" -> 0; "failed", "unverifiable" -> 1
        "behind", "held", "downloading", "verifying", "installing", "waiting-constraints" -> 2
        "not-owner", "missing", "ahead" -> 3; else -> 4
    }

    companion object {
        // brand/README.md tokens: ink on the neon ground, gold for the one action.
        private val BG = Color.parseColor("#000000")
        private val CARD = Color.parseColor("#14101C")
        private val INK = Color.parseColor("#F2EDF7")
        private val MUTED = Color.parseColor("#9A90A8")
        private val GREEN = Color.parseColor("#5CE0A0")
        private val CYAN = Color.parseColor("#2CE8FF")
        private val AMBER = Color.parseColor("#FFD84D")
        private val RED = Color.parseColor("#FF5C9E")
        private val GOLD = Color.parseColor("#FFD84D")
        private val ATTENTION = setOf("user-action", "failed", "unverifiable", "not-owner", "missing", "ahead")
        private val IN_FLIGHT = setOf("behind", "held", "downloading", "verifying", "installing", "waiting-constraints")
    }
}
