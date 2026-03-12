package com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.leanback.app.PlaybackSupportFragment;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout.ResizeMode;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerEngine;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.util.RayNeoDeviceUtil;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * Subclass of {@link PlaybackSupportFragment} that is responsible for providing a {@link SurfaceView}
 * and rendering video.
 */
public class SurfacePlaybackFragment extends PlaybackSupportFragment {
    private static final String TAG = SurfacePlaybackFragment.class.getSimpleName();
    private SurfaceWrapper mVideoSurfaceWrapper;
    private AspectRatioFrameLayout mVideoSurfaceRoot;
    private SubtitleView mLeanbackSubtitles;
    private int mSubtitlesPadding;
    private int mBackgroundResId;
    private float mAspectRatio;
    private float mPixelRatio = 1.0f;
    private float mVideoAspectRatio;
    // RayNeo binocular video mirroring support
    private View mMirroringView;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup root = (ViewGroup) super.onCreateView(inflater, container, savedInstanceState);
        if (root == null) {
            throw new IllegalStateException("Can't create root of SurfacePlaybackFragment");
        }

        // On RayNeo X3 Pro, force TextureView mode — MirroringView.setSource() requires TextureView
        boolean isRayNeo = RayNeoDeviceUtil.isRayNeoDevice();
        mVideoSurfaceWrapper = (isRayNeo || PlayerTweaksData.instance(getContext()).isTextureViewEnabled() ||
                PlayerData.instance(getContext()).getRotationAngle() != 0) ?
                new TextureViewWrapper(getContext(), root) : new SurfaceViewWrapper(getContext(), root);

        mVideoSurfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
        mVideoSurfaceRoot.setAspectRatioListener((targetAspectRatio, naturalAspectRatio, aspectRatioMismatch) -> scaleIfNeeded());
        mLeanbackSubtitles = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.leanback_subtitles);
        mSubtitlesPadding = mLeanbackSubtitles.getPaddingLeft();
        setBackgroundType(PlaybackSupportFragment.BG_LIGHT);

        // RayNeo X3 Pro: Inject MirroringView for binocular video fusion
        if (isRayNeo) {
            initRayNeoMirroring();
        }

        return root;
    }

    /**
     * Adds {@link SurfaceHolder.Callback} to {@link SurfaceView}.
     */
    public void setSurfaceHolderCallback(SurfaceHolder.Callback callback) {
        if (mVideoSurfaceWrapper != null) {
            mVideoSurfaceWrapper.setSurfaceHolderCallback(callback);
        }
    }

    @Override
    protected void onVideoSizeChanged(int width, int height) {
        mVideoAspectRatio = ((float) width) / height;
        mVideoSurfaceRoot.setAspectRatio(calculateAspectRatio());
    }

    /**
     * Returns the surface view.
     */
    public View getSurfaceView() {
        return mVideoSurfaceWrapper.getSurfaceView();
    }

    @Override
    public void onDestroyView() {
        stopRayNeoMirroring();
        mVideoSurfaceWrapper = null;
        super.onDestroyView();
    }

    /** Returns the {@link ResizeMode}. */
    protected @ResizeMode int getResize() {
        return mVideoSurfaceRoot.getResizeMode();
    }

    /**
     * Sets the {@link ResizeMode}.
     *
     * @param resizeMode The {@link ResizeMode}.
     */
    protected void setResize(@ResizeMode int resizeMode) {
        mVideoSurfaceRoot.setResizeMode(resizeMode);
    }

    protected void setZoom(int percents) {
        mVideoSurfaceRoot.setZoom(percents);
    }

    protected void setAspect(float aspectRatio) {
        mAspectRatio = aspectRatio;
        mVideoSurfaceRoot.setAspectRatio(calculateAspectRatio());
    }

    protected void setRotation(int angle) {
        if (Helpers.floatEquals(mVideoSurfaceRoot.getRotation(), angle) || mVideoSurfaceWrapper == null) {
            return;
        }

        if (mVideoSurfaceWrapper instanceof TextureViewWrapper) {
            mVideoSurfaceRoot.setRotation(angle);
        } else {
            mVideoSurfaceRoot.removeView(mVideoSurfaceWrapper.getSurfaceView());
            mVideoSurfaceWrapper = new TextureViewWrapper(getContext(), (ViewGroup) getView());
            mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
            mVideoSurfaceRoot.setRotation(angle);

            ((PlayerEngine) this).restartEngine();
        }
    }

    protected void setFlipEnabled(boolean enabled) {
        float scaleX = enabled ? -1f : 1f;

        if (Helpers.floatEquals(mVideoSurfaceRoot.getScaleX(), scaleX) || mVideoSurfaceWrapper == null) {
            return;
        }

        if (mVideoSurfaceWrapper instanceof TextureViewWrapper) {
            mVideoSurfaceRoot.setScaleX(scaleX);
        } else {
            mVideoSurfaceRoot.removeView(mVideoSurfaceWrapper.getSurfaceView());
            mVideoSurfaceWrapper = new TextureViewWrapper(getContext(), (ViewGroup) getView());
            mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
            mVideoSurfaceRoot.setScaleX(scaleX);

            ((PlayerEngine) this).restartEngine();
        }
    }

    private void scaleIfNeeded() {
        if (!(mVideoSurfaceWrapper instanceof TextureViewWrapper)) {
            return;
        }

        if (mVideoSurfaceRoot.getWidth() == 0 || mVideoSurfaceRoot.getHeight() == 0) {
            return;
        }

        float angle = mVideoSurfaceRoot.getRotation();

        int width, height;

        if (Helpers.floatEquals(angle, 90) || Helpers.floatEquals(angle, 270)) {
            float ratio = mVideoSurfaceRoot.getWidth() / ((float) mVideoSurfaceRoot.getHeight());

            width = mVideoSurfaceRoot.getHeight();
            height = (int) (mVideoSurfaceRoot.getHeight() / ratio);
        } else {
            width = mVideoSurfaceRoot.getWidth();
            height = mVideoSurfaceRoot.getHeight();
        }

        // https://stackoverflow.com/questions/52196362/how-resize-textureview-to-fullscreen-when-rotation-90
        View textureView = mVideoSurfaceWrapper.getSurfaceView();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER;
        textureView.setLayoutParams(params);
    }

    protected void setPixelRatio(float pixelRatio) {
        mPixelRatio = pixelRatio;
    }

    /**
     * Setup player's background used when controls are showed.
     * @param resId background
     */
    protected void setBackgroundResource(int resId) {
        if (resId <= 0 || mBackgroundResId == resId) {
            return;
        }

        View backgroundView = (View) Helpers.getField(this, "mBackgroundView");

        if (backgroundView != null) {
            backgroundView.setBackgroundResource(resId);
            mBackgroundResId = resId;
        }
    }

    protected void setGravity(int gravity) {
        ViewUtil.setGravity(mVideoSurfaceRoot, gravity);

        scaleSubtitles(gravity);
    }

    private void scaleSubtitles(int gravity) {
        if ((gravity & Gravity.START) == Gravity.START) {
            scaleSubtitles(scaleSubsWidth(), scaleSubsPadding(), Gravity.START);
        } else if ((gravity & Gravity.END) == Gravity.END) {
            scaleSubtitles(scaleSubsWidth(), scaleSubsPadding(), Gravity.END);
        } else if ((gravity & Gravity.CENTER) == Gravity.CENTER) {
            scaleSubtitles(ViewGroup.LayoutParams.MATCH_PARENT, mSubtitlesPadding, Gravity.CENTER);
        }
    }

    private void scaleSubtitles(int width, int padding, int gravity) {
        ViewUtil.setWidth(mLeanbackSubtitles, width);
        ViewUtil.setPadding(mLeanbackSubtitles, padding);
        ViewUtil.setGravity(mLeanbackSubtitles, gravity);
    }

    private int scaleSubsWidth() {
        View parent = (View) mLeanbackSubtitles.getParent();
        return parent.getWidth() / 100 * calculateZoom();
    }

    private int scaleSubsPadding() {
        return mSubtitlesPadding / 100 * calculateZoom();
    }

    private int calculateZoom() {
        View parent = (View) mLeanbackSubtitles.getParent();
        int widthRatio = mVideoSurfaceRoot.getWidth() * 100 / parent.getWidth();
        return mVideoSurfaceRoot.getZoom() * widthRatio / 100;
    }

    private float calculateAspectRatio() {
        return (mAspectRatio == 0 ? mVideoAspectRatio : mAspectRatio) * mPixelRatio;
    }

    /**
     * Initialize RayNeo MirroringView to duplicate the TextureView content to the right eye.
     * The left eye sees the TextureView directly; the MirroringView renders a copy for the right eye.
     */
    private void initRayNeoMirroring() {
        try {
            View surfaceView = mVideoSurfaceWrapper.getSurfaceView();
            if (!(surfaceView instanceof android.view.TextureView)) {
                Log.w(TAG, "RayNeo: MirroringView requires TextureView, got " + surfaceView.getClass().getSimpleName());
                return;
            }

            // Create MirroringView via reflection to avoid hard compile-time dependency
            Class<?> mirroringViewClass = Class.forName("com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView");
            mMirroringView = (View) mirroringViewClass
                    .getConstructor(android.content.Context.class)
                    .newInstance(getContext());

            // Add MirroringView as sibling in the same AspectRatioFrameLayout
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            mVideoSurfaceRoot.addView(mMirroringView, params);

            // Bind: mirroringView.setSource(textureView)
            mirroringViewClass.getMethod("setSource", android.view.TextureView.class)
                    .invoke(mMirroringView, (android.view.TextureView) surfaceView);

            // Start mirroring: mirroringView.startMirroring()
            mirroringViewClass.getMethod("startMirroring").invoke(mMirroringView);

            Log.d(TAG, "RayNeo: MirroringView initialized successfully for binocular video fusion");
        } catch (Throwable e) {
            // SDK classes not available or API mismatch — fall back to single-eye rendering
            Log.w(TAG, "RayNeo: MirroringView setup failed: " + e.getMessage());
            mMirroringView = null;
        }
    }

    /**
     * Stop mirroring and clean up the MirroringView.
     */
    private void stopRayNeoMirroring() {
        if (mMirroringView != null) {
            try {
                mMirroringView.getClass().getMethod("stopMirroring").invoke(mMirroringView);
            } catch (Throwable e) {
                    Log.w(TAG, "RayNeo: stopMirroring failed: " + e.getMessage());
            }
            if (mVideoSurfaceRoot != null) {
                mVideoSurfaceRoot.removeView(mMirroringView);
            }
            mMirroringView = null;
        }
    }
}
