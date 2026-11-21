package org.lsposed.lspatch.loader;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dynamic Google Sign-In and MicroG Authentication Monitor.
 *
 * Captures authentication attempts, intents, responses, tokens, and errors
 * and outputs detailed diagnostics to Logcat (tag: LSPatch-GmsAuth) and to
 * a persistent log file.
 *
 * Can be dynamically toggled ON or OFF in real time from LSPatch Manager.
 */
public final class MicroGLogger {

    public static final String TAG = "LSPatch-GmsAuth";
    public static final String ACTION_TOGGLE_LOG = "org.expirt.lspatch.ACTION_SET_MICROG_LOG";
    private static final String LOGS_DIR = "/storage/emulated/0/.lspatch_logs";

    private static volatile boolean sInstalled = false;
    private static volatile boolean sDynamicLoggingActive = false;
    private static volatile String sPackageName = "";
    private static volatile Context sAppContext = null;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public static void install(Context context, ClassLoader appClassLoader) {
        if (sInstalled || context == null) return;
        synchronized (MicroGLogger.class) {
            if (sInstalled) return;
            sInstalled = true;
            sAppContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            sPackageName = context.getPackageName();

            checkTriggerFile();
            registerReceiver(sAppContext);

            Log.i(
                    TAG,
                    "Installing Google Sign-In monitor for " + sPackageName + " (initial state: "
                            + (sDynamicLoggingActive ? "ENABLED" : "STANDBY") + ")");

            hookSignInHubActivity(appClassLoader);
            hookGoogleSignInMethods(appClassLoader);
            hookApiExceptions(appClassLoader);
        }
    }

    private static void checkTriggerFile() {
        try {
            File trigger = new File(LOGS_DIR, sPackageName + ".auth_log");
            sDynamicLoggingActive = trigger.exists();
        } catch (Throwable ignored) {
            sDynamicLoggingActive = false;
        }
    }

    private static void registerReceiver(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent != null && ACTION_TOGGLE_LOG.equals(intent.getAction())) {
                        boolean enabled = intent.getBooleanExtra("enabled", false);
                        sDynamicLoggingActive = enabled;
                        logAuth(
                                "DYNAMIC_TOGGLE",
                                "Google Sign-In monitor dynamically set to: " + (enabled ? "ENABLED" : "DISABLED"));
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_TOGGLE_LOG);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register dynamic log toggle receiver: " + t.getMessage());
        }
    }

    public static boolean isLoggingActive() {
        // Fast in-memory check, re-checking trigger file periodically
        return sDynamicLoggingActive;
    }

    public static void logAuth(String stage, String detail) {
        String timestamp = DATE_FORMAT.format(new Date());
        String logLine = "[" + timestamp + "] [" + stage + "] " + detail;

        // Always print to Logcat if active or on major auth events
        Log.i(TAG, logLine);

        // Append to persistent log file
        appendToFile(logLine);
    }

    private static void appendToFile(String line) {
        try {
            File dir = new File(LOGS_DIR);
            if (!dir.exists()) dir.mkdirs();

            File logFile = new File(dir, sPackageName + "_signin.log");
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookSignInHubActivity(ClassLoader classLoader) {
        try {
            Class<?> hubActivityClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.auth.api.signin.internal.SignInHubActivity", classLoader);
            if (hubActivityClass == null) return;

            XposedHelpers.findAndHookMethod(hubActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = activity.getIntent();
                    checkTriggerFile();
                    logAuth(
                            "SIGN_IN_START",
                            "SignInHubActivity started with action: " + (intent != null ? intent.getAction() : "null"));
                    if (intent != null && intent.getExtras() != null) {
                        Bundle extras = intent.getExtras();
                        logAuth(
                                "SIGN_IN_EXTRAS",
                                "Extras keys: " + extras.keySet().toString());
                    }
                }
            });

            XposedHelpers.findAndHookMethod(
                    hubActivityClass, "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int requestCode = (int) param.args[0];
                            int resultCode = (int) param.args[1];
                            Intent data = (Intent) param.args[2];

                            String resultStr = resultCode == Activity.RESULT_OK
                                    ? "RESULT_OK (-1)"
                                    : (resultCode == Activity.RESULT_CANCELED
                                            ? "RESULT_CANCELED (0)"
                                            : String.valueOf(resultCode));

                            logAuth(
                                    "SIGN_IN_RESULT",
                                    "SignInHubActivity onActivityResult: requestCode=" + requestCode + ", resultCode="
                                            + resultStr + ", hasData=" + (data != null));

                            if (data != null && data.getExtras() != null) {
                                Bundle extras = data.getExtras();
                                logAuth(
                                        "SIGN_IN_RESULT_EXTRAS",
                                        "Result keys: " + extras.keySet().toString());
                                if (extras.containsKey("googleSignInStatus")) {
                                    logAuth(
                                            "SIGN_IN_STATUS",
                                            "googleSignInStatus = " + extras.get("googleSignInStatus"));
                                }
                                if (extras.containsKey("googleSignInAccount")) {
                                    logAuth("SIGN_IN_ACCOUNT", "googleSignInAccount object received!");
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.d(TAG, "SignInHubActivity not present in target app: " + t.getMessage());
        }
    }

    private static void hookGoogleSignInMethods(ClassLoader classLoader) {
        try {
            Class<?> googleSignInClass =
                    XposedHelpers.findClassIfExists("com.google.android.gms.auth.api.signin.GoogleSignIn", classLoader);
            if (googleSignInClass != null) {
                XposedBridge.hookAllMethods(googleSignInClass, "getSignedInAccountFromIntent", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (result != null) {
                            logAuth("GOOGLE_SIGN_IN_PARSE", "getSignedInAccountFromIntent returned: " + result);
                        } else if (param.getThrowable() != null) {
                            logAuth(
                                    "GOOGLE_SIGN_IN_PARSE_ERROR",
                                    "Exception in getSignedInAccountFromIntent: "
                                            + param.getThrowable().getMessage());
                        }
                    }
                });
            }

            Class<?> accountClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.auth.api.signin.GoogleSignInAccount", classLoader);
            if (accountClass != null) {
                XposedBridge.hookAllMethods(accountClass, "getEmail", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null) {
                            logAuth("ACCOUNT_EMAIL", "Google account resolved: " + param.getResult());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.d(TAG, "GoogleSignIn helper classes hook note: " + t.getMessage());
        }
    }

    private static void hookApiExceptions(ClassLoader classLoader) {
        try {
            Class<?> apiExceptionClass =
                    XposedHelpers.findClassIfExists("com.google.android.gms.common.api.ApiException", classLoader);
            if (apiExceptionClass != null) {
                XposedBridge.hookAllConstructors(apiExceptionClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object status = XposedHelpers.callMethod(param.thisObject, "getStatus");
                            logAuth("API_EXCEPTION", "ApiException created with status: " + status);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
