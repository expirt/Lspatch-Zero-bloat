package org.lsposed.lspatch.ui.page

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.lsposed.lspatch.ui.navigation.FileSelection
import org.matrix.vector.ui.navigation.Navigator

private enum class FileSortBy {
    NAME,
    DATE,
    SIZE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltInFilePickerScreen(
    navigator: Navigator,
    requestedBy: String,
    title: String = "Select file",
    extensions: List<String> = listOf("apk", "xapk", "apks", "apkm"),
    multiSelect: Boolean = false,
    initialPath: String? = null,
) {
    val defaultRoot = remember {
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apks = File(download, "APKs")
        when {
            initialPath != null && File(initialPath).exists() -> {
                val f = File(initialPath)
                if (f.isDirectory) f else f.parentFile ?: Environment.getExternalStorageDirectory()
            }
            apks.exists() && apks.isDirectory -> apks
            download.exists() && download.isDirectory -> download
            else -> Environment.getExternalStorageDirectory()
        }
    }

    var currentDir by remember { mutableStateOf(defaultRoot) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }
    var sortBy by remember { mutableStateOf(FileSortBy.NAME) }
    var sortAsc by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    // Read and sort directories and files
    val directoryEntries by remember(currentDir, sortBy, sortAsc, extensions) {
        derivedStateOf {
            val list = runCatching { currentDir.listFiles() }.getOrNull() ?: emptyArray()
            val dirs = list.filter { it.isDirectory && !it.name.startsWith(".") }.toMutableList()
            val files = list.filter { file ->
                file.isFile && !file.name.startsWith(".") &&
                    (extensions.isEmpty() || extensions.any { ext -> file.name.endsWith(".$ext", ignoreCase = true) })
            }.toMutableList()

            when (sortBy) {
                FileSortBy.NAME -> {
                    dirs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    files.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    if (!sortAsc) {
                        dirs.reverse()
                        files.reverse()
                    }
                }
                FileSortBy.DATE -> {
                    dirs.sortBy { it.lastModified() }
                    files.sortBy { it.lastModified() }
                    if (!sortAsc) {
                        dirs.reverse()
                        files.reverse()
                    }
                }
                FileSortBy.SIZE -> {
                    files.sortBy { it.length() }
                    if (!sortAsc) {
                        files.reverse()
                    }
                }
            }
            Pair(dirs, files)
        }
    }

    val totalSelectedSize = remember(selectedFiles) {
        selectedFiles.sumOf { it.length() }
    }

    val externalStorageRoot = Environment.getExternalStorageDirectory().absolutePath
    val canGoUp = currentDir.parentFile != null &&
        currentDir.absolutePath != "/storage/emulated/0" &&
        currentDir.absolutePath != externalStorageRoot &&
        currentDir.absolutePath.startsWith("/storage/emulated/0")

    val onBackAction: () -> Unit = {
        if (canGoUp) {
            currentDir = currentDir.parentFile ?: Environment.getExternalStorageDirectory()
        } else {
            navigator.back()
        }
    }

    BackHandler(enabled = true) {
        onBackAction()
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onBackAction,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9999.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Internal",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        },
        bottomBar = {
            if (selectedFiles.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = "${selectedFiles.size} file${if (selectedFiles.size > 1) "s" else ""} selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = formatBytes(totalSelectedSize),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = {
                                    FileSelection.offer(requestedBy, selectedFiles.map { it.absolutePath })
                                    navigator.back()
                                },
                                shape = RoundedCornerShape(9999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "Use selected",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Quick Storage Shortcuts Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val storageRoot = Environment.getExternalStorageDirectory()
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val apksDir = File(downloadDir, "APKs")
                val lspatchDir = File(storageRoot, "LSPatch")

                ShortcutChip("APKs", currentDir.absolutePath == apksDir.absolutePath) {
                    if (apksDir.exists() && apksDir.isDirectory) currentDir = apksDir
                    else currentDir = downloadDir
                }
                ShortcutChip("Download", currentDir.absolutePath == downloadDir.absolutePath) {
                    if (downloadDir.exists()) currentDir = downloadDir
                }
                ShortcutChip("LSPatch", currentDir.absolutePath == lspatchDir.absolutePath) {
                    if (lspatchDir.exists() && lspatchDir.isDirectory) currentDir = lspatchDir
                    else {
                        lspatchDir.mkdirs()
                        currentDir = lspatchDir
                    }
                }
                ShortcutChip("Storage", currentDir.absolutePath == storageRoot.absolutePath) {
                    currentDir = storageRoot
                }
            }

            // Breadcrumb Path Bar & Sort Menu
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "Sort",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sort by Name") },
                            onClick = {
                                if (sortBy == FileSortBy.NAME) sortAsc = !sortAsc
                                else { sortBy = FileSortBy.NAME; sortAsc = true }
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sort by Date") },
                            onClick = {
                                if (sortBy == FileSortBy.DATE) sortAsc = !sortAsc
                                else { sortBy = FileSortBy.DATE; sortAsc = false }
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Sort by Size") },
                            onClick = {
                                if (sortBy == FileSortBy.SIZE) sortAsc = !sortAsc
                                else { sortBy = FileSortBy.SIZE; sortAsc = false }
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Main Directory and File Listing
            val (dirs, files) = directoryEntries

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                // Parent Folder entry
                val parent = currentDir.parentFile
                if (parent != null && currentDir.absolutePath != "/" && currentDir.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                    item(key = "parent_folder") {
                        FileItemCard(
                            icon = Icons.Rounded.Folder,
                            iconContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            title = ".. (Parent folder)",
                            subtitle = "Back to ${parent.name}",
                            isSelected = false,
                            trailingIcon = Icons.Rounded.ChevronRight,
                            onClick = { currentDir = parent }
                        )
                    }
                }

                // Directories
                items(dirs, key = { "dir_${it.absolutePath}" }) { dir ->
                    val itemCount = runCatching { dir.listFiles()?.size ?: 0 }.getOrDefault(0)
                    FileItemCard(
                        icon = Icons.Rounded.Folder,
                        iconContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = dir.name,
                        subtitle = "Folder · $itemCount item${if (itemCount != 1) "s" else ""}",
                        isSelected = false,
                        trailingIcon = Icons.Rounded.ChevronRight,
                        onClick = { currentDir = dir }
                    )
                }

                // Files
                items(files, key = { "file_${it.absolutePath}" }) { file ->
                    val isSelected = selectedFiles.contains(file)
                    val isApk = file.name.endsWith(".apk", ignoreCase = true) ||
                        file.name.endsWith(".xapk", ignoreCase = true) ||
                        file.name.endsWith(".apks", ignoreCase = true) ||
                        file.name.endsWith(".apkm", ignoreCase = true)

                    FileItemCard(
                        icon = if (isApk) Icons.Rounded.Android else Icons.Rounded.Description,
                        iconContainerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                        iconColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        title = file.name,
                        subtitle = "${formatBytes(file.length())} · ${dateFormat.format(Date(file.lastModified()))}",
                        isSelected = isSelected,
                        trailingIcon = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        trailingColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        cardContainerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else null,
                        onClick = {
                            if (multiSelect) {
                                selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                            } else {
                                selectedFiles = if (isSelected) emptySet() else setOf(file)
                            }
                        }
                    )
                }

                if (dirs.isEmpty() && files.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No files found in this directory",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9999.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileItemCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconContainerColor: androidx.compose.ui.graphics.Color,
    iconColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    trailingColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outline,
    cardContainerColor: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val container = cardContainerColor ?: MaterialTheme.colorScheme.surfaceContainerLowest
    val borderCol = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .border(1.dp, borderCol, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = trailingColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
