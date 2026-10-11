package org.andashi.updater.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import kotlinx.serialization.encodeToString
import org.andashi.updater.UpdaterApp
import org.andashi.updater.store.Store

/**
 * Phone → host, read-only (design §9.2):
 *
 *     content query --uri content://org.andashi.updater.state/state
 *     content query --uri content://org.andashi.updater.state/diagnostics
 *
 * One row, one column `json`. `diagnostics` answers "did you load exactly
 * my config" (the sha256 of the last ingest and success or the error);
 * the host compares it before it believes anything in `state`.
 */
class StateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("state is restricted to the shell (caller uid $uid)")
        }
        val context = context ?: throw IllegalStateException("Provider has no context")
        val store = UpdaterApp.store(context)
        val json = when (uri.pathSegments) {
            listOf("state") -> Store.json.encodeToString(store.state())
            listOf("diagnostics") -> Store.json.encodeToString(store.diagnostics())
            else -> throw IllegalArgumentException("Unknown state path: $uri")
        }
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(json)) }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("read-only")
}
