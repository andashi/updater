package org.andashi.updater.pm

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import org.andashi.updater.core.ArchiveInfo
import org.andashi.updater.core.Hex
import org.andashi.updater.core.InstalledPackage
import org.andashi.updater.core.Packages
import java.security.MessageDigest

/**
 * The package manager of this user, as the decisions see it. Every field is
 * read from the system; the installer of record comes from
 * `getInstallSourceInfo`, not from `installerPackageName` on PackageInfo,
 * which is deprecated and was null for a shell install without `-i` in both.
 */
class AndroidPackages(private val context: Context) : Packages {
    private val pm = context.packageManager

    override fun installed(pkg: String): InstalledPackage? {
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of((PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS).toLong()))
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return InstalledPackage(
            pkg = pkg,
            versionCode = info.longVersionCode,
            signers = signersOf(info.signingInfo),
            installer = installerOf(pkg),
            holdsInternet = pm.checkPermission(android.Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED,
            apkSha256 = apkHashes.sha256(info),
            updateOwner = updateOwnerOf(pkg),
        )
    }

    private fun updateOwnerOf(pkg: String): String? = try {
        pm.getInstallSourceInfo(pkg).updateOwnerPackageName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private val apkHashes = ApkHashCache(context)

    /** Every non-system package in this user. The system image's own packages are the OS's to update and would drown the report of what the catalog does not manage. */
    override fun all(): List<InstalledPackage> =
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            .filter { (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { info ->
                InstalledPackage(
                    pkg = info.packageName,
                    versionCode = info.longVersionCode,
                    signers = signersOf(info.signingInfo),
                    installer = installerOf(info.packageName),
                    holdsInternet = pm.checkPermission(android.Manifest.permission.INTERNET, info.packageName) == PackageManager.PERMISSION_GRANTED,
                )
            }

    override fun archive(path: String): ArchiveInfo? {
        val info: PackageInfo = pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            ?: return null
        val signing = info.signingInfo ?: return null
        return ArchiveInfo(info.packageName, info.longVersionCode, signersOf(signing))
    }

    private fun installerOf(pkg: String): String? = try {
        pm.getInstallSourceInfo(pkg).installingPackageName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * SHA-256 of each certificate that currently signs the APK. With a v3
     * lineage `apkContentsSigners` is the newest certificate, which is the
     * one a pin must name after a rotation the maintainer accepted.
     */
    private fun signersOf(signing: SigningInfo?): Set<String> {
        if (signing == null) return emptySet()
        val sigs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.apkContentsSigners
        return sigs.map { Hex.encode(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    /** Whether an install session of this app still exists AND was committed - only then will the system report on it; an uncommitted one is a leftover. */
    fun sessionCommitted(sessionId: Int): Boolean = pm.packageInstaller.getSessionInfo(sessionId)?.isCommitted == true

    /** The device's primary ABI, the one the lock's entries are chosen by. */
    val abi: String get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    val self: String get() = context.packageName
}

/**
 * SHA-256 of an installed package's base APK (`sourceDir`), hashed once per
 * installed build and remembered by package, versionCode and
 * lastUpdateTime, so a check costs a file read per package rather than a
 * hash of a hundred megabytes of Tor Browser every six hours. The file is
 * the package manager's own copy; if it cannot be read, the answer is null,
 * and null is never current (design §7).
 */
class ApkHashCache(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "apk-hashes.properties")
    private val props = java.util.Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }

    @Synchronized
    fun sha256(info: PackageInfo): String? {
        val src = info.applicationInfo?.sourceDir ?: return null
        val key = "${info.packageName}|${info.longVersionCode}|${info.lastUpdateTime}"
        props.getProperty(key)?.let { return it }
        val sha = try {
            val digest = MessageDigest.getInstance("SHA-256")
            java.io.File(src).inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            Hex.encode(digest.digest())
        } catch (_: Exception) {
            return null
        }
        // Entries for builds that are gone leave with the next write.
        val stale = props.stringPropertyNames().filter { it.startsWith("${info.packageName}|") }
        stale.forEach { props.remove(it) }
        props.setProperty(key, sha)
        runCatching { file.outputStream().use { props.store(it, null) } }
        return sha
    }
}
