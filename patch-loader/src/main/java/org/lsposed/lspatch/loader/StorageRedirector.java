package org.lsposed.lspatch.loader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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

    private static boolean sRedirectInternal = true;
    private static boolean sRedirectExternal = true;
    private static boolean sRedirectDatabases = true;
    private static boolean sRedirectPreferences = true;
    private static boolean sRedirectConstructors = true;
    private static boolean sLoggingEnabled = false;

    private static volatile long sLastLogTime = 0;
    private static final long LOG_MIN_INTERVAL_MS = 250;

    // Recursion guard to prevent infinite re-entrant hooks on File constructors
    private static final ThreadLocal<Boolean> sInHook = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void activate(Context context, JSONObject config) {
        if (sActivated || context == null) return;
        synchronized (StorageRedirector.class) {
            if (sActivated) return;
            sActivated = true;

            sPackageName = context.getPackageName();
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

            Log.i(TAG, "Activating Storage Redirection for " + sPackageName + " -> " + sContainerRoot);

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
            if (sRedirectConstructors) {
                hookFileConstructors();
                hookStreamConstructors();
            }
            if (sRedirectDatabases) {
                hookDatabaseOpeners();
            }

            Log.i(TAG, "Storage Redirection hooks installed successfully");
        }
    }

    public static boolean isExempt(String path) {
        if (path == null) return true;
        return path.contains("/code_cache") ||
               path.contains("/app_lib") ||
               path.contains("/app_webview") ||
               path.contains("/lib/") ||
               path.endsWith(".so") ||
               path.endsWith(".dex") ||
               path.endsWith(".apk");
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
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
                String line = "[" + new java.util.Date(now) + "] " + action + ": " + original +
                        (mapped != null && !mapped.equals(original) ? " -> " + mapped : "") + "\n";
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    public static String mapPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return rawPath;
        if (sInHook.get()) return rawPath;

        sInHook.set(Boolean.TRUE);
        try {
            String path = normalizePath(rawPath);
            if (sContainerRoot != null && !sContainerRoot.isEmpty() && path.startsWith(sContainerRoot)) {
                return path;
            }
            if (isExempt(path)) {
                return path;
            }

            String root = sContainerRoot;
            String pkg = sPackageName;

            // 1. Internal Storage: /data/user/0/<pkg> or /data/data/<pkg>
            if (sRedirectInternal && pkg != null && !pkg.isEmpty()) {
                String p1 = "/data/user/0/" + pkg;
                String p2 = "/data/data/" + pkg;
                if (pathMatchesPrefix(path, p1)) {
                    String mapped = root + "/data/data/" + pkg + path.substring(p1.length());
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
                if (pathMatchesPrefix(path, p2)) {
                    String mapped = root + "/data/data/" + pkg + path.substring(p2.length());
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
            }

            // 2. External Storage
            if (sRedirectExternal) {
                String sd1 = "/storage/emulated/0";
                String sd2 = "/sdcard";

                if (pkg != null && !pkg.isEmpty()) {
                    String ext1 = sd1 + "/Android/data/" + pkg;
                    String ext2 = sd2 + "/Android/data/" + pkg;
                    if (pathMatchesPrefix(path, ext1)) {
                        String mapped = root + "/Android/data/" + pkg + path.substring(ext1.length());
                        ensureParentDirs(mapped);
                        logRedirection("REDIRECT", rawPath, mapped);
                        return mapped;
                    }
                    if (pathMatchesPrefix(path, ext2)) {
                        String mapped = root + "/Android/data/" + pkg + path.substring(ext2.length());
                        ensureParentDirs(mapped);
                        logRedirection("REDIRECT", rawPath, mapped);
                        return mapped;
                    }
                }

                // Never capture other apps' private data/obb
                if (path.startsWith(sd1 + "/Android/data/") || path.startsWith(sd2 + "/Android/data/") ||
                    path.startsWith(sd1 + "/Android/obb/") || path.startsWith(sd2 + "/Android/obb/")) {
                    return rawPath;
                }

                if (pathMatchesPrefix(path, sd1)) {
                    String mapped = root + path.substring(sd1.length());
                    ensureParentDirs(mapped);
                    logRedirection("REDIRECT", rawPath, mapped);
                    return mapped;
                }
                if (pathMatchesPrefix(path, sd2)) {
                    String mapped = root + path.substring(sd2.length());
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
        } catch (Throwable ignored) {}
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

                @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
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

                Log.i(TAG, "Prompting user for MANAGE_EXTERNAL_STORAGE permission from Activity: " + activity.getClass().getName());

                activity.getWindow().getDecorView().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
                            if (Environment.isExternalStorageManager()) return;

                            try {
                                new AlertDialog.Builder(activity)
                                        .setTitle("Storage Redirection Permission")
                                        .setMessage("Storage Redirection is enabled for this app. To access its containerized files without crashes, 'All files access' permission is required.\n\nPlease enable it in the next screen.")
                                        .setPositiveButton("Grant", (dialog, which) -> {
                                            launchManageAppAllFilesIntent(activity);
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .setCancelable(false)
                                        .show();
                            } catch (Throwable t) {
                                Log.w(TAG, "AlertDialog failed (e.g. game window theme), launching Intent directly: " + t.getMessage());
                                Toast.makeText(activity, "Please grant All Files Access for Storage Redirection", Toast.LENGTH_LONG).show();
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
                if (activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    long now = System.currentTimeMillis();
                    if (now - sLastPromptTime < 8000) return;
                    sLastPromptTime = now;
                    activity.requestPermissions(new String[]{
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, 1001);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void launchManageAppAllFilesIntent(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + sPackageName));
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
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + sPackageName));
                    if (!(context instanceof Activity)) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    context.startActivity(intent);
                } catch (Throwable ignored) {}
            }
        }
    }

    // -------------------------------------------------------------
    // Context Storage Hooks
    // -------------------------------------------------------------

    private static void hookContextStorage() {
        Class<?>[] contextClasses = new Class<?>[] { ContextWrapper.class };

        for (Class<?> clazz : contextClasses) {
            try {
                if (sRedirectInternal) {
                    XposedBridge.hookAllMethods(clazz, "getFilesDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/data/data/" + sPackageName + "/files");
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getCacheDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/data/data/" + sPackageName + "/cache");
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getDataDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/data/data/" + sPackageName);
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getNoBackupFilesDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/data/data/" + sPackageName + "/no_backup");
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            File dir = new File(sContainerRoot + "/data/data/" + sPackageName + "/app_" + name);
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getFileStreamPath", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            File dir = new File(sContainerRoot + "/data/data/" + sPackageName + "/files");
                            param.setResult(new File(dir, name));
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
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getExternalFilesDirs", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String type = (String) param.args[0];
                            String sub = (type != null && !type.isEmpty()) ? "/" + type : "";
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/files" + sub);
                            ensureDirExists(dir);
                            param.setResult(new File[] { dir });
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getExternalCacheDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/cache");
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getExternalCacheDirs", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/data/" + sPackageName + "/cache");
                            ensureDirExists(dir);
                            param.setResult(new File[] { dir });
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getObbDir", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/obb/" + sPackageName);
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });

                    XposedBridge.hookAllMethods(clazz, "getObbDirs", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot + "/Android/obb/" + sPackageName);
                            ensureDirExists(dir);
                            param.setResult(new File[] { dir });
                        }
                    });
                }

                if (sRedirectDatabases) {
                    XposedBridge.hookAllMethods(clazz, "getDatabasePath", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
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
                    param.setResult(dir);
                }
            });

            XposedBridge.hookAllMethods(Environment.class, "getExternalStoragePublicDirectory", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String type = (String) param.args[0];
                    File dir = new File(sContainerRoot, type);
                    ensureDirExists(dir);
                    param.setResult(dir);
                }
            });

            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    XposedBridge.hookAllMethods(Environment.class, "getStorageDirectory", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            File dir = new File(sContainerRoot);
                            ensureDirExists(dir);
                            param.setResult(dir);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking Environment storage methods", t);
        }
    }

    // -------------------------------------------------------------
    // File & Stream Constructor Hooks
    // -------------------------------------------------------------

    private static void hookFileConstructors() {
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
                    } else if (param.args.length == 2) {
                        if (param.args[0] instanceof String && param.args[1] instanceof String) {
                            String parent = (String) param.args[0];
                            String child = (String) param.args[1];
                            String full = parent + "/" + child;
                            String mapped = mapPath(full);
                            if (!mapped.equals(full)) {
                                param.args[0] = mapped;
                                param.args[1] = "";
                            }
                        } else if (param.args[0] instanceof File && param.args[1] instanceof String) {
                            File parent = (File) param.args[0];
                            String child = (String) param.args[1];
                            String full = (parent != null ? parent.getPath() : "") + "/" + child;
                            String mapped = mapPath(full);
                            if (!mapped.equals(full)) {
                                param.args[0] = new File(mapped);
                                param.args[1] = "";
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
        try {
            XC_MethodHook dbHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sInHook.get() || param.args == null || param.args.length == 0) return;
                    if (param.args[0] instanceof String) {
                        String path = (String) param.args[0];
                        String mapped = mapPath(path);
                        ensureParentDirs(mapped);
                        param.args[0] = mapped;
                    } else if (param.args[0] instanceof File) {
                        File f = (File) param.args[0];
                        File mapped = mapFile(f);
                        ensureParentDirs(mapped.getPath());
                        param.args[0] = mapped;
                    }
                }
            };

            XposedBridge.hookAllMethods(SQLiteDatabase.class, "openDatabase", dbHook);
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "openOrCreateDatabase", dbHook);
        } catch (Throwable t) {
            Log.w(TAG, "Error hooking SQLiteDatabase openers", t);
        }
    }
}
