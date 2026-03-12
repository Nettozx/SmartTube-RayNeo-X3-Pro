package com.liskovsoft.smartyoutubetv2.tv.util;

import android.os.Build;

/**
 * Utility class for detecting RayNeo X3 Pro AR glasses hardware.
 * Uses multiple detection strategies for robustness.
 */
public class RayNeoDeviceUtil {

    private static Boolean sIsRayNeoDevice;

    /**
     * Returns true if running on a RayNeo X3 Pro device.
     * Result is cached after first call.
     */
    public static boolean isRayNeoDevice() {
        if (sIsRayNeoDevice == null) {
            sIsRayNeoDevice = detectRayNeo();
        }
        return sIsRayNeoDevice;
    }

    private static boolean detectRayNeo() {
        // Strategy 1: Try the SDK's DeviceUtil (most reliable)
        try {
            Class<?> deviceUtil = Class.forName("com.ffalcon.mercury.android.sdk.util.DeviceUtil");
            Object result = deviceUtil.getMethod("isX3Device").invoke(null);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
            // SDK not available
        }

        // Strategy 2: Check Build properties for known RayNeo identifiers
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";

        return manufacturer.contains("rayneo") || manufacturer.contains("ffalcon") ||
               brand.contains("rayneo") || brand.contains("mercury") ||
               model.contains("x3 pro") || model.contains("x3pro");
    }
}
