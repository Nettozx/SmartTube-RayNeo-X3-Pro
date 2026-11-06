package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.leanback.widget.TitleView;

import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback;
import com.ffalcon.mercury.android.sdk.touch.FlingArgs;
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher;
import com.ffalcon.mercury.android.sdk.util.MyTouchUtils;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Intercepts temple touchpad MotionEvents on RayNeo X3 Pro AR glasses and translates
 * detected gestures into standard Android TV DPAD KeyEvents that SmartTube expects.
 */
public class RayNeoInputInterceptor {
    private static final String TAG = "RayNeoInputInterceptor";
    private static final float SWIPE_THRESHOLD = 45f;
    private static final ExecutorService sInputExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("RayNeoInputThread");
        return t;
    });

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
    // Title bar (search/account orbs): one step per swipe, or the burst overshoots into the rows
    private volatile boolean mFocusInTitleBar = false;
    private boolean mOneStepSwipe = false;
    private WeakReference<View> mLastFocused;

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

        // Ignore the left temple touchpad (typically used for system volume/brightness)
        if (MyTouchUtils.isLeft(event)) {
            return false;
        }

        // Only process events from a touchscreen or touchpad to prevent swallowing mouse events
        if ((event.getSource() & android.view.InputDevice.SOURCE_TOUCHSCREEN) != android.view.InputDevice.SOURCE_TOUCHSCREEN && 
            (event.getSource() & android.view.InputDevice.SOURCE_TOUCHPAD) != android.view.InputDevice.SOURCE_TOUCHPAD) {
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
                mOneStepSwipe = mFocusInTitleBar;
            }

            mTouchDispatcher.onMotionEvent(event, mTouchCallback);

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
                // The SDK also reports short swipes as a click (~300ms after touch down).
                // Ignore it if this touch already moved focus, or swiping onto e.g. the search orb opens it.
                if (mInjectedContinuousThisSwipe) {
                    android.util.Log.d(TAG, "onTPClick ignored - touch was a swipe");
                    return true;
                }

                android.util.Log.d(TAG, "onTPClick detected - explicitly injecting click");
                injectClick();
                return true;
            }

            @Override
            public boolean onTPDoubleClick() {
                android.util.Log.d(TAG, "onTPDoubleClick detected - injecting BACK");
                injectKeyEventAsync(KeyEvent.KEYCODE_BACK);
                return true;
            }

            @Override
            public boolean onTPSlideForward(FlingArgs flingArgs) {
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_RIGHT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideBackward(FlingArgs flingArgs) {
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideUpwards(FlingArgs flingArgs) {
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_UP);
                }
                return true;
            }

            @Override
            public boolean onTPSlideDownwards(FlingArgs flingArgs) {
                if (!mInjectedContinuousThisSwipe) {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_DOWN);
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

    /**
     * The temple touchpad reports as a touchscreen, so each touch puts the window into touch mode,
     * which clears focus from views not focusable in touch mode (e.g. the title bar orbs).
     * Requesting focus on the decor view then lands on a default view (the first video), so put
     * focus back on the view that had it after the last injected key instead.
     * Runs inline on the UI thread; from other threads, waits until it has run.
     */
    private void ensureFocus() {
        Runnable restore = () -> {
            try {
                if (mActivity.getWindow() == null || mActivity.getWindow().getDecorView() == null) {
                    return;
                }

                View decorView = mActivity.getWindow().getDecorView();
                if (!decorView.isInTouchMode()) {
                    return;
                }

                View last = mLastFocused != null ? mLastFocused.get() : null;
                if (last != null && ViewCompat.isAttachedToWindow(last) && last.isShown() && last.isFocusable()) {
                    android.util.Log.d(TAG, "Exiting touch mode, restoring focus to " + last.getClass().getName());
                    boolean focusableInTouchMode = last.isFocusableInTouchMode();
                    last.setFocusableInTouchMode(true);
                    last.requestFocus();
                    last.setFocusableInTouchMode(focusableInTouchMode);
                } else {
                    android.util.Log.d(TAG, "Exiting touch mode via requestFocus");
                    decorView.requestFocus();
                }
            } catch (Throwable ignored) {}
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            restore.run();
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        mActivity.runOnUiThread(() -> {
            restore.run();
            done.countDown();
        });
        try {
            done.await(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    private void handleContinuous(float delta, boolean vertical) {
        if (mOneStepSwipe && mInjectedContinuousThisSwipe) {
            return;
        }

        if (vertical) {
            float step = delta - mLastDeltaY;
            mLastDeltaY = delta;
            mAccumulatedDeltaY += step;
            
            if (Math.abs(mAccumulatedDeltaY) > SWIPE_THRESHOLD) {
                mInjectedContinuousThisSwipe = true;
                if (mAccumulatedDeltaY > 0) {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_DOWN);
                } else {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_UP);
                }
                mAccumulatedDeltaY = 0f;
            }
        } else {
            float step = delta - mLastDeltaX;
            mLastDeltaX = delta;
            mAccumulatedDeltaX += step;

            if (Math.abs(mAccumulatedDeltaX) > SWIPE_THRESHOLD) {
                mInjectedContinuousThisSwipe = true;
                if (mAccumulatedDeltaX > 0) {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_RIGHT);
                } else {
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                mAccumulatedDeltaX = 0f;
            }
        }
    }

    /**
     * Remember whether focus is in the title bar after each injected key. Read on the next touch down,
     * since by then touch mode may already have cleared focus. Must run on the UI thread.
     */
    private void updateFocusState() {
        View focus = mActivity.getWindow() != null ? mActivity.getWindow().getCurrentFocus() : null;
        boolean inTitleBar = false;
        for (View view = focus; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view instanceof TitleView) {
                inTitleBar = true;
                break;
            }
        }
        mFocusInTitleBar = inTitleBar;
        if (focus != null) {
            mLastFocused = new WeakReference<>(focus);
        }
        android.util.Log.d(TAG, "Focus after key: " + (focus != null ? focus.getClass().getSimpleName() : "none") + (inTitleBar ? " (title bar)" : ""));
    }

    private void injectClick() {
        mActivity.runOnUiThread(() -> {
            try {
                ensureFocus();
                View focus = null;
                if (mActivity.getWindow() != null) {
                    focus = mActivity.getWindow().getCurrentFocus();
                }
                if (focus != null) {
                    android.util.Log.d(TAG, "Explicitly clicking focused view: " + focus.getClass().getSimpleName());
                    boolean clicked = focus.performClick();
                    if (!clicked) {
                        android.util.Log.d(TAG, "performClick returned false, falling back to KeyEvent");
                        injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER);
                    }
                } else {
                    android.util.Log.d(TAG, "No focused view found, falling back to KeyEvent");
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER);
                }
            } catch (Throwable e) {
                android.util.Log.e(TAG, "Error in injectClick: " + e.getMessage());
                injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER);
            }
        });
    }

    private void injectKeyEventAsync(int keyCode) {
        sInputExecutor.execute(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                return;
            }

            try {
                // Ensure focus highlight is visible before injecting
                ensureFocus();
                
                android.app.Instrumentation inst = new android.app.Instrumentation();
                
                // Synchronously force the system out of touch mode so that DPAD_CENTER isn't swallowed
                // as a "wake up from touch" event.
                inst.setInTouchMode(false);
                
                inst.sendKeyDownUpSync(keyCode);
                
                // SmartTube dialogs/menus sometimes prefer ENTER over DPAD_CENTER
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    inst.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
                }

                mActivity.runOnUiThread(this::updateFocusState);
            } catch (Throwable e) {
                // Fallback to direct dispatch on UI thread if instrumentation fails
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD);
                    KeyEvent upEvent = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD);
                    mActivity.dispatchKeyEvent(downEvent);
                    mActivity.dispatchKeyEvent(upEvent);
                    updateFocusState();
                });
            }
        });
    }
}
