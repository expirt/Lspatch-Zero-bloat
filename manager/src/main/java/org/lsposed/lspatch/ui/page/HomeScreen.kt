package org.lsposed.lspatch.ui.page

import org.matrix.vector.ui.SheetAction
import androidx.compose.material.icons.rounded.Reorder
import org.lsposed.lspatch.ui.component.rememberAppIcons
import org.matrix.vector.ui.show
import org.matrix.vector.ui.SnackbarTone
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import org.matrix.vector.ui.SharedAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.lsposed.lspatch.ui.component.rememberAppIcon
import org.lsposed.lspatch.ui.theme.StatusSuccess
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.R
import org.lsposed.lspatch.config.Configs
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.manager.ModuleDeliveryReports
import org.lsposed.lspatch.share.Constants
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.ui.appearance.LSPAmbienceSettings
import org.lsposed.lspatch.ui.appearance.LSPSettings
import org.lsposed.lspatch.ui.component.ShizukuSheet
import org.lsposed.lspatch.ui.component.StayAliveSheet
import org.lsposed.lspatch.ui.component.hasNotificationPermission
import org.lsposed.lspatch.ui.component.isIgnoringBatteryOptimizations
import org.lsposed.lspatch.ui.navigation.AppDetail
import org.lsposed.lspatch.ui.navigation.NewPatch
import org.lsposed.lspatch.ui.navigation.TopLevelRoute
import org.lsposed.lspatch.ui.navigation.Update
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.viewmodel.HomeViewModel
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ManagerCloakFlow
import org.lsposed.lspatch.util.PackageNameValidator
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.lspatch.util.findActivity
import org.matrix.vector.ui.REACH_PREVIEW_LIMIT
import org.matrix.vector.ui.RepoStatsRow
import org.matrix.vector.ui.StatusHeader
import org.matrix.vector.ui.StatusTone
import org.matrix.vector.ui.ToggleRow
import org.matrix.vector.ui.UpdatableVersion
import org.matrix.vector.ui.ambience.AmbienceKind
import org.matrix.vector.ui.appearance.AppearanceSheet
import org.matrix.vector.ui.locale.LanguageSheet
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.theme.Mono
import org.matrix.vector.ui.R as UiR

