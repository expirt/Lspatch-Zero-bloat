package org.lsposed.lspatch.loader;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Debug;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.lsposed.lspatch.share.FeatureConfig;

/**
 * Runtime Anti-Detection protection against root/Xposed/LSPatch/cloner/debugger/screenshot checks.
 */
public final class AntiDetection {

    private static final String TAG = "LSPatch-AntiDetect";
    private static volatile boolean sInstalled = false;

    private static final Set<String> HIDDEN_PACKAGES = new HashSet<>(Arrays.asList(
            "org.expirt.lspatch",
            "io.github.expirt.lspatch",
            "org.lsposed.lspatch",
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            "de.robv.android.xposed.installer"));

    private static final String[] SUSPICIOUS_STACK_FRAGMENTS = new String[] {
        "org.lsposed.lspatch.",
        "de.robv.android.xposed.",
        "org.matrix.vector.",
        "LSPHooker_",
        "EdHooker_",
        "Pine",
        "lsplant"
    };

    private static final String[] SUSPICIOUS_PATH_FRAGMENTS = new String[] {
        "/su",
        "/magisk",
        "/supersu",
        "superuser.apk",
        "/sbin/.magisk",
        "/data/adb/",
        "/xposed",
        "xposedbridge",
        "lsposed"
    };

    private AntiDetection() {}

    public static void install(Context context, FeatureConfig fc, String selfPackage) {
        if (fc == null || !fc.hasAnyAntiDetection()) return;
        if (sInstalled) return;
        synchronized (AntiDetection.class) {
            if (sInstalled) return;
            sInstalled = true;
            Log.i(TAG, "Installing Anti-Detection protections...");

            boolean master = fc.antiDetectionEnabled;
            if (master || fc.hideLspatchTraces) {
                hookStackTraces();
                scrubSelfMeta(context, selfPackage);
            }
            if (master || fc.hidePackages) {
                hookPackageManager(selfPackage);
            }
            if (master || fc.hideDevOptions) {
                hookDevAndDebugOptions(context, selfPackage);
            }
            if (master || fc.hideRootFiles) {
                hookRootAndHookFiles(context);
            }
            if (fc.disableFlagSecure) {
                hookFlagSecure();
            }
        }
    }

    private static boolean isSuspiciousFrame(String className) {
        if (className == null) return false;
        for (String frag : SUSPICIOUS_STACK_FRAGMENTS) {
            if (className.contains(frag)) return true;
        }
        return false;
    }

    private static StackTraceElement[] filterFrames(StackTraceElement[] raw) {
        if (raw == null || raw.length == 0) return raw;
        boolean found = false;
        for (StackTraceElement e : raw) {
            if (e != null && isSuspiciousFrame(e.getClassName())) {
                found = true;
                break;
            }
        }
        if (!found) return raw;
        List<StackTraceElement> filtered = new ArrayList<>(raw.length);
        for (StackTraceElement e : raw) {
            if (e != null && !isSuspiciousFrame(e.getClassName())) {
                filtered.add(e);
            }
        }
        return filtered.toArray(new StackTraceElement[0]);
    }

