package org.lsposed.lspatch.loader;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * A lightweight, zero-overhead dummy accessibility service designed to maintain
 * the patched app's process priority in the background without needing a permanent
 * foreground notification.
 *
 * Placed in meta-loader so it is included directly into the patched APK's primary
 * DexPathList (classes<N>.dex). This ensures Android's ActivityThread can instantiate
 * the service directly without relying on in-memory loader classloader bridging.
 */
public class DummyAccessibilityService extends AccessibilityService {

    private static final String TAG = "LSPatch-A11y";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Zero-overhead dummy: do not consume, process, or intercept any events
    }

    @Override
    public void onInterrupt() {
        // Zero-overhead dummy: do nothing
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = 0; // Do not register for any accessibility events
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.DEFAULT;
            info.notificationTimeout = 100;
            setServiceInfo(info);
            Log.i(TAG, "Dummy accessibility service connected - process granted background persistence");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to configure dummy accessibility service", t);
        }
    }
}