/**
 * The dashboard, copying Vector's Home: a living, immersive status header carrying the app's identity and its running
 * state, the theme and language buttons, then the primary action and the environment it is running in. The differences
 * from Vector are its subject — the status is Shizuku's, not a daemon's, and its badge opens nothing; "Take part" is
 * the patch button; and the activity feed is replaced by the system properties, which are the substance of this page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigator: Navigator) {
    var isIntentLaunched by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Not `context as Activity`: under an in-app language override the context is a LocalizedContext
    // wrapper, so the cast throws the moment a language is chosen. Unwrap to the real activity.
    val activity = context.findActivity()
    val intent = activity?.intent
    LaunchedEffect(Unit) {
        if (
            !isIntentLaunched &&
                intent != null &&
                intent.action == Intent.ACTION_VIEW &&
                intent.hasCategory(Intent.CATEGORY_DEFAULT) &&
                intent.type == "application/vnd.android.package-archive"
        ) {
            isIntentLaunched = true
            intent.data?.let { uri ->
                // An apk opened from a file manager is a patch target like any other: it goes
                // through the same request the picker builds, rather than a third code path.
                lspApp.globalScope.launch {
                    LSPPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess { infos ->
                        val primary = infos.first()
                        val token =
                            PatchRequestStore.put(
                                PatchRequest(
                                    token = UUID.randomUUID().toString(),
                                    target =
                                        PatchTarget.ApkFiles(
                                            packageName = primary.app.packageName,
                                            label = primary.label,
                                            apkPaths =
                                                listOf(primary.app.sourceDir) +
                                                    (primary.app.splitSourceDirs ?: emptyArray()),
                                        ),
                                    mode = PatchMode.Local,
                                    origin = PatchOrigin.New,
                                )
                            )
                        withContext(Dispatchers.Main) {
                            navigator.go(NewPatch(token = token))
                        }
                    }
                }
            }
        }
    }

    // Once, on the first open, and only while there is something left to ask for: what LSPatch needs
    // to still be there when a patched app starts. Deciding it here rather than inside the sheet keeps
    // a sheet from opening and closing itself on a device that already granted both.
    var showStayAlive by remember {
        mutableStateOf(
            !Configs.askedStayAlive && !(hasNotificationPermission(context) && isIgnoringBatteryOptimizations(context))
        )
    }

    var showAppearance by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    val ambienceKey by LSPSettings.headerAmbience.collectAsStateWithLifecycle()
    val granted = ShizukuApi.isPermissionGranted
    // Hoisted above the header so its version line can carry the update mark, the way Vector's
    // framework version does.
    val homeVm = viewModel<HomeViewModel>()
    val update = homeVm.update

    val core = LSPConfig.instance
    val apps = LSPPackageManager.appList
    val moduleCount = apps.count { it.isModule }
    val patchedApps = apps.filter { it.app.metaData?.containsKey("lspatch") == true }
    val patchedCount = patchedApps.size

    val shizukuVersion = if (granted) ShizukuApi.serverVersion() else null
    val shizukuText = if (shizukuVersion != null) "v$shizukuVersion" else "Offline"

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Bottom)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Sticky Top App Bar
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Layers,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "LSPatch",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "zero",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }

                        IconButton(
                            onClick = { showAppearance = true },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.appearance_title),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Hero Shizuku & Daemon Status Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Header: Status badge & version
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9999.dp))
                                .background(
                                    if (granted) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.errorContainer
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (granted) StatusSuccess else MaterialTheme.colorScheme.error)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (granted) "Shizuku active" else "Shizuku limited",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }

                        Text(
                            text = "v${core.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                navigator.go(Update(prerelease = update == null))
                            }
                        )
                    }

                    // Headline & Subtitle
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (granted) "Ready to patch" else "Shizuku needed",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (granted)
                                "Wireless debugging bridge connected. Rootless Xposed injection is available."
                            else
                                "Grant Shizuku permission to manage and patch applications with system privileges.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Glanceable Metrics Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable {
                                    LSPSettings.setManageTab(0)
                                    navigator.switchTo(TopLevelRoute.Manage)
                                }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Patched apps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "$patchedCount",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable {
                                    LSPSettings.setManageTab(1)
                                    navigator.switchTo(TopLevelRoute.Manage)
                                }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Active modules",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "$moduleCount",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { startNewPatch(navigator) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Patch APK",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Button(
                            onClick = { navigator.switchTo(TopLevelRoute.Logs) },
                            shape = RoundedCornerShape(9999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = "Logs",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // If Shizuku not granted, display grant card
                if (!granted) {
                    ShizukuGrantCard()
                }

                // Environment Readout Strip
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Environment",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "System ABI",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = Build.SUPPORTED_ABIS[0],
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Android SDK",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "$androidRelease (API $androidApiLevel)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Shizuku API",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = shizukuText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Patch engine",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "SigBypass v3",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                // Recently Patched Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Recently patched",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "View all",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                LSPSettings.setManageTab(0)
                                navigator.switchTo(TopLevelRoute.Manage)
                            }
                        )
                    }

                    if (patchedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No patched applications yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(16.dp)
                                ),
                        ) {
                            val recentList = patchedApps.take(4)
                            recentList.forEachIndexed { index, app ->
                                val icon = rememberAppIcon(app)
                                val isLocal = app.app.metaData?.getInt("lspatch") == 0
                                val mode = if (isLocal) "Local" else "Portable"

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { navigator.go(AppDetail(app.app.packageName)) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f, fill = false),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (icon != null) {
                                            Image(
                                                bitmap = icon,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Android,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(26.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f, fill = false)) {
                                            Text(
                                                text = app.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "$mode · ${app.app.packageName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { navigator.go(AppDetail(app.app.packageName)) },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                if (index < recentList.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        }
                    }
                }

                // Repository status row
                RepoStatusRow(homeVm.repo)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showStayAlive) {
        StayAliveSheet(
            onDismiss = {
                showStayAlive = false
                Configs.askedStayAlive = true
            }
        )
    }
    if (showAppearance) {
        val floatingNav by LSPSettings.floatingNav.collectAsStateWithLifecycle()
        AppearanceSheet(
            LSPSettings,
            onDismiss = { showAppearance = false },
            extra = {
                ToggleRow(
                    title = stringResource(R.string.appearance_floating_nav),
                    icon = Icons.Rounded.BubbleChart,
                    subtitle = stringResource(R.string.appearance_floating_nav_summary),
                    checked = floatingNav,
                    onCheckedChange = { LSPSettings.setFloatingNav(it) },
                )
                SheetAction(
                    title = stringResource(UiR.string.settings_rearrange_panels),
                    icon = Icons.Rounded.Reorder,
                    onClick = {
                        // Edit mode and the dismissal in the one click, and deliberately without
                        // animating the sheet out first: hiding it through its own sheetState would
                        // leave this sheet's window, scrim and all, over the container for the
                        // length of the animation, and the first thing anyone does in edit mode is
                        // drag an item. Dropping the sheet out of composition takes its window with
                        // it in the same frame the container enters edit mode, so the first touch
                        // that lands, lands on a panel.
                        navigator.editingPanels = true
                        showAppearance = false
                    },
                    subtitle = stringResource(UiR.string.settings_rearrange_panels_summary),
                )
            },
        )
    }
    if (showLanguage) {
        LanguageSheet(
            LSPSettings,
            onDismiss = { showLanguage = false },
            onHelpTranslate = {
                showLanguage = false
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(CROWDIN_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }
}

/** The core is built from Vector; its rows link here. */
private const val VECTOR_REPO_URL = "https://github.com/JingMatrix/Vector"

