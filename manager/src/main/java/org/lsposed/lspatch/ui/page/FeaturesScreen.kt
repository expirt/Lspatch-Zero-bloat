package org.lsposed.lspatch.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import org.lsposed.lspatch.config.AppFeaturesStore
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.share.FeatureConfig
import org.lsposed.lspatch.ui.component.DetailTopBar
import org.lsposed.lspatch.ui.util.LocalSnackbarHost
import org.matrix.vector.ui.SnackbarTone
import org.matrix.vector.ui.ToggleRow
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.show

/**
 * Configures per-app Anti-Detection & Identity Randomizer ([FeatureConfig]).
 *
 * Works in two modes:
 * - Pre-patch ([token] != null): reads & writes [PatchRequestStore] for the pending patch.
 * - Dynamic/Installed ([packageName] != null): reads & writes [AppFeaturesStore] so Local-mode
 *   apps pick up the new identity on next launch without re-patching.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeaturesScreen(
    navigator: Navigator,
    token: String? = null,
    packageName: String? = null,
    label: String = "",
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    var loading by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(FeatureConfig()) }
    var revision by remember { mutableIntStateOf(0) }
    var resolvedLabel by remember { mutableStateOf(label.ifBlank { "Features" }) }
    var resolvedPkg by remember { mutableStateOf(packageName.orEmpty()) }

    LaunchedEffect(token, packageName) {
        if (token != null) {
            val req = PatchRequestStore.get(token)
            if (req != null) {
                resolvedLabel = req.label
                resolvedPkg = req.newPackageName ?: req.packageName
                config = AppFeaturesStore.fromJson(req.featuresJson)
            }
        } else if (packageName != null) {
            resolvedPkg = packageName
            config = AppFeaturesStore.fromJson(AppFeaturesStore.getJson(packageName))
        }
        revision++
        loading = false
    }

    fun updateConfig(mutate: (FeatureConfig) -> Unit) {
        val copy = config.copy()
        mutate(copy)
        config = copy
        revision++
    }

    fun saveAndExit() {
        scope.launch {
            val json = AppFeaturesStore.toJson(config)
            if (token != null) {
                val req = PatchRequestStore.get(token)
                if (req != null) {
                    PatchRequestStore.update(req.copy(featuresJson = json))
                }
            } else if (packageName != null) {
                AppFeaturesStore.putJson(packageName, json)
            }
            snackbarHost.show("Features saved", SnackbarTone.Success)
            navigator.back()
        }
    }

    Scaffold(
        topBar = {
            DetailTopBar(
                label = resolvedLabel,
                packageName = resolvedPkg.ifBlank { "Identity & Anti-Detection" },
                onBack = { navigator.back() },
                actions = {
                    IconButton(
                        onClick = {
                            config = FeatureConfig()
                            revision++
                        }
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear all")
                    }
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.Rounded.Check, contentDescription = "Save")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { updateConfig { it.regenerateAll(true) } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New Identity")
                    }
                    Button(
                        onClick = { saveAndExit() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }
        },
    ) { innerPadding ->
        if (loading) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // Keyed on revision for text fields so 1-click "New Identity" / Preset / Randomize
        // immediately refreshes every OutlinedTextField value.
        val current = config

        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Anti-Detection ---
            FeatureSectionHeader(title = "Anti-Detection", icon = Icons.Rounded.Shield)
            FeatureGroup {
                ToggleRow(
                    title = "Enable Anti-Detection",
                    subtitle = "Master switch for LSPatch, root, and debugger cloaking",
                    icon = Icons.Rounded.Shield,
                    checked = current.antiDetectionEnabled,
                    onCheckedChange = { checked ->
                        updateConfig {
                            if (checked && !it.hasAnyAntiDetection()) {
                                it.enableAllAntiDetection()
                            } else {
                                it.antiDetectionEnabled = checked
                            }
                        }
                    },
                )
                AnimatedVisibility(visible = current.antiDetectionEnabled) {
                    Column {
                        FeatureDivider()
                        ToggleRow(
                            title = "Hide LSPatch & Xposed Traces",
                            subtitle = "Filter framework classes from stack traces and class loaders",
                            icon = Icons.Rounded.VisibilityOff,
                            checked = current.hideLspatchTraces,
                            onCheckedChange = { v -> updateConfig { it.hideLspatchTraces = v } },
                        )
                        ToggleRow(
                            title = "Hide Manager & Module Packages",
                            subtitle = "Hide LSPatch Manager & Xposed modules from PackageManager queries",
                            icon = Icons.Rounded.Android,
                            checked = current.hidePackages,
                            onCheckedChange = { v -> updateConfig { it.hidePackages = v } },
                        )
                        ToggleRow(
                            title = "Hide Developer & ADB Options",
                            subtitle = "Return 0 for adb_enabled and development_settings_enabled",
                            icon = Icons.Rounded.BugReport,
                            checked = current.hideDevOptions,
                            onCheckedChange = { v -> updateConfig { it.hideDevOptions = v } },
                        )
                        ToggleRow(
                            title = "Bypass FLAG_SECURE",
                            subtitle = "Allow screenshots and screen recording in protected windows",
                            icon = Icons.Rounded.Screenshot,
                            checked = current.disableFlagSecure,
                            onCheckedChange = { v -> updateConfig { it.disableFlagSecure = v } },
                        )
                        ToggleRow(
                            title = "Hide Root & Magisk Files",
                            subtitle = "Return ENOENT for su, Magisk, KernelSU, APatch, and Frida paths",
                            icon = Icons.Rounded.Security,
                            checked = current.hideRootFiles,
                            onCheckedChange = { v -> updateConfig { it.hideRootFiles = v } },
                        )
                        ToggleRow(
                            title = "Mask /proc/self/maps",
                            subtitle = "Strip lspatch, libxposed, lsplant, and module entries in native hooks",
                            icon = Icons.Rounded.Memory,
                            checked = current.maskProcMaps,
                            onCheckedChange = { v -> updateConfig { it.maskProcMaps = v } },
                        )
                    }
                }
            }

            // --- Identity Randomizer ---
            FeatureSectionHeader(title = "Identity Randomizer", icon = Icons.Rounded.Fingerprint)
            FeatureGroup {
                ToggleRow(
                    title = "Enable Identity Spoofer",
                    subtitle = "Spoof hardware IDs, device fingerprint, network, and telephony",
                    icon = Icons.Rounded.Fingerprint,
                    checked = current.identitySpoofEnabled,
                    onCheckedChange = { checked ->
                        updateConfig {
                            if (checked && !it.hasAnyIdentitySpoof()) {
                                it.regenerateAll(true)
                            } else {
                                it.identitySpoofEnabled = checked
                            }
                        }
                    },
                )
            }

            AnimatedVisibility(visible = current.identitySpoofEnabled || current.hasAnyIdentitySpoof()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Device Preset Chips
                    FeatureGroup {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = "Device Profile Preset",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FeatureConfig.DEVICE_PRESETS.forEach { preset ->
                                    FilterChip(
                                        selected = current.devicePreset == preset.name,
                                        onClick = { updateConfig { it.applyDevicePreset(preset) } },
                                        label = { Text(preset.name) },
                                    )
                                }
                            }
                        }
                    }

                    // Core Identifiers
                    FeatureSectionHeader(title = "Device & Advertising IDs", icon = Icons.Rounded.Fingerprint)
                    FeatureGroup {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IdentityField(
                                label = "Android ID (SSAID)",
                                value = current.androidId.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.androidId = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.androidId = FeatureConfig.randomHex(16) } },
                            )
                            IdentityField(
                                label = "Widevine DRM ID",
                                value = current.widevineId.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.widevineId = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.widevineId = FeatureConfig.randomHex(32) } },
                            )
                            IdentityField(
                                label = "Advertising ID (GAID)",
                                value = current.advertisingId.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.advertisingId = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.advertisingId = UUID.randomUUID().toString() } },
                            )
                            IdentityField(
                                label = "GSF ID (Google Services)",
                                value = current.gsfId.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.gsfId = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.gsfId = FeatureConfig.randomDecDigits(19) } },
                            )
                            IdentityField(
                                label = "App Set ID",
                                value = current.appSetId.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.appSetId = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.appSetId = UUID.randomUUID().toString() } },
                            )
                            IdentityField(
                                label = "Kernel Boot ID",
                                value = current.bootId.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.bootId = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.bootId = UUID.randomUUID().toString() } },
                            )
                            IdentityField(
                                label = "Hardware Serial",
                                value = current.serial.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.serial = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.serial = FeatureConfig.randomAlphaNumUpper(10) } },
                            )
                        }
                    }

                    // Build / Hardware Fingerprint (Collapsible)
                    var buildExpanded by remember { mutableStateOf(false) }
                    FeatureGroup {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { buildExpanded = !buildExpanded }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Build & Hardware Fingerprint",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = current.model ?: "Not overridden",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (buildExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        AnimatedVisibility(visible = buildExpanded) {
                            Column(
                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IdentityField("Brand", current.brand.orEmpty(), revision, { v -> updateConfig { it.brand = v.ifBlank { null } } })
                                IdentityField("Manufacturer", current.manufacturer.orEmpty(), revision, { v -> updateConfig { it.manufacturer = v.ifBlank { null } } })
                                IdentityField("Model", current.model.orEmpty(), revision, { v -> updateConfig { it.model = v.ifBlank { null } } })
                                IdentityField("Device", current.device.orEmpty(), revision, { v -> updateConfig { it.device = v.ifBlank { null } } })
                                IdentityField("Product", current.product.orEmpty(), revision, { v -> updateConfig { it.product = v.ifBlank { null } } })
                                IdentityField("Hardware", current.hardware.orEmpty(), revision, { v -> updateConfig { it.hardware = v.ifBlank { null } } })
                                IdentityField("Board", current.board.orEmpty(), revision, { v -> updateConfig { it.board = v.ifBlank { null } } })
                                IdentityField("Bootloader", current.bootloader.orEmpty(), revision, { v -> updateConfig { it.bootloader = v.ifBlank { null } } })
                                IdentityField("Build Fingerprint", current.fingerprint.orEmpty(), revision, { v -> updateConfig { it.fingerprint = v.ifBlank { null } } })
                            }
                        }
                    }

                    // Network / Wi-Fi / Bluetooth
                    FeatureSectionHeader(title = "Wi-Fi & Bluetooth", icon = Icons.Rounded.Wifi)
                    FeatureGroup {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IdentityField(
                                label = "Wi-Fi MAC Address",
                                value = current.wifiMac.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.wifiMac = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.wifiMac = FeatureConfig.randomMac() } },
                            )
                            IdentityField(
                                label = "Wi-Fi BSSID",
                                value = current.wifiBssid.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.wifiBssid = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.wifiBssid = FeatureConfig.randomMac() } },
                            )
                            IdentityField(
                                label = "Wi-Fi SSID",
                                value = current.wifiSsid.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.wifiSsid = v.ifBlank { null } } },
                            )
                            IdentityField(
                                label = "Bluetooth MAC",
                                value = current.bluetoothMac.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.bluetoothMac = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.bluetoothMac = FeatureConfig.randomMac() } },
                            )
                            IdentityField(
                                label = "Bluetooth Device Name",
                                value = current.bluetoothName.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.bluetoothName = v.ifBlank { null } } },
                            )
                        }
                    }

                    // Telephony / SIM / Carrier
                    FeatureSectionHeader(title = "Telephony & SIM", icon = Icons.Rounded.SimCard)
                    FeatureGroup {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IdentityField(
                                label = "IMEI",
                                value = current.imei.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.imei = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.imei = FeatureConfig.randomImei() } },
                            )
                            IdentityField(
                                label = "IMSI",
                                value = current.imsi.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.imsi = v.ifBlank { null } } },
                                onRandomize = {
                                    updateConfig {
                                        val prefix = it.carrierMccMnc?.takeIf { m -> m.length >= 5 } ?: "310260"
                                        it.imsi = prefix + FeatureConfig.randomDecDigits(15 - prefix.length)
                                    }
                                },
                            )
                            IdentityField(
                                label = "SIM ICCID",
                                value = current.iccid.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.iccid = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.iccid = "89310" + FeatureConfig.randomDecDigits(14) } },
                            )
                            IdentityField(
                                label = "Phone Number",
                                value = current.phoneNumber.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.phoneNumber = v.ifBlank { null } } },
                                onRandomize = { updateConfig { it.phoneNumber = "+1" + FeatureConfig.randomDecDigits(10) } },
                            )
                            IdentityField(
                                label = "Carrier Name",
                                value = current.carrierName.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.carrierName = v.ifBlank { null } } },
                            )
                            IdentityField(
                                label = "Carrier MCC/MNC",
                                value = current.carrierMccMnc.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.carrierMccMnc = v.ifBlank { null } } },
                            )
                            IdentityField(
                                label = "Country ISO",
                                value = current.countryIso.orEmpty(),
                                revision = revision,
                                onValueChange = { v -> updateConfig { it.countryIso = v.ifBlank { null } } },
                            )
                        }
                    }

                    // WebView / User-Agent
                    FeatureSectionHeader(title = "WebView & HTTP", icon = Icons.Rounded.Http)
                    FeatureGroup {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IdentityField(
                                label = "User-Agent",
                                value = current.userAgent.orEmpty(),
                                revision = revision,
                                singleLine = false,
                                onValueChange = { v -> updateConfig { it.userAgent = v.ifBlank { null } } },
                                onRandomize = {
                                    updateConfig {
                                        it.userAgent = FeatureConfig.buildDefaultUserAgent(it.model)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FeatureSectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FeatureGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun FeatureDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

@Composable
private fun IdentityField(
    label: String,
    value: String,
    revision: Int,
    onValueChange: (String) -> Unit,
    onRandomize: (() -> Unit)? = null,
    singleLine: Boolean = true,
) {
    var text by remember(revision) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        trailingIcon =
            if (onRandomize != null) {
                {
                    IconButton(onClick = onRandomize) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Randomize $label")
                    }
                }
            } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}
