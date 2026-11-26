package org.lsposed.lspatch.loader;

import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.database.MatrixCursor;
import android.media.MediaDrm;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.WebSettings;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.lsposed.lspatch.share.FeatureConfig;

/**
 * Runtime Identity Spoofer for per-app / per-clone Android identifier isolation.
 */
public final class IdentitySpoofer {

    private static final String TAG = "LSPatch-Identity";
    private static volatile boolean sPreInstalled = false;
    private static volatile boolean sAppInstalled = false;
    private static volatile File sBootIdFile = null;

    private IdentitySpoofer() {}

    public static void install(Context context, ClassLoader appClassLoader, FeatureConfig fc) {
        installPreApp(context, fc);
        installAppHooks(appClassLoader, fc);
    }

    public static void installPreApp(Context context, FeatureConfig fc) {
        if (fc == null || !fc.hasAnyIdentitySpoof()) return;
        if (sPreInstalled) return;
        synchronized (IdentitySpoofer.class) {
            if (sPreInstalled) return;
            sPreInstalled = true;
            Log.i(TAG, "Installing pre-app Identity Spoofer...");

            if (FeatureConfig.nonEmpty(fc.androidId)) {
                hookAndroidId(fc.androidId.trim());
            }
            if (FeatureConfig.nonEmpty(fc.widevineId)) {
                hookWidevineId(fc.widevineId.trim());
            }
            if (fc.hasAnyBuildSpoof()) {
                hookBuildAndProps(fc);
            }
            if (FeatureConfig.nonEmpty(fc.gsfId)) {
                hookGsfId(fc.gsfId.trim());
            }
            if (FeatureConfig.nonEmpty(fc.bootId)) {
                prepareAndHookBootId(context, fc.bootId.trim());
            }
            if (FeatureConfig.nonEmpty(fc.wifiMac)
                    || FeatureConfig.nonEmpty(fc.wifiBssid)
                    || FeatureConfig.nonEmpty(fc.wifiSsid)
                    || FeatureConfig.nonEmpty(fc.bluetoothMac)
                    || FeatureConfig.nonEmpty(fc.bluetoothName)) {
                hookWifiAndBluetooth(fc);
            }
            if (FeatureConfig.nonEmpty(fc.imei)
                    || FeatureConfig.nonEmpty(fc.imsi)
                    || FeatureConfig.nonEmpty(fc.iccid)
                    || FeatureConfig.nonEmpty(fc.phoneNumber)
                    || FeatureConfig.nonEmpty(fc.carrierName)
                    || FeatureConfig.nonEmpty(fc.carrierMccMnc)
                    || FeatureConfig.nonEmpty(fc.countryIso)) {
                hookTelephony(fc);
            }
            if (FeatureConfig.nonEmpty(fc.userAgent)) {
                hookUserAgent(fc.userAgent.trim());
            }
        }
    }

    public static void installAppHooks(ClassLoader appClassLoader, FeatureConfig fc) {
        if (fc == null || appClassLoader == null) return;
        if (sAppInstalled) return;
        synchronized (IdentitySpoofer.class) {
            if (sAppInstalled) return;
            sAppInstalled = true;
            if (FeatureConfig.nonEmpty(fc.advertisingId) || FeatureConfig.nonEmpty(fc.appSetId)) {
                hookGmsIds(appClassLoader, fc);
            }
        }
    }

    public static File getBootIdFile() {
        return sBootIdFile;
    }

