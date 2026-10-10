package org.andashi.updater.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Everything the updater knows, on disk, in app-private storage that is
 * never backed up: the accepted config, the cached lock, the state the host
 * reads, the diagnostics of the last ingest, and a bounded history.
 *
 * Writes are atomic (temp file, rename) and serialised on one lock, because
 * three things write here from three threads: the ingest provider, the
 * workers, and the install status receiver - and the receiver may be the
 * first thing that runs in a process the self-update just restarted.
 */
class Store(context: Context) {
    private val dir = File(context.noBackupFilesDir, "updater").apply { mkdirs() }
    private val lock = Any()

    @Serializable
    data class AppRecord(
        val pkg: String,
        val label: String = "",
        val state: String,
        val installed: Long? = null,
        val locked: Long? = null,
        val installer: String? = null,
        val reason: String? = null,
        val attempts: Int = 0,
        val nextTry: String? = null,
        val sessionId: Int? = null,
        val since: String,
        val lastError: String? = null,
        /** When this zone first saw [locked] in a valid lock: the clock of `minimumAgeHours` (design §11). */
        val seenAt: String? = null,
    )

    @Serializable
    data class LockState(
        val generated: String? = null,
        val sha256: String? = null,
        val fetchedAt: String? = null,
        val source: String? = null,
        val freshness: String = "unknown",
        val freshnessReason: String? = null,
        val lastError: String? = null,
    )

    @Serializable
    data class ForeignApp(val pkg: String, val installer: String? = null, val versionCode: Long = 0)

    @Serializable
    data class ChecksState(val netFalseOk: List<String> = emptyList(), val netFalseViolations: List<String> = emptyList())

    @Serializable
    data class HistoryEvent(
        val at: String,
        val pkg: String,
        val event: String,
        val from: Long? = null,
        val to: Long? = null,
        val lock: String? = null,
        val detail: String? = null,
        /** `success` or `failure` for the events that end an attempt, null for the steps in between - so a reader can count outcomes without knowing every event name. */
        val result: String? = null,
    )

    @Serializable
    data class ConfigState(val sha256: String? = null, val appliedAt: String? = null, val zone: String? = null)

    @Serializable
    data class State(
        val schemaVersion: Int = 1,
        val updaterVersionCode: Long = 0,
        val updaterInstaller: String? = null,
        val exemption: String = "unknown",
        val config: ConfigState = ConfigState(),
        val lock: LockState = LockState(),
        val lastCheck: String? = null,
        val lastCheckOk: String? = null,
        val lastCheckError: String? = null,
        val apps: List<AppRecord> = emptyList(),
        val foreign: List<ForeignApp> = emptyList(),
        val checks: ChecksState = ChecksState(),
        val history: List<HistoryEvent> = emptyList(),
    )

    /** The last ingest of each document: the config's, and in host-only mode the lock's, kept apart so one cannot erase the other. */
    @Serializable
    data class Diagnostics(
        val configSha256: String? = null, val success: Boolean = false, val error: String? = null, val at: String? = null,
        val lockSha256: String? = null, val lockSuccess: Boolean? = null, val lockError: String? = null, val lockAt: String? = null,
    )

    // ---- raw documents the host wrote or the fetch cached --------------------

    fun configText(): String? = read("config.json")
    fun writeConfigText(text: String) = write("config.json", text)
    fun lockText(): String? = read("lock.json")
    fun lockEtag(): String? = read("lock.etag")
    fun writeLock(text: String, etag: String?) = synchronized(lock) { write("lock.json", text); if (etag != null) write("lock.etag", etag) else File(dir, "lock.etag").delete() }
    fun hostLockText(): String? = read("host-lock.json")
    fun writeHostLockText(text: String) = write("host-lock.json", text)

    // ---- state and diagnostics -----------------------------------------------

    fun state(): State = synchronized(lock) { read("state.json")?.let { runCatching { json.decodeFromString<State>(it) }.getOrNull() } ?: State() }

    /** Read-modify-write under the lock, so two writers never lose each other's fields. */
    fun update(change: (State) -> State): State = synchronized(lock) {
        val next = change(state())
        write("state.json", json.encodeToString(next))
        next
    }

    fun diagnostics(): Diagnostics = synchronized(lock) { read("diagnostics.json")?.let { runCatching { json.decodeFromString<Diagnostics>(it) }.getOrNull() } ?: Diagnostics() }
    fun writeDiagnostics(d: Diagnostics) = synchronized(lock) { write("diagnostics.json", json.encodeToString(d)) }

    fun record(pkg: String, event: String, from: Long? = null, to: Long? = null, lockSha: String? = null, detail: String? = null) = update { s ->
        val result = when (event) {
            "updated", "already-current", "confirmed-current", "config-accepted", "host-lock-accepted" -> "success"
            "failed", "lock-refused", "updater.json-refused", "lock.json-refused" -> "failure"
            else -> null
        }
        val e = HistoryEvent(Instant.now().toString(), pkg, event, from, to, lockSha?.take(12), detail, result)
        s.copy(history = (s.history + e).takeLast(HISTORY_LIMIT))
    }

    fun app(pkg: String): AppRecord? = state().apps.firstOrNull { it.pkg == pkg }

    fun setApp(record: AppRecord) = update { s ->
        s.copy(apps = s.apps.filter { it.pkg != record.pkg } + record)
    }

    // ---- files -------------------------------------------------------------------

    private fun read(name: String): String? = synchronized(lock) {
        val f = File(dir, name); if (f.exists()) f.readText() else null
    }

    private fun write(name: String, text: String) = synchronized(lock) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) throw java.io.IOException("could not replace $name")
    }

    /** Where downloads land: app-private cache, one file per package, deleted after every attempt. */
    fun downloadFile(context: Context, pkg: String): File = File(File(context.cacheDir, "downloads").apply { mkdirs() }, "$pkg.apk")

    companion object {
        const val HISTORY_LIMIT = 500
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    }
}
