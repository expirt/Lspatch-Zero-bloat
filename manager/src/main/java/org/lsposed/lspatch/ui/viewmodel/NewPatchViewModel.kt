package org.lsposed.lspatch.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.launch
import org.lsposed.lspatch.data.model.ModuleBinding
import org.lsposed.lspatch.data.model.ModuleRef
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.repository.PatchRequestStore
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.patch.ManifestOverrides

/**
 * The patch being configured.
 *
 * Loaded from a persisted [PatchRequest] by token rather than carrying the request itself, so the screen comes back
 * intact after the process is killed instead of landing on an uninitialised `lateinit`. Nothing here runs the patch:
 * that belongs to `PatchJobHost`, which outlives the screen.
 */
class NewPatchViewModel(private val token: String) : ViewModel() {

    var request by mutableStateOf<PatchRequest?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    /**
     * The modules to embed, as full bindings so the list can be reviewed rather than counted.
     *
     * Kept whatever the mode is. Switching to Local used to clear it outright, which meant a user comparing the two
     * modes lost their whole selection by looking at the other one; the request decides what is *used*
     * ([PatchRequest.effectiveModules]) without destroying what was chosen.
     */
    val modules = mutableListOf<ModuleBinding>().toMutableStateList()

    init {
        viewModelScope.launch {
            val loaded = PatchRequestStore.get(token)
            request = loaded
            if (loaded != null) modules.addAll(bindingsFor(loaded.modules))
            loading = false
        }
    }

    private suspend fun bindingsFor(refs: List<ModuleRef>): List<ModuleBinding> = refs.mapNotNull { ref ->
        val file = File(ref.apkPath)
        LSPPackageManager.moduleBindingFromFile(file)
            ?.copy(
                packageName = ref.packageName,
                origin = ref.origin,
            )
            ?: ModuleBinding(
                packageName = ref.packageName,
                label = ref.packageName,
                versionName = null,
                versionCode = 0L,
                manifest = null,
                icon = null,
                apkPath = ref.apkPath.takeIf { file.exists() },
                origin = ref.origin,
            )
    }

    private fun mutate(block: (PatchRequest) -> PatchRequest) {
        val current = request ?: return
        val updated = block(current)
        request = updated
        // Persisted as it is edited, so the draft survives the process being killed mid-decision.
        viewModelScope.launch { PatchRequestStore.update(updated) }
    }

    fun setMode(mode: PatchMode) = mutate { it.copy(mode = mode) }

    fun setDebuggable(value: Boolean) = mutate { it.copy(debuggable = value) }

    fun setVersionCodeOverride(value: Int?) = mutate { it.copy(versionCodeOverride = value) }

    fun setInjectDex(value: Boolean) = mutate { it.copy(injectDex = value) }

    fun setSigBypassLevel(level: Int) = mutate { it.copy(sigBypassLevel = level) }

    fun setLabelOverride(label: String) = mutate { it.copy(labelOverride = label.ifBlank { null }) }

    fun setNewPackageName(pkg: String) = mutate { it.copy(newPackageName = pkg.ifBlank { null }) }

    fun setExtractNativeLibs(value: Boolean) = mutate { it.copy(extractNativeLibs = value) }

    fun setUsesCleartextTraffic(value: Boolean) = mutate { it.copy(usesCleartextTraffic = value) }

    /**
     * Adds a permission, canonicalised and de-duplicated.
     *
     * The name is normalised the same way the patcher's CLI normalises it -- one rule, in the patch module -- so a bare
     * "INTERNET" and a typed-out "android.permission.INTERNET" are the same entry rather than two. A blank field or a
     * name already in the set changes nothing.
     */
    fun addPermission(raw: String) = mutate {
        val name = ManifestOverrides.normalizePermission(raw)
        if (name.isEmpty() || name in it.addedPermissions) it
        else it.copy(addedPermissions = it.addedPermissions + name)
    }

    fun removePermission(name: String) = mutate { it.copy(addedPermissions = it.addedPermissions - name) }

    fun setInjectDocumentsProvider(value: Boolean) = mutate { it.copy(injectDocumentsProvider = value) }

