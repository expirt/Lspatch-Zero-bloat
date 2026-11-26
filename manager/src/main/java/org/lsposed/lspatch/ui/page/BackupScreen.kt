package org.lsposed.lspatch.ui.page

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.mode
import org.lsposed.lspatch.data.repository.PatchInputs
import org.lsposed.lspatch.manager.BackupEntry
import org.lsposed.lspatch.manager.BackupManager
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.ui.component.rememberAppIcon
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.util.LSPPackageManager
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.SnackbarTone
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.show

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    val backupDirUri by LSPSettings.backupDirUri.collectAsState()
    val hasFolder = remember(backupDirUri) { BackupManager.hasBackupDir(context) }

    var loadingApps by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<LSPPackageManager.AppInfo?>(null) }

    LaunchedEffect(Unit) {
        if (LSPPackageManager.appList.isEmpty()) {
            loadingApps = true
            LSPPackageManager.fetchAppList()
            loadingApps = false
        }
    }

    val patchedApps = remember(LSPPackageManager.appList) {
        LSPPackageManager.appList.filter { it.isLSPatched }
    }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                    LSPSettings.setBackupDirUri(uri.toString())
                    scope.launch { snackbarHost.show("Backup folder saved", SnackbarTone.Success) }
                }.onFailure { e ->
                    scope.launch { snackbarHost.show(e.message ?: "Permission error", SnackbarTone.Failure) }
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                actions = {
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Select backup folder")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize()
        ) {
            if (!hasFolder) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Select Default Backup Folder",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "Choose a folder once as the default destination for all app backups. Multiple named backups per app are stored inside.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        )
                        Button(onClick = { folderPicker.launch(null) }) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose Folder")
                        }
                    }
                }
            }

            when {
                loadingApps -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                patchedApps.isEmpty() -> {
                    PanelEmptyState(
                        icon = Icons.Rounded.Backup,
                        text = "No patched apps installed yet",
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(patchedApps, key = { it.app.packageName }) { app ->
                            BackupAppRow(
                                app = app,
                                onClick = {
                                    if (!hasFolder) {
                                        folderPicker.launch(null)
                                    } else {
                                        selectedApp = app
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    selectedApp?.let { app ->
        AppBackupsBottomSheet(
            app = app,
            onDismiss = { selectedApp = null },
        )
    }
}

@Composable
private fun BackupAppRow(
    app: LSPPackageManager.AppInfo,
    onClick: () -> Unit,
) {
    val icon = rememberAppIcon(app)
    val config = remember(app.app.packageName) { PatchInputs.configOf(app) }
    val modeLabel = if (config?.mode == PatchMode.Integrated) "Integrated" else "Dynamic (Local)"
    val redirectLabel = if (config?.storageRedirectEnabled == true) " • Redirected" else ""

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$modeLabel$redirectLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(Icons.Rounded.Restore, contentDescription = "Manage backups", tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBackupsBottomSheet(
    app: LSPPackageManager.AppInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val pkg = app.app.packageName
    val config = remember(pkg) { PatchInputs.configOf(app) }
    val containerPath =
        if (config?.storageRedirectEnabled == true) {
            config.containerPath?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0/Containers/$pkg"
        } else null

    var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BackupEntry?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch { backups = BackupManager.listBackups(pkg) }
    }

    LaunchedEffect(pkg) { refresh() }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    statusMsg = null
                    BackupManager.importBackupZip(pkg, uri, BackupManager.defaultBackupName())
                        .onSuccess {
                            refresh()
                            statusError = false
                            statusMsg = "Backup imported"
                        }
                        .onFailure {
                            statusError = true
                            statusMsg = it.message ?: "Import failed"
                        }
                    busy = false
                }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${app.label} Backups",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    if (config?.mode == PatchMode.Integrated) {
                        "Integrated app: create/export backups or embed a .zip during patching."
                    } else {
                        "Dynamic app: create, rename, and restore internal + redirected data anytime."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { showCreateDialog = true },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Backup Now")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import .zip")
                }
            }

            statusMsg?.let { msg ->
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (statusError) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (statusError) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Working...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider()

            if (backups.isEmpty()) {
                Text(
                    text = "No backups found for this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    backups.forEach { entry ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.name.removeSuffix(".zip"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${Formatter.formatShortFileSize(context, entry.sizeBytes)} • ${dateFmt.format(Date(entry.lastModified))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        statusMsg = null
                                        BackupManager.restoreApp(pkg, entry.documentUri, containerPath)
                                            .onSuccess { count ->
                                                statusError = false
                                                statusMsg = "Restored $count files"
                                            }
                                            .onFailure {
                                                statusError = true
                                                statusMsg = it.message ?: "Restore failed"
                                            }
                                        busy = false
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = "Restore")
                            }
                            IconButton(
                                enabled = !busy,
                                onClick = { renameTarget = entry },
                            ) {
                                Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = "Rename")
                            }
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, entry.documentUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(send, "Export Backup"))
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Export")
                            }
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        BackupManager.deleteBackup(entry)
                                        refresh()
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf(BackupManager.defaultBackupName()) }
        var scopes by remember { mutableStateOf<org.lsposed.lspatch.manager.BackupScopes?>(null) }
        var selectedInternal by remember { mutableStateOf(setOf<String>()) }
        var selectedExternal by remember { mutableStateOf(setOf<String>()) }

        LaunchedEffect(pkg) {
            val rules: List<String> = config?.customRules?.mapNotNull { it?.toString() } ?: emptyList()
            val loaded = BackupManager.listBackupScopes(pkg, containerPath, rules)
            scopes = loaded
            selectedInternal = loaded.internalDirs.toSet()
            selectedExternal = loaded.externalTargets.map { it.path }.toSet()
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Backup") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Backup name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val s = scopes
                    if (s == null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Scanning folders...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        if (s.internalDirs.isNotEmpty()) {
                            Text(
                                text = "Internal Storage",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            s.internalDirs.forEach { dir ->
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth().clickable {
                                            selectedInternal =
                                                if (dir in selectedInternal) selectedInternal - dir
                                                else selectedInternal + dir
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = dir in selectedInternal,
                                        onCheckedChange = { checked ->
                                            selectedInternal =
                                                if (checked) selectedInternal + dir
                                                else selectedInternal - dir
                                        },
                                    )
                                    Text(dir, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (s.externalTargets.isNotEmpty()) {
                            Text(
                                text = "External / Redirected / Custom Rules",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            s.externalTargets.forEach { target ->
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth().clickable {
                                            selectedExternal =
                                                if (target.path in selectedExternal) selectedExternal - target.path
                                                else selectedExternal + target.path
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = target.path in selectedExternal,
                                        onCheckedChange = { checked ->
                                            selectedExternal =
                                                if (checked) selectedExternal + target.path
                                                else selectedExternal - target.path
                                        },
                                    )
                                    Text(target.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = scopes != null && (selectedInternal.isNotEmpty() || selectedExternal.isNotEmpty()),
                    onClick = {
                        showCreateDialog = false
                        scope.launch {
                            busy = true
                            statusMsg = null
                            BackupManager.backupApp(
                                packageName = pkg,
                                backupName = name,
                                containerPath = containerPath,
                                selectedInternalDirs = selectedInternal.toList(),
                                selectedExternalPaths = selectedExternal.toList(),
                            )
                                .onSuccess { count ->
                                    refresh()
                                    statusError = false
                                    statusMsg = "Backed up $count files"
                                }
                                .onFailure {
                                    statusError = true
                                    statusMsg = it.message ?: "Backup failed"
                                }
                            busy = false
                        }
                    },
                ) { Text("Backup") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { target ->
        var newName by remember { mutableStateOf(target.name.removeSuffix(".zip")) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Backup") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val entry = target
                        renameTarget = null
                        scope.launch {
                            BackupManager.renameBackup(entry, newName)
                                .onSuccess { refresh() }
                                .onFailure { snackbarHost.show(it.message ?: "Rename failed", SnackbarTone.Failure) }
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}