/** Shizuku's own package, so its status row can open the app. */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Where community translations are contributed. */
private const val CROWDIN_URL = "https://crowdin.com/project/lspatch_jingmatrix"

/** Shown only until Shizuku is granted: the way in to grant it, since the header's badge does not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShizukuGrantCard() {
    ElevatedCard(
        onClick = { ShizukuApi.requestPermission() },
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null)
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.shizuku_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_shizuku_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The release name alone — "14", or the codename for a preview build. */
private val androidRelease =
    if (Build.VERSION.PREVIEW_SDK_INT != 0) "${Build.VERSION.CODENAME} Preview" else Build.VERSION.RELEASE

/** The platform API level, shown as "API n" in its own cell (in place of the old "SDK" wording). */
private val androidApiLevel =
    if (Build.VERSION.PREVIEW_SDK_INT != 0) Build.VERSION.PREVIEW_SDK_INT else Build.VERSION.SDK_INT

/** Release with its ABI in parentheses — "17 (arm64-v8a)" — one Android cell instead of two. */
private val androidAndAbi = "$androidRelease (${Build.SUPPORTED_ABIS[0]})"

/** Detailed form kept for the copyable diagnostic. */
private val androidVersion = "$androidRelease (API $androidApiLevel)"

private val deviceName = buildString {
    append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
    append(" ${Build.MODEL}")
}
    .trim()

