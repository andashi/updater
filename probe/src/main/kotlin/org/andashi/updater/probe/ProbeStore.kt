package org.andashi.updater.probe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** One run, as the record the shell reads back. Mutable while the run is in progress, frozen by [finish]. */
class ProbeRecord(
    val startedAt: String,
    val trigger: String,
    val url: String,
    val attempt: Int,
) {
    var finishedAt: String? = null
    var foreground: Boolean? = null
    var network: String? = null
    var httpStatus: Int? = null
    var bytes: Long? = null
    var sha256: String? = null
    var durationMs: Long? = null
    var ok: Boolean = false
    var error: String? = null

    fun finish(): ProbeRecord = apply { finishedAt = Instant.now().toString() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("trigger", trigger)
        put("url", url)
        put("attempt", attempt)
        put("foreground", foreground)
        put("network", network)
        put("httpStatus", httpStatus)
        put("bytes", bytes)
        put("sha256", sha256)
        put("durationMs", durationMs)
        put("ok", ok)
        put("error", error)
    }
}

/**
 * Append-only JSON Lines file in app-private storage. One line per run, so a
 * 24-hour soak reads as a table: how many runs, how many got network, what
 * the failures said. Nothing is ever rewritten; a run that crashed mid-way
 * leaves no line, which is itself visible as a gap between scheduled times.
 */
object ProbeStore {
    private const val FILE = "runs.jsonl"

    @Synchronized
    fun append(context: Context, record: ProbeRecord) {
        file(context).appendText(record.toJson().toString() + "\n")
    }

    fun readAll(context: Context): List<String> {
        val f = file(context)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE)
}
