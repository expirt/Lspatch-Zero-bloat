package org.lsposed.lspatch.config

import com.google.gson.Gson
import java.io.File
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.FeatureConfig

/**
 * Persists per-package [FeatureConfig] (Identity Randomizer + Anti-Detection) in Manager's
 * private files directory so Local-mode (dynamic) apps can query their latest identity on launch
 * without re-patching, and re-patches can inherit it cleanly.
 */
object AppFeaturesStore {

    private val gson = Gson()

    private fun dir(): File {
        val d = File(lspApp.filesDir, "app_features")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileFor(packageName: String): File {
        val safe = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir(), "$safe.json")
    }

    fun get(packageName: String): FeatureConfig? {
        val f = fileFor(packageName)
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), FeatureConfig::class.java) }.getOrNull()
    }

    fun getJson(packageName: String): String? {
        val f = fileFor(packageName)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun put(packageName: String, config: FeatureConfig?) {
        val f = fileFor(packageName)
        if (config == null || config.isEmpty()) {
            if (f.exists()) f.delete()
            return
        }
        runCatching { f.writeText(gson.toJson(config)) }
    }

    fun putJson(packageName: String, json: String?) {
        val f = fileFor(packageName)
        if (json.isNullOrBlank()) {
            if (f.exists()) f.delete()
            return
        }
        runCatching { f.writeText(json) }
    }

    fun toJson(config: FeatureConfig?): String? {
        if (config == null || config.isEmpty()) return null
        return gson.toJson(config)
    }

    fun fromJson(json: String?): FeatureConfig {
        if (json.isNullOrBlank()) return FeatureConfig()
        return runCatching { gson.fromJson(json, FeatureConfig::class.java) }.getOrNull() ?: FeatureConfig()
    }
}