/**
 * The environment, given room to be read.
 *
 * Replaces the cramped chip cloud: each fact is a row of its own — a leading icon that says what kind of fact it is,
 * the label, and the value in the monospace face the values elsewhere use. The whole set copies to the clipboard from
 * the row at the foot, which is what a bug report wants.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemPropertiesCard(navigator: Navigator) {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.home_info_copied)

    val core = LSPConfig.instance
    val apps = LSPPackageManager.appList
    val moduleCount = apps.count { it.isModule }
    val patchedCount = apps.count { it.app.metaData?.containsKey("lspatch") == true }

    // The Vector tag the core was built from — a release or a canary build — pointing at the exact
    // embedded commit on Vector when tapped. The core *is* Vector, so "Core" is best expressed as
    // the point in Vector's history it stands at rather than a bare version string.
    val coreHash = core.CORE_VERSION_HASH.orEmpty()
    val coreValue = core.CORE_VERSION_NAME

    // Xposed API and the LSPatch version are deliberately absent from the rows: they already sit in
    // the header's version line, and repeating them here reads as three views of one fact. What is
    // left is what the header does not say.
    val shizukuGranted = ShizukuApi.isPermissionGranted
    // When connected, the server's API version is the useful fact (feature availability tracks it);
    // getVersion is only valid while the binder is alive, which a granted permission guarantees.
    val shizukuVersion = if (shizukuGranted) ShizukuApi.serverVersion() else null
    val shizukuValue = if (shizukuVersion != null) "API $shizukuVersion" else stringResource(R.string.shizuku_off)
    // Tapping Shizuku opens what LSPatch does with it, not Shizuku's own screen: this row's reader is
    // asking about the shell LSPatch runs on, and opening the app is one of the rows in that drawer.
    var showShizukuSheet by remember { mutableStateOf(false) }
    val openShizuku: () -> Unit = { showShizukuSheet = true }

    val toApplications = {
        LSPSettings.setManageTab(0)
        navigator.switchTo(TopLevelRoute.Manage)
        Unit
    }
    val toModules = {
        LSPSettings.setManageTab(1)
        navigator.switchTo(TopLevelRoute.Manage)
        Unit
    }
    // The counts are shown as the actual app icons rather than a bare number — the same way Vector
    // presents a set of packages — with a +N overflow when there are more than the row can hold.
    val patchedIcons =
        rememberAppIcons(apps.filter { it.app.metaData?.containsKey("lspatch") == true })
    val moduleIcons = rememberAppIcons(apps.filter { it.isModule })

    // The manager's own installed package. Surfaced here, next to the environment facts, because it
    // is the one identity a detector enumerates the device for -- and because that is the guiding
    // place to offer changing it. Tapping opens the cloak flow (or the revert, once cloaked).
    var showPackageDialog by remember { mutableStateOf(false) }
    val packageProp =
        SystemProperty(
            Icons.Rounded.Badge,
            stringResource(R.string.home_package),
            lspApp.packageName,
            onClick = { showPackageDialog = true },
        )

    // Marked, not spelled out: a patched app that had to start without the manager is a fact about the
    // shell LSPatch runs on, so it belongs on this row rather than in a card of its own -- and the row
    // it belongs to already leads to the page that explains it.
    val missedLaunches = ModuleDeliveryReports.reports.collectAsStateWithLifecycle().value.isNotEmpty()
    val shizukuProp =
        SystemProperty(Icons.Rounded.Terminal, "Shizuku", shizukuValue, openShizuku, mark = missedLaunches)
    val androidProp = SystemProperty(Icons.Rounded.Android, "Android", androidAndAbi)
    val deviceProp = SystemProperty(Icons.Rounded.Smartphone, stringResource(R.string.home_device), deviceName)
    // The core *is* Vector; its row carries the tag it was built from and links to that exact commit,
    // so it stays full-width (a link, and the longest value) rather than squeezed into a grid cell.
    val vectorProp =
        SystemProperty(
            Icons.Rounded.Dns,
            "Vector",
            coreValue,
            onClick =
                if (coreHash.isBlank()) null
                else {
                    {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("$VECTOR_REPO_URL/commit/$coreHash"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
        )

    // A complete diagnostic, not a screenshot of the card: it re-adds the API and LSPatch version the
    // rows omit, and — since counts are useless in a bug report — spells out every module and patched
    // app by package name.
    val onCopy: () -> Unit = {
        val lines = buildList {
            add("Xposed API: ${core.API_CODE}")
            add("LSPatch: ${core.VERSION_NAME}")
            add("Shizuku: ${if (shizukuVersion != null) "API $shizukuVersion" else "not connected"}")
            add("Vector: ${core.CORE_VERSION_NAME} ($coreHash)")
            add("Android: $androidVersion")
            add("ABI: ${Build.SUPPORTED_ABIS[0]}")
            add("Device: $deviceName")
            val modules = apps.filter { it.isModule }
            val patched = apps.filter { it.app.metaData?.containsKey("lspatch") == true }
            add("")
            add("Modules ($moduleCount):")
            if (modules.isEmpty()) add("  (none)") else modules.forEach { add("  ${it.app.packageName}") }
            add("")
            add("Applications ($patchedCount):")
            if (patched.isEmpty()) add("  (none)") else patched.forEach { add("  ${it.app.packageName}") }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LSPatch", lines.joinToString("\n")))
        scope.launch { snackbarHost.show(copied, SnackbarTone.Success) }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {
            // A card header rather than a title-less stack of rows: a quiet section label with the
            // copy action in the conventional top-right corner, so the whole diagnostic is one tap
            // away without a full-width row at the foot masquerading as another property.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_system),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(android.R.string.copy),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconClusterRow(Icons.Rounded.Apps, stringResource(R.string.apps), patchedIcons, toApplications)
            IconClusterRow(Icons.Rounded.Extension, stringResource(R.string.modules), moduleIcons, toModules)
            // Parts the two nav clusters from the plain environment facts: clusters above, facts below.
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            PropertyRow(packageProp)
            PropertyRow(shizukuProp)
            PropertyRow(vectorProp)
            PropertyRow(androidProp)
            PropertyRow(deviceProp)
        }
    }

    if (showPackageDialog) {
        ManagerPackageDialog(onDismiss = { showPackageDialog = false })
    }
    if (showShizukuSheet) {
        ShizukuSheet(onDismiss = { showShizukuSheet = false })
    }
}

/**
 * Configures the manager's own package name.
 *
 * On the stock package it offers to cloak: reinstall under a custom or random id so a detector enumerating for
 * `org.lsposed.lspatch` finds nothing. Once cloaked it offers the reverse. Both are driven by [ManagerCloakFlow], which
 * reports progress as it installs, retargets manager-mode apps, and removes the old package -- so the dialog stays
 * open, showing that progress, until it finishes.
 *
 * This only defeats known-package-name checks; the signing certificate, app label and exported components are
 * unchanged, and it does nothing for Play Integrity. Manager mode only -- integrated patches bind no manager and are
 * unaffected.
 */
