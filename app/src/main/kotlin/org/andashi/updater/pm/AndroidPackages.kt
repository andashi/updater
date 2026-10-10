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
        )
    }

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

    /** Whether an install session of this app still exists, committed or not. */
    fun sessionExists(sessionId: Int): Boolean = pm.packageInstaller.getSessionInfo(sessionId) != null

    /** The device's primary ABI, the one the lock's entries are chosen by. */
    val abi: String get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    val self: String get() = context.packageName
}
