package org.andashi.updater.probe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * Read-only: `content query --uri content://org.andashi.updater.probe.state/runs`
 * prints one row per recorded run, the JSON line as its single column. The
 * shell holds WRITE_SECURE_SETTINGS (the manifest's gate); the uid check is
 * the second lock, as in the launcher's state provider.
 */
class ProbeStateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("probe state is restricted to the shell (caller uid $uid)")
        }
        if (uri.pathSegments != listOf("runs")) throw IllegalArgumentException("Unknown path: $uri")
        val context = context ?: throw IllegalStateException("Provider has no context")
        return MatrixCursor(arrayOf("run")).apply {
            ProbeStore.readAll(context).forEach { addRow(arrayOf(it)) }
        }
    }

    override fun getType(uri: Uri): String = "application/x-ndjson"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")
}
