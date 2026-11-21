package org.lsposed.lspatch.ui.component.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsposed.lspatch.R
import org.lsposed.lspatch.ui.component.SheetBody
import org.matrix.vector.ui.sheetRowColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbiStripperSetting(
    keepAbis: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }

    val currentDescription = when {
        keepAbis.isEmpty() -> stringResource(R.string.patch_abi_all)
        keepAbis.size == 1 && keepAbis[0] == "arm64-v8a" -> stringResource(R.string.patch_abi_arm64)
        keepAbis.size == 1 && keepAbis[0] == "armeabi-v7a" -> stringResource(R.string.patch_abi_arm32)
        keepAbis.size == 2 && keepAbis.containsAll(listOf("arm64-v8a", "armeabi-v7a")) ->
            stringResource(R.string.patch_abi_arm_both)
        else -> keepAbis.joinToString(", ")
    }

    ListItem(
        modifier = Modifier.clickable { showSheet = true },
        leadingContent = { Icon(Icons.Rounded.Memory, contentDescription = null) },
        supportingContent = { Text(currentDescription) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = sheetRowColors,
    ) { Text(stringResource(R.string.patch_abi_strip)) }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            SheetBody {
                Column(Modifier.padding(bottom = 16.dp)) {
                    Row(
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.patch_abi_strip),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.patch_abi_strip_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                    )

                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ChoiceCard(
                            selected = keepAbis.isEmpty(),
                            icon = Icons.Rounded.Layers,
                            title = stringResource(R.string.patch_abi_all),
                            subtitle = stringResource(R.string.patch_abi_all_desc),
                            onClick = {
                                onChange(emptyList())
                                showSheet = false
                            },
                        )
                        ChoiceCard(
                            selected = keepAbis.size == 1 && keepAbis[0] == "arm64-v8a",
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.patch_abi_arm64),
                            subtitle = stringResource(R.string.patch_abi_arm64_desc),
                            onClick = {
                                onChange(listOf("arm64-v8a"))
                                showSheet = false
                            },
                        )
                        ChoiceCard(
                            selected = keepAbis.size == 2 && keepAbis.containsAll(listOf("arm64-v8a", "armeabi-v7a")),
                            icon = Icons.Rounded.Smartphone,
                            title = stringResource(R.string.patch_abi_arm_both),
                            subtitle = stringResource(R.string.patch_abi_arm_both_desc),
                            onClick = {
                                onChange(listOf("arm64-v8a", "armeabi-v7a"))
                                showSheet = false
                            },
                        )
                        ChoiceCard(
                            selected = keepAbis.size == 1 && keepAbis[0] == "armeabi-v7a",
                            icon = Icons.Rounded.Memory,
                            title = stringResource(R.string.patch_abi_arm32),
                            subtitle = stringResource(R.string.patch_abi_arm32_desc),
                            onClick = {
                                onChange(listOf("armeabi-v7a"))
                                showSheet = false
                            },
                        )
                    }
                }
            }
        }
    }
}


