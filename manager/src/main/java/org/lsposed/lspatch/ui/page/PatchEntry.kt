package org.lsposed.lspatch.ui.page

import java.util.UUID
import org.lsposed.lspatch.data.model.ModuleRef
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.model.mode
import org.lsposed.lspatch.data.repository.PatchInputs
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.ui.navigation.NewPatch
import org.lsposed.lspatch.ui.navigation.SelectPatchTarget
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.data.model.ModuleOrigin
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.matrix.vector.ui.navigation.Navigator

/**
 * The one way into patching.
 *
 * Home and Manage both call this, and every path onwards -- an installed app, apks from storage, a re-patch, a loader
 * update, an integrated-mode module edit -- converges on one persisted [PatchRequest] and one screen. The two entry
 * points used to differ: Manage demanded a storage folder first and Home did not, which is why patching from Home
 * failed every time.
 */
fun startNewPatch(navigator: Navigator) {
    navigator.go(SelectPatchTarget)
}

/** Persists [request] and opens the patch screen on it. */
suspend fun beginPatch(request: PatchRequest, navigator: Navigator) {
    val token = PatchRequestStore.put(request)
    navigator.go(NewPatch(token = token))
}

/** A fresh patch of an installed app, at the defaults. */
fun newRequestFor(app: AppInfo): PatchRequest =
    PatchRequest(
        token = UUID.randomUUID().toString(),
        target =
            PatchTarget.InstalledApp(
                packageName = app.app.packageName,
                label = app.label,
                apkPaths = listOf(app.app.sourceDir) + (app.app.splitSourceDirs ?: emptyArray()),
            ),
        mode = PatchMode.Local,
        origin = PatchOrigin.New,
    )

/**
 * Rebuilds an already-patched app from the originals stored inside it.
 *
 * The settings it was built with come back with it, so a re-patch is a change to one thing rather than a form filled in
 * again from scratch -- and [modules], when given, replaces the embedded set, which is how an Integrated app's module
 * list is edited at all.
 */
suspend fun rePatchRequestFor(
    app: AppInfo,
    mode: PatchMode? = null,
    modules: List<ModuleRef>? = null,
    origin: PatchOrigin = PatchOrigin.RePatch,
): PatchRequest {
    val recovered = PatchInputs.fromInstalledPatchedApp(app)
    val config = recovered.config
    val embedded =
        recovered.embeddedModules.mapNotNull { binding ->
            binding.apkPath?.let { ModuleRef(binding.packageName, it, binding.origin) }
        }
    return PatchRequest(
        token = UUID.randomUUID().toString(),
        target =
            PatchTarget.RecoveredOrigin(
                packageName = app.app.packageName,
                label = app.label,
                apkPaths = recovered.originApks.map { it.absolutePath },
            ),
        mode = mode ?: config?.mode ?: PatchMode.Local,
        debuggable = config?.debuggable ?: false,
        versionCodeOverride = config?.versionCode,
        // Recovered from the recorded config, not the original apks -- those never carried them.
        addedPermissions = config?.addedPermissions?.toList() ?: emptyList(),
        injectDocumentsProvider = config?.injectDocumentsProvider ?: false,
        accessibilityDummy = config?.accessibilityDummy ?: false,
        sslUnpinning = config?.sslUnpinning ?: false,
        crashDebugger = config?.crashDebugger ?: false,
        gmsReplacementPackage = config?.gmsReplacementPackage,
        declareFakeSignature = config?.declareFakeSignature ?: false,
        storageRedirectEnabled = config?.storageRedirectEnabled ?: false,
        containerPath = config?.containerPath,
        storageSpoofing = config?.storageSpoofing ?: (config?.storageRedirectEnabled ?: true),
        customRules = config?.customRules?.toList() ?: emptyList(),
        redirectInternal = config?.redirectInternal ?: true,
        redirectExternal = config?.redirectExternal ?: true,
        redirectDatabases = config?.redirectDatabases ?: true,
        redirectPreferences = config?.redirectPreferences ?: true,
        redirectConstructors = config?.redirectConstructors ?: true,
        redirectLogging = config?.redirectLogging ?: false,
        useMicroG = config?.useMicroG ?: false,
        microGVendor = config?.microGVendor ?: "app.revanced",
        sigBypassLevel = config?.sigBypassLevel ?: 2,
        injectDex = config?.injectDex ?: false,
        keepAbis = config?.keepAbis?.toList() ?: emptyList(),
        newPackageName = config?.newPackageName,
        modules = modules ?: embedded,
        origin = origin,
    )
}

