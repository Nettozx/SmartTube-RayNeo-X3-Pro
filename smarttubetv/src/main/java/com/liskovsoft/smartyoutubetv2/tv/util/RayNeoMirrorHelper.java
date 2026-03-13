package com.liskovsoft.smartyoutubetv2.tv.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Wraps an Activity's content view in a dual-pane layout for RayNeo X3 Pro binocular display.
 * <p>
 * The X3 Pro has a 1280×480 display (640 per eye, side by side). This helper:
 * 1. Takes the existing content view after setContentView()
 * 2. Places it in the left 640×480 half
 * 3. Adds a mirror ImageView in the right 640×480 half that continuously
 *    captures the left side's bitmap and draws it
 * <p>
 * Uses {@link View#draw(Canvas)} on a software bitmap for reliable capture of
 * both hardware-accelerated UI and video content.
 * <p>
 * Call {@link #wrapContentView(Activity)} from onPostCreate() (after setContentView).
 */
public class RayNeoMirrorHelper {
    private static final String TAG = RayNeoMirrorHelper.class.getSimpleName();
    private static final long MIRROR_INTERVAL_MS = 16; // ~60fps to match display refresh

    private ImageView mMirrorImageView;
    private View mSourceView;
    private Bitmap mMirrorBitmap;
    private Canvas mMirrorCanvas;
    private Handler mHandler;
    private boolean mActive;
    private boolean mMirroring;

    private final Runnable mMirrorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mMirroring || mSourceView == null || mMirrorImageView == null) {
                return;
            }

            try {
                int w = mSourceView.getWidth();
                int h = mSourceView.getHeight();

                if (w > 0 && h > 0) {
                    // Recreate bitmap if size changed
                    if (mMirrorBitmap == null || mMirrorBitmap.getWidth() != w || mMirrorBitmap.getHeight() != h) {
                        if (mMirrorBitmap != null) {
                            mMirrorBitmap.recycle();
                        }
                        mMirrorBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        mMirrorCanvas = new Canvas(mMirrorBitmap);
                    }

                    // Capture the source view's content into the bitmap
                    mMirrorCanvas.drawColor(0xFF000000); // Clear with black (AR transparency)
                    mSourceView.draw(mMirrorCanvas);

                    // Update the mirror ImageView
                    mMirrorImageView.setImageBitmap(mMirrorBitmap);
                }
            } catch (Throwable e) {
                // Don't crash on draw errors
            }

            // Schedule next frame
            if (mMirroring) {
                mHandler.postDelayed(this, MIRROR_INTERVAL_MS);
            }
        }
    };

    /**
     * Wraps the activity's content view for binocular display.
     * Must be called AFTER setContentView() — typically in onPostCreate().
     */
    public void wrapContentView(Activity activity) {
        if (!RayNeoDeviceUtil.isRayNeoDevice()) {
            return;
        }

        try {
            // Get the existing content view
            FrameLayout contentFrame = activity.findViewById(android.R.id.content);

            if (contentFrame == null || contentFrame.getChildCount() == 0) {
                Log.w(TAG, "No content view found to wrap");
                return;
            }

            // Remove the existing content from the FrameLayout
            View originalContent = contentFrame.getChildAt(0);
            contentFrame.removeView(originalContent);
            mSourceView = originalContent;

            // Create a horizontal LinearLayout as the new root (1280×480)
            LinearLayout dualPane = new LinearLayout(activity);
            dualPane.setOrientation(LinearLayout.HORIZONTAL);
            dualPane.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // Left half: original content (weight=1 → 640px on 1280px display)
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            originalContent.setLayoutParams(leftParams);
            dualPane.addView(originalContent);

            // Right half: ImageView mirror (weight=1 → 640px)
            mMirrorImageView = new ImageView(activity);
            mMirrorImageView.setScaleType(ImageView.ScaleType.FIT_XY);
            mMirrorImageView.setBackgroundColor(0xFF000000); // Black background
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            mMirrorImageView.setLayoutParams(rightParams);
            dualPane.addView(mMirrorImageView);

            // Add the dual-pane layout as the new content
            contentFrame.addView(dualPane);

            // Start the continuous mirroring loop
            mHandler = new Handler(Looper.getMainLooper());
            mMirroring = true;
            mActive = true;

            // Wait for the first layout pass before starting mirror capture
            originalContent.post(() -> {
                if (mMirroring) {
                    mHandler.post(mMirrorRunnable);
                }
            });

            Log.d(TAG, "Binocular display mirroring initialized (bitmap-based)");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to setup binocular mirroring: " + e.getMessage());
            e.printStackTrace();
            mActive = false;
        }
    }

    /**
     * Stop mirroring. Call in Activity.onDestroy().
     */
    public void destroy() {
        mMirroring = false;
        if (mHandler != null) {
            mHandler.removeCallbacks(mMirrorRunnable);
        }
        if (mMirrorBitmap != null) {
            mMirrorBitmap.recycle();
            mMirrorBitmap = null;
        }
        mMirrorCanvas = null;
        mMirrorImageView = null;
        mSourceView = null;
        mActive = false;
    }

    public boolean isActive() {
        return mActive;
    }
}
