package org.lsposed.lspatch.util

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants

/**
 * A Google Play services replacement installed on this device.
 *
 * Carries what the chooser shows: the build names itself through [label] and [applicationInfo], and [versionName]
 * is worth showing because these builds are updated often and two of them side by side are otherwise
 * indistinguishable.
 */
data class GmsReplacement(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val applicationInfo: ApplicationInfo,
)

/**
 * The Google Play services replacements this device actually has.
 *
 * Found by asking, not by reading names. A replacement is whatever answers the services a Play services client
 * binds, and builds differ in what they call themselves: one renames itself and its permissions so it can sit
 * beside Google's own, another keeps Google's names and replaces it outright. Querying the sign-in service is
 * how a client finds Google Play services in the first place, so anything that answers it is by definition a
 * candidate, whatever it is called.
 *
 * Needs package visibility, which the manager holds; Google's own package is dropped, since it is what is being
 * replaced.
 */
object GmsReplacements {

    private val KNOWN_REPLACEMENTS = listOf(
        "app.revanced.android.gms",
        "org.microg.gms",
        "com.mgoogle.android.gms",
        "org.microg.android.gms",
    )

    private val SIGN_IN_ACTIONS = listOf(
        "com.google.android.gms.auth.api.signin.service.START",
        "com.google.android.gms.signin.service.START",
        "org.microg.gms.signin.service.START",
        "app.revanced.android.gms.auth.api.signin.service.START",
    )

    /**
     * The vendor token of a replacement's package -- what distinguishes one build from another.
     *
     * The trailing segments every such build shares are dropped first, so what is left is the part that names
     * the vendor rather than the service: `app.revanced.android.gms` and `org.microg.gms` give `revanced` and
     * `microg`, not `android` and `gms`.
     */
    fun shortName(packageName: String): String =
        packageName
            .removeSuffix(Constants.GMS_REPLACEMENT_SUFFIX)
            .removeSuffix(".android")
            .substringAfterLast('.')

    fun installed(): List<GmsReplacement> {
        val candidates = LinkedHashSet<String>()

        // 1. Check known replacements first
        for (pkg in KNOWN_REPLACEMENTS) {
            if (isInstalled(pkg) && Constants.isGmsReplacement(pkg)) {
                candidates.add(pkg)
            }
        }

        // 2. Query sign-in service actions
        for (action in SIGN_IN_ACTIONS) {
            runCatching {
                lspApp.packageManager
                    .queryIntentServices(Intent(action), 0)
                    .mapNotNull { it.serviceInfo?.packageName }
                    .filter { Constants.isGmsReplacement(it) }
                    .forEach { candidates.add(it) }
            }
        }

        // 3. Scan installed packages ending with .gms (excluding real Google GMS)
        runCatching {
            lspApp.packageManager.getInstalledPackages(0).forEach { pi ->
                val name = pi.packageName
                if (Constants.isGmsReplacement(name)) {
                    candidates.add(name)
                }
            }
        }

        return candidates.sorted().mapNotNull { describe(it) }
    }

    fun autoDetect(): String? = installed().firstOrNull()?.packageName

    fun isInstalled(packageName: String): Boolean =
        runCatching { lspApp.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun describe(packageName: String): GmsReplacement? =
        runCatching {
            val info = lspApp.packageManager.getPackageInfo(packageName, 0)
            val app = info.applicationInfo ?: return null
            GmsReplacement(
                packageName = packageName,
                label = lspApp.packageManager.getApplicationLabel(app).toString(),
                versionName = info.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                applicationInfo = app,
            )
        }.getOrNull()
}
