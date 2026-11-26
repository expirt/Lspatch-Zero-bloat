package org.lsposed.lspatch.manager

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.util.ShizukuApi

data class BackupEntry(
    val documentUri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class ExternalBackupTarget(
    val label: String,
    val path: String,
    val isCustomRule: Boolean = false,
)

data class BackupScopes(
    val internalDirs: List<String>,
    val externalTargets: List<ExternalBackupTarget>,
)

/**
 * Manages root-free per-app backups and restores using [LSPatchBackupProvider] for internal
 * `/data/data/<pkg>` storage and direct filesystem access for Storage Redirection containers.
 *
 * Backups are stored as named `.zip` archives inside per-package subfolders of the user's
 * one-time selected SAF backup directory ([LSPSettings.backupDirUri]).
 */
object BackupManager {

    private fun authorityFor(packageName: String) =
        Uri.parse("content://$packageName${Constants.BACKUP_PROVIDER_AUTHORITY_SUFFIX}")

    fun hasBackupDir(context: Context = lspApp): Boolean {
        val raw = LSPSettings.backupDirUri.value
        if (raw.isBlank()) return false
        val uri = Uri.parse(raw)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    fun defaultBackupName(): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "backup_${fmt.format(Date())}"
    }

    private fun ensurePackageDir(context: Context, treeUri: Uri, packageName: String): Uri {
        val cr = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)

        cr.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name = cursor.getString(1)
                val mime = cursor.getString(2)
                if (name == packageName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
            }
        }

        return DocumentsContract.createDocument(
            cr,
            rootDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            packageName,
        ) ?: throw IllegalStateException("Failed to create backup folder for $packageName")
    }

    suspend fun listBackups(packageName: String): List<BackupEntry> = withContext(Dispatchers.IO) {
        val raw = LSPSettings.backupDirUri.value
        if (raw.isBlank()) return@withContext emptyList()
        val treeUri = Uri.parse(raw)
        val cr = lspApp.contentResolver
        runCatching {
            val pkgDirUri = findPackageDir(lspApp, treeUri, packageName) ?: return@withContext emptyList()
            val pkgDocId = DocumentsContract.getDocumentId(pkgDirUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pkgDocId)
            val list = mutableListOf<BackupEntry>()
            cr.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val size = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                    val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val mime = cursor.getString(4)
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR && name.endsWith(".zip", ignoreCase = true)) {
                        list += BackupEntry(
                            documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name = name,
                            sizeBytes = size,
                            lastModified = modified,
                        )
                    }
                }
            }
            list.sortedByDescending { it.lastModified }
        }.getOrDefault(emptyList())
    }

    private fun findPackageDir(context: Context, treeUri: Uri, packageName: String): Uri? {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name = cursor.getString(1)
                val mime = cursor.getString(2)
                if (name == packageName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
            }
        }
        return null
    }

    suspend fun listBackupScopes(
        packageName: String,
        containerPath: String? = null,
        customRules: List<String> = emptyList(),
    ): BackupScopes = withContext(Dispatchers.IO) {
        val internal = runCatching {
            val res = lspApp.contentResolver.call(authorityFor(packageName), "listInternalDirs", null, null)
            res?.getStringArrayList("dirs")?.toList()
        }.getOrNull() ?: listOf("files", "shared_prefs", "databases")

        val external = mutableListOf<ExternalBackupTarget>()
        val extData = File("/storage/emulated/0/Android/data/$packageName")
        if (extData.exists() && extData.isDirectory) {
            external += ExternalBackupTarget(
                label = "External: Android/data",
                path = extData.absolutePath,
                isCustomRule = false,
            )
        }
        val extObb = File("/storage/emulated/0/Android/obb/$packageName")
        if (extObb.exists() && extObb.isDirectory) {
            external += ExternalBackupTarget(
                label = "External: Android/obb",
                path = extObb.absolutePath,
                isCustomRule = false,
            )
        }
        val containerDir = containerPath?.let { File(it) }?.takeIf { it.exists() && it.isDirectory }
        if (containerDir != null) {
            containerDir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (child.isDirectory) {
                    external += ExternalBackupTarget(
                        label = "Redirected: ${child.name}",
                        path = child.absolutePath,
                        isCustomRule = false,
                    )
                }
            }
        }
        for (rule in customRules) {
            val eq = rule.indexOf('=')
            if (eq > 0 && eq < rule.length - 1) {
                val real = rule.substring(eq + 1).trim()
                    .replace("{pkg}", packageName)
                    .replace("{container}", containerPath ?: "/storage/emulated/0/Containers/$packageName")
                val f = File(real)
                if (f.exists()) {
                    external += ExternalBackupTarget(
                        label = "Rule: ${f.name} (${rule.substring(0, eq).trim()})",
                        path = f.absolutePath,
                        isCustomRule = true,
                    )
                }
            }
        }
        BackupScopes(internalDirs = internal, externalTargets = external)
    }

    suspend fun backupApp(
        packageName: String,
        backupName: String,
        containerPath: String? = null,
        selectedInternalDirs: List<String>? = null,
        selectedExternalPaths: List<String>? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = LSPSettings.backupDirUri.value
            require(raw.isNotBlank()) { "Please select a default backup folder first" }
            val treeUri = Uri.parse(raw)
            val cr = lspApp.contentResolver

            val cleanName = backupName.trim().ifBlank { defaultBackupName() }
            val fileName = if (cleanName.endsWith(".zip", ignoreCase = true)) cleanName else "$cleanName.zip"

            val internalZip = File(lspApp.cacheDir, "bkp_internal_${System.currentTimeMillis()}.zip")
            val mergedZip = File(lspApp.cacheDir, "bkp_merged_${System.currentTimeMillis()}.zip")

            try {
                var totalCount = 0
                val wantInternal = selectedInternalDirs == null || selectedInternalDirs.isNotEmpty()
                if (wantInternal) {
                    val pfd = ParcelFileDescriptor.open(
                        internalZip,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
                    )
                    val extras = Bundle().apply {
                        putParcelable("pfd", pfd)
                        if (!selectedInternalDirs.isNullOrEmpty()) {
                            putStringArrayList("includeDirs", ArrayList(selectedInternalDirs))
                        }
                    }
                    val res = pfd.use {
                        cr.call(authorityFor(packageName), "backup", null, extras)
                    } ?: throw IllegalStateException("App did not respond. Make sure it was patched with this LSPatch version.")

                    if (!res.getBoolean("ok", false)) {
                        throw IllegalStateException(res.getString("error") ?: "Backup failed inside app")
                    }
                    totalCount = res.getInt("fileCount", 0)
                }

                val extTargets: List<File> = when {
                    selectedExternalPaths != null -> selectedExternalPaths.map { File(it) }.filter { it.exists() }
                    else -> {
                        val c = containerPath?.let { File(it) }?.takeIf { it.exists() && it.isDirectory }
                        if (c != null) listOf(c) else emptyList()
                    }
                }

                val finalFile = if (extTargets.isNotEmpty() || !wantInternal) {
                    val buf = ByteArray(16384)
                    ZipOutputStream(FileOutputStream(mergedZip)).use { zos ->
                        if (wantInternal && internalZip.exists()) {
                            ZipInputStream(FileInputStream(internalZip)).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    zos.putNextEntry(ZipEntry(entry.name))
                                    if (!entry.isDirectory) {
                                        var len: Int
                                        while (zis.read(buf).also { len = it } > 0) {
                                            zos.write(buf, 0, len)
                                        }
                                    }
                                    zos.closeEntry()
                                    entry = zis.nextEntry
                                }
                            }
                        }
                        for (ext in extTargets) {
                            val prefix = if (containerPath != null && ext.absolutePath == containerPath) {
                                "container"
                            } else {
                                "container/${ext.name}"
                            }
                            if (ext.isDirectory) {
                                totalCount += zipDirectory(ext, prefix, zos, buf)
                            } else if (ext.isFile) {
                                zos.putNextEntry(ZipEntry(prefix))
                                FileInputStream(ext).use { fis ->
                                    var len: Int
                                    while (fis.read(buf).also { len = it } > 0) zos.write(buf, 0, len)
                                }
                                zos.closeEntry()
                                totalCount++
                            }
                        }
                    }
                    mergedZip
                } else {
                    internalZip
                }

                val pkgDirUri = ensurePackageDir(lspApp, treeUri, packageName)
                val destUri = DocumentsContract.createDocument(cr, pkgDirUri, "application/zip", fileName)
                    ?: throw IllegalStateException("Could not create $fileName in backup folder")

                cr.openOutputStream(destUri)?.use { out ->
                    FileInputStream(finalFile).use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IllegalStateException("Could not write to $fileName")

                totalCount
            } finally {
                internalZip.delete()
                mergedZip.delete()
            }
        }
    }

    suspend fun restoreApp(
        packageName: String,
        backupUri: Uri,
        containerPath: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = lspApp.contentResolver
            val tempZip = File(lspApp.cacheDir, "rst_${System.currentTimeMillis()}.zip")
            try {
                cr.openInputStream(backupUri)?.use { input ->
                    FileOutputStream(tempZip).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("Cannot read backup archive")

                if (ShizukuApi.isPermissionGranted) {
                    runCatching { ShizukuApi.runShellCommand("am force-stop $packageName") }
                }

                var containerCount = 0
                if (!containerPath.isNullOrBlank()) {
                    val containerDir = File(containerPath)
                    containerDir.mkdirs()
                    val buf = ByteArray(16384)
                    ZipInputStream(FileInputStream(tempZip)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name.startsWith("container/")) {
                                val rel = entry.name.removePrefix("container/")
                                if (rel.isNotEmpty()) {
                                    val target = File(containerDir, rel)
                                    if (target.canonicalPath.startsWith(containerDir.canonicalPath)) {
                                        if (entry.isDirectory) {
                                            target.mkdirs()
                                        } else {
                                            target.parentFile?.mkdirs()
                                            FileOutputStream(target).use { out ->
                                                var len: Int
                                                while (zis.read(buf).also { len = it } > 0) {
                                                    out.write(buf, 0, len)
                                                }
                                            }
                                            containerCount++
                                        }
                                    }
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }

                val pfd = ParcelFileDescriptor.open(tempZip, ParcelFileDescriptor.MODE_READ_ONLY)
                val extras = Bundle().apply { putParcelable("pfd", pfd) }
                val res = pfd.use {
                    cr.call(authorityFor(packageName), "restore", null, extras)
                } ?: throw IllegalStateException("App did not respond. Re-patch with this LSPatch version to enable restore.")

                if (!res.getBoolean("ok", false)) {
                    throw IllegalStateException(res.getString("error") ?: "Restore failed inside app")
                }

                if (ShizukuApi.isPermissionGranted) {
                    runCatching { ShizukuApi.runShellCommand("am force-stop $packageName") }
                }

                val total = res.getInt("fileCount", 0) + containerCount
                Log.i("LSPatch-BackupMgr", "Restored $packageName: $total files")
                total
            } finally {
                tempZip.delete()
            }
        }
    }

    suspend fun renameBackup(entry: BackupEntry, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = newName.trim()
            require(clean.isNotEmpty()) { "Name cannot be empty" }
            val finalName = if (clean.endsWith(".zip", ignoreCase = true)) clean else "$clean.zip"
            DocumentsContract.renameDocument(lspApp.contentResolver, entry.documentUri, finalName)
            Unit
        }
    }

    suspend fun deleteBackup(entry: BackupEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.deleteDocument(lspApp.contentResolver, entry.documentUri)
            Unit
        }
    }

    suspend fun importBackupZip(packageName: String, sourceUri: Uri, backupName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = LSPSettings.backupDirUri.value
                require(raw.isNotBlank()) { "Please select a default backup folder first" }
                val treeUri = Uri.parse(raw)
                val cr = lspApp.contentResolver
                val clean = backupName.trim().ifBlank { defaultBackupName() }
                val fileName = if (clean.endsWith(".zip", ignoreCase = true)) clean else "$clean.zip"
                val pkgDirUri = ensurePackageDir(lspApp, treeUri, packageName)
                val destUri = DocumentsContract.createDocument(cr, pkgDirUri, "application/zip", fileName)
                    ?: throw IllegalStateException("Could not create $fileName")
                cr.openInputStream(sourceUri)?.use { input ->
                    cr.openOutputStream(destUri)?.use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("Could not read selected file")
                Unit
            }
        }

    private fun zipDirectory(dir: File, prefix: String, zos: ZipOutputStream, buf: ByteArray): Int {
        var count = 0
        val children = dir.listFiles() ?: return 0
        for (child in children) {
            val path = "$prefix/${child.name}"
            if (child.isDirectory) {
                count += zipDirectory(child, path, zos, buf)
            } else if (child.isFile) {
                zos.putNextEntry(ZipEntry(path))
                FileInputStream(child).use { fis ->
                    var len: Int
                    while (fis.read(buf).also { len = it } > 0) {
                        zos.write(buf, 0, len)
                    }
                }
                zos.closeEntry()
                count++
            }
        }
        return count
    }
}