    private static void hookAndroidId(final String androidId) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && Settings.Secure.ANDROID_ID.equals(param.args[1])) {
                    param.setResult(androidId);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Settings.Secure.class, "getString", hook);
            XposedBridge.hookAllMethods(Settings.Secure.class, "getStringForUser", hook);
            XposedBridge.hookAllMethods(Settings.System.class, "getString", hook);
            XposedBridge.hookAllMethods(Settings.System.class, "getStringForUser", hook);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Settings ANDROID_ID", t);
        }
    }

    private static void hookWidevineId(final String widevineHex) {
        final byte[] spoofBytes = parseHexOrUtf8(widevineHex);
        try {
            XposedBridge.hookAllMethods(MediaDrm.class, "getPropertyByteArray", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && MediaDrm.PROPERTY_DEVICE_UNIQUE_ID.equals(param.args[0])) {
                        param.setResult(spoofBytes.clone());
                    }
                }
            });
            XposedBridge.hookAllMethods(MediaDrm.class, "getPropertyString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && MediaDrm.PROPERTY_DEVICE_UNIQUE_ID.equals(param.args[0])) {
                        param.setResult(widevineHex);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook MediaDrm Widevine ID", t);
        }
    }

    private static void hookBuildAndProps(FeatureConfig fc) {
        try {
            if (FeatureConfig.nonEmpty(fc.brand)) setBuildField("BRAND", fc.brand);
            if (FeatureConfig.nonEmpty(fc.manufacturer)) setBuildField("MANUFACTURER", fc.manufacturer);
            if (FeatureConfig.nonEmpty(fc.model)) setBuildField("MODEL", fc.model);
            if (FeatureConfig.nonEmpty(fc.device)) setBuildField("DEVICE", fc.device);
            if (FeatureConfig.nonEmpty(fc.product)) setBuildField("PRODUCT", fc.product);
            if (FeatureConfig.nonEmpty(fc.hardware)) setBuildField("HARDWARE", fc.hardware);
            if (FeatureConfig.nonEmpty(fc.board)) setBuildField("BOARD", fc.board);
            if (FeatureConfig.nonEmpty(fc.bootloader)) setBuildField("BOOTLOADER", fc.bootloader);
            if (FeatureConfig.nonEmpty(fc.fingerprint)) setBuildField("FINGERPRINT", fc.fingerprint);
            if (FeatureConfig.nonEmpty(fc.serial)) {
                setBuildField("SERIAL", fc.serial);
                XposedBridge.hookAllMethods(Build.class, "getSerial", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(fc.serial);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to update Build fields", t);
        }

        final Map<String, String> props = new HashMap<>();
        if (FeatureConfig.nonEmpty(fc.brand)) {
            props.put("ro.product.brand", fc.brand);
            props.put("ro.product.system.brand", fc.brand);
            props.put("ro.product.vendor.brand", fc.brand);
            props.put("ro.product.odm.brand", fc.brand);
        }
        if (FeatureConfig.nonEmpty(fc.manufacturer)) {
            props.put("ro.product.manufacturer", fc.manufacturer);
            props.put("ro.product.system.manufacturer", fc.manufacturer);
            props.put("ro.product.vendor.manufacturer", fc.manufacturer);
        }
        if (FeatureConfig.nonEmpty(fc.model)) {
            props.put("ro.product.model", fc.model);
            props.put("ro.product.system.model", fc.model);
            props.put("ro.product.vendor.model", fc.model);
            props.put("ro.product.odm.model", fc.model);
        }
        if (FeatureConfig.nonEmpty(fc.device)) {
            props.put("ro.product.device", fc.device);
            props.put("ro.product.system.device", fc.device);
            props.put("ro.product.vendor.device", fc.device);
            props.put("ro.build.product", fc.device);
        }
        if (FeatureConfig.nonEmpty(fc.product)) {
            props.put("ro.product.name", fc.product);
            props.put("ro.product.system.name", fc.product);
            props.put("ro.product.vendor.name", fc.product);
        }
        if (FeatureConfig.nonEmpty(fc.hardware)) {
            props.put("ro.hardware", fc.hardware);
        }
        if (FeatureConfig.nonEmpty(fc.board)) {
            props.put("ro.board.platform", fc.board);
            props.put("ro.product.board", fc.board);
        }
        if (FeatureConfig.nonEmpty(fc.bootloader)) {
            props.put("ro.bootloader", fc.bootloader);
        }
        if (FeatureConfig.nonEmpty(fc.fingerprint)) {
            props.put("ro.build.fingerprint", fc.fingerprint);
            props.put("ro.system.build.fingerprint", fc.fingerprint);
            props.put("ro.vendor.build.fingerprint", fc.fingerprint);
            props.put("ro.odm.build.fingerprint", fc.fingerprint);
            props.put("ro.bootimage.build.fingerprint", fc.fingerprint);
        }
        if (FeatureConfig.nonEmpty(fc.serial)) {
            props.put("ro.serialno", fc.serial);
            props.put("ro.boot.serialno", fc.serial);
        }

        if (!props.isEmpty()) {
            try {
                Class<?> sysProps = Class.forName("android.os.SystemProperties");
                XposedBridge.hookAllMethods(sysProps, "get", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length >= 1 && param.args[0] instanceof String) {
                            String v = props.get(param.args[0]);
                            if (v != null) param.setResult(v);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "SystemProperties hook note: " + t.getMessage());
            }
        }
    }

    private static void setBuildField(String name, String value) {
        try {
            XposedHelpers.setStaticObjectField(Build.class, name, value);
        } catch (Throwable ignored) {}
    }

    private static void hookGsfId(final String gsfId) {
        try {
            XposedBridge.hookAllMethods(ContentResolver.class, "query", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 4 && param.args[0] instanceof Uri) {
                        Uri uri = (Uri) param.args[0];
                        if ("com.google.android.gsf.gservices".equals(uri.getAuthority())) {
                            Object selArgsObj = param.args[3];
                            if (selArgsObj instanceof String[]) {
                                String[] selArgs = (String[]) selArgsObj;
                                for (String s : selArgs) {
                                    if ("android_id".equals(s)) {
                                        MatrixCursor c = new MatrixCursor(new String[] {"key", "value"});
                                        c.addRow(new Object[] {"android_id", gsfId});
                                        param.setResult(c);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook GSF ID", t);
        }
    }

    private static void hookGmsIds(ClassLoader appClassLoader, FeatureConfig fc) {
        if (appClassLoader == null) return;
        if (FeatureConfig.nonEmpty(fc.advertisingId)) {
            try {
                Class<?> adInfoClass = XposedHelpers.findClassIfExists(
                        "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", appClassLoader);
                if (adInfoClass != null) {
                    XposedBridge.hookAllMethods(adInfoClass, "getId", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(fc.advertisingId.trim());
                        }
                    });
                }
            } catch (Throwable t) {
                Log.d(TAG, "AAID hook note: " + t.getMessage());
            }
        }
        if (FeatureConfig.nonEmpty(fc.appSetId)) {
            try {
                Class<?> appSetClass =
                        XposedHelpers.findClassIfExists("com.google.android.gms.appset.AppSetIdInfo", appClassLoader);
                if (appSetClass != null) {
                    XposedBridge.hookAllMethods(appSetClass, "getId", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(fc.appSetId.trim());
                        }
                    });
                }
            } catch (Throwable t) {
                Log.d(TAG, "AppSetId hook note: " + t.getMessage());
            }
        }
    }

    private static void prepareAndHookBootId(Context context, String bootId) {
        try {
            File dir = new File(context.getNoBackupFilesDir(), "lspatch");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "spoof_boot_id");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write((bootId + "\n").getBytes(StandardCharsets.UTF_8));
            }
            sBootIdFile = f;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to prepare spoof_boot_id file", t);
        }
    }

    private static void hookWifiAndBluetooth(FeatureConfig fc) {
        try {
            if (FeatureConfig.nonEmpty(fc.wifiMac)) {
                final String mac = fc.wifiMac.trim();
                final byte[] macBytes = parseMacBytes(mac);
                XposedBridge.hookAllMethods(WifiInfo.class, "getMacAddress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(mac);
                    }
                });
                if (macBytes != null) {
                    XposedBridge.hookAllMethods(NetworkInterface.class, "getHardwareAddress", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() != null) {
                                param.setResult(macBytes.clone());
                            }
                        }
                    });
                }
            }
            if (FeatureConfig.nonEmpty(fc.wifiBssid)) {
                final String bssid = fc.wifiBssid.trim();
                XposedBridge.hookAllMethods(WifiInfo.class, "getBSSID", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(bssid);
                    }
                });
            }
            if (FeatureConfig.nonEmpty(fc.wifiSsid)) {
                String raw = fc.wifiSsid.trim();
                final String quoted = raw.startsWith("\"") ? raw : ("\"" + raw + "\"");
                XposedBridge.hookAllMethods(WifiInfo.class, "getSSID", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(quoted);
                    }
                });
            }
            if (FeatureConfig.nonEmpty(fc.bluetoothMac)) {
                final String btMac = fc.bluetoothMac.trim().toUpperCase(Locale.ROOT);
                XposedBridge.hookAllMethods(BluetoothAdapter.class, "getAddress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(btMac);
                    }
                });
                XposedBridge.hookAllMethods(Settings.Secure.class, "getString", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length >= 2 && "bluetooth_address".equals(param.args[1])) {
                            param.setResult(btMac);
                        }
                    }
                });
            }
            if (FeatureConfig.nonEmpty(fc.bluetoothName)) {
                final String btName = fc.bluetoothName.trim();
                XposedBridge.hookAllMethods(BluetoothAdapter.class, "getName", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(btName);
                    }
                });
                XposedBridge.hookAllMethods(Settings.Secure.class, "getString", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length >= 2 && "bluetooth_name".equals(param.args[1])) {
                            param.setResult(btName);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook WiFi/Bluetooth", t);
        }
    }

    private static void hookTelephony(FeatureConfig fc) {
        try {
            if (FeatureConfig.nonEmpty(fc.imei)) {
                final String imei = fc.imei.trim();
                XC_MethodHook imeiHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(imei);
                    }
                };
                XposedBridge.hookAllMethods(TelephonyManager.class, "getImei", imeiHook);
                XposedBridge.hookAllMethods(TelephonyManager.class, "getMeid", imeiHook);
                XposedBridge.hookAllMethods(TelephonyManager.class, "getDeviceId", imeiHook);
            }
            if (FeatureConfig.nonEmpty(fc.imsi)) {
                final String imsi = fc.imsi.trim();
                XposedBridge.hookAllMethods(TelephonyManager.class, "getSubscriberId", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(imsi);
                    }
                });
            }
            if (FeatureConfig.nonEmpty(fc.iccid)) {
                final String iccid = fc.iccid.trim();
                XposedBridge.hookAllMethods(TelephonyManager.class, "getSimSerialNumber", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(iccid);
                    }
                });
            }
            if (FeatureConfig.nonEmpty(fc.phoneNumber)) {
                final String phone = fc.phoneNumber.trim();
                XposedBridge.hookAllMethods(TelephonyManager.class, "getLine1Number", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(phone);
                    }
                });
            }
            if (FeatureConfig.nonEmpty(fc.carrierMccMnc)) {
                final String mccMnc = fc.carrierMccMnc.trim();
                XC_MethodHook opHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(mccMnc);
                    }
                };
                XposedBridge.hookAllMethods(TelephonyManager.class, "getSimOperator", opHook);
                XposedBridge.hookAllMethods(TelephonyManager.class, "getNetworkOperator", opHook);
            }
            if (FeatureConfig.nonEmpty(fc.carrierName)) {
                final String opName = fc.carrierName.trim();
                XC_MethodHook opNameHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(opName);
                    }
                };
                XposedBridge.hookAllMethods(TelephonyManager.class, "getSimOperatorName", opNameHook);
                XposedBridge.hookAllMethods(TelephonyManager.class, "getNetworkOperatorName", opNameHook);
            }
            if (FeatureConfig.nonEmpty(fc.countryIso)) {
                final String iso = fc.countryIso.trim().toLowerCase(Locale.ROOT);
                XC_MethodHook isoHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(iso);
                    }
                };
                XposedBridge.hookAllMethods(TelephonyManager.class, "getSimCountryIso", isoHook);
                XposedBridge.hookAllMethods(TelephonyManager.class, "getNetworkCountryIso", isoHook);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Telephony", t);
        }
    }

    private static void hookUserAgent(final String ua) {
        try {
            XposedBridge.hookAllMethods(WebSettings.class, "getDefaultUserAgent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(ua);
                }
            });
            // Hook Chromium/AOSP WebSettings subclass methods when WebSettings implementation is resolved
            Class<?> contentSettings = null;
            try {
                contentSettings = Class.forName("com.android.webview.chromium.ContentSettingsAdapter");
            } catch (Throwable ignored) {}
            if (contentSettings != null) {
                XposedBridge.hookAllMethods(contentSettings, "getUserAgentString", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(ua);
                    }
                });
            }
        } catch (Throwable t) {
            Log.d(TAG, "UserAgent hook note: " + t.getMessage());
        }
    }

    private static byte[] parseHexOrUtf8(String input) {
        String clean = input.replaceAll("[^0-9a-fA-F]", "");
        if (clean.length() >= 16 && clean.length() % 2 == 0) {
            int len = clean.length() / 2;
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) {
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        return input.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] parseMacBytes(String mac) {
        String[] parts = mac.split("[:-]");
        if (parts.length != 6) return null;
        try {
            byte[] out = new byte[6];
            for (int i = 0; i < 6; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
