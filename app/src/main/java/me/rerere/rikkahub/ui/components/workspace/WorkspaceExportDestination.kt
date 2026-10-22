package me.rerere.rikkahub.ui.components.workspace

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Device-local preference: a restored folder URI never substitutes for an actual OS grant. */
internal class WorkspaceExportDestination(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences("workspace_downloads", Context.MODE_PRIVATE)

    fun observe(): Flow<Uri?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "directory" || key == null) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.map { current() }.flowOn(Dispatchers.IO)

    fun current(): Uri? {
        val uri = preferences.getString("directory", null)?.let(Uri::parse) ?: return null
        if (DocumentsContract.isTreeUri(uri) && resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }) return uri
        forget(uri)
        return null
    }

    fun remember(uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "请选择文件夹" }
        resolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        check(resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }) { "无法保留文件夹权限" }
        preferences.edit().putString("directory", uri.toString()).apply()
    }

    fun forget(uri: Uri) {
        if (preferences.getString("directory", null) == uri.toString()) {
            preferences.edit().remove("directory").apply()
        }
        // Another feature (such as backups) may share this grant. Never revoke it here.
    }
}
