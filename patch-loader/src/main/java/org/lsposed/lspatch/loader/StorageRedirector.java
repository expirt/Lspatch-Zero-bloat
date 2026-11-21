package org.lsposed.lspatch.loader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * High-performance Storage Redirection Engine for LSPatch.
 *
 * Redirects internal data directories, external shared storage, SQLite databases,
 * and file constructors to an isolated, configurable application container.
 */
public class StorageRedirector {
    private static final String TAG = "LSPatch-StorageRedirect";

    private static volatile boolean sActivated = false;
    private static String sPackageName = "";
    private static String sContainerRoot = "";
    private static volatile String sRealInternalDataDir = "";
    private static volatile String sNativeLibraryDir = "";
    private static final Set<String> sNativeLibDirs = Collections.synchronizedSet(new HashSet<>());

    private static boolean sRedirectInternal = true;
    private static boolean sRedirectExternal = true;
    private static boolean sRedirectDatabases = true;
    private static boolean sRedirectPreferences = true;
    private static boolean sRedirectConstructors = true;
    private static boolean sLoggingEnabled = false;

    private static boolean sStorageSpoofing = false;
    private static boolean sNativeSpoofLoaded = false;

    public static class JavaCustomRule {
        public final String virtualPrefix;
        public final String realPrefix;
        public final int flags;

        public JavaCustomRule(String virt, String real, int flags) {
            this.virtualPrefix = virt;
            this.realPrefix = real;
            this.flags = flags;
        }
    }

    private static final java.util.List<JavaCustomRule> sCustomRules = new java.util.ArrayList<>();

    private static volatile long sLastLogTime = 0;
    private static final long LOG_MIN_INTERVAL_MS = 250;

    // Recursion guard to prevent infinite re-entrant hooks on File constructors
    private static final ThreadLocal<Boolean> sInHook = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Native JNI Interface for liblspatch_storage.so
    private static native void nativeInitStorageSpoof(
            String containerRoot,
            String packageName,
            String internalDataDir,
            boolean redirectExternal,
            boolean redirectInternal);

    private static native void nativeAddNativeLibDir(String libDir);

    private static native void nativeAddCustomRule(String virt, String real, int flags);

    private static native String nativeToRealPath(String path);

    private static native String nativeToVirtualPath(String path);

    private static native void nativeRescanNativeLibs();

