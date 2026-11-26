package org.lsposed.lspatch.ui.page

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import kotlinx.coroutines.launch
import org.lsposed.lspatch.R
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.ui.navigation.AppDetail
import org.lsposed.lspatch.ui.navigation.BuiltInFilePicker
import org.lsposed.lspatch.ui.navigation.FileSelection
import org.lsposed.lspatch.ui.navigation.NewPatch
import org.lsposed.lspatch.ui.page.manage.AppManageBody
import org.lsposed.lspatch.ui.page.manage.AppManageFab
import org.lsposed.lspatch.ui.page.manage.ModuleManageBody
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.viewmodel.manage.AppManageViewModel
import org.lsposed.lspatch.ui.viewmodel.manage.ModuleManageViewModel
import org.lsposed.lspatch.util.LSPPackageManager
import org.matrix.vector.ui.SearchField
import org.matrix.vector.ui.SnackbarTone
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.show

/**
 * Redesigned Manage Screen adhering to the LSPatch Zero-Bloat design system:
 * - Direct "Import APK" action using the built-in file picker
 * - Tactile segmented pill switcher between Patched Apps and Modules with live counts
 * - Clean search bar and smooth swipeable tab pager
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(navigator: Navigator) {
    val initialTab = remember { LSPSettings.manageTab.value }
    val pagerState = rememberPagerState(initialPage = initialTab.coerceIn(0, 1), pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    LaunchedEffect(pagerState.currentPage) {
        LSPSettings.setManageTab(pagerState.currentPage)
    }

    val appViewModel = viewModel<AppManageViewModel>()
    val moduleViewModel = viewModel<ModuleManageViewModel>()
    val patched = appViewModel.appList.size
    val modules = moduleViewModel.appList.size
    var query by remember { mutableStateOf("") }

    // Handle files imported via BuiltInFilePicker
    val pendingFiles by FileSelection.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingFiles) {
        val picked = FileSelection.consume("ManageScreen") ?: return@LaunchedEffect
        if (picked.isNotEmpty()) {
            scope.launch {
                LSPPackageManager.getAppInfoFromApkFiles(picked)
                    .onSuccess { infos ->
                        val primary = infos.first()
                        if (primary.isLSPatched) {
                            navigator.go(AppDetail(packageName = primary.app.packageName))
                        } else {
                            val token = PatchRequestStore.put(createPatchRequestForApks(infos))
                            navigator.go(NewPatch(token = token))
                        }
                    }
                    .onFailure {
                        snackbarHost.show(it.message ?: "Failed to import APK", SnackbarTone.Failure)
                    }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (pagerState.currentPage == 0) AppManageFab(navigator)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Contextual Top App Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.screen_manage),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Button(
                            onClick = {
                                navigator.go(
                                    BuiltInFilePicker(
                                        requestedBy = "ManageScreen",
                                        title = "Import APK",
                                        extensions = listOf("apk", "xapk", "apks", "apkm"),
                                        multiSelect = true,
                                    )
                                )
                            },
                            shape = RoundedCornerShape(9999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Import APK",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    // Segmented Pill Switcher: Apps vs Modules
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9999.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val tab0Selected = pagerState.currentPage == 0
                            val tab1Selected = pagerState.currentPage == 1

                            // Tab 0: Patched apps
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(if (tab0Selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                                    .clickable { scope.launch { pagerState.animateScrollToPage(0) } }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.apps),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (tab0Selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (tab0Selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "$patched",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tab0Selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // Tab 1: Modules
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9999.dp))
                                    .background(if (tab1Selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                                    .clickable { scope.launch { pagerState.animateScrollToPage(1) } }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.modules),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (tab1Selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (tab1Selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "$modules",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tab1Selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Search Field
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = stringResource(R.string.manage_search),
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> AppManageBody(navigator, query)
                    1 -> ModuleManageBody(query)
                }
            }
        }
    }
}
