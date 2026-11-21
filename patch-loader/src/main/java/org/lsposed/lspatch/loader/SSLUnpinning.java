package org.lsposed.lspatch.loader;

import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

/**
 * Network Security and Custom Certificate Trust Manager.
 *
 * Configures the patched app to trust user-installed and system CAs,
 * and bypasses domain-specific certificate pinning checks (OkHttp,
 * Conscrypt, TrustManagerImpl, WebView) to facilitate network debugging.
 */
public final class SSLUnpinning {

    private static final String TAG = "LSPatch-SSLUnpinning";
    private static volatile boolean sInstalled = false;

    public static void install(Context context, ClassLoader appClassLoader) {
        if (sInstalled) return;
        synchronized (SSLUnpinning.class) {
            if (sInstalled) return;
            sInstalled = true;
            Log.i(TAG, "Installing Certificate & SSL trust hooks...");

            hookConscrypt();
            hookTrustManager();
            hookOkHttp(appClassLoader);
            hookWebView(appClassLoader);
        }
    }

    private static void hookConscrypt() {
        try {
            Class<?> trustManagerImpl = Class.forName("com.android.org.conscrypt.TrustManagerImpl");

            // checkTrusted(X509Certificate[] certs, String authType, SSLSession session, SSLParameters params, boolean
            // clientAuth)
            try {
                XposedBridge.hookAllMethods(trustManagerImpl, "checkTrusted", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() == null) {
                            param.setResult(new ArrayList<X509Certificate>());
                        }
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "checkTrusted hook note: " + t.getMessage());
            }

            // checkTrustedRecursive(...) -> returns List<X509Certificate>
            try {
                XposedBridge.hookAllMethods(trustManagerImpl, "checkTrustedRecursive", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() == null) {
                            param.setResult(new ArrayList<X509Certificate>());
                        }
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "checkTrustedRecursive hook note: " + t.getMessage());
            }

            // checkServerTrusted(X509Certificate[] certs, String authType, String host)
            try {
                XposedBridge.hookAllMethods(trustManagerImpl, "checkServerTrusted", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() == null) {
                            param.setResult(new ArrayList<X509Certificate>());
                        }
                    }
                });
            } catch (Throwable t) {
                Log.d(TAG, "checkServerTrusted hook note: " + t.getMessage());
            }

            Log.i(TAG, "Conscrypt TrustManagerImpl hooked successfully");
        } catch (Throwable t) {
            Log.d(TAG, "Conscrypt hook not applicable or failed: " + t.getMessage());
        }
    }

    private static void hookTrustManager() {
        try {
            Class<?> x509TrustManager = Class.forName("javax.net.ssl.X509TrustManager");
            // Standard X509TrustManager implementations checkServerTrusted / checkClientTrusted
            XC_MethodReplacement emptyReplacement = new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return null;
                }
            };
        } catch (Throwable t) {
            Log.d(TAG, "TrustManager hook note: " + t.getMessage());
        }
    }

    private static void hookOkHttp(ClassLoader classLoader) {
        // OkHttp v3 / v4
        try {
            Class<?> pinner3 = XposedHelpers.findClassIfExists("okhttp3.CertificatePinner", classLoader);
            if (pinner3 != null) {
                XposedBridge.hookAllMethods(pinner3, "check", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return null; // Bypass certificate pinner check
                    }
                });
                Log.i(TAG, "OkHttp3 CertificatePinner hooked");
            }
        } catch (Throwable t) {
            Log.d(TAG, "OkHttp3 hook note: " + t.getMessage());
        }

        // OkHttp v2 (legacy)
        try {
            Class<?> pinner2 = XposedHelpers.findClassIfExists("com.squareup.okhttp.CertificatePinner", classLoader);
            if (pinner2 != null) {
                XposedBridge.hookAllMethods(pinner2, "check", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return null;
                    }
                });
                Log.i(TAG, "OkHttp2 CertificatePinner hooked");
            }
        } catch (Throwable t) {
            Log.d(TAG, "OkHttp2 hook note: " + t.getMessage());
        }
    }

    private static void hookWebView(ClassLoader classLoader) {
        try {
            Class<?> webViewClient = XposedHelpers.findClassIfExists("android.webkit.WebViewClient", classLoader);
            if (webViewClient != null) {
                XposedBridge.hookAllMethods(webViewClient, "onReceivedSslError", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object handler = param.args[1];
                            if (handler != null) {
                                XposedHelpers.callMethod(handler, "proceed");
                                param.setResult(null);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                Log.i(TAG, "WebViewClient SSL error handler hooked");
            }
        } catch (Throwable t) {
            Log.d(TAG, "WebViewClient hook note: " + t.getMessage());
        }
    }
}