    private static boolean loadNativeStorageLibrary(Context context, JSONObject config) {
        if (sNativeSpoofLoaded) return true;
        try {
            String arch = "arm64-v8a";
            try {
                Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
                java.lang.reflect.Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
                getRuntime.setAccessible(true);
                java.lang.reflect.Method vmInstructionSet = VMRuntime.getDeclaredMethod("vmInstructionSet");
                vmInstructionSet.setAccessible(true);
                String isa = (String) vmInstructionSet.invoke(getRuntime.invoke(null));
                if ("arm64".equals(isa)) arch = "arm64-v8a";
                else if ("arm".equals(isa)) arch = "armeabi-v7a";
                else if ("x86_64".equals(isa)) arch = "x86_64";
                else if ("x86".equals(isa)) arch = "x86";
            } catch (Throwable ignored) {
            }

            String soRelativePath = "assets/lspatch/so/" + arch + "/liblspatch_storage.so";

            // 1. Try loading from target app's own sourceDir
            if (context != null
                    && context.getApplicationInfo() != null
                    && context.getApplicationInfo().sourceDir != null) {
                String appApk = context.getApplicationInfo().sourceDir;
                if (tryLoadFromApk(context, appApk, soRelativePath)) {
                    sNativeSpoofLoaded = true;
                    Log.i(TAG, "Loaded liblspatch_storage.so from app sourceDir: " + appApk);
                    return true;
                }
                if (context.getApplicationInfo().splitSourceDirs != null) {
                    for (String splitApk : context.getApplicationInfo().splitSourceDirs) {
                        if (tryLoadFromApk(context, splitApk, soRelativePath)) {
                            sNativeSpoofLoaded = true;
                            Log.i(TAG, "Loaded liblspatch_storage.so from split sourceDir: " + splitApk);
                            return true;
                        }
                    }
                }
            }

            // 2. Try loading from Manager APK (for Local Mode when not bundled in app)
            List<String> managerCandidates = new ArrayList<>();
            if (config != null) {
                String mgrPkg = config.optString("managerPackageName", "");
                if (!mgrPkg.isEmpty()) managerCandidates.add(mgrPkg);
            }
            managerCandidates.add("org.expirt.lspatch");
            managerCandidates.add("org.lsposed.lspatch");
            managerCandidates.add("io.github.expirt.lspatch");

            for (String mgrCandidate : managerCandidates) {
                String mgrApk = getManagerSourceDir(context, mgrCandidate);
                if (mgrApk != null && tryLoadFromApk(context, mgrApk, soRelativePath)) {
                    sNativeSpoofLoaded = true;
                    Log.i(TAG, "Loaded liblspatch_storage.so from manager APK: " + mgrApk);
                    return true;
                }
            }

            // 3. Try ClassLoader resources
            ClassLoader cl = StorageRedirector.class.getClassLoader();
            if (cl != null) {
                java.net.URL resource = cl.getResource(soRelativePath);
                if (resource != null) {
                    String resourcePath = resource.getPath();
                    String[] parts = resourcePath.split("file:");
                    String soPath = parts[parts.length - 1];
                    try {
                        System.load(soPath);
                        sNativeSpoofLoaded = true;
                        Log.i(TAG, "Loaded liblspatch_storage.so directly from ClassLoader resource: " + soPath);
                        return true;
                    } catch (Throwable t) {
                        Log.w(TAG, "Direct ClassLoader load failed: " + t.getMessage());
                    }
                }

                try (InputStream is = cl.getResourceAsStream(soRelativePath)) {
                    if (is != null && context != null) {
                        if (extractAndLoad(context, is)) {
                            sNativeSpoofLoaded = true;
                            Log.i(TAG, "Loaded liblspatch_storage.so via ClassLoader resource extraction");
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 4. Try standard System.loadLibrary
            try {
                System.loadLibrary("lspatch_storage");
                sNativeSpoofLoaded = true;
                Log.i(TAG, "Loaded liblspatch_storage via System.loadLibrary");
                return true;
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            Log.e(TAG, "Failed to load native storage library: " + t.getMessage(), t);
        }
        return false;
    }

    private static boolean tryLoadFromApk(Context context, String apkPath, String entryName) {
        if (apkPath == null || apkPath.isEmpty()) return false;
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) return false;

        // Direct load (stored uncompressed)
        try {
            System.load(apkPath + "!/" + entryName);
            return true;
        } catch (Throwable directLoadErr) {
            // Direct load failed, try extraction to codeCacheDir
        }

        // Extraction fallback
        try (ZipFile zip = new ZipFile(apkFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry)) {
                    return extractAndLoad(context, is);
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean extractAndLoad(Context context, InputStream is) {
        try {
            File codeCache = (context != null) ? context.getCodeCacheDir() : null;
            if (codeCache == null && context != null) codeCache = context.getCacheDir();
            if (codeCache == null) return false;

            File target = new File(codeCache, "liblspatch_storage.so");
            try (FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
            }
            target.setReadable(true, false);
            target.setExecutable(true, false);
            System.load(target.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "extractAndLoad failed: " + t.getMessage(), t);
            return false;
        }
    }

    private static String getManagerSourceDir(Context context, String candidatePkg) {
        if (candidatePkg == null || candidatePkg.isEmpty()) return null;
        try {
            if (context != null && context.getPackageManager() != null) {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(candidatePkg, 0);
                if (info != null && info.sourceDir != null && new File(info.sourceDir).exists()) {
                    return info.sourceDir;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "package");
            if (binder != null) {
                android.content.pm.IPackageManager ipm = android.content.pm.IPackageManager.Stub.asInterface(binder);
                int userId = android.os.Process.myUid() / 100000;
                ApplicationInfo info = ipm.getApplicationInfo(candidatePkg, 0, userId);
                if (info != null && info.sourceDir != null && new File(info.sourceDir).exists()) {
                    return info.sourceDir;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    public static void activate(Context context, JSONObject config) {
        if (sActivated || context == null) return;
        synchronized (StorageRedirector.class) {
            if (sActivated) return;
            sActivated = true;

            sPackageName = context.getPackageName();
            if (context.getApplicationInfo() != null) {
                sRealInternalDataDir = normalizePath(context.getApplicationInfo().dataDir);
                if (context.getApplicationInfo().nativeLibraryDir != null) {
                    sNativeLibraryDir = normalizePath(context.getApplicationInfo().nativeLibraryDir);
                    sNativeLibDirs.add(sNativeLibraryDir);
                }
            }

            String containerTemplate = config.optString("containerPath", "Containers/{pkg}");
            if (containerTemplate == null || containerTemplate.isEmpty()) {
                containerTemplate = "Containers/{pkg}";
            }

            String c = containerTemplate.replace("{pkg}", sPackageName);
            if (c.startsWith("/")) {
                sContainerRoot = c;
            } else {
                sContainerRoot = "/storage/emulated/0/" + c;
            }

            sRedirectInternal = config.optBoolean("redirectInternal", true);
            sRedirectExternal = config.optBoolean("redirectExternal", true);
            sRedirectDatabases = config.optBoolean("redirectDatabases", true);
            sRedirectPreferences = config.optBoolean("redirectPreferences", true);
            sRedirectConstructors = config.optBoolean("redirectConstructors", true);
            sLoggingEnabled = config.optBoolean("redirectLogging", false);

            sStorageSpoofing = config.optBoolean("storageSpoofing", config.optBoolean("storageRedirectEnabled", true));
            if (sStorageSpoofing) {
                if (loadNativeStorageLibrary(context, config)) {
                    nativeInitStorageSpoof(
                            sContainerRoot, sPackageName, sRealInternalDataDir, sRedirectExternal, sRedirectInternal);
                    if (sNativeLibraryDir != null && !sNativeLibraryDir.isEmpty()) {
                        nativeAddNativeLibDir(sNativeLibraryDir);
                    }
                    for (String libDir : sNativeLibDirs) {
                        nativeAddNativeLibDir(libDir);
                    }
                    try {
                        XC_MethodHook loadHook = new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (sStorageSpoofing && sNativeSpoofLoaded) {
                                    try {
                                        nativeRescanNativeLibs();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        };
                        XposedBridge.hookAllMethods(Runtime.class, "loadLibrary0", loadHook);
                        XposedBridge.hookAllMethods(Runtime.class, "load0", loadHook);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to hook Runtime.load methods", t);
                    }
                    Log.i(TAG, "Native storage spoofing activated for " + sPackageName + " -> " + sContainerRoot);
                } else {
                    Log.e(TAG, "Failed to load native storage library, falling back to legacy redirection");
                    sStorageSpoofing = false;
                }
            }

            sCustomRules.clear();
            JSONArray customRulesArr = config.optJSONArray("customRules");
            if (customRulesArr != null) {
                for (int i = 0; i < customRulesArr.length(); i++) {
                    Object item = customRulesArr.opt(i);
                    String virt = null;
                    String real = null;
                    int flags = 0;
                    if (item instanceof JSONObject) {
                        JSONObject ruleObj = (JSONObject) item;
                        if (!ruleObj.optBoolean("enabled", true)) continue;
                        String rulePkg = ruleObj.optString("package", null);
                        if (rulePkg != null
                                && !rulePkg.isEmpty()
                                && !rulePkg.equals("*")
                                && !rulePkg.equals("{pkg}")
                                && !rulePkg.equals(sPackageName)) {
                            continue;
                        }
                        virt = ruleObj.optString("virtual", "");
                        real = ruleObj.optString("real", "");
                        if (ruleObj.optBoolean("readOnly", false)) {
                            flags |= 1;
                        }
                    } else if (item instanceof String) {
                        String ruleStr = (String) item;
                        int eq = ruleStr.indexOf('=');
                        if (eq > 0) {
                            virt = ruleStr.substring(0, eq).trim();
                            real = ruleStr.substring(eq + 1).trim();
                        }
                    }
                    if (virt != null && !virt.isEmpty() && real != null && !real.isEmpty()) {
                        String vExp = virt.replace("{pkg}", sPackageName).replace("{container}", sContainerRoot);
                        String rExp = real.replace("{pkg}", sPackageName).replace("{container}", sContainerRoot);
                        String normV = normalizePath(vExp);
                        String normR = normalizePath(rExp);
                        sCustomRules.add(new JavaCustomRule(normV, normR, flags));

                        // Synthesize shared storage and container aliases
                        if (sContainerRoot != null
                                && !sContainerRoot.isEmpty()
                                && pathMatchesPrefix(normV, sContainerRoot)) {
                            String sub = normV.substring(sContainerRoot.length());
                            String cData = sContainerRoot + "/data/data/" + sPackageName;
                            if (pathMatchesPrefix(normV, cData)) {
                                String dataSub = normV.substring(cData.length());
                                String internalBase = (sRealInternalDataDir != null && !sRealInternalDataDir.isEmpty())
                                        ? sRealInternalDataDir
                                        : ("/data/user/0/" + sPackageName);
                                sCustomRules.add(
                                        new JavaCustomRule(normalizePath(internalBase + dataSub), normR, flags));
                                sCustomRules.add(new JavaCustomRule(
                                        normalizePath("/data/data/" + sPackageName + dataSub), normR, flags));
                            } else {
                                String unmappedShared = normalizePath("/storage/emulated/0" + sub);
                                if (!unmappedShared.equals(normV)) {
                                    sCustomRules.add(new JavaCustomRule(unmappedShared, normR, flags));
                                    sCustomRules.add(new JavaCustomRule(normalizePath("/sdcard" + sub), normR, flags));
                                    sCustomRules.add(new JavaCustomRule(
                                            normalizePath("/storage/self/primary" + sub), normR, flags));
                                    sCustomRules.add(
                                            new JavaCustomRule(normalizePath("/mnt/sdcard" + sub), normR, flags));
                                }
                            }
                        } else if (pathMatchesPrefix(normV, "/storage/emulated/0")
                                && sContainerRoot != null
                                && !sContainerRoot.isEmpty()) {
                            String sub = normV.substring("/storage/emulated/0".length());
                            String cVariant = normalizePath(sContainerRoot + sub);
                            if (!cVariant.equals(normV)) {
                                sCustomRules.add(new JavaCustomRule(cVariant, normR, flags));
                            }
                            sCustomRules.add(new JavaCustomRule(normalizePath("/sdcard" + sub), normR, flags));
                            sCustomRules.add(
                                    new JavaCustomRule(normalizePath("/storage/self/primary" + sub), normR, flags));
                            sCustomRules.add(new JavaCustomRule(normalizePath("/mnt/sdcard" + sub), normR, flags));
                        }

                        if (sStorageSpoofing && sNativeSpoofLoaded) {
                            try {
                                nativeAddCustomRule(normV, normR, flags);
                            } catch (Throwable t) {
                                Log.w(
                                        TAG,
                                        "Failed to register custom rule in native layer: " + normV + " -> " + normR,
                                        t);
                            }
                        }
                    }
                }
                java.util.Collections.sort(
                        sCustomRules, (a, b) -> Integer.compare(b.virtualPrefix.length(), a.virtualPrefix.length()));
                for (JavaCustomRule rule : sCustomRules) {
                    ensureDirExists(new File(rule.realPrefix));
                }
                Log.i(TAG, "Loaded " + sCustomRules.size() + " custom storage rules for " + sPackageName);
            }

            Log.i(
                    TAG,
                    "Activating Storage Redirection for " + sPackageName + " -> " + sContainerRoot + " (real dataDir: "
                            + sRealInternalDataDir + ", nativeLibDir: " + sNativeLibraryDir + ")");

            // Ensure root container exists
            ensureDirExists(new File(sContainerRoot));

            // Install Activity & Application hooks to prompt MANAGE_EXTERNAL_STORAGE on foreground launch
            installActivityHooks();
            if (context instanceof Application) {
                registerAppLifecycle((Application) context);
            }

            // Install hooks
            hookContextStorage();
            hookEnvironmentStorage();
            hookClassLoaderStorage();
            if (sRedirectConstructors) {
                hookFileConstructors();
                hookStreamConstructors();
            }
            if (sRedirectDatabases) {
                hookDatabaseOpeners();
            }

            // Generic discovery & safe migration of any native libraries previously extracted into container
            scanAndSyncNativeLibraries(context);

            Log.i(TAG, "Storage Redirection hooks installed successfully");
        }
    }

    public static boolean isNativeLibLocation(String path) {
        if (path == null || path.isEmpty()) return false;

        // 1. Explicit native library file extensions or versioned/temporary library patterns
        if (path.endsWith(".so") || path.contains(".so.") || path.endsWith(".so.tmp") || path.contains(".so-")) {
            return true;
        }

        // 2. Application's primary native library directory
        if (sNativeLibraryDir != null && !sNativeLibraryDir.isEmpty()) {
            if (pathMatchesPrefix(path, sNativeLibraryDir)) {
                return true;
            }
        }

        // 3. Known / discovered native library directories (both internal and container mirrors)
        for (String libDir : sNativeLibDirs) {
            if (pathMatchesPrefix(path, libDir)) {
                return true;
            }
            String containerMirror = toContainerPath(libDir);
            if (containerMirror != null && !containerMirror.isEmpty() && pathMatchesPrefix(path, containerMirror)) {
                return true;
            }
        }

        // 4. Standard native library directory patterns under private data
        if (path.contains("/lib/")
                || path.endsWith("/lib")
                || path.contains("/libs/")
                || path.endsWith("/libs")
                || path.contains("/app_lib")
                || path.contains("/code_cache")) {
            return true;
        }

        // 5. Check ELF header if file exists on disk
        return isElfBinary(path);
    }

    private static boolean isElfBinary(String path) {
        if (path == null || path.isEmpty()) return false;
        boolean wasInHook = sInHook.get();
        sInHook.set(Boolean.TRUE);
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < 4) return false;
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] header = new byte[4];
                if (fis.read(header) == 4) {
                    return header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
                }
            }
        } catch (Throwable ignored) {
        } finally {
            sInHook.set(wasInHook);
        }
        return false;
    }

    private static boolean isDexOrZipBinary(String path) {
        if (path == null || path.isEmpty()) return false;
        boolean wasInHook = sInHook.get();
        sInHook.set(Boolean.TRUE);
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < 4) return false;
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] header = new byte[4];
                if (fis.read(header) == 4) {
                    // DEX magic: 0x64, 0x65, 0x78, 0x0A ("dex\n")
                    if (header[0] == 0x64 && header[1] == 0x65 && header[2] == 0x78 && header[3] == 0x0A) {
                        return true;
                    }
                    // ZIP / JAR magic: 0x50, 0x4B, 0x03, 0x04 ("PK\003\004")
                    if (header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            sInHook.set(wasInHook);
        }
        return false;
    }

    private static void registerParentNativeLibDir(String filePath) {
        try {
            if (filePath == null) return;
            if (!filePath.endsWith(".so") && !filePath.contains(".so.") && !isElfBinary(filePath)) {
                return;
            }
            File f = new File(filePath);
            File parent = f.getParentFile();
            if (parent != null) {
                String parentPath = normalizePath(parent.getPath());
                String internalParent = unmapContainerPath(parentPath);
                if (internalParent != null && !internalParent.isEmpty() && !sNativeLibDirs.contains(internalParent)) {
                    sNativeLibDirs.add(internalParent);
                    if (sStorageSpoofing && sNativeSpoofLoaded) {
                        try {
                            nativeAddNativeLibDir(internalParent);
                        } catch (Throwable ignored) {
                        }
                    }
                    Log.i(TAG, "Registered dynamic native library directory: " + internalParent);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String unmapContainerPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }
        String path = normalizePath(rawPath);
        for (JavaCustomRule rule : sCustomRules) {
            if (pathMatchesPrefix(path, rule.realPrefix)) {
                String sub = path.substring(rule.realPrefix.length());
                String virt = rule.virtualPrefix;
                if (sContainerRoot != null && !sContainerRoot.isEmpty() && pathMatchesPrefix(virt, sContainerRoot)) {
                    virt = "/storage/emulated/0" + virt.substring(sContainerRoot.length());
                }
                return normalizePath(virt + sub);
            }
        }
        if (sContainerRoot == null || sContainerRoot.isEmpty() || !path.startsWith(sContainerRoot)) {
            return path;
        }

        String pkg = sPackageName;
        if (pkg == null || pkg.isEmpty()) return path;

        String internalRoot = (sRealInternalDataDir != null && !sRealInternalDataDir.isEmpty())
                ? sRealInternalDataDir
                : ("/data/user/0/" + pkg);

        String cDataData = normalizePath(sContainerRoot + "/data/data/" + pkg);
        String cDataUser0 = normalizePath(sContainerRoot + "/data/user/0/" + pkg);

        if (pathMatchesPrefix(path, cDataData)) {
            String sub = path.substring(cDataData.length());
            return normalizePath(internalRoot + sub);
        }
        if (pathMatchesPrefix(path, cDataUser0)) {
            String sub = path.substring(cDataUser0.length());
            return normalizePath(internalRoot + sub);
        }

        return path;
    }

    public static String toContainerPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }
        String path = normalizePath(rawPath);
        for (JavaCustomRule rule : sCustomRules) {
            if (pathMatchesPrefix(path, rule.virtualPrefix)) {
                return normalizePath(rule.realPrefix + path.substring(rule.virtualPrefix.length()));
            }
        }
        if (sContainerRoot == null || sContainerRoot.isEmpty()) {
            return rawPath;
        }
        String pkg = sPackageName;
        if (pkg == null || pkg.isEmpty()) return path;

        String p1 = "/data/user/0/" + pkg;
        String p2 = "/data/data/" + pkg;

        if (pathMatchesPrefix(path, p1)) {
            return normalizePath(sContainerRoot + "/data/data/" + pkg + path.substring(p1.length()));
        }
        if (pathMatchesPrefix(path, p2)) {
            return normalizePath(sContainerRoot + "/data/data/" + pkg + path.substring(p2.length()));
        }
        return path;
    }

    private static void copyFile(File src, File dest) throws java.io.IOException {
        boolean wasInHook = sInHook.get();
        sInHook.set(Boolean.TRUE);
        try {
            ensureParentDirs(dest.getPath());
            try (FileInputStream fis = new FileInputStream(src);
                    FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = fis.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
            }
        } finally {
            sInHook.set(wasInHook);
        }
    }

    private static void scanAndSyncNativeLibraries(Context context) {
        try {
            if (context == null || context.getApplicationInfo() == null) return;

            // 1. Scan real internal storage for existing native libraries & register their directories
            if (sRealInternalDataDir != null && !sRealInternalDataDir.isEmpty()) {
                File internalDir = new File(sRealInternalDataDir);
                if (internalDir.exists() && internalDir.isDirectory()) {
                    scanInternalLibDirs(internalDir, 0);
                }
            }

            // 2. Scan container storage for existing native libraries, register directories, and safely sync missing
            // files
            if (sContainerRoot != null
                    && !sContainerRoot.isEmpty()
                    && sPackageName != null
                    && !sPackageName.isEmpty()) {
                String containerPkgDir = sContainerRoot + "/data/data/" + sPackageName;
                File cDir = new File(containerPkgDir);
                if (cDir.exists() && cDir.isDirectory()) {
                    syncContainerNativeLibs(cDir, 0);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanAndSyncNativeLibraries failed: " + t.getMessage(), t);
        }
    }

    private static void scanInternalLibDirs(File dir, int depth) {
        if (dir == null || !dir.exists() || depth > 5) return;
        boolean wasInHook = sInHook.get();
        sInHook.set(Boolean.TRUE);
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) {
                    scanInternalLibDirs(f, depth + 1);
                } else if (f.isFile()) {
                    String name = f.getName();
                    if (name.endsWith(".so") || name.contains(".so.") || isElfBinary(f.getPath())) {
                        File parent = f.getParentFile();
                        if (parent != null) {
                            String p = normalizePath(parent.getPath());
                            if (!sNativeLibDirs.contains(p)) {
                                sNativeLibDirs.add(p);
                                if (sStorageSpoofing && sNativeSpoofLoaded) {
                                    try {
                                        nativeAddNativeLibDir(p);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                Log.i(TAG, "Discovered existing internal native lib dir: " + p);
                            }
                        }
                    }
                }
            }
        } finally {
            sInHook.set(wasInHook);
        }
    }

    private static void syncContainerNativeLibs(File dir, int depth) {
        if (dir == null || !dir.exists() || depth > 5) return;
        boolean wasInHook = sInHook.get();
        sInHook.set(Boolean.TRUE);
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isDirectory()) {
                    syncContainerNativeLibs(f, depth + 1);
                } else if (f.isFile()) {
                    String name = f.getName();
                    boolean isNative = name.endsWith(".so") || name.contains(".so.") || isElfBinary(f.getPath());
                    boolean isCode = name.endsWith(".dex") || name.endsWith(".jar") || isDexOrZipBinary(f.getPath())
                            || f.getPath().contains("/sdk/plugin") || f.getPath().contains("/plugin/") || f.getPath().contains("/app_dex");
                    if (isNative || isCode) {
                        File parent = f.getParentFile();
                        if (parent != null && isNative) {
                            String unmappedParent = unmapContainerPath(normalizePath(parent.getPath()));
                            if (unmappedParent != null
                                    && !unmappedParent.isEmpty()
                                    && !sNativeLibDirs.contains(unmappedParent)) {
                                sNativeLibDirs.add(unmappedParent);
                                if (sStorageSpoofing && sNativeSpoofLoaded) {
                                    try {
                                        nativeAddNativeLibDir(unmappedParent);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                Log.i(TAG, "Discovered native library directory from container: " + unmappedParent);
                            }
                        }

                        String unmappedPath = unmapContainerPath(normalizePath(f.getPath()));
                        if (unmappedPath != null && !unmappedPath.isEmpty()) {
                            File dest = new File(unmappedPath);
                            if (!dest.exists() || dest.length() != f.length()) {
                                try {
                                    copyFile(f, dest);
                                    dest.setReadable(true, false);
                                    dest.setWritable(true, true);
                                    dest.setExecutable(true, false);
                                    if (dest.getParentFile() != null) {
                                        dest.getParentFile().setReadable(true, false);
                                        dest.getParentFile().setWritable(true, true);
                                        dest.getParentFile().setExecutable(true, false);
                                    }
                                    Log.i(
                                            TAG,
                                            "Migrated internal asset from container to internal storage: "
                                                    + dest.getPath());
                                } catch (Throwable t) {
                                    Log.w(
                                            TAG,
                                            "Failed to migrate asset " + f.getPath() + " -> " + dest.getPath(),
                                            t);
                                }
                            }
                            // Safely remove stale file from container once safely in internal storage
                            if (dest.exists() && dest.length() == f.length()) {
                                try {
                                    f.delete();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            sInHook.set(wasInHook);
        }
    }

    public static boolean isExempt(String path) {
        if (path == null) return true;
        if (isNativeLibLocation(path)) {
            return true;
        }
        if (path.contains("/code_cache")
                || path.contains("/app_lib")
                || path.contains("/app_webview")
                || path.contains("/app_lspatch")
                || path.contains("/lspatch/")
                || path.contains("/lib/")
                || path.contains("/files/sdk/plugin")
                || path.contains("/files/sdk")
                || path.contains("/sdk/plugin")
                || path.contains("/plugin/")
                || path.contains("/plugins/")
                || path.contains("/app_plugin")
                || path.contains("/app_plugins")
                || path.contains("/app_dex")
                || path.contains("/app_p_dex")
                || path.contains("/dex/")
                || path.endsWith("/dex")
                || path.contains("/dalvik-cache")
                || path.endsWith(".dex")
                || path.endsWith(".jar")
                || path.endsWith(".apk")
                || path.endsWith(".odex")
                || path.endsWith(".vdex")
                || path.endsWith(".oat")) {
            return true;
        }

        // Exempt DEX / ZIP / JAR binary payloads in app's internal storage or container data mirror
        if (path.contains("/data/data/") || path.contains("/data/user/")
                || (sContainerRoot != null && !sContainerRoot.isEmpty() && path.startsWith(sContainerRoot))) {
            if (isDexOrZipBinary(path)) {
                return true;
            }
        }

        // Google Play Services, MicroG & Play Games Authentication data MUST stay in private internal storage
        // to prevent SQLite WAL shared-memory mmap lock crashes, FileProvider root failures, and token errors on FUSE.
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("com.google.android.gms")
                || lower.contains("app.revanced.android.gms")
                || lower.contains("org.microg")
                || lower.contains("default_google_sign_in")
                || lower.contains("google_app_measurement")
                || lower.contains("games_signed_in")
                || lower.contains("games_signin")
                || lower.contains("playgames")
                || lower.contains("google_sign_in")
                || lower.contains("gservices")
                || lower.contains("androidx.work.workdb")
                || lower.contains("auth_state");
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        // Never canonicalize relative paths against root filesystem (e.g. 'foo.db' -> '/foo.db')
        if (!path.startsWith("/") && !path.startsWith("\\")) {
            return path;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (Throwable t) {
            return new File(path).getAbsolutePath();
        }
    }

    private static boolean pathMatchesPrefix(String path, String prefix) {
        if (path.equals(prefix)) return true;
        if (path.startsWith(prefix)) {
            char next = path.charAt(prefix.length());
            return next == '/' || next == '\\';
        }
        return false;
    }

    private static void logRedirection(String action, String original, String mapped) {
        if (!sLoggingEnabled) return;
        try {
            long now = System.currentTimeMillis();
            if (now - sLastLogTime < LOG_MIN_INTERVAL_MS) return;
            sLastLogTime = now;

            File logFile = new File(sContainerRoot, "redirect.log");
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                String line = "[" + new java.util.Date(now) + "] " + action + ": " + original
                        + (mapped != null && !mapped.equals(original) ? " -> " + mapped : "") + "\n";
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isAncestorOfRealPrefix(String path) {
        if (path == null
                || path.isEmpty()
                || path.equals("/")
                || path.equals("/storage")
                || path.equals("/storage/emulated")
                || path.equals("/storage/emulated/0")
                || path.equals("/sdcard")
                || path.equals("/storage/self/primary")
                || path.equals("/mnt/sdcard")) {
            return false;
        }
        for (JavaCustomRule rule : sCustomRules) {
            if (rule.realPrefix.length() > path.length()
                    && rule.realPrefix.startsWith(path)
                    && rule.realPrefix.charAt(path.length()) == '/') {
                return true;
            }
        }
        return false;
    }

    public static String canonicalizeAlias(String path) {
        if (path == null || path.isEmpty()) return path;
        String[] aliases = {"/sdcard", "/storage/self/primary", "/mnt/sdcard", "/mnt/user/0/primary", "/data/media/0"};
        for (String alias : aliases) {
            if (pathMatchesPrefix(path, alias)) {
                return "/storage/emulated/0" + path.substring(alias.length());
            }
        }
        return path;
    }

    public static String toRealPathSafe(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return rawPath;
        if (sNativeSpoofLoaded) {
            try {
                String real = nativeToRealPath(rawPath);
                if (real != null && !real.isEmpty()) return real;
            } catch (Throwable ignored) {
            }
        }
        return mapPath(rawPath);
    }

    public static File findExistingAncestor(File file) {
        if (file == null) return null;
        File cur = file;
        while (cur != null && !cur.exists()) {
            cur = cur.getParentFile();
        }
        if (cur != null && cur.exists()) {
            return cur;
        }
        if (sContainerRoot != null && !sContainerRoot.isEmpty()) {
            File root = new File(sContainerRoot);
            if (root.exists()) return root;
        }
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) return ext;
        } catch (Throwable ignored) {
        }
        File defStorage = new File("/storage/emulated/0");
        if (defStorage.exists()) return defStorage;
        return null;
    }

    public static String mapPath(String rawPath) {
        if (sStorageSpoofing || rawPath == null || rawPath.isEmpty()) return rawPath;
        if (sInHook.get()) return rawPath;

        sInHook.set(Boolean.TRUE);
        try {
            String path = normalizePath(rawPath);

            // 1. Idempotency guard for custom rules real destinations & their ancestors
            for (JavaCustomRule rule : sCustomRules) {
                if (pathMatchesPrefix(path, rule.realPrefix)) {
                    return path;
                }
            }
            if (isAncestorOfRealPrefix(path)) {
                return path;
            }

            // 2. Custom rules (HIGHEST PRECEDENCE - SUPERIOR TO CONTAINER & EXEMPTIONS)
            for (JavaCustomRule rule : sCustomRules) {
                if (pathMatchesPrefix(path, rule.virtualPrefix)) {
                    String mapped = rule.realPrefix + path.substring(rule.virtualPrefix.length());
                    ensureParentDirs(mapped);
                    logRedirection("CUSTOM_RULE", rawPath, mapped);
                    return mapped;
                }
            }

            // Also check aliases (/sdcard, /storage/self/primary, etc.)
            String aliased = canonicalizeAlias(path);
            if (!aliased.equals(path)) {
                for (JavaCustomRule rule : sCustomRules) {
                    if (pathMatchesPrefix(aliased, rule.realPrefix)) {
                        return aliased;
                    }
                }
                if (isAncestorOfRealPrefix(aliased)) {
                    return aliased;
                }
                for (JavaCustomRule rule : sCustomRules) {
                    if (pathMatchesPrefix(aliased, rule.virtualPrefix)) {
                        String mapped = rule.realPrefix + aliased.substring(rule.virtualPrefix.length());
                        ensureParentDirs(mapped);
                        logRedirection("CUSTOM_RULE", rawPath, mapped);
                        return mapped;
                    }
                }
            }

            // 3. Normal path exemptions: native libraries and protected directories
            if (isNativeLibLocation(path) || isExempt(path) || isNativeLibLocation(aliased) || isExempt(aliased)) {
                if (isNativeLibLocation(path)) {
                    registerParentNativeLibDir(path);
                }
                return path;
            }

            // 4. Container path check: do NOT return early before checking custom rules & exemptions
            if (sContainerRoot != null
                    && !sContainerRoot.isEmpty()
                    && (path.startsWith(sContainerRoot) || aliased.startsWith(sContainerRoot))) {
                String cPath = path.startsWith(sContainerRoot) ? path : aliased;
                for (JavaCustomRule rule : sCustomRules) {
                    if (pathMatchesPrefix(cPath, rule.virtualPrefix)) {
                        String mapped = rule.realPrefix + cPath.substring(rule.virtualPrefix.length());
                        ensureParentDirs(mapped);
                        logRedirection("CUSTOM_RULE", rawPath, mapped);
                        return mapped;
                    }
                }
                String unmapped = unmapContainerPath(cPath);
                if (isNativeLibLocation(cPath)
                        || isNativeLibLocation(unmapped)
                        || isExempt(cPath)
                        || isExempt(unmapped)) {
                    if (isNativeLibLocation(cPath) || isNativeLibLocation(unmapped)) {
                        registerParentNativeLibDir(unmapped);
                    }
                    ensureParentDirs(unmapped);
                    logRedirection("EXEMPT_UNMAP", rawPath, unmapped);
                    return unmapped;
                }
                return path;
            }

            String root = sContainerRoot;
            String pkg = sPackageName;

            // 5. Internal Storage: /data/user/0/<pkg> or /data/data/<pkg>
            if (sRedirectInternal && pkg != null && !pkg.isEmpty()) {
                String p1 = "/data/user/0/" + pkg;
                String p2 = "/data/data/" + pkg;
                if (pathMatchesPrefix(path, p1)) {
                    String mapped = root + "/data/data/" + pkg + path.substring(p1.length());
                    for (JavaCustomRule rule : sCustomRules) {
                        if (pathMatchesPrefix(mapped, rule.virtualPrefix)) {
                            mapped = rule.realPrefix + mapped.substring(rule.virtualPrefix.length());
                            break;
                        }
                    }
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
                if (pathMatchesPrefix(path, p2)) {
                    String mapped = root + "/data/data/" + pkg + path.substring(p2.length());
                    for (JavaCustomRule rule : sCustomRules) {
                        if (pathMatchesPrefix(mapped, rule.virtualPrefix)) {
                            mapped = rule.realPrefix + mapped.substring(rule.virtualPrefix.length());
                            break;
                        }
                    }
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
            }

            // 6. External Storage
            if (sRedirectExternal) {
                String sd1 = "/storage/emulated/0";
                String sd2 = "/sdcard";

                if (pkg != null && !pkg.isEmpty()) {
                    String ext1 = sd1 + "/Android/data/" + pkg;
                    String ext2 = sd2 + "/Android/data/" + pkg;
                    if (pathMatchesPrefix(path, ext1)) {
                        String mapped = root + "/Android/data/" + pkg + path.substring(ext1.length());
                        for (JavaCustomRule rule : sCustomRules) {
                            if (pathMatchesPrefix(mapped, rule.virtualPrefix)) {
                                mapped = rule.realPrefix + mapped.substring(rule.virtualPrefix.length());
                                break;
                            }
                        }
                        ensureParentDirs(mapped);
                        logRedirection("REDIRECT", rawPath, mapped);
                        return mapped;
                    }
                    if (pathMatchesPrefix(path, ext2)) {
                        String mapped = root + "/Android/data/" + pkg + path.substring(ext2.length());
                        for (JavaCustomRule rule : sCustomRules) {
                            if (pathMatchesPrefix(mapped, rule.virtualPrefix)) {
                                mapped = rule.realPrefix + mapped.substring(rule.virtualPrefix.length());
                                break;
                            }
                        }
                        ensureParentDirs(mapped);
                        logRedirection("REDIRECT", rawPath, mapped);
                        return mapped;
                    }
                }

                // Never capture other apps' private data/obb
                if (path.startsWith(sd1 + "/Android/data/")
                        || path.startsWith(sd2 + "/Android/data/")
                        || path.startsWith(sd1 + "/Android/obb/")
                        || path.startsWith(sd2 + "/Android/obb/")) {
                    return rawPath;
                }

                if (pathMatchesPrefix(path, sd1)) {
                    String mapped = root + path.substring(sd1.length());
                    for (JavaCustomRule rule : sCustomRules) {
                        if (pathMatchesPrefix(mapped, rule.virtualPrefix)) {
                            mapped = rule.realPrefix + mapped.substring(rule.virtualPrefix.length());
                            break;
                        }
                    }
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
                if (pathMatchesPrefix(path, sd2)) {
                    String mapped = root + path.substring(sd2.length());
                    for (JavaCustomRule rule : sCustomRules) {
                        if (pathMatchesPrefix(mapped, rule.virtualPrefix)) {
                            mapped = rule.realPrefix + mapped.substring(rule.virtualPrefix.length());
                            break;
                        }
                    }
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
            }

            return path;
        } finally {
            sInHook.set(Boolean.FALSE);
        }
    }

    public static File mapFile(File file) {
        if (file == null) return null;
        String mapped = mapPath(file.getPath());
        if (mapped.equals(file.getPath())) return file;
        return new File(mapped);
    }

    private static void ensureParentDirs(String filePath) {
        try {
            File f = new File(filePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureDirExists(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
    }

    // -------------------------------------------------------------
    // Permission Management & Foreground Activity Hooks
    // -------------------------------------------------------------

    private static volatile long sLastPromptTime = 0;

    private static void registerAppLifecycle(Application app) {
        if (app == null) return;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(Activity activity) {
                    checkAndPromptPermission(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    checkAndPromptPermission(activity);
                }

                @Override
                public void onActivityCreated(Activity a, android.os.Bundle b) {}

                @Override
                public void onActivityPaused(Activity a) {}

                @Override
                public void onActivityStopped(Activity a) {}

                @Override
                public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}

                @Override
                public void onActivityDestroyed(Activity a) {}
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register activity lifecycle callbacks: " + t.getMessage());
        }
    }

    private static void installActivityHooks() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onStart", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    checkAndPromptPermission((Activity) param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    checkAndPromptPermission((Activity) param.thisObject);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Activity lifecycle: " + t.getMessage());
        }

        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Application) {
                        registerAppLifecycle((Application) param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Application.onCreate: " + t.getMessage());
        }
    }

    public static void checkAndPromptPermission(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                if (Environment.isExternalStorageManager()) return;

                long now = System.currentTimeMillis();
                if (now - sLastPromptTime < 8000) return;
                sLastPromptTime = now;

                Log.i(
                        TAG,
                        "Prompting user for MANAGE_EXTERNAL_STORAGE permission from Activity: "
                                + activity.getClass().getName());

                activity.getWindow().getDecorView().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()))
                                return;
                            if (Environment.isExternalStorageManager()) return;

                            try {
                                new AlertDialog.Builder(activity)
                                        .setTitle("Storage Redirection Permission")
                                        .setMessage(
                                                "Storage Redirection is enabled for this app. To access its containerized files without crashes, 'All files access' permission is required.\n\nPlease enable it in the next screen.")
                                        .setPositiveButton("Grant", (dialog, which) -> {
                                            launchManageAppAllFilesIntent(activity);
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .setCancelable(false)
                                        .show();
                            } catch (Throwable t) {
                                Log.w(
                                        TAG,
                                        "AlertDialog failed (e.g. game window theme), launching Intent directly: "
                                                + t.getMessage());
                                Toast.makeText(
                                                activity,
                                                "Please grant All Files Access for Storage Redirection",
                                                Toast.LENGTH_LONG)
                                        .show();
                                launchManageAppAllFilesIntent(activity);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Error showing permission prompt: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Error checking external storage manager: " + t.getMessage());
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            try {
                if (activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    long now = System.currentTimeMillis();
                    if (now - sLastPromptTime < 8000) return;
                    sLastPromptTime = now;
                    activity.requestPermissions(
                            new String[] {
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            },
                            1001);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void launchManageAppAllFilesIntent(Context context) {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + sPackageName));
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t1) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                if (!(context instanceof Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
            } catch (Throwable t2) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + sPackageName));
                    if (!(context instanceof Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    context.startActivity(intent);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Context Storage Hooks
    // -------------------------------------------------------------

    private static void hookContextStorage() {
        java.util.List<Class<?>> contextClasses = new java.util.ArrayList<>();
        contextClasses.add(ContextWrapper.class);
        try {
            contextClasses.add(Class.forName("android.app.ContextImpl"));
        } catch (Throwable ignored) {
        }

        for (Class<?> clazz : contextClasses) {
            try {
                if (sRedirectInternal) {
                    final String internalRoot = (sRealInternalDataDir != null && !sRealInternalDataDir.isEmpty())
                            ? sRealInternalDataDir
                            : ("/data/user/0/" + sPackageName);

                    XposedBridge.hookAllMethods(clazz, "getFilesDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File containerDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/files");
                            ensureDirExists(containerDir);
                            if (sStorageSpoofing) {
                                param.setResult(new File(internalRoot, "files"));
                            } else {
                                param.setResult(containerDir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getCacheDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File containerDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/cache");
                            ensureDirExists(containerDir);
                            if (sStorageSpoofing) {
                                param.setResult(new File(internalRoot, "cache"));
                            } else {
                                param.setResult(containerDir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getDataDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File containerDir = new File(sContainerRoot + "/data/data/" + sPackageName);
                            ensureDirExists(containerDir);
                            if (sStorageSpoofing) {
                                param.setResult(new File(internalRoot));
                            } else {
                                param.setResult(containerDir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getNoBackupFilesDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File containerDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/no_backup");
                            ensureDirExists(containerDir);
                            if (sStorageSpoofing) {
                                param.setResult(new File(internalRoot, "no_backup"));
                            } else {
                                param.setResult(containerDir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            if (name == null) return;

                            File privateDir = new File(internalRoot, "app_" + name);
                            String privatePath = normalizePath(privateDir.getPath());

                            if (name.equals("lib")
                                    || name.equals("libs")
                                    || name.equals("jniLibs")
                                    || name.contains("dex")
                                    || name.contains("plugin")
                                    || isNativeLibLocation(privatePath)
                                    || isExempt(privatePath)) {
                                ensureDirExists(privateDir);
                                if (name.equals("lib")
                                        || name.equals("libs")
                                        || name.equals("jniLibs")
                                        || name.contains("lib")) {
                                    sNativeLibDirs.add(privatePath);
                                    if (sStorageSpoofing && sNativeSpoofLoaded) {
                                        try {
                                            nativeAddNativeLibDir(privatePath);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                                param.setResult(privateDir);
                                return;
                            }
                            File containerDir =
                                    new File(sContainerRoot + "/data/data/" + sPackageName + "/app_" + name);
                            ensureDirExists(containerDir);
                            if (sStorageSpoofing) {
                                param.setResult(new File(internalRoot, "app_" + name));
                            } else {
                                param.setResult(containerDir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getFileStreamPath", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            File containerDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/files");
                            ensureDirExists(containerDir);
                            if (sStorageSpoofing) {
                                param.setResult(new File(new File(internalRoot, "files"), name));
                            } else {
                                param.setResult(new File(containerDir, name));
                            }
                        }
                    });
                }

                if (sRedirectExternal) {
                    XposedBridge.hookAllMethods(clazz, "getExternalFilesDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String type = (String) param.args[0];
                            String sub = (type != null && !type.isEmpty()) ? "/" + type : "";
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/files" + sub);
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(
                                        new File("/storage/emulated/0/Android/data/" + sPackageName + "/files" + sub));
                            } else {
                                param.setResult(dir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getExternalFilesDirs", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String type = (String) param.args[0];
                            String sub = (type != null && !type.isEmpty()) ? "/" + type : "";
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/files" + sub);
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(new File[] {
                                    new File("/storage/emulated/0/Android/data/" + sPackageName + "/files" + sub)
                                });
                            } else {
                                param.setResult(new File[] {dir});
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getExternalCacheDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/cache");
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(
                                        new File("/storage/emulated/0/Android/data/" + sPackageName + "/cache"));
                            } else {
                                param.setResult(dir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getExternalCacheDirs", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/cache");
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(new File[] {
                                    new File("/storage/emulated/0/Android/data/" + sPackageName + "/cache")
                                });
                            } else {
                                param.setResult(new File[] {dir});
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getObbDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/obb/" + sPackageName);
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(new File("/storage/emulated/0/Android/obb/" + sPackageName));
                            } else {
                                param.setResult(dir);
                            }
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getObbDirs", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/obb/" + sPackageName);
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(
                                        new File[] {new File("/storage/emulated/0/Android/obb/" + sPackageName)});
                            } else {
                                param.setResult(new File[] {dir});
                            }
                        }
                    });
                }

                if (sRedirectDatabases) {
                    XposedBridge.hookAllMethods(clazz, "getDatabasePath", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            if (name == null || name.isEmpty() || isExempt(name)) {
                                return;
                            }
                            final String internalRoot =
                                    (sRealInternalDataDir != null && !sRealInternalDataDir.isEmpty())
                                            ? sRealInternalDataDir
                                            : ("/data/user/0/" + sPackageName);
                            if (sStorageSpoofing) {
                                File dbDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/databases");
                                ensureDirExists(dbDir);
                                if (name.startsWith("/") || name.startsWith("\\")) {
                                    param.setResult(new File(name));
                                } else {
                                    param.setResult(new File(new File(internalRoot, "databases"), name));
                                }
                                return;
                            }
                            if (name.startsWith("/") || name.startsWith("\\")) {
                                String mapped = mapPath(name);
                                ensureParentDirs(mapped);
                                param.setResult(new File(mapped));
                                return;
                            }
                            if (sContainerRoot != null && name.contains(sContainerRoot)) {
                                return;
                            }
                            File dbDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/databases");
                            ensureDirExists(dbDir);
                            param.setResult(new File(dbDir, name));
                        }
                    });
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error hooking Context methods on " + clazz.getName(), t);
            }
        }
    }

    // -------------------------------------------------------------
    // Environment Hooks
    // -------------------------------------------------------------

    private static void hookEnvironmentStorage() {
        if (!sRedirectExternal) return;
        try {
            XposedBridge.hookAllMethods(Environment.class, "getExternalStorageDirectory", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    File dir = new File(sContainerRoot);
                    ensureDirExists(dir);
                    if (sStorageSpoofing) {
                        param.setResult(new File("/storage/emulated/0"));
                    } else {
                        param.setResult(dir);
                    }
                }
            });

            XposedBridge.hookAllMethods(Environment.class, "getExternalStoragePublicDirectory", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String type = (String) param.args[0];
                    String target = "/storage/emulated/0" + (type != null && !type.isEmpty() ? "/" + type : "");
                    String mapped = mapPath(target);
                    File dir = new File(mapped);
                    ensureDirExists(dir);
                    if (sStorageSpoofing) {
                        param.setResult(new File("/storage/emulated/0", type != null ? type : ""));
                    } else {
                        param.setResult(dir);
                    }
                }
            });

            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    XposedBridge.hookAllMethods(Environment.class, "getStorageDirectory", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot);
                            ensureDirExists(dir);
                            if (sStorageSpoofing) {
                                param.setResult(new File("/storage/emulated/0"));
                            } else {
                                param.setResult(dir);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking Environment storage methods", t);
        }
    }

    // -------------------------------------------------------------
    // File & Stream Constructor Hooks
    // -------------------------------------------------------------

    private static void hookFileConstructors() {
        if (sStorageSpoofing) {
            Log.d(TAG, "Storage spoofing active: File constructors are pass-through");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(File.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args.length == 1 && param.args[0] instanceof String) {
                        String orig = (String) param.args[0];
                        String mapped = mapPath(orig);
                        if (!mapped.equals(orig)) {
                            param.args[0] = mapped;
                        }
                    } else if (param.args.length == 1 && param.args[0] instanceof java.net.URI) {
                        java.net.URI uri = (java.net.URI) param.args[0];
                        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                            String p = uri.getPath();
                            String mapped = mapPath(p);
                            if (mapped != null && !mapped.equals(p)) {
                                param.args[0] = new File(mapped).toURI();
                            }
                        }
                    } else if (param.args.length == 2) {
                        if (param.args[0] instanceof String && param.args[1] instanceof String) {
                            String parent = (String) param.args[0];
                            String child = (String) param.args[1];
                            if (child != null && (child.startsWith("/") || child.startsWith("\\"))) {
                                String mapped = mapPath(child);
                                if (!mapped.equals(child)) {
                                    param.args[0] = null;
                                    param.args[1] = mapped;
                                }
                            } else if (parent != null && !parent.isEmpty()) {
                                String full = parent + (parent.endsWith("/") ? "" : "/") + (child != null ? child : "");
                                String mapped = mapPath(full);
                                if (!mapped.equals(full)) {
                                    param.args[0] = null;
                                    param.args[1] = mapped;
                                }
                            }
                        } else if (param.args[0] instanceof File && param.args[1] instanceof String) {
                            File parent = (File) param.args[0];
                            String child = (String) param.args[1];
                            if (child != null && (child.startsWith("/") || child.startsWith("\\"))) {
                                String mapped = mapPath(child);
                                if (!mapped.equals(child)) {
                                    param.args[0] = null;
                                    param.args[1] = mapped;
                                }
                            } else if (parent != null
                                    && parent.getPath() != null
                                    && !parent.getPath().isEmpty()) {
                                String pPath = parent.getPath();
                                String full = pPath + (pPath.endsWith("/") ? "" : "/") + (child != null ? child : "");
                                String mapped = mapPath(full);
                                if (!mapped.equals(full)) {
                                    param.args[0] = null;
                                    param.args[1] = mapped;
                                }
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking File constructors", t);
        }
    }

    private static void hookStreamConstructors() {
        if (sStorageSpoofing) {
            Log.d(TAG, "Storage spoofing active: Stream constructors are pass-through");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(FileInputStream.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof File) {
                        param.args[0] = mapFile((File) param.args[0]);
                    } else if (param.args[0] instanceof String) {
                        param.args[0] = mapPath((String) param.args[0]);
                    }
                }
            });

            XposedBridge.hookAllConstructors(FileOutputStream.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof File) {
                        File f = mapFile((File) param.args[0]);
                        ensureParentDirs(f.getPath());
                        param.args[0] = f;
                    } else if (param.args[0] instanceof String) {
                        String mapped = mapPath((String) param.args[0]);
                        ensureParentDirs(mapped);
                        param.args[0] = mapped;
                    }
                }
            });

            XposedBridge.hookAllConstructors(RandomAccessFile.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof File) {
                        File f = mapFile((File) param.args[0]);
                        ensureParentDirs(f.getPath());
                        param.args[0] = f;
                    } else if (param.args[0] instanceof String) {
                        String mapped = mapPath((String) param.args[0]);
                        ensureParentDirs(mapped);
                        param.args[0] = mapped;
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking stream constructors", t);
        }
    }

    private static void hookDatabaseOpeners() {
        if (sStorageSpoofing) {
            Log.d(TAG, "Storage spoofing active: SQLiteDatabase openers handled natively by libsqlite PLT hooks");
            return;
        }
        try {
            XC_MethodHook dbHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof String) {
                        String path = (String) param.args[0];
                        if (isExempt(path)) return;
                        if (!path.startsWith("/") && !path.startsWith("\\")) {
                            File dbDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/databases");
                            ensureDirExists(dbDir);
                            path = new File(dbDir, path).getPath();
                        } else {
                            path = mapPath(path);
                        }
                        ensureParentDirs(path);
                        param.args[0] = path;
                    } else if (param.args[0] instanceof File) {
                        File f = (File) param.args[0];
                        if (f == null || isExempt(f.getPath())) return;
                        String path = f.getPath();
                        if (!path.startsWith("/") && !path.startsWith("\\")) {
                            File dbDir = new File(sContainerRoot + "/data/data/" + sPackageName + "/databases");
                            ensureDirExists(dbDir);
                            File mapped = new File(dbDir, path);
                            ensureParentDirs(mapped.getPath());
                            param.args[0] = mapped;
                        } else {
                            File mapped = mapFile(f);
                            ensureParentDirs(mapped.getPath());
                            param.args[0] = mapped;
                        }
                    }
                }
            };

            XposedBridge.hookAllMethods(SQLiteDatabase.class, "openDatabase", dbHook);
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "openOrCreateDatabase", dbHook);
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking SQLiteDatabase openers", t);
        }

        // Hook StatFs constructors and restat to map virtual paths and ensure existing ancestor
        try {
            XC_MethodHook statFsHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get()) return;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                        String original = (String) param.args[0];
                        if (original == null || original.isEmpty()) return;
                        String mapped = toRealPathSafe(original);
                        File target = new File(mapped);
                        if (!target.exists()) {
                            File existing = findExistingAncestor(target);
                            if (existing != null) {
                                param.args[0] = existing.getAbsolutePath();
                                return;
                            }
                        }
                        param.args[0] = mapped;
                    }
                }
            };
            XposedBridge.hookAllConstructors(android.os.StatFs.class, statFsHook);
            XposedBridge.hookAllMethods(android.os.StatFs.class, "restat", statFsHook);
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking StatFs", t);
        }

        // Hook File space query methods (getUsableSpace, getFreeSpace, getTotalSpace)
        try {
            XC_MethodHook fileSpaceHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sInHook.get()) return;
                    Object res = param.getResult();
                    if (res instanceof Long && ((Long) res) == 0L) {
                        File f = (File) param.thisObject;
                        if (f != null) {
                            sInHook.set(Boolean.TRUE);
                            try {
                                String path = f.getPath();
                                String mapped = toRealPathSafe(path);
                                File target = (mapped != null && !mapped.isEmpty()) ? new File(mapped) : f;
                                File existing = findExistingAncestor(target);
                                if (existing != null && existing.exists()) {
                                    String methodName = param.method != null ? param.method.getName() : "getUsableSpace";
                                    long space = 0L;
                                    if ("getUsableSpace".equals(methodName)) {
                                        space = existing.getUsableSpace();
                                    } else if ("getFreeSpace".equals(methodName)) {
                                        space = existing.getFreeSpace();
                                    } else if ("getTotalSpace".equals(methodName)) {
                                        space = existing.getTotalSpace();
                                    }
                                    if (space > 0L) {
                                        param.setResult(space);
                                    }
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                sInHook.set(Boolean.FALSE);
                            }
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(File.class, "getUsableSpace", fileSpaceHook);
            XposedBridge.hookAllMethods(File.class, "getFreeSpace", fileSpaceHook);
            XposedBridge.hookAllMethods(File.class, "getTotalSpace", fileSpaceHook);
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking File space methods", t);
        }
    }

    // -------------------------------------------------------------
    // ClassLoader & Dynamic Code Hooks
    // -------------------------------------------------------------

    private static void hookClassLoaderStorage() {
        try {
            XC_MethodHook classLoaderHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof String) {
                        String rawDexPath = (String) param.args[0];
                        String sanitized = sanitizeDexPath(rawDexPath);
                        if (sanitized != null && !sanitized.equals(rawDexPath)) {
                            param.args[0] = sanitized;
                            Log.i(TAG, "Sanitized ClassLoader dexPath: " + rawDexPath + " -> " + sanitized);
                        }
                    }
                    if (param.args.length >= 2 && param.args[1] instanceof String) {
                        String optDir = (String) param.args[1];
                        if (optDir != null && !optDir.isEmpty()) {
                            String sanitizedOpt = sanitizeDexDirectory(optDir);
                            if (sanitizedOpt != null && !sanitizedOpt.equals(optDir)) {
                                param.args[1] = sanitizedOpt;
                                Log.i(TAG, "Sanitized ClassLoader optimizedDirectory: " + optDir + " -> " + sanitizedOpt);
                            }
                        }
                    }
                }
            };

            try {
                XposedBridge.hookAllConstructors(BaseDexClassLoader.class, classLoaderHook);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook BaseDexClassLoader constructors: " + t.getMessage());
            }

            try {
                XposedBridge.hookAllConstructors(DexClassLoader.class, classLoaderHook);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook DexClassLoader constructors: " + t.getMessage());
            }

            try {
                XposedBridge.hookAllConstructors(PathClassLoader.class, classLoaderHook);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook PathClassLoader constructors: " + t.getMessage());
            }

            try {
                XposedBridge.hookAllConstructors(dalvik.system.DexFile.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (sInHook.get() || param.args == null || param.args.length == 0) return;
                        if (param.args[0] instanceof String) {
                            String p = (String) param.args[0];
                            String safe = ensureInternalDexFile(p);
                            if (safe != null && !safe.equals(p)) {
                                param.args[0] = safe;
                            }
                        } else if (param.args[0] instanceof File) {
                            File f = (File) param.args[0];
                            String p = f.getPath();
                            String safe = ensureInternalDexFile(p);
                            if (safe != null && !safe.equals(p)) {
                                param.args[0] = new File(safe);
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(dalvik.system.DexFile.class, "loadDex", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (sInHook.get() || param.args == null || param.args.length == 0) return;
                        if (param.args[0] instanceof String) {
                            String p = (String) param.args[0];
                            String safe = ensureInternalDexFile(p);
                            if (safe != null && !safe.equals(p)) {
                                param.args[0] = safe;
                            }
                        }
                        if (param.args.length > 1 && param.args[1] instanceof String) {
                            String opt = (String) param.args[1];
                            String safeOpt = sanitizeDexDirectory(opt);
                            if (safeOpt != null && !safeOpt.equals(opt)) {
                                param.args[1] = safeOpt;
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Failed to hook DexFile methods: " + t.getMessage());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error installing ClassLoader hooks: " + t.getMessage(), t);
        }
    }

    public static String sanitizeDexPath(String dexPath) {
        if (dexPath == null || dexPath.isEmpty()) return dexPath;
        String[] parts = dexPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            String safe = ensureInternalDexFile(p);
            try {
                File f = new File(safe);
                if (f.exists() && f.canWrite()) {
                    f.setReadOnly();
                }
            } catch (Throwable ignored) {
            }
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(safe != null ? safe : p);
        }
        return sb.toString();
    }

    public static String sanitizeDexDirectory(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) return dirPath;
        String unmapped = unmapContainerPath(dirPath);
        File d = new File(unmapped);
        ensureDirExists(d);
        try {
            d.setReadable(true, false);
            d.setWritable(true, true);
            d.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
        return unmapped;
    }

    public static String ensureInternalDexFile(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return rawPath;
        String path = normalizePath(rawPath);

        // 1. If path is inside container root or external storage: unmap to internal data
        String internalPath = unmapContainerPath(path);
        if (internalPath.startsWith("/storage/") || internalPath.startsWith("/sdcard/")) {
            // Path was not mapped to internal data; if it's on shared storage, ART will strictly reject it.
            // Cache it inside app's private code_cache
            String pkg = sPackageName != null ? sPackageName : "unknown";
            String base = (sRealInternalDataDir != null && !sRealInternalDataDir.isEmpty())
                    ? sRealInternalDataDir
                    : ("/data/user/0/" + pkg);
            File codeCache = new File(base, "code_cache/dex_plugins");
            ensureDirExists(codeCache);
            File source = new File(path);
            String safeName = Integer.toHexString(path.hashCode()) + "_" + source.getName();
            File dest = new File(codeCache, safeName);
            boolean wasInHook = sInHook.get();
            sInHook.set(Boolean.TRUE);
            try {
                if (source.exists() && (!dest.exists() || dest.length() != source.length())) {
                    copyFile(source, dest);
                }
                dest.setReadable(true, false);
                dest.setReadOnly();
                return dest.getAbsolutePath();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to copy external dex to code_cache: " + path, t);
            } finally {
                sInHook.set(wasInHook);
            }
            return path;
        }

        // 2. If internalPath is internal storage (/data/user/0/<pkg>/... or /data/data/<pkg>/...)
        File internalFile = new File(internalPath);
        File containerFile = new File(path);

        boolean wasInHook = sInHook.get();
        sInHook.set(Boolean.TRUE);
        try {
            // If container file exists and internal file is missing or out of date, sync it
            if (containerFile.exists() && containerFile.isFile()) {
                if (!internalFile.exists() || internalFile.length() != containerFile.length()) {
                    copyFile(containerFile, internalFile);
                    Log.i(TAG, "Migrated dynamic code/dex from container to internal storage: " + internalPath);
                }
            }
            if (internalFile.exists()) {
                internalFile.setReadOnly();
                File parent = internalFile.getParentFile();
                if (parent != null) {
                    parent.setReadable(true, false);
                    parent.setWritable(true, true);
                    parent.setExecutable(true, false);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureInternalDexFile sync failed for " + path + " -> " + internalPath, t);
        } finally {
            sInHook.set(wasInHook);
        }

        return internalPath;
    }
}