/**
 * Creates a PatchRequest for the selected APK files.
 *
 * If the application is already installed on the device (or if the APK is already patched),
 * automatically recovers and applies its existing configuration (mode, storage redirection,
 * transparent spoofing, custom rules, dummy accessibility service, unpinning, and active modules)
 * so the user doesn't have to re-configure settings.
 */
suspend fun createPatchRequestForApks(
    infos: List<AppInfo>,
    origin: PatchOrigin = PatchOrigin.New,
): PatchRequest {
    val primary = infos.first()
    val apkPaths = listOf(primary.app.sourceDir) + (primary.app.splitSourceDirs ?: emptyArray())
    val pkgName = primary.app.packageName

    // 1. Check if the app is already installed on device
    val installed = LSPPackageManager.appList.firstOrNull { it.app.packageName == pkgName }

    // 2. Recover previous config: first from installed app if patched, or from the primary apk itself if patched
    val config = (if (installed?.isLSPatched == true) PatchInputs.configOf(installed) else null)
        ?: (if (primary.isLSPatched) PatchInputs.configOf(primary) else null)

    // 3. Recover modules:
    // If it was in Local mode and installed, fetch modules configured in ConfigManager
    val localModules = if (installed != null) {
        runCatching {
            val appScopes = ConfigManager.getModulesForApp(pkgName)
            appScopes.map { ModuleRef(it.pkgName, it.apkPath, ModuleOrigin.Installed) }
        }.getOrNull().orEmpty()
    } else emptyList()

    // If it was in Integrated mode, check for embedded modules
    val embeddedModules = if (installed?.isLSPatched == true && config?.useManager == false) {
        runCatching {
            PatchInputs.fromInstalledPatchedApp(installed).embeddedModules.mapNotNull { binding ->
                binding.apkPath?.let { ModuleRef(binding.packageName, it, binding.origin) }
            }
        }.getOrNull().orEmpty()
    } else emptyList()

    val modules = if (config?.mode == PatchMode.Integrated) {
        embeddedModules
    } else if (localModules.isNotEmpty()) {
        localModules
    } else emptyList()

    return PatchRequest(
        token = UUID.randomUUID().toString(),
        target = PatchTarget.ApkFiles(
            packageName = primary.app.packageName,
            label = primary.label,
            apkPaths = apkPaths,
        ),
        mode = config?.mode ?: PatchMode.Local,
        debuggable = config?.debuggable ?: false,
        versionCodeOverride = config?.versionCode,
        addedPermissions = config?.addedPermissions?.toList() ?: emptyList(),
        injectDocumentsProvider = config?.injectDocumentsProvider ?: false,
        accessibilityDummy = config?.accessibilityDummy ?: false,
        sslUnpinning = config?.sslUnpinning ?: false,
        crashDebugger = config?.crashDebugger ?: false,
        gmsReplacementPackage = config?.gmsReplacementPackage,
        declareFakeSignature = config?.declareFakeSignature ?: false,
        storageRedirectEnabled = config?.storageRedirectEnabled ?: false,
        containerPath = config?.containerPath,
        storageSpoofing = config?.storageSpoofing ?: (config?.storageRedirectEnabled ?: true),
        customRules = config?.customRules?.toList() ?: emptyList(),
        redirectInternal = config?.redirectInternal ?: true,
        redirectExternal = config?.redirectExternal ?: true,
        redirectDatabases = config?.redirectDatabases ?: true,
        redirectPreferences = config?.redirectPreferences ?: true,
        redirectConstructors = config?.redirectConstructors ?: true,
        redirectLogging = config?.redirectLogging ?: false,
        useMicroG = config?.useMicroG ?: false,
        microGVendor = config?.microGVendor ?: "app.revanced",
        sigBypassLevel = config?.sigBypassLevel ?: 2,
        injectDex = config?.injectDex ?: false,
        keepAbis = config?.keepAbis?.toList() ?: emptyList(),
        newPackageName = config?.newPackageName,
        modules = modules,
        origin = origin,
    )
}
