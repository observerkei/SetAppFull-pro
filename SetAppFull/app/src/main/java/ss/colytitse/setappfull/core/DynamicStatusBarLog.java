package ss.colytitse.setappfull.core;

import android.util.Log;

/**
 * 动态状态栏日志工具。
 * 默认开启，可通过 ENABLED 统一关闭。
 */
public final class DynamicStatusBarLog {

    private static final String TAG = "SetAppFull-Dynamic";

    /** 日志总开关，默认开启。 */
    public static volatile boolean ENABLED = true;

    private DynamicStatusBarLog() {
    }

    public static void d(String message) {
        if (ENABLED) Log.d(TAG, message);
    }

    public static void i(String message) {
        if (ENABLED) Log.i(TAG, message);
    }

    public static void w(String message) {
        if (ENABLED) Log.w(TAG, message);
    }

    public static void w(String message, Throwable error) {
        if (ENABLED) Log.w(TAG, message, error);
    }
}