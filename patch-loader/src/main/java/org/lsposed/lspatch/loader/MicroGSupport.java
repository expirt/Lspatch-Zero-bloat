/*
 * Copyright (C) 2026 LSPatch Contributors
 *
 * Original code is credited to Morphe:
 * https://github.com/MorpheApp/morphe-patches/blob/main/extensions/shared-youtube/library/src/main/java/app/morphe/extension/shared/patches/GmsCoreSupportPatch.java
 * and hoo-dles:
 * https://github.com/hoo-dles/morphe-patches/blob/main/extensions/all/microg/src/main/java/hoodles/morphe/extension/microg/MicroGSupport.java
 *
 * Licensed under the GNU General Public License v3.0.
 */

package org.lsposed.lspatch.loader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.util.concurrent.atomic.AtomicBoolean;

public final class MicroGSupport {

    private static final String TAG = "LSPatch-MicroG";
    private static final AtomicBoolean CHECKED = new AtomicBoolean(false);
    private static volatile String vendorPrefix = "app.revanced";

    private MicroGSupport() {}

    public static void init(String vendor) {
        if (vendor != null && !vendor.isEmpty()) {
            vendorPrefix = vendor;
        }
        Log.i(TAG, "Initializing MicroG support hooks (vendor: " + vendorPrefix + ")");

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onStart", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (CHECKED.compareAndSet(false, true)) {
                        activity.getWindow().getDecorView().post(() -> checkGmsCore(activity));
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Activity.onStart for MicroG check", t);
        }
    }

    public static void checkGmsCore(Activity activity) {
        String gmsPackage = vendorPrefix + ".android.gms";
        PackageManager pm = activity.getPackageManager();

        // 1. Verify GmsCore is installed
        PackageInfo info = null;
        try {
            info = pm.getPackageInfo(gmsPackage, 0);
        } catch (PackageManager.NameNotFoundException ignored) {}

        if (info == null) {
            Log.w(TAG, "GmsCore package (" + gmsPackage + ") is not installed");
            Toast.makeText(
                    activity,
                    "MicroG GmsCore is not installed. Please install it for Google services to work.",
                    Toast.LENGTH_LONG
            ).show();

            String downloadUrl = "app.revanced".equals(vendorPrefix)
                    ? "https://morphe.software/microg"
                    : "https://github.com/microg/GmsCore/releases";
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(browserIntent);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to launch browser for MicroG download", t);
            }
            return;
        }

        // 2. Verify battery optimization whitelist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(gmsPackage)) {
                Log.i(TAG, "GmsCore (" + gmsPackage + ") is not whitelisted from battery optimizations");
                showBatteryOptimizationDialog(activity, gmsPackage);
            }
        }
    }

    private static void showBatteryOptimizationDialog(Activity activity, String gmsPackage) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("MicroG Battery Optimization")
                    .setMessage("To receive push notifications and keep background services active, MicroG requires battery optimization to be disabled.")
                    .setPositiveButton("Configure", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.fromParts("package", gmsPackage, null));
                            activity.startActivity(intent);
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to open battery optimization settings", t);
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show battery optimization dialog", t);
        }
    }
}
