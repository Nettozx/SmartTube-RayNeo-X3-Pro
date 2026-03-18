package com.liskovsoft.smartyoutubetv2.tv.util;

import android.app.Activity;
import android.os.Bundle;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.RayNeoDeviceUtil;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler.RayNeoInputInterceptor;

/**
 * Orchestrates RayNeo X3 Pro hardware-specific features (input interception, binocular mirroring)
 * across the activity lifecycle.
 */
public class RayNeoHardwareManager {
    private final Activity mActivity;
    private RayNeoInputInterceptor mInputInterceptor;
    private RayNeoMirrorHelper mMirrorHelper;
    private final boolean mIsRayNeo;

    public RayNeoHardwareManager(Activity activity) {
        mActivity = activity;
        mIsRayNeo = RayNeoDeviceUtil.isRayNeoDevice();
    }

    public void onCreate(Bundle savedInstanceState) {
        if (!mIsRayNeo) {
            return;
        }

        // Wire temple touch gestures to DPAD KeyEvents
        mInputInterceptor = new RayNeoInputInterceptor(mActivity);
        if (mInputInterceptor.isActive() && mActivity instanceof MotherActivity) {
            ((MotherActivity) mActivity).setTouchEventInterceptor(mInputInterceptor::onTouchEvent);
        }

        // Initialize mirroring helper
        mMirrorHelper = new RayNeoMirrorHelper();
    }

    public void onPostCreate(Bundle savedInstanceState) {
        if (!mIsRayNeo) {
            return;
        }

        // Wrap content view for binocular display mirroring
        if (mMirrorHelper != null) {
            mMirrorHelper.wrapContentView(mActivity);
        }
    }

    public void onDestroy() {
        if (mMirrorHelper != null) {
            mMirrorHelper.destroy();
        }
        mInputInterceptor = null;
        mMirrorHelper = null;
    }
}
