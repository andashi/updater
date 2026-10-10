package org.andashi.updater.work

import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.andashi.updater.Debug
import org.andashi.updater.Notifications
import org.andashi.updater.UpdaterApp
import org.andashi.updater.core.AppState
import org.andashi.updater.core.Backoff
import org.andashi.updater.core.Config
import org.andashi.updater.core.Decide
import org.andashi.updater.core.Fresh
import org.andashi.updater.core.Freshness
import org.andashi.updater.core.Heartbeat
import org.andashi.updater.core.Hex
import org.andashi.updater.core.Hold
import org.andashi.updater.core.InstalledPackage
import org.andashi.updater.core.Lock
import org.andashi.updater.core.LockFormatException
import org.andashi.updater.net.Fetcher
import org.andashi.updater.net.HttpFetcher
import org.andashi.updater.pm.AndroidPackages
import org.andashi.updater.store.Store
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * One check (design §7, steps 1-2): get the lock, decide every managed
 * package's state, write the state the host reads, and enqueue the install
 * run if anything is due. Cheap and frequent; the install run is the
 * expensive one and runs only when this one found work.
 *
 * Checks are serialised in the process ([gate]): the periodic one, a
 * shell's CHECK_NOW and a reconcile are enqueued under different names, and
 * two of them deciding at once would race each other's lock and records.
 * The record merge happens inside the store's transaction, against the
 * records as they are then, so a result the install run or the status
 * receiver wrote meanwhile is never overwritten by a decision made before it.
 */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = gate.withLock { check() }

    private fun check(): Result {
        val context = applicationContext
        val store = UpdaterApp.store(context)
        val trigger = inputData.getString(Schedule.KEY_TRIGGER) ?: "unknown"
        val now = Instant.now()
        val packages = AndroidPackages(context)
        val self = packages.installed(packages.self)

        val config = Loader.config(context, store)
        if (config == null) {
            store.update { it.copy(lastCheck = now.toString(), lastCheckError = "no configuration from the host", updaterVersionCode = self?.versionCode ?: 0, updaterInstaller = self?.installer) }
            return Result.success()
        }

        // The exemption that lets the install run start its foreground
        // service from a background job (design §8). Granted by the host,
        // reported here, loudly when missing.
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        if (exempt) Notifications.clearExemptionMissing(context) else Notifications.exemptionMissing(context)

        val fetcher = HttpFetcher(config.lockPolicy(Debug.cleartextLocalhost(context)).allowedHosts, Debug.cleartextLocalhost(context))
        val (lock, lockState) = obtainLock(config, store, fetcher, now)

        val states = Decide.all(config, lock, packages.abi, packages, packages.self)
        val lockSha = lockState.sha256
        val installRunning = Loader.installRunning(context)
        val installed = states.associate { it.pkg to packages.installed(it.pkg) }
        val foreign = Decide.foreign(config, packages).map { Store.ForeignApp(it.pkg, it.installer, it.versionCode) }
        val violations = Decide.netFalseViolations(config, packages)
        val checks = Store.ChecksState(netFalseOk = config.checks.netFalse - violations.toSet(), netFalseViolations = violations)

        // Decided outside, merged inside: the previous record of each package
        // is read in the transaction, so a newer transition wins.
        var previous: Map<String, Store.AppRecord> = emptyMap()
        var records: List<Store.AppRecord> = emptyList()
        var earliestHold: Instant? = null
        store.update { s ->
            previous = s.apps.associateBy { it.pkg }
            val events = mutableListOf<Store.HistoryEvent>()
            records = states.map { st ->
                val (r, e) = toRecord(st, config, previous[st.pkg], now, lockSha, installed[st.pkg], installRunning, packages)
                events += e
                if (r.state == "held") {
                    val opens = r.nextTry?.let { t -> runCatching { Instant.parse(t) }.getOrNull() }
                    if (opens != null && (earliestHold == null || opens.isBefore(earliestHold))) earliestHold = opens
                }
                r
            }
            s.copy(
                updaterVersionCode = self?.versionCode ?: 0, updaterInstaller = self?.installer,
                exemption = if (exempt) "granted" else "missing",
                lock = lockState, lastCheck = now.toString(),
                lastCheckOk = if (lockState.lastError == null) now.toString() else s.lastCheckOk,
                lastCheckError = lockState.lastError,
                apps = records, foreign = foreign, checks = checks,
                history = (s.history + events).takeLast(Store.HISTORY_LIMIT),
            )
        }
        for ((state, record) in states.zip(records)) notify(context, state, record, previous[record.pkg])

        val due = records.any { it.state == "behind" && Backoff.due(it.nextTry?.let { t -> runCatching { Instant.parse(t) }.getOrNull() }, now) }
        if (due && exempt && !config.hostOnly) Schedule.install(context, trigger)
        // One check at the moment the earliest hold opens, and - separately,
        // under its own name - periodic looks while the system holds a
        // session for a quiet moment.
        earliestHold?.let { Schedule.reconcileHold(context, Duration.between(now, it).toMinutes().coerceAtLeast(1)) }
        if (records.any { it.state == "waiting-constraints" }) Schedule.reconcileSessions(context, RECONCILE_EVERY_MINUTES)
        return Result.success()
    }

    /**
     * The lock from the host (host-only) or from the network, with the
     * cached one as the fallback. The rollback guard runs against the lock
     * on disk at the moment of replacement, inside the store's lock, so a
     * slow answer for an older lock cannot replace a newer one another
     * check accepted meanwhile.
     */
    private fun obtainLock(config: Config, store: Store, fetcher: Fetcher, now: Instant): Pair<Lock?, Store.LockState> {
        val policy = config.lockPolicy(Debug.cleartextLocalhost(applicationContext))
        val prior = store.state().lock
        if (config.hostOnly) {
            val text = store.hostLockText()
            val lock = text?.let { runCatching { Lock.parse(it, policy) }.getOrNull() }
            return lock to Store.LockState(generated = lock?.generated, sha256 = text?.let { sha(it) }, fetchedAt = prior.fetchedAt, source = "host",
                freshness = "unknown", freshnessReason = "host-only: the lock is as fresh as the last push",
                lastError = if (lock == null) "no lock from the host yet" else null)
        }

        var error: String? = null
        var fetchedAt = prior.fetchedAt
        when (val r = fetcher.text(config.lock.url, store.lockEtag(), MAX_LOCK_BYTES)) {
            is Fetcher.TextResult.Ok -> {
                val refusal = store.replaceLock(r.text, r.etag) { existing ->
                    val held = existing?.let { runCatching { Lock.parse(it, policy) }.getOrNull() }
                    try {
                        Lock.parse(r.text, policy, held); null
                    } catch (e: LockFormatException) {
                        e.message ?: "refused"
                    }
                }
                when (refusal) {
                    null -> {
                        fetchedAt = now.toString()
                        if (prior.sha256 != sha(r.text)) store.record("-", "lock-fetched", lockSha = sha(r.text), detail = "generated ${runCatching { Lock.parse(r.text, policy) }.getOrNull()?.generated}")
                    }
                    else -> { error = "lock refused: $refusal"; store.record("-", "lock-refused", detail = refusal) }
                }
            }
            Fetcher.TextResult.NotModified -> fetchedAt = now.toString()
            is Fetcher.TextResult.Failed -> error = "lock unreachable: ${r.reason}"
        }
        val text = store.lockText()
        val lock = text?.let { runCatching { Lock.parse(it, policy) }.getOrNull() }
        val lockSha = text?.let { sha(it) }
        val heartbeat = if (config.lock.heartbeatUrl.isNotEmpty()) {
            (fetcher.text(config.lock.heartbeatUrl, null, MAX_LOCK_BYTES) as? Fetcher.TextResult.Ok)?.let { Heartbeat.parse(it.text) }
        } else null
        val freshness = if (lockSha != null) Fresh.of(lockSha, lock?.generatedAt, heartbeat, now, config.lock.maxAgeDays) else Freshness.Unknown("no lock")
        val (fresh, why) = when (freshness) {
            is Freshness.Fresh -> "fresh" to "refresh ran ${freshness.checkedAt}"
            is Freshness.Stale -> "stale" to freshness.reason
            is Freshness.Unknown -> "unknown" to freshness.reason
        }
        if (freshness is Freshness.Stale) Notifications.lockProblem(applicationContext, "The lock is stale: ${freshness.reason}.")
        else if (error != null && lock == null) Notifications.lockProblem(applicationContext, error)
        else if (error == null) Notifications.clearLockProblem(applicationContext)
        return lock to Store.LockState(generated = lock?.generated, sha256 = lockSha, fetchedAt = fetchedAt, source = "fetch", freshness = fresh, freshnessReason = why, lastError = error)
    }

    /**
     * The record the host reads, carrying over what is in flight. A package
     * the install run is working on keeps its state while the session it
     * names still exists (or, before a session, while the install run is
     * running); a decision that now says `current` wins over any in-flight
     * state, because it is the package manager speaking. Returns the record
     * and the history events to append in the same transaction.
     */
    private fun toRecord(
        state: AppState, config: Config, prev: Store.AppRecord?, now: Instant, lockSha: String?,
        installed: InstalledPackage?, installRunning: Boolean, packages: AndroidPackages,
    ): Pair<Store.AppRecord, List<Store.HistoryEvent>> {
        val label = config.managed.firstOrNull { it.pkg == state.pkg }?.label ?: state.pkg
        val events = mutableListOf<Store.HistoryEvent>()
        fun event(e: String, from: Long? = null, to: Long? = null, detail: String? = null) {
            events += Store.HistoryEvent(now.toString(), state.pkg, e, from, to, lockSha?.take(12), detail, Store.resultOf(e))
        }
        val base = Store.AppRecord(pkg = state.pkg, label = label, state = "", installed = installed?.versionCode, installer = installed?.installer, since = prev?.since ?: now.toString())
        fun keepSince(newState: String) = if (prev?.state == newState) prev.since else now.toString()
        val record = when (state) {
            is AppState.Current -> {
                if (prev != null && prev.state != "current") event("confirmed-current", from = prev.installed, to = state.versionCode)
                base.copy(state = "current", locked = state.versionCode, since = keepSince("current"))
            }
            is AppState.Behind -> heldRecord(state, config, prev, now, base) ?: when (prev?.state) {
                "downloading", "verifying", "installing", "waiting-constraints", "user-action" ->
                    if (prev.sessionId != null && packages.sessionExists(prev.sessionId)) {
                        prev.copy(installed = installed?.versionCode, locked = state.entry.versionCode, lockedSha256 = state.entry.sha256, installer = installed?.installer)
                    } else if ((prev.state == "downloading" || prev.state == "verifying") && installRunning) {
                        prev.copy(installed = installed?.versionCode, locked = state.entry.versionCode, lockedSha256 = state.entry.sha256, installer = installed?.installer)
                    } else {
                        event("session-lost", to = state.entry.versionCode, detail = if (prev.sessionId != null) "session ${prev.sessionId} is gone; trying again" else "the install run stopped in ${prev.state}; trying again")
                        base.copy(state = "behind", locked = state.entry.versionCode, lockedSha256 = state.entry.sha256, seenAt = seenAtFor(prev, state.entry.versionCode, now))
                    }
                "failed" -> if (prev.locked == state.entry.versionCode && prev.lockedSha256 == state.entry.sha256) prev.copy(state = "behind", installer = installed?.installer)
                    else base.copy(state = "behind", locked = state.entry.versionCode, lockedSha256 = state.entry.sha256, seenAt = now.toString())
                else -> base.copy(state = "behind", locked = state.entry.versionCode, lockedSha256 = state.entry.sha256, since = keepSince("behind"), seenAt = seenAtFor(prev, state.entry.versionCode, now),
                    reason = when {
                        config.hostOnly -> "host-only zone: the laptop installs this"
                        state.rebuilt -> "same version, other bytes in the lock: converging to the locked build"
                        else -> prev?.reason?.takeIf { it.startsWith("bound reached") }
                    })
            }
            is AppState.Ahead -> base.copy(state = "ahead", locked = state.locked, reason = "installed ${state.installed} is newer than the lock's ${state.locked}", since = keepSince("ahead"))
            is AppState.NotOwner -> base.copy(state = "not-owner", locked = state.locked, reason = Records.notOwnerReason(state, packages.self), since = keepSince("not-owner"))
            is AppState.Missing -> base.copy(state = "missing", reason = "not installed in this user; the updater never first-installs", since = keepSince("missing"))
            is AppState.Unverifiable -> base.copy(state = "unverifiable", reason = state.reason, since = keepSince("unverifiable"))
        }
        return record to events
    }

    /** The first sighting of this locked version in this zone, kept across checks; a new version starts a new clock. */
    private fun seenAtFor(prev: Store.AppRecord?, locked: Long, now: Instant): String =
        if (prev != null && prev.locked == locked && prev.seenAt != null) prev.seenAt else now.toString()

    /** `held` while the version is younger than `minimumAgeHours` (design §11); null when there is no hold. The check that opens it is scheduled once, for the earliest hold, by the caller. */
    private fun heldRecord(state: AppState.Behind, config: Config, prev: Store.AppRecord?, now: Instant, base: Store.AppRecord): Store.AppRecord? {
        if (config.policy.minimumAgeHours <= 0) return null
        if (prev?.state in setOf("downloading", "verifying", "installing", "waiting-constraints", "user-action")) return null
        val seenAt = seenAtFor(prev, state.entry.versionCode, now)
        val seen = runCatching { Instant.parse(seenAt) }.getOrDefault(now)
        if (!Hold.held(seen, config.policy.minimumAgeHours, now)) return null
        val opens = Hold.opensAt(seen, config.policy.minimumAgeHours)
        return base.copy(state = "held", locked = state.entry.versionCode, lockedSha256 = state.entry.sha256, seenAt = seenAt, nextTry = opens.toString(),
            reason = "held until $opens (minimumAgeHours ${config.policy.minimumAgeHours})", since = if (prev?.state == "held") prev.since else now.toString())
    }

    /**
     * Once, when a state is entered: unverifiable, not-owner and missing are
     * the host's to fix, and the phone says so (design §14). Current clears.
     */
    private fun notify(context: Context, state: AppState, record: Store.AppRecord, prev: Store.AppRecord?) {
        val entered = prev?.state != record.state || prev.reason != record.reason
        when (state) {
            is AppState.Unverifiable -> if (entered) Notifications.failure(context, record.pkg, record.label, record.reason ?: "unverifiable")
            is AppState.NotOwner -> if (entered) Notifications.failure(context, record.pkg, record.label, "Updates would ask for a tap: ${record.reason}. The laptop repairs this.")
            is AppState.Missing -> if (entered) Notifications.failure(context, record.pkg, record.label, "Managed by the catalog but not installed in this zone; the laptop installs it.")
            is AppState.Current -> Notifications.clearFailure(context, record.pkg)
            else -> {}
        }
    }

    private fun sha(text: String): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    companion object {
        const val MAX_LOCK_BYTES = 4L * 1024 * 1024
        const val RECONCILE_EVERY_MINUTES = 30L

        /** One check at a time in this process, whatever name enqueued it. */
        private val gate = Mutex()
    }
}
