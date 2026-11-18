<div align="center">

# LSPatch-Zero-Bloat (LSP)

**An enhanced, zero-bloat fork of LSPatch with in-place execution, universal MicroG integration, and granular storage redirection.**

[![Release](https://img.shields.io/github/v/release/expirt/Lspatch-Zero-bloat?color=blue&logo=github&label=Release)](https://github.com/expirt/Lspatch-Zero-bloat/releases/latest)
[![License](https://img.shields.io/badge/License-GPL%20v3-green.svg)](LICENSE)

</div>

---

### What Makes This Fork Different?

Standard upstream LSPatch wraps the entire original APK inside `assets/lspatch/origin.apk` and extracts it to private cache at runtime. This fork completely overhauls the patch pipeline and runtime loader to eliminate bloat, add build-time MicroG bytecode remapping, and integrate native storage containerization.

---

## Key Features & Modifications

### 1. Zero-Bloat In-Place Execution (No Doubled APK Size)
- **Direct Entry Merging:** Drops the legacy `assets/lspatch/origin.apk` embedding mechanism entirely. All original DEX, native libraries, and resources are merged directly into the patched APK root alongside the loader companion DEX.
- **1:1 File Size:** Patched APK size remains essentially identical to the original APK (+ few KB for the companion stub), instead of doubling.
- **Zero Cache Duplication:** The loader runs directly from `/data/app/.../base.apk` without extracting a secondary multi-hundred-megabyte APK copy into `/data/user/0/<package>/cache/`.
- **In-Place Re-Patching:** An installed patched app can be re-patched directly from the system without keeping the original unpatched `.apk` file stored on your device.

### 2. Universal MicroG Integration (Ported from Morphe)
Ported directly from `hoo-dles/morphe-patches` into LSPatch's build engine and loader:
- **Build-Time Bytecode Rewrite:** Runs a streaming DEX pass using `dexlib2` that rewrites GMS references across 150+ intent actions, authorities, permissions, capabilities, and content URIs (`content://<authority>/...`, `subscribedfeeds`) to target the chosen vendor prefix (`app.revanced` by default).
- **Strict Idempotency:** Strings already rewritten to the vendor prefix are safely skipped, preventing re-patch corruption.
- **Play Services Availability Spoofing:** Method bodies for `GooglePlayUtility`, `isGooglePlayServicesAvailable` variants (standard, Proguard, Light), and service checks are patched at build time to return `0` (`SUCCESS`) or return early.
- **Credential Manager Adjustment:** Automatically forces `isSystemProviderRequired = false` on `CredentialOption$Builder`.
- **Manifest Injection:** Automatically adds `<queries><package android:name="<vendor>.android.gms" /></queries>`, remaps C2DM push permissions, and injects spoofed signature metadata.
- **First-Run Verification:** On first launch, the loader checks that MicroG GmsCore is installed and prompts to whitelist it from battery optimizations.

### 3. Built-in Granular Storage Redirection
Ported from Storage Redirection Patcher directly into the loader:
- **Sandboxed Container:** Redirects app data to a custom path template (default: `Containers/{pkg}`).
- **Granular Toggles:** Selectively redirect internal data (`/data/data/<pkg>`), external storage (`/sdcard`), SQLite databases, SharedPreferences, and file constructors.
- **Activity Logging:** Option to log file redirection operations to `<container>/redirect.log`.
- **Android 14 BAL Compliance:** Fixed Android 14 background activity start restrictions by prompting for `MANAGE_EXTERNAL_STORAGE` through foreground Activity lifecycle hooks on first launch.

### 4. Memory & Performance Fixes for Large APKs
- **OutOfMemoryError Prevention:** Eliminated redundant in-memory byte buffer cloning (`is.readAllBytes()`) in `apkzlib`'s `ZFile.mergeFrom`, allowing massive APKs (such as 680MB+ Minecraft) to patch on Android devices without crashing the JVM.
- **Compose UI Measurement Fix:** Fixed an `IllegalStateException` crash in the Manager caused by nested scrolling containers measuring infinite constraints.
- **Fat JAR Build Fix:** Configured `DuplicatesStrategy.EXCLUDE` to prevent collisions on duplicate license files during packaging.

---

## Downloads

Download the pre-compiled APK and standalone CLI JAR from the [Releases](https://github.com/expirt/Lspatch-Zero-bloat/releases/latest) page:

- **LSP Manager APK (`manager-v1.2-492-release.apk`):** Modern Material 3 UI for patching and managing apps on Android.
- **LSPatch CLI JAR (`lspatch-v1.2-492-release.jar`):** Standalone JAR for terminal or automated environments.

---

## Disclaimer & Support Notice

> [!WARNING]
> **I will not answer to any bugs, issues, or feature requests.**  
> If you want to add or fix anything, fork it and do it yourself — AI is free.