@Composable
private fun ManagerPackageDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isCloaked = lspApp.packageName != Constants.MANAGER_PACKAGE_NAME
    val granted = ShizukuApi.isPermissionGranted

    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf(PackageNameValidator.randomPackageName()) }
    var invalid by remember { mutableStateOf(false) }

    SharedAlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (isCloaked) R.string.settings_revert_dialog_title else R.string.settings_cloak_dialog_title
                )
            )
        },
        text = {
            Column {
                Text(
                    stringResource(if (isCloaked) R.string.settings_revert_warning else R.string.settings_cloak_warning)
                )
                if (!isCloaked) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            invalid = false
                        },
                        singleLine = true,
                        isError = invalid,
                        enabled = !running,
                        label = { Text(stringResource(R.string.settings_cloak_package)) },
                        trailingIcon = {
                            TextButton(
                                enabled = !running,
                                onClick = {
                                    newName = PackageNameValidator.randomPackageName()
                                    invalid = false
                                },
                            ) {
                                Text(stringResource(R.string.settings_cloak_randomize))
                            }
                        },
                    )
                    if (invalid)
                        Text(
                            stringResource(R.string.settings_cloak_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
                if (!granted)
                    Text(
                        stringResource(R.string.settings_cloak_need_shizuku),
                        color = MaterialTheme.colorScheme.error,
                    )
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && granted,
                onClick = {
                    if (!isCloaked && !PackageNameValidator.isValid(newName)) {
                        invalid = true
                        return@TextButton
                    }
                    running = true
                    status = null
                    val onProgress: (ManagerCloakFlow.Progress) -> Unit = { p ->
                        when (p) {
                            is ManagerCloakFlow.Progress.Message -> status = p.text
                            is ManagerCloakFlow.Progress.Error -> {
                                status = p.message
                                running = false
                            }
                            // On success the flow launches the new package and this process is about
                            // to be uninstalled; close the dialog so nothing lingers behind it.
                            is ManagerCloakFlow.Progress.Success -> {
                                running = false
                                onDismiss()
                            }
                        }
                    }
                    scope.launch {
                        if (isCloaked) ManagerCloakFlow.revertToOriginal(onProgress)
                        else ManagerCloakFlow.run(newName, onProgress)
                    }
                },
            ) {
                Text(
                    stringResource(if (isCloaked) R.string.settings_revert_confirm else R.string.settings_cloak_confirm)
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private data class SystemProperty(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val onClick: (() -> Unit)? = null,
    /** Draws a dot beside the value: something behind this row wants the reader, without saying so twice. */
    val mark: Boolean = false,
)

/** A full-width property row: icon well, label, value, and a chevron when it leads somewhere. */
@Composable
private fun PropertyRow(property: SystemProperty) {
    val clickable = property.onClick != null
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (clickable) Modifier.clickable(onClick = property.onClick!!) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                property.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        // Label is the caption and the value carries the ink: facts read louder than their names.
        Text(
            property.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            property.value,
            style = Mono.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
        if (property.mark) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier =
                    Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.error)
            )
        }
        if (clickable) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A property row whose value is a cluster of app-icon thumbnails rather than a number — used for the patched apps and
 * the modules. Shows the first few icons and a "+N" chip for the rest, and leads into Manage when tapped; an empty set
 * falls back to a dimmed ghost well of the row's own icon.
 */
@Composable
private fun IconClusterRow(
    icon: ImageVector,
    label: String,
    icons: List<ImageBitmap>,
    onClick: (() -> Unit)?,
) {
    val clickable = onClick != null
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (clickable) Modifier.clickable(onClick = onClick!!) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (icons.isEmpty()) {
            // A dimmed ghost well matching the +N chip geometry: the row keeps its height and right
            // edge whether or not the async app list has populated, asserting no possibly-wrong count.
            Box(
                modifier =
                    Modifier.size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                icons.take(REACH_PREVIEW_LIMIT).forEach { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier =
                            Modifier.size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(7.dp),
                                ),
                    )
                }
                if (icons.size > REACH_PREVIEW_LIMIT) {
                    Box(
                        modifier =
                            Modifier.size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+${icons.size - REACH_PREVIEW_LIMIT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        if (clickable) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Vector-style one-line repository status: stars / forks / open issues / license, muted and centered. */
@Composable
private fun RepoStatusRow(repo: HomeViewModel.RepoStatus?) {
    // Reserve the row's height on cold start so the New Patch button below does not reflow when the
    // async repo stats arrive.
    if (repo == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(20.dp))
        return
    }
    val context = LocalContext.current
    // The shared project footer — the same row Vector's Home shows, over the same GitHub repo.
    RepoStatsRow(
        stars = repo.stars,
        forks = repo.forks,
        openIssues = repo.openIssues,
        license = repo.license,
        onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HomeViewModel.REPO_URL))) }
        },
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
