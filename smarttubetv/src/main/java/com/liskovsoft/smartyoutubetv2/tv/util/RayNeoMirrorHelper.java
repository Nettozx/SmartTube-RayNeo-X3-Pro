package com.liskovsoft.smartyoutubetv2.tv.util;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Enables binocular display for RayNeo X3 Pro by splitting the 1280×480 screen
 * into two 640×480 halves using the official SDK MirroringView component.
 * <p>
 * Does not re-parent the content view (preserves TextureView surfaces).
 * Call {@link #wrapContentView(Activity)} from onPostCreate().
 */
public class RayNeoMirrorHelper {
    private static final String TAG = RayNeoMirrorHelper.class.getSimpleName();

    private MirroringView mMirrorImageView;
    private View mSourceView;
    private boolean mActive;

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
            int halfWidth = screenWidth / 2;

            // Resize the existing content view to the left half — NO re-parenting!
            mSourceView = contentFrame.getChildAt(0);
            FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                    halfWidth, FrameLayout.LayoutParams.MATCH_PARENT);
            contentParams.gravity = Gravity.LEFT;
            mSourceView.setLayoutParams(contentParams);

            // To prevent the UI mirror from overflowing, we must force clipping on the source
            if (mSourceView instanceof ViewGroup) {
                ((ViewGroup) mSourceView).setClipChildren(true);
                ((ViewGroup) mSourceView).setClipToPadding(true);
            }

            // Add official SDK MirroringView as a sibling in the right half
            mMirrorImageView = new MirroringView(activity);
            mMirrorImageView.setBackgroundColor(0xFF000000);
            FrameLayout.LayoutParams mirrorParams = new FrameLayout.LayoutParams(
                    halfWidth, FrameLayout.LayoutParams.MATCH_PARENT);
            mirrorParams.gravity = Gravity.RIGHT;
            mMirrorImageView.setLayoutParams(mirrorParams);
            mMirrorImageView.setElevation(1000f);
            contentFrame.addView(mMirrorImageView);

            // Bind it and start mirroring
            mMirrorImageView.setSource(mSourceView);
            mMirrorImageView.startMirroring();

            mActive = true;
            Log.d(TAG, "Binocular mirroring initialized using pure official MirroringView: " + halfWidth + "px per eye");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to setup binocular mirroring: " + e.getMessage());
            e.printStackTrace();
            mActive = false;
        }
    }

    public void destroy() {
        if (mMirrorImageView != null) {
            try {
                mMirrorImageView.stopMirroring();
            } catch (Throwable e) {
                Log.w(TAG, "stopMirroring failed: " + e.getMessage());
            }
            mMirrorImageView = null;
        }
        mSourceView = null;
        mActive = false;
    }

    public boolean isActive() {
        return mActive;
    }
}
