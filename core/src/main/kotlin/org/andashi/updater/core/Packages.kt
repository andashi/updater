package org.andashi.updater.core

/**
 * What the package manager says about one installed package, as far as the
 * decisions need it. The Android side fills this from PackageManager; tests
 * fill it by hand. Nothing here is derived - every field is a fact the
 * system reported.
 */
data class InstalledPackage(
    val pkg: String,
    val versionCode: Long,
    /** SHA-256 of each current signing certificate, lower-case hex. More than one means the app has multiple signers, which no managed app has. */
    val signers: Set<String>,
    /** `installerPackageName`, or null after a shell install without `-i`. */
    val installer: String?,
    /** Whether `android.permission.INTERNET` is granted - for the `netFalse` check, nothing else. */
    val holdsInternet: Boolean,
    /** SHA-256 of the installed base APK's bytes, lower-case hex; null when the file could not be read, which is a reason to refuse, not to assume. */
    val apkSha256: String? = null,
    /** Android 14+ update owner (`InstallSourceInfo.updateOwnerPackageName`), null when nobody holds it. Another owner means a silent update is impossible even with the installer of record. */
    val updateOwner: String? = null,
)

/** What `getPackageArchiveInfo` says about a downloaded file before any session exists. */
data class ArchiveInfo(
    val pkg: String,
    val versionCode: Long,
    val signers: Set<String>,
)

/** The package manager, behind an interface so the decisions run without Android. */
interface Packages {
    /** Installed in this user, or null. */
    fun installed(pkg: String): InstalledPackage?

    /** Every package installed in this user - for the report of what is not managed. */
    fun all(): List<InstalledPackage>

    /** Parses an APK file; null when it is not one. */
    fun archive(path: String): ArchiveInfo?
}