    private static void hookStackTraces() {
        try {
            XC_MethodHook filterHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof StackTraceElement[]) {
                        param.setResult(filterFrames((StackTraceElement[]) res));
                    }
                }
            };
            XposedBridge.hookAllMethods(Throwable.class, "getStackTrace", filterHook);
            XposedBridge.hookAllMethods(Thread.class, "getStackTrace", filterHook);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook stack traces", t);
        }
    }

    private static void scrubSelfMeta(Context context, String selfPackage) {
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            if (ai != null && ai.metaData != null) {
                ai.metaData.remove("lspatch");
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");
            XposedBridge.hookAllMethods(appPm, "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof ApplicationInfo) {
                        ApplicationInfo info = (ApplicationInfo) res;
                        if (info.metaData != null && info.metaData.containsKey("lspatch")) {
                            info.metaData.remove("lspatch");
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(appPm, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof PackageInfo) {
                        PackageInfo pi = (PackageInfo) res;
                        if (pi.applicationInfo != null && pi.applicationInfo.metaData != null) {
                            pi.applicationInfo.metaData.remove("lspatch");
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "Meta scrub hook note: " + t.getMessage());
        }
    }

    private static boolean isHiddenPackage(String pkg, String selfPackage) {
        if (pkg == null || pkg.equals(selfPackage)) return false;
        if (HIDDEN_PACKAGES.contains(pkg)) return true;
        String lower = pkg.toLowerCase(Locale.ROOT);
        return lower.contains("lspatch") || lower.contains("lsposed") || lower.contains("magisk");
    }

    private static void hookPackageManager(final String selfPackage) {
        try {
            Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");

            XposedBridge.hookAllMethods(appPm, "getInstalledPackages", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof List<?>) {
                        List<?> list = (List<?>) res;
                        List<Object> out = new ArrayList<>(list.size());
                        for (Object item : list) {
                            if (item instanceof PackageInfo && isHiddenPackage(((PackageInfo) item).packageName, selfPackage)) {
                                continue;
                            }
                            out.add(item);
                        }
                        param.setResult(out);
                    }
                }
            });

            XposedBridge.hookAllMethods(appPm, "getInstalledApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof List<?>) {
                        List<?> list = (List<?>) res;
                        List<Object> out = new ArrayList<>(list.size());
                        for (Object item : list) {
                            if (item instanceof ApplicationInfo
                                    && isHiddenPackage(((ApplicationInfo) item).packageName, selfPackage)) {
                                continue;
                            }
                            out.add(item);
                        }
                        param.setResult(out);
                    }
                }
            });

            XposedBridge.hookAllMethods(appPm, "queryIntentActivities", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    filterResolveList(param, selfPackage);
                }
            });
            XposedBridge.hookAllMethods(appPm, "queryIntentServices", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    filterResolveList(param, selfPackage);
                }
            });

            XposedBridge.hookAllMethods(appPm, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (isHiddenPackage(pkg, selfPackage)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(appPm, "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        if (isHiddenPackage(pkg, selfPackage)) {
                            param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook PackageManager", t);
        }
    }

    private static void filterResolveList(XC_MethodHook.MethodHookParam param, String selfPackage) {
        Object res = param.getResult();
        if (!(res instanceof List<?>)) return;
        List<?> list = (List<?>) res;
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof ResolveInfo) {
                ResolveInfo ri = (ResolveInfo) item;
                String pkg = null;
                if (ri.activityInfo != null) pkg = ri.activityInfo.packageName;
                else if (ri.serviceInfo != null) pkg = ri.serviceInfo.packageName;
                else if (ri.providerInfo != null) pkg = ri.providerInfo.packageName;
                if (isHiddenPackage(pkg, selfPackage)) continue;
            }
            out.add(item);
        }
        param.setResult(out);
    }

    private static void hookDevAndDebugOptions(Context context, String selfPackage) {
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            if (ai != null) {
                ai.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
            }
        } catch (Throwable ignored) {}

        try {
            XposedBridge.hookAllMethods(Debug.class, "isDebuggerConnected", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            });
            XposedBridge.hookAllMethods(Debug.class, "waitingForDebugger", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "Debug hook note: " + t.getMessage());
        }

        XC_MethodHook settingsIntHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && param.args[1] instanceof String) {
                    String key = (String) param.args[1];
                    if ("adb_enabled".equals(key)
                            || "development_settings_enabled".equals(key)
                            || "adb_wifi_enabled".equals(key)) {
                        param.setResult(0);
                    }
                }
            }
        };
        XC_MethodHook settingsStrHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && param.args[1] instanceof String) {
                    String key = (String) param.args[1];
                    if ("adb_enabled".equals(key)
                            || "development_settings_enabled".equals(key)
                            || "adb_wifi_enabled".equals(key)) {
                        param.setResult("0");
                    }
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Settings.Global.class, "getInt", settingsIntHook);
            XposedBridge.hookAllMethods(Settings.Global.class, "getString", settingsStrHook);
            XposedBridge.hookAllMethods(Settings.Secure.class, "getInt", settingsIntHook);
            XposedBridge.hookAllMethods(Settings.Secure.class, "getString", settingsStrHook);
        } catch (Throwable t) {
            Log.d(TAG, "Settings dev hook note: " + t.getMessage());
        }

        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            XposedBridge.hookAllMethods(sysProps, "get", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] instanceof String) {
                        String key = (String) param.args[0];
                        if ("ro.debuggable".equals(key) || "ro.adb.secure".equals(key)) {
                            param.setResult("0");
                        } else if ("ro.secure".equals(key)) {
                            param.setResult("1");
                        } else if ("init.svc.adbd".equals(key)) {
                            param.setResult("stopped");
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");
            XposedBridge.hookAllMethods(appPm, "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof ApplicationInfo) {
                        ApplicationInfo info = (ApplicationInfo) res;
                        if (selfPackage.equals(info.packageName)) {
                            info.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean isSuspiciousPath(String path, String appDataDir) {
        if (path == null) return false;
        if (appDataDir != null && path.startsWith(appDataDir)) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String frag : SUSPICIOUS_PATH_FRAGMENTS) {
            if (lower.endsWith(frag) || lower.contains(frag + "/")) return true;
        }
        return false;
    }

    private static void hookRootAndHookFiles(Context context) {
        final String dataDir = context.getApplicationInfo() != null ? context.getApplicationInfo().dataDir : null;
        try {
            XC_MethodHook fileBoolHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof File) {
                        String path = ((File) param.thisObject).getPath();
                        if (isSuspiciousPath(path, dataDir)) {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(File.class, "exists", fileBoolHook);
            XposedHelpers.findAndHookMethod(File.class, "canRead", fileBoolHook);
            XposedHelpers.findAndHookMethod(File.class, "canExecute", fileBoolHook);
            XposedHelpers.findAndHookMethod(File.class, "isFile", fileBoolHook);
        } catch (Throwable t) {
            Log.d(TAG, "Root file hook note: " + t.getMessage());
        }
    }

    private static void hookFlagSecure() {
        try {
            XposedBridge.hookAllMethods(Window.class, "setFlags", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && param.args[0] instanceof Integer && param.args[1] instanceof Integer) {
                        int flags = (Integer) param.args[0];
                        int mask = (Integer) param.args[1];
                        param.args[0] = flags & ~WindowManager.LayoutParams.FLAG_SECURE;
                        param.args[1] = mask & ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                }
            });
            XposedBridge.hookAllMethods(Window.class, "addFlags", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] instanceof Integer) {
                        param.args[0] = ((Integer) param.args[0]) & ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                }
            });
            XposedBridge.hookAllMethods(SurfaceView.class, "setSecure", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1) {
                        param.args[0] = Boolean.FALSE;
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook FLAG_SECURE", t);
        }
    }
}
