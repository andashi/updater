package org.andashi.updater.core

/**
 * The gate between a downloaded file and an install session (design §7,
 * step 4). Four facts must agree with what the lock and the host said; the
 * first that does not is the reason, and the file is deleted by the caller.
 *
 * Nothing here reads the file: the caller hashes it while downloading and
 * parses it through [Packages.archive], so this stays a pure comparison
 * that a test can exercise with every mismatch.
 */
object Verify {
    sealed class Result {
        object Ok : Result()
        data class Refused(val reason: String) : Result()
    }

    fun downloaded(
        entry: LockEntry,
        pin: String,
        fileSha256: String,
        fileSize: Long,
        archive: ArchiveInfo?,
    ): Result {
        if (fileSize != entry.size) return Result.Refused("size $fileSize, lock says ${entry.size}")
        if (fileSha256 != entry.sha256) return Result.Refused("sha256 $fileSha256, lock says ${entry.sha256}")
        if (archive == null) return Result.Refused("not an APK")
        if (archive.pkg != entry.pkg) return Result.Refused("package ${archive.pkg}, lock says ${entry.pkg}")
        if (archive.versionCode != entry.versionCode) return Result.Refused("versionCode ${archive.versionCode}, lock says ${entry.versionCode}")
        if (archive.signers.size != 1) return Result.Refused("${archive.signers.size} signers, exactly one expected")
        val signer = archive.signers.single()
        if (signer != pin) return Result.Refused("signer $signer, pinned $pin")
        // The lock's signer was checked against the pin before the download
        // (Decide); checking the file against the lock too costs nothing and
        // keeps this function honest on its own.
        if (signer != entry.signer) return Result.Refused("signer $signer, lock says ${entry.signer}")
        return Result.Ok
    }

    /**
     * The last look before commit (design §7, step 5): the bytes written into
     * the session were re-hashed while writing, and the package manager was
     * asked again what is installed. Either can have changed since the
     * download: a swapped file, or another zone that got there first.
     */
    sealed class Commit {
        object Proceed : Commit()
        /** Exactly the lock's version and bytes are installed: another zone got there first. */
        data class AlreadyCurrent(val versionCode: Long) : Commit()
        /** Something newer than the lock is installed meanwhile: never downgraded, reported as ahead. */
        data class Ahead(val versionCode: Long) : Commit()
        data class Refused(val reason: String) : Commit()
    }

    fun beforeCommit(entry: LockEntry, sessionSha256: String, installedNow: InstalledPackage?): Commit {
        if (sessionSha256 != entry.sha256) return Commit.Refused("bytes written to the session hash to $sessionSha256, lock says ${entry.sha256}")
        if (installedNow == null) return Commit.Refused("package is no longer installed; the updater never first-installs")
        if (installedNow.versionCode > entry.versionCode) return Commit.Ahead(installedNow.versionCode)
        // The same version with the lock's bytes is current; with other bytes
        // (a rebuild) the session proceeds and replaces it.
        if (installedNow.versionCode == entry.versionCode && installedNow.apkSha256 == entry.sha256) return Commit.AlreadyCurrent(installedNow.versionCode)
        return Commit.Proceed
    }
}
