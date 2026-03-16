package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.liskovsoft.smartyoutubetv2.tv.util.RayNeoDeviceUtil;

import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback;
import com.ffalcon.mercury.android.sdk.touch.FlingArgs;
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher;

/**
 * Intercepts temple touchpad MotionEvents on RayNeo X3 Pro AR glasses and translates
 * detected gestures into standard Android TV DPAD KeyEvents that SmartTube expects.
 */
public class RayNeoInputInterceptor {
    private static final String TAG = "RayNeoInputInterceptor";

    private final Activity mActivity;
    private TouchDispatcher mTouchDispatcher;
    private CommonTouchCallback mTouchCallback;
    private boolean mInitialized;

    // For continuous scrolling emulation
    private float mAccumulatedDeltaX = 0f;
    private float mAccumulatedDeltaY = 0f;
    private float mLastDeltaX = 0f;
    private float mLastDeltaY = 0f;
    private boolean mInjectedContinuousThisSwipe = false;

    public RayNeoInputInterceptor(Activity activity) {
        mActivity = activity;
        android.util.Log.d(TAG, "Initializing for Activity: " + activity.getClass().getSimpleName());
        try {
            initDispatcher();
        } catch (Throwable e) {
            android.util.Log.e(TAG, "Failed to init RayNeo TouchDispatcher: " + e.getMessage(), e);
            mInitialized = false;
        }
    }

    public boolean isActive() {
        return mInitialized;
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!mInitialized || mTouchDispatcher == null || mTouchCallback == null) {
            return false;
        }

        // Only process events from a touchscreen or touchpad to prevent swallowing mouse events
        if (!event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) && 
            !event.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) {
            return false;
        }

        try {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                android.util.Log.d(TAG, "ACTION_DOWN received, resetting deltas.");
                mAccumulatedDeltaX = 0f;
                mAccumulatedDeltaY = 0f;
                mLastDeltaX = 0f;
                mLastDeltaY = 0f;
                mInjectedContinuousThisSwipe = false;
            }

            mTouchDispatcher.onMotionEvent(event, mTouchCallback);

            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                android.util.Log.d(TAG, "ACTION_UP/CANCEL received.");
            }

            return true; // Consume event to prevent default pointer/drag behavior
        } catch (Throwable e) {
            android.util.Log.e(TAG, "Error dispatching touch event: " + e.getMessage(), e);
            return false;
        }
    }

    private void initDispatcher() {
        mTouchDispatcher = new TouchDispatcher(TouchDispatcher.Source.Activity.INSTANCE);

        mTouchCallback = new CommonTouchCallback() {
            @Override
            public boolean onTPClick() {
                android.util.Log.d(TAG, "onTPClick detected");
                injectKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
                return true;
            }

            @Override
            public boolean onTPDoubleClick() {
                android.util.Log.d(TAG, "onTPDoubleClick detected");
                injectKeyEvent(KeyEvent.KEYCODE_BACK);
                return true;
            }

            @Override
            public boolean onTPSlideForward(FlingArgs flingArgs) {
                android.util.Log.d(TAG, "onTPSlideForward detected (swipe forward)");
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideBackward(FlingArgs flingArgs) {
                android.util.Log.d(TAG, "onTPSlideBackward detected (swipe backward)");
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideUpwards(FlingArgs flingArgs) {
                android.util.Log.d(TAG, "onTPSlideUpwards detected (swipe up)");
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
                }
                return true;
            }

            @Override
            public boolean onTPSlideDownwards(FlingArgs flingArgs) {
                android.util.Log.d(TAG, "onTPSlideDownwards detected (swipe down)");
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
                }
                return true;
            }

            @Override
            public boolean onTPSlideContinuous(float delta, boolean longClick, boolean vertical) {
                handleContinuous(delta, vertical);
                return true;
            }
        };

        mInitialized = true;
        android.util.Log.d(TAG, "RayNeo TouchDispatcher initialized successfully");
    }

    private void ensureFocus() {
        mActivity.runOnUiThread(() -> {
            try {
                if (mActivity.getWindow() != null && mActivity.getWindow().getDecorView() != null) {
                    View decorView = mActivity.getWindow().getDecorView();
                    if (decorView.isInTouchMode()) {
                        android.util.Log.d(TAG, "Exiting touch mode via requestFocus");
                        decorView.requestFocus();
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private void handleContinuous(float delta, boolean vertical) {
        float threshold = 45f; // Threshold for stability
        
        if (vertical) {
            float step = delta - mLastDeltaY;
            mLastDeltaY = delta;
            mAccumulatedDeltaY += step;
            
            if (Math.abs(mAccumulatedDeltaY) > threshold) {
                mInjectedContinuousThisSwipe = true;
                if (mAccumulatedDeltaY > 0) {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
                } else {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
                }
                mAccumulatedDeltaY = 0f;
            }
        } else {
            float step = delta - mLastDeltaX;
            mLastDeltaX = delta;
            mAccumulatedDeltaX += step;

            if (Math.abs(mAccumulatedDeltaX) > threshold) {
                mInjectedContinuousThisSwipe = true;
                if (mAccumulatedDeltaX > 0) {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
                } else {
                    injectKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                mAccumulatedDeltaX = 0f;
            }
        }
    }

    private void injectKeyEvent(int keyCode) {
        android.util.Log.d(TAG, "Injecting key event: " + keyCode + " using Instrumentation");

        // Instrumentation must be called from a background thread
        new Thread(() -> {
            try {
                // Ensure focus highlight is visible before injecting
                ensureFocus();
                
                android.app.Instrumentation inst = new android.app.Instrumentation();
                inst.sendKeyDownUpSync(keyCode);
                
                android.util.Log.d(TAG, "Instrumentation injection success for " + keyCode);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "Instrumentation injection failed: " + e.getMessage());
                
                // Fallback to direct dispatch on UI thread if instrumentation fails
                mActivity.runOnUiThread(() -> {
                    long now = SystemClock.uptimeMillis();
                    KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD);
                    KeyEvent upEvent = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD);
                    boolean consumed = mActivity.dispatchKeyEvent(downEvent);
                    mActivity.dispatchKeyEvent(upEvent);
                    android.util.Log.d(TAG, "Fallback dispatch results: consumed=" + consumed);
                });
            }
        }).start();
    }
}
