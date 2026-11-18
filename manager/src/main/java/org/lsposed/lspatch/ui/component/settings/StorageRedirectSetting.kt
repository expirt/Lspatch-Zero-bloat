package org.lsposed.lspatch.ui.component.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsposed.lspatch.R
import org.lsposed.lspatch.ui.component.SheetBody
import org.matrix.vector.ui.ToggleRow
import org.matrix.vector.ui.sheetRowColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageRedirectSetting(
    enabled: Boolean,
    containerPath: String?,
    redirectInternal: Boolean,
    redirectExternal: Boolean,
    redirectDatabases: Boolean,
    redirectPreferences: Boolean,
    redirectConstructors: Boolean,
    redirectLogging: Boolean,
    needsPermission: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onContainerPathChange: (String?) -> Unit,
    onToggleInternal: (Boolean) -> Unit,
    onToggleExternal: (Boolean) -> Unit,
    onToggleDatabases: (Boolean) -> Unit,
    onTogglePreferences: (Boolean) -> Unit,
    onToggleConstructors: (Boolean) -> Unit,
    onToggleLogging: (Boolean) -> Unit,
    onAddPermission: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    val displayContainer = containerPath?.ifBlank { null } ?: "Containers/{pkg}"

    ListItem(
        modifier = Modifier.clickable { showSheet = true },
        leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
        supportingContent = {
            Text(
                if (enabled) stringResource(R.string.patch_storage_redirect_enabled, displayContainer)
                else stringResource(R.string.patch_storage_redirect_disabled)
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = sheetRowColors,
    ) { Text(stringResource(R.string.patch_storage_redirect)) }

    if (showSheet) {
        StorageRedirectSheet(
            enabled = enabled,
            containerPath = containerPath,
            redirectInternal = redirectInternal,
            redirectExternal = redirectExternal,
            redirectDatabases = redirectDatabases,
            redirectPreferences = redirectPreferences,
            redirectConstructors = redirectConstructors,
            redirectLogging = redirectLogging,
            needsPermission = needsPermission,
            onToggleEnabled = onToggleEnabled,
            onContainerPathChange = onContainerPathChange,
            onToggleInternal = onToggleInternal,
            onToggleExternal = onToggleExternal,
            onToggleDatabases = onToggleDatabases,
            onTogglePreferences = onTogglePreferences,
            onToggleConstructors = onToggleConstructors,
            onToggleLogging = onToggleLogging,
            onAddPermission = onAddPermission,
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageRedirectSheet(
    enabled: Boolean,
    containerPath: String?,
    redirectInternal: Boolean,
    redirectExternal: Boolean,
    redirectDatabases: Boolean,
    redirectPreferences: Boolean,
    redirectConstructors: Boolean,
    redirectLogging: Boolean,
    needsPermission: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onContainerPathChange: (String?) -> Unit,
    onToggleInternal: (Boolean) -> Unit,
    onToggleExternal: (Boolean) -> Unit,
    onToggleDatabases: (Boolean) -> Unit,
    onTogglePreferences: (Boolean) -> Unit,
    onToggleConstructors: (Boolean) -> Unit,
    onToggleLogging: (Boolean) -> Unit,
    onAddPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var pathText by rememberSaveable { mutableStateOf(containerPath ?: "Containers/{pkg}") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        SheetBody {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.patch_storage_redirect),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.patch_storage_redirect_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )

                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChoiceCard(
                        selected = enabled,
                        icon = Icons.Rounded.FolderOpen,
                        title = stringResource(R.string.patch_storage_enable_card),
                        subtitle = stringResource(R.string.patch_storage_enable_card_desc),
                        role = Role.Checkbox,
                        onClick = { onToggleEnabled(!enabled) },
                    )
                }

                AnimatedVisibility(visible = enabled) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        OutlinedTextField(
                            value = pathText,
                            onValueChange = {
                                pathText = it
                                onContainerPathChange(it.trim().ifBlank { null })
                            },
                            label = { Text(stringResource(R.string.patch_storage_container_path)) },
                            placeholder = { Text(stringResource(R.string.patch_storage_container_path_hint)) },
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (needsPermission) {
                            Text(
                                text = stringResource(R.string.patch_storage_needs_permission),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onAddPermission() }
                                    .padding(vertical = 8.dp),
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.patch_storage_categories),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )

                        ToggleRow(
                            title = stringResource(R.string.patch_storage_cat_internal),
                            icon = Icons.Rounded.Smartphone,
                            checked = redirectInternal,
                            onCheckedChange = onToggleInternal,
                            subtitle = stringResource(R.string.patch_storage_cat_internal_desc),
                        )

                        ToggleRow(
                            title = stringResource(R.string.patch_storage_cat_external),
                            icon = Icons.Rounded.FolderOpen,
                            checked = redirectExternal,
                            onCheckedChange = onToggleExternal,
                            subtitle = stringResource(R.string.patch_storage_cat_external_desc),
                        )

                        ToggleRow(
                            title = stringResource(R.string.patch_storage_cat_databases),
                            icon = Icons.Rounded.Layers,
                            checked = redirectDatabases,
                            onCheckedChange = onToggleDatabases,
                            subtitle = stringResource(R.string.patch_storage_cat_databases_desc),
                        )

                        ToggleRow(
                            title = stringResource(R.string.patch_storage_cat_preferences),
                            icon = Icons.Rounded.Tune,
                            checked = redirectPreferences,
                            onCheckedChange = onTogglePreferences,
                            subtitle = stringResource(R.string.patch_storage_cat_preferences_desc),
                        )

                        ToggleRow(
                            title = stringResource(R.string.patch_storage_cat_constructors),
                            icon = Icons.Rounded.Code,
                            checked = redirectConstructors,
                            onCheckedChange = onToggleConstructors,
                            subtitle = stringResource(R.string.patch_storage_cat_constructors_desc),
                        )

                        ToggleRow(
                            title = stringResource(R.string.patch_storage_cat_logging),
                            icon = Icons.AutoMirrored.Rounded.Notes,
                            checked = redirectLogging,
                            onCheckedChange = onToggleLogging,
                            subtitle = stringResource(R.string.patch_storage_cat_logging_desc),
                        )
                    }
                }
            }
        }
    }
}
