# AGENT NOTES & ARCHITECTURAL DECISIONS

## 1. Zero-Bloat Execution Design
- **Problem in Upstream:** Upstream LSPatch packs the entire target APK into `assets/lspatch/origin.apk`. At runtime, `LSPApplication` extracts this entire APK to `/data/user/0/<package>/cache/origin.apk` and loads DEX from it. This doubles the disk footprint and prevents in-place repatching.
- **Solution:** 
  - In `ApkPatcher.java`, we copy all original APK entries (DEX, assets, native libs, resources) directly to the root of the output APK alongside `loader.dex`.
  - In `LSPApplication.java`, we detect if `assets/lspatch/origin.apk` is absent. If so, we point the classloader directly to `/data/app/.../base.apk` (the installed APK) without extracting anything to disk cache.
  - In `ZFile.mergeFrom`, eliminated memory buffering `is.readAllBytes()` to allow large apps like Minecraft (680MB+) to patch on Android devices with constrained heap.

## 2. MicroG Integration Architecture
- Ported from Morphe patches (`hoo-dles/morphe-patches`).
- Implemented as a streaming bytecode pass in `MicroGPatcher.java` using `dexlib2`.
- Handles 150+ GMS string mappings to prevent Play Services hard dependencies.
- Patches Google Play Services availability checks to return `0` (`SUCCESS`).
- Binary AXML modification in `ApkPatcher.java` to inject `<queries>` and C2DM permissions.
- Added `MicroGSupport.java` runtime check for installation and battery optimization whitelist.

## 3. Storage Redirection Architecture
- Integrated hooks in `StorageRedirector.java`.
- Container path defaults to `Containers/{pkg}`.
- Intercepts File constructors, SQLite databases, SharedPreferences, and external storage paths when enabled.
- Avoids Android 14 Background Activity Launch (BAL) restrictions by executing permission requests in foreground activity lifecycle (`Activity.onStart`).

## 4. Git & GitHub Configuration
- User GitHub Account: `expirt`
- Repo: `https://github.com/expirt/Lspatch-Zero-bloat`
- Auth token provided in session context.
- Remote URL format: `https://expirt:<token>@github.com/expirt/Lspatch-Zero-bloat.git`.
