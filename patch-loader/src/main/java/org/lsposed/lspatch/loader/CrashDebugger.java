package org.lsposed.lspatch.loader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Notification-Based Crash Debugger.
 *
 * Captures uncaught exceptions without any floating logos or overlays.
 * Saves a full formatted error log to disk and posts a high-priority
 * Android system notification with the stack trace details.
 */
public final class CrashDebugger {

    private static final String TAG = "LSPatch-CrashDebugger";
    private static final String CHANNEL_ID = "lspatch_crash_channel";
    private static final int NOTIFICATION_ID = 991122;

    private static volatile boolean sInstalled = false;

    public static void install(Context context) {
        if (sInstalled || context == null) return;
        synchronized (CrashDebugger.class) {
            if (sInstalled) return;
            sInstalled = true;

            final Context appContext =
                    context.getApplicationContext() != null ? context.getApplicationContext() : context;
            final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    handleCrash(appContext, thread, throwable);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in crash handler: " + t.getMessage(), t);
                } finally {
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, throwable);
                    } else {
                        Process.killProcess(Process.myPid());
                        System.exit(10);
                    }
                }
            });

            Log.i(TAG, "Notification-based crash debugger installed for " + context.getPackageName());
        }
    }

    private static void handleCrash(Context context, Thread thread, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String pkg = context.getPackageName();

        // 1. Format the full error log
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        StringBuilder sb = new StringBuilder();
        sb.append("=================== LSPATCH CRASH REPORT ===================\n");
        sb.append("Package: ").append(pkg).append("\n");
        sb.append("Process PID: ")
                .append(Process.myPid())
                .append(", UID: ")
                .append(Process.myUid())
                .append("\n");
        sb.append("Thread: ")
                .append(thread.getName())
                .append(" (ID: ")
                .append(thread.getId())
                .append(")\n");
        sb.append("Timestamp: ").append(timestamp).append("\n");
        sb.append("Device: ")
                .append(Build.MANUFACTURER)
                .append(" ")
                .append(Build.MODEL)
                .append("\n");
        sb.append("Android: ")
                .append(Build.VERSION.RELEASE)
                .append(" (SDK ")
                .append(Build.VERSION.SDK_INT)
                .append(")\n");
        sb.append("Exception: ")
                .append(throwable.getClass().getName())
                .append(": ")
                .append(throwable.getMessage())
                .append("\n");
        sb.append("----------------------- STACK TRACE -----------------------\n");
        sb.append(stackTrace);
        sb.append("===========================================================\n");

        String fullLog = sb.toString();
        Log.e(TAG, fullLog);

        // 2. Persist log to files
        File logFile = saveLogToDisk(context, pkg, timestamp, fullLog);

        // 3. Post notification (Notification only - strictly NO floating logo/icon)
        postCrashNotification(context, pkg, throwable, stackTrace, logFile);

        // Brief delay to ensure notification IPC dispatches before process termination
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
    }

    private static File saveLogToDisk(Context context, String pkg, String timestamp, String content) {
        File logFile = null;
        try {
            File externalDir = new File("/storage/emulated/0/.lspatch_logs");
            if (externalDir.exists() || externalDir.mkdirs()) {
                logFile = new File(externalDir, "crash_" + pkg + "_" + timestamp + ".log");
                try (FileOutputStream fos = new FileOutputStream(logFile)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed writing to external storage: " + t.getMessage());
        }

        if (logFile == null || !logFile.exists()) {
            try {
                File cacheDir = context.getCacheDir();
                logFile = new File(cacheDir, "crash_" + timestamp + ".log");
                try (FileOutputStream fos = new FileOutputStream(logFile)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed writing to cache dir: " + t.getMessage());
            }
        }
        return logFile;
    }

    private static void postCrashNotification(
            Context context, String pkg, Throwable throwable, String stackTrace, File logFile) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel =
                        new NotificationChannel(CHANNEL_ID, "App Crash Reports", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Shows crash reports when an uncaught exception occurs");
                nm.createNotificationChannel(channel);
            }

            String exceptionTitle = throwable.getClass().getSimpleName();
            String exceptionMsg = throwable.getMessage() != null ? throwable.getMessage() : "No message";
            String title = "Crash: " + pkg;
            String summary = exceptionTitle + ": " + exceptionMsg;

            int iconRes = android.R.drawable.stat_notify_error;

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            builder.setSmallIcon(iconRes)
                    .setContentTitle(title)
                    .setContentText(summary)
                    .setAutoCancel(false)
                    .setOngoing(false);

            if (logFile != null) {
                builder.setSubText(logFile.getName());
            }

            Notification.BigTextStyle bigTextStyle = new Notification.BigTextStyle()
                    .setBigContentTitle(title)
                    .setSummaryText(exceptionTitle)
                    .bigText(summary + "\n\n"
                            + (logFile != null ? "Log saved: " + logFile.getAbsolutePath() + "\n\n" : "") + stackTrace);
            builder.setStyle(bigTextStyle);

            nm.notify(NOTIFICATION_ID, builder.build());
            Log.i(TAG, "Crash notification posted successfully for " + pkg);
        } catch (Throwable t) {
            Log.e(TAG, "Failed posting crash notification: " + t.getMessage(), t);
        }
    }
}
