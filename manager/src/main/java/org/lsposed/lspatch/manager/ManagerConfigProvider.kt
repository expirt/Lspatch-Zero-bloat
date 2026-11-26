package org.lsposed.lspatch.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.lsposed.lspatch.config.AppFeaturesStore

/**
 * Lightweight exported ContentProvider allowing Local-mode (dynamic) patched apps to fetch
 * their latest [org.lsposed.lspatch.share.FeatureConfig] at startup. Each caller can only read
 * the config belonging to its own calling UID/package.
 */
class ManagerConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        if (method == "getAppFeatures") {
            val uid = Binder.getCallingUid()
            val pkgs = ctx.packageManager.getPackagesForUid(uid)
            val targetPkg = if (arg != null && pkgs?.contains(arg) == true) arg else pkgs?.firstOrNull()
            if (targetPkg != null) {
                val json = AppFeaturesStore.getJson(targetPkg)
                if (json != null) {
                    return Bundle().apply { putString("featuresJson", json) }
                }
            }
        }
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
