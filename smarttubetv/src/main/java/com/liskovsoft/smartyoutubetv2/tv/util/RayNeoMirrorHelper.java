package com.liskovsoft.smartyoutubetv2.tv.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Enables binocular display for RayNeo X3 Pro by splitting the 1280×480 screen
 * into two 640×480 halves using a Hybrid approach:
 * 1. UI is mirrored via a custom 60fps software bitmap capture loop.
 * 2. DRM Video (SurfaceView) is mirrored via the official SDK MirroringView underneath the UI.
 * <p>
 * Does not re-parent the content view (preserves SurfaceView structures).
 * Call {@link #wrapContentView(Activity)} from onPostCreate().
 */
public class RayNeoMirrorHelper {
    private static final String TAG = RayNeoMirrorHelper.class.getSimpleName();

    private View mSourceView;
    private FrameLayout mRightEyeFrame;
    private ImageView mUiMirrorImageView;
    private MirroringView mVideoMirrorView;
    private TextureView mBoundTextureView;

    private boolean mActive;
    private Handler mHandler;
    private Runnable mCaptureRunnable;

    private Bitmap mMirrorBitmap;
    private Canvas mMirrorCanvas;
    private int mHalfWidth;
    private int mScreenHeight;

    public void wrapContentView(Activity activity) {
        if (!RayNeoDeviceUtil.isRayNeoDevice()) {
            return;
        }

        try {
            FrameLayout contentFrame = activity.findViewById(android.R.id.content);
            if (contentFrame == null || contentFrame.getChildCount() == 0) {
                Log.w(TAG, "No content view found to wrap");
                return;
            }

            // Get the screen width to calculate half
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int screenWidth = dm.widthPixels;
            mHalfWidth = screenWidth / 2;
            mScreenHeight = dm.heightPixels;

            // Resize the existing content view to the left half — NO re-parenting!
            mSourceView = contentFrame.getChildAt(0);
            FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                    mHalfWidth, FrameLayout.LayoutParams.MATCH_PARENT);
            contentParams.gravity = Gravity.LEFT;
            mSourceView.setLayoutParams(contentParams);
            
            // To prevent the UI mirror from overflowing, we must force clipping on the source
            if (mSourceView instanceof ViewGroup) {
                ((ViewGroup) mSourceView).setClipChildren(true);
                ((ViewGroup) mSourceView).setClipToPadding(true);
            }

            // Create the right eye container
            mRightEyeFrame = new FrameLayout(activity);
            FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(
                    mHalfWidth, FrameLayout.LayoutParams.MATCH_PARENT);
            rightParams.gravity = Gravity.RIGHT;
            mRightEyeFrame.setLayoutParams(rightParams);
            // Ensure drawing above the left eye root background, but below native popups
            mRightEyeFrame.setElevation(500f);
            contentFrame.addView(mRightEyeFrame);

            // Add the UI mirror on top (index 0 for now, video will be inserted at 0 later)
            mUiMirrorImageView = new ImageView(activity);
            mUiMirrorImageView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            mRightEyeFrame.addView(mUiMirrorImageView);

            mActive = true;
            Log.d(TAG, "Hybrid Binocular mirroring initialized: " + mHalfWidth + "px per eye");

            startHybridCaptureLoop(activity);

        } catch (Throwable e) {
            Log.e(TAG, "Failed to setup binocular mirroring: " + e.getMessage());
            e.printStackTrace();
            mActive = false;
        }
    }

    private void startHybridCaptureLoop(Activity activity) {
        if (mMirrorBitmap == null) {
            mMirrorBitmap = Bitmap.createBitmap(mHalfWidth, mScreenHeight, Bitmap.Config.ARGB_8888);
            mMirrorCanvas = new Canvas(mMirrorBitmap);
        }

        mHandler = new Handler(Looper.getMainLooper());
        mCaptureRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mActive || mSourceView == null) return;

                try {
                    // 1. Mirror the UI Layer (Software Canvas)
                    // Erase previous frame to ensure transparent holes aren't filled with old frames
                    mMirrorBitmap.eraseColor(Color.TRANSPARENT);
                    
                    // Temporarily hide the video root to punch a natural transparent hole 
                    // without destroying the playback UI overlays (which are its siblings)!
                    View surfaceRoot = null;
                    int oldVis = View.VISIBLE;
                    if (mBoundTextureView != null) {
                        surfaceRoot = (View) mBoundTextureView.getParent();
                        if (surfaceRoot != null) {
                            oldVis = surfaceRoot.getVisibility();
                            surfaceRoot.setVisibility(View.INVISIBLE);
                        }
                    }
                    
                    // Also temporarily remove opaque background from the root content frame if it has one
                    android.graphics.drawable.Drawable bg = mSourceView.getBackground();
                    if (bg != null) {
                        mSourceView.setBackground(null);
                    }

                    // Draw the entire left-eye view hierarchy (Video hole naturally remains transparent)
                    mSourceView.draw(mMirrorCanvas);
                    
                    // Restore original visibility and background immediately
                    if (surfaceRoot != null) {
                        surfaceRoot.setVisibility(oldVis);
                    }
                    if (bg != null) {
                        mSourceView.setBackground(bg);
                    }
                    
                    mUiMirrorImageView.setImageBitmap(mMirrorBitmap);
                    mUiMirrorImageView.invalidate();

                    // 2. Poll for TextureView to bind SDK MirroringView
                    if (mBoundTextureView == null) {
                        TextureView tv = findTextureView(mSourceView);
                        if (tv != null) {
                            Log.d(TAG, "Found TextureView for Video Mirroring!");
                            mBoundTextureView = tv;
                            
                            // Initialize official SDK MirroringView specifically for the DRM video
                            mVideoMirrorView = new MirroringView(activity);
                            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                            videoParams.gravity = Gravity.TOP | Gravity.LEFT;
                            mVideoMirrorView.setLayoutParams(videoParams);
                            
                            // Insert BELOW the UI ImageView so the UI draws over the video
                            mRightEyeFrame.addView(mVideoMirrorView, 0);
                            
                            mVideoMirrorView.setSource(mBoundTextureView);
                            mVideoMirrorView.startMirroring();
                        }
                    } else if (mBoundTextureView.getWidth() > 0 && mBoundTextureView.getHeight() > 0) {
                        // Sync MirroringView bounds to exactly match the TextureView's absolute location
                        int[] sourceLoc = new int[2];
                        mSourceView.getLocationInWindow(sourceLoc);
                        
                        int[] tvLoc = new int[2];
                        mBoundTextureView.getLocationInWindow(tvLoc);
                        
                        int relX = tvLoc[0] - sourceLoc[0];
                        int relY = tvLoc[1] - sourceLoc[1];
                        int tvWidth = mBoundTextureView.getWidth();
                        int tvHeight = mBoundTextureView.getHeight();
                        
                        if (mVideoMirrorView != null) {
                            FrameLayout.LayoutParams vp = (FrameLayout.LayoutParams) mVideoMirrorView.getLayoutParams();
                            if (vp.width != tvWidth || vp.height != tvHeight || vp.leftMargin != relX || vp.topMargin != relY) {
                                vp.width = tvWidth;
                                vp.height = tvHeight;
                                vp.leftMargin = relX;
                                vp.topMargin = relY;
                                mVideoMirrorView.setLayoutParams(vp);
                            }
                        }
                    }

                } catch (Throwable e) {
                    Log.w(TAG, "Mirroring loop error: " + e.getMessage());
                }

                // ~60 FPS
                if (mActive) {
                    mHandler.postDelayed(this, 16);
                }
            }
        };
        mHandler.post(mCaptureRunnable);
    }

    private TextureView findTextureView(View root) {
        if (root instanceof TextureView) {
            return (TextureView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextureView result = findTextureView(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    public void destroy() {
        mActive = false;
        if (mHandler != null && mCaptureRunnable != null) {
            mHandler.removeCallbacks(mCaptureRunnable);
        }
        if (mVideoMirrorView != null) {
            try {
                mVideoMirrorView.stopMirroring();
            } catch (Throwable e) {
                Log.w(TAG, "stopMirroring failed: " + e.getMessage());
            }
            mVideoMirrorView = null;
        }
        if (mMirrorBitmap != null) {
            mMirrorBitmap.recycle();
            mMirrorBitmap = null;
        }
        mMirrorCanvas = null;
        mRightEyeFrame = null;
        mUiMirrorImageView = null;
        mSourceView = null;
        mBoundTextureView = null;
    }

    public boolean isActive() {
        return mActive;
    }
}
