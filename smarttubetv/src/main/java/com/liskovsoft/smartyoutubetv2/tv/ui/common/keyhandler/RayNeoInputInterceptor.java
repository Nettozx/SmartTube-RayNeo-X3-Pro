package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.util.RayNeoDeviceUtil;

import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback;
import com.ffalcon.mercury.android.sdk.touch.FlingArgs;
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher;

/**
 * Intercepts temple touchpad MotionEvents on RayNeo X3 Pro AR glasses and translates
 * detected gestures into standard Android TV DPAD KeyEvents that SmartTube expects.
 * <p>
 * Gesture mapping:
 * - Single Tap → KEYCODE_DPAD_CENTER
 * - Double Tap → KEYCODE_BACK
 * - Swipe Forward → KEYCODE_DPAD_RIGHT
 * - Swipe Backward → KEYCODE_DPAD_LEFT
 * - Swipe Up → KEYCODE_DPAD_UP
 * - Swipe Down → KEYCODE_DPAD_DOWN
 */
public class RayNeoInputInterceptor {
    private static final String TAG = RayNeoInputInterceptor.class.getSimpleName();

    private final Activity mActivity;
    private TouchDispatcher mTouchDispatcher;
    private CommonTouchCallback mTouchCallback;
    private boolean mInitialized;

    public RayNeoInputInterceptor(Activity activity) {
        mActivity = activity;
        if (RayNeoDeviceUtil.isRayNeoDevice()) {
            try {
                initDispatcher();
            } catch (Throwable e) {
                Log.e(TAG, "Failed to init RayNeo TouchDispatcher: " + e.getMessage());
                mInitialized = false;
            }
        }
    }

    /**
     * Returns true if this interceptor is active (running on RayNeo hardware with SDK).
     */
    public boolean isActive() {
        return mInitialized;
    }

    /**
     * Feed a raw MotionEvent from Activity.dispatchTouchEvent() into the SDK's gesture detector.
     * Returns true if the event was consumed.
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (!mInitialized || mTouchDispatcher == null || mTouchCallback == null) {
            return false;
        }

        try {
            mTouchDispatcher.onMotionEvent(event, mTouchCallback);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Error dispatching touch event: " + e.getMessage());
            return false;
        }
    }

    /**
     * Initialize the RayNeo SDK TouchDispatcher and create a CommonTouchCallback
     * that maps gestures to DPAD KeyEvents.
     */
    private void initDispatcher() {
        mTouchDispatcher = new TouchDispatcher(TouchDispatcher.Source.Activity.INSTANCE);

        mTouchCallback = new CommonTouchCallback() {
            @Override
            public boolean onTPClick() {
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
                return true;
            }

            @Override
            public boolean onTPDoubleClick() {
                injectKeyEvent(KeyEvent.KEYCODE_BACK);
                return true;
            }

            @Override
            public boolean onTPSlideForward(FlingArgs flingArgs) {
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            }

            @Override
            public boolean onTPSlideBackward(FlingArgs flingArgs) {
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            }

            @Override
            public boolean onTPSlideUpwards(FlingArgs flingArgs) {
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
                return true;
            }

            @Override
            public boolean onTPSlideDownwards(FlingArgs flingArgs) {
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
                return true;
            }
        };

        mInitialized = true;
        Log.d(TAG, "RayNeo TouchDispatcher initialized successfully");
    }

    /**
     * Synthesize and inject a DPAD KeyEvent pair (DOWN + UP) into the Activity's dispatch chain.
     */
    private void injectKeyEvent(int keyCode) {
        long now = SystemClock.uptimeMillis();

        KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);

        mActivity.runOnUiThread(() -> {
            try {
                mActivity.dispatchKeyEvent(downEvent);
                mActivity.dispatchKeyEvent(upEvent);
            } catch (Throwable e) {
                Log.e(TAG, "Error injecting key event " + keyCode + ": " + e.getMessage());
            }
        });
    }
}
