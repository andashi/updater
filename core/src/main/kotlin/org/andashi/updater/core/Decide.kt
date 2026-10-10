package org.andashi.updater.core

/**
 * The state of one managed package, decided from three facts: what the host
 * pinned, what the lock names, what the package manager reports (design §7).
 * Every state carries the words the host will read.
 */
sealed class AppState {
    abstract val pkg: String

    /** Installed versionCode and bytes equal the lock's, installer of record is the updater. Nothing to do. */
    data class Current(override val pkg: String, val versionCode: Long) : AppState()

    /** Installed is older than the lock, or the same version with other bytes ([rebuilt]): an update is due, and [entry] is what to fetch. */
    data class Behind(override val pkg: String, val installed: Long, val entry: LockEntry, val rebuilt: Boolean = false) : AppState()

    /** Installed is newer than the lock. Never downgraded; reported. */
    data class Ahead(override val pkg: String, val installed: Long, val locked: Long) : AppState()

    /**
     * A silent update would be impossible, whether or not one is due: the
     * installer of record is somebody else or nobody, or (Android 14+)
     * another package holds update ownership. Both facts are carried, so the
     * host repairs the right one.
     */
    data class NotOwner(override val pkg: String, val installer: String?, val installed: Long, val locked: Long, val updateOwner: String? = null) : AppState()

    /** Managed, not installed in this user. The updater never first-installs; the host does. */
    data class Missing(override val pkg: String) : AppState()

    /** Cannot be verified: no lock entry for this ABI, lock and pin disagree, or the installed signer differs from the pin. */
    data class Unverifiable(override val pkg: String, val reason: String) : AppState()
}

/**
 * Pure decision: no I/O, no clock. The order of the checks is the order of
 * the design's trust chain: pin first (the host's word), then the lock
 * (fetched), then the device (what runs). A package fails on the first
 * check it cannot pass, and the reason names that check.
 */
object Decide {
    fun state(
        managed: Config.ManagedApp,
        lock: Lock?,
        abi: String,
        installed: InstalledPackage?,
        self: String,
    ): AppState {
        val pkg = managed.pkg
        if (installed == null) return AppState.Missing(pkg)
        // The installed signer must be the pin before anything else is
        // believed about this package: a differently signed build is not
        // the app the catalog placed here, whatever its version says.
        if (installed.signers != setOf(managed.signer)) {
            return AppState.Unverifiable(pkg, "installed signer ${installed.signers.joinToString()} is not the pinned ${managed.signer}")
        }
        if (lock == null) return AppState.Unverifiable(pkg, "no lock")
        val entry = lock.entryFor(pkg, abi) ?: return AppState.Unverifiable(pkg, "no lock entry for abi $abi")
        if (entry.signer != managed.signer) {
            return AppState.Unverifiable(pkg, "lock signer ${entry.signer} disagrees with the pinned ${managed.signer}")
        }
        return when {
            installed.versionCode > entry.versionCode -> AppState.Ahead(pkg, installed.versionCode, entry.versionCode)
            // Ownership is reported even while the version matches: the next
            // update would prompt, and the host can repair it now, silently,
            // with a same-version reinstall naming the updater (design §6).
            installed.installer != self || (installed.updateOwner != null && installed.updateOwner != self) ->
                // Android 14+: update ownership is a second fact. Held by another
                // package, the system prompts although we are installer of record.
                AppState.NotOwner(pkg, installed.installer, installed.versionCode, entry.versionCode, installed.updateOwner)
            installed.versionCode < entry.versionCode -> AppState.Behind(pkg, installed.versionCode, entry)
            // Same version: current only with the lock's bytes. A rebuild the
            // maintainer took through review converges; an APK that cannot
            // be read cannot be called current (design §7).
            installed.apkSha256 == null -> AppState.Unverifiable(pkg, "installed APK could not be hashed")
            installed.apkSha256 != entry.sha256 -> AppState.Behind(pkg, installed.versionCode, entry, rebuilt = true)
            else -> AppState.Current(pkg, installed.versionCode)
        }
    }

    /** All managed packages, in the config's order. */
    fun all(config: Config, lock: Lock?, abi: String, packages: Packages, self: String): List<AppState> =
        config.managed.map { state(it, lock, abi, packages.installed(it.pkg), self) }

    /** Installed in this user and not managed: reported, never touched. */
    fun foreign(config: Config, packages: Packages): List<InstalledPackage> {
        val managed = config.managed.map { it.pkg }.toSet()
        return packages.all().filter { it.pkg !in managed }
    }

    /** `net: false` packages that hold INTERNET after all - the check boundaries.md asked for. */
    fun netFalseViolations(config: Config, packages: Packages): List<String> =
        config.checks.netFalse.filter { packages.installed(it)?.holdsInternet == true }
}
