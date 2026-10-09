package org.andashi.updater.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import org.andashi.updater.UpdaterApp
import org.andashi.updater.Debug
import org.andashi.updater.core.Config
import org.andashi.updater.core.ConfigFormatException
import org.andashi.updater.core.Hex
import org.andashi.updater.core.Lock
import org.andashi.updater.core.LockFormatException
import org.andashi.updater.store.Store
import org.andashi.updater.work.Schedule
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Instant

/**
 * Host → phone, write-only (design §9.1), the launcher's pattern:
 *
 *     content write --uri content://org.andashi.updater.ingest/updater.json < updater.json
 *     content write --uri content://org.andashi.updater.ingest/lock.json    < lock.json   (host-only mode)
 *
 * The manifest requires WRITE_SECURE_SETTINGS of the writer; this class
 * also refuses every uid but shell and root. The upload lands in a temp
 * file and is parsed before it replaces anything: a config that does not
 * validate leaves the previous one in force and says why in diagnostics.
 * An accepted config schedules a check at once.
 */
class IngestProvider : ContentProvider() {
    private val closeHandler: Handler by lazy { Handler(HandlerThread("ingest").apply { start() }.looper) }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("ingest is restricted to the shell (caller uid $uid)")
        }
        if (!mode.startsWith("w")) throw SecurityException("ingest is write-only (mode '$mode')")
        val context = context ?: throw IllegalStateException("Provider has no context")
        val kind = when (uri.pathSegments) {
            listOf(CONFIG) -> CONFIG
            listOf(LOCK) -> LOCK
            else -> throw FileNotFoundException("Unknown ingest path: $uri")
        }
        val tmp = File.createTempFile("ingest-", ".tmp", context.cacheDir)
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        // The writer closes its end when the upload is complete; only then is
        // the file whole. The close listener runs off the binder thread.
        return ParcelFileDescriptor.wrap(pfd, closeHandler) { accept(kind, tmp) }
    }

    private fun accept(kind: String, tmp: File) {
        val context = context ?: return
        val store = UpdaterApp.store(context)
        try {
            if (tmp.length() > MAX_BYTES) throw ConfigFormatException("upload of ${tmp.length()} bytes exceeds $MAX_BYTES")
            val text = tmp.readText()
            val sha = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
            when (kind) {
                CONFIG -> {
                    val config = Config.parse(text, Debug.cleartextLocalhost(context))   // throws with every problem named
                    store.writeConfigText(text)
                    store.update { it.copy(config = Store.ConfigState(sha, Instant.now().toString(), config.zone)) }
                    store.writeDiagnostics(Store.Diagnostics(sha, true, null, Instant.now().toString()))
                    store.record("-", "config-accepted", detail = "zone ${config.zone}, ${config.managed.size} managed, mode ${config.lock.mode}")
                    Schedule.apply(context, config)
                    Schedule.checkNow(context, "config")
                }
                LOCK -> {
                    val config = store.configText()?.let { Config.parse(it, Debug.cleartextLocalhost(context)) }
                        ?: throw LockFormatException("no config yet; a host-pushed lock needs the config's pins and hosts first")
                    val previous = store.hostLockText()?.let { runCatching { Lock.parse(it, config.lockPolicy(Debug.cleartextLocalhost(context))) }.getOrNull() }
                    Lock.parse(text, config.lockPolicy(Debug.cleartextLocalhost(context)), previous)
                    store.writeHostLockText(text)
                    store.writeDiagnostics(Store.Diagnostics(sha, true, null, Instant.now().toString()))
                    store.record("-", "host-lock-accepted", lockSha = sha)
                    Schedule.checkNow(context, "host-lock")
                }
            }
        } catch (e: Exception) {
            val why = "${e.javaClass.simpleName}: ${e.message}"
            store.writeDiagnostics(Store.Diagnostics(null, false, why, Instant.now().toString()))
            store.record("-", "$kind-refused", detail = why)
        } finally {
            tmp.delete()
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val CONFIG = "updater.json"
        const val LOCK = "lock.json"
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