    fun setGmsReplacementPackage(value: String?) = mutate { it.copy(gmsReplacementPackage = value) }

    fun setDeclareFakeSignature(value: Boolean) = mutate { it.copy(declareFakeSignature = value) }

    fun setUseMicroG(value: Boolean) = mutate { it.copy(useMicroG = value) }

    fun setMicroGVendor(value: String) = mutate { it.copy(microGVendor = value.ifBlank { "app.revanced" }) }

    fun setStorageRedirectEnabled(value: Boolean) = mutate {
        val updated = it.copy(storageRedirectEnabled = value)
        if (value && "android.permission.MANAGE_EXTERNAL_STORAGE" !in updated.addedPermissions) {
            updated.copy(addedPermissions = updated.addedPermissions + "android.permission.MANAGE_EXTERNAL_STORAGE")
        } else {
            updated
        }
    }

    fun setContainerPath(value: String?) = mutate { it.copy(containerPath = value) }

    fun setStorageSpoofing(value: Boolean) = mutate { it.copy(storageSpoofing = value) }

    fun setCustomRules(rules: List<String>) = mutate { it.copy(customRules = rules) }

    fun setRedirectInternal(value: Boolean) = mutate { it.copy(redirectInternal = value) }

    fun setRedirectExternal(value: Boolean) = mutate { it.copy(redirectExternal = value) }

    fun setRedirectDatabases(value: Boolean) = mutate { it.copy(redirectDatabases = value) }

    fun setRedirectPreferences(value: Boolean) = mutate { it.copy(redirectPreferences = value) }

    fun setRedirectConstructors(value: Boolean) = mutate { it.copy(redirectConstructors = value) }

    fun setRedirectLogging(value: Boolean) = mutate { it.copy(redirectLogging = value) }

    fun setKeepAbis(abis: List<String>) = mutate { it.copy(keepAbis = abis) }

    fun setSslUnpinning(value: Boolean) = mutate { it.copy(sslUnpinning = value) }

    fun setCrashDebugger(value: Boolean) = mutate { it.copy(crashDebugger = value) }

    fun setAccessibilityDummy(value: Boolean) = mutate { it.copy(accessibilityDummy = value) }

    fun setCustomIconPath(path: String?) = mutate { it.copy(customIconPath = path) }

    /**
     * Adds modules to the set, keeping what is already there.
     *
     * Additive and de-duplicated by package. The old picker assigned its result over the previous selection, so
     * embedding a second module silently discarded the first -- and the patcher keys every embedded module by its
     * package name, so two apks for one package could not both be carried anyway.
     */
    fun addModules(added: List<ModuleBinding>) {
        val existing = modules.mapTo(mutableSetOf()) { it.packageName }
        val fresh = added.filter { existing.add(it.packageName) }
        if (fresh.isEmpty()) return
        modules.addAll(fresh)
        syncModules()
    }

    fun removeModule(packageName: String) {
        if (modules.removeAll { it.packageName == packageName }) syncModules()
    }

    private fun syncModules() {
        mutate { current ->
            current.copy(
                modules =
                    modules.mapNotNull { binding ->
                        binding.apkPath?.let { ModuleRef(binding.packageName, it, binding.origin) }
                    }
            )
        }
    }

    /** Adds installed modules chosen by package name in the module picker. */
    fun addInstalled(packageNames: List<String>) {
        viewModelScope.launch {
            val installed = LSPPackageManager.installedModuleBindings().filter { it.packageName in packageNames }
            addModules(installed)
        }
    }

    fun setFeaturesJson(json: String?) = mutate {
        it.copy(featuresJson = json?.takeIf { s -> s.isNotBlank() })
    }

    fun setEmbeddedBackupPath(path: String?) = mutate {
        it.copy(embeddedBackupPath = path?.takeIf { s -> s.isNotBlank() })
    }

    fun reloadFromStore() {
        viewModelScope.launch {
            PatchRequestStore.get(token)?.let { latest ->
                if (latest != request) {
                    request = latest
                }
            }
        }
    }
}
