/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

public class ProtectionView extends LinearLayout {

    private static final long PILL_ANIMATION_MS = 250;
    private static final long FILL_ANIMATION_MS = 100;
    private static final float PILL_ALPHA = 0.35f;
    private static final float CLIP_RADIUS = 1000f;

    public interface Callback {
        void onDismissed();
    }

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClipPath = new Path();
    private final Rect mRingBounds = new Rect();
    private final int[] mLocation = new int[2];

    private View mPill;
    private View mLock;
    private View mRing;
    private Callback mCallback;

    private boolean mSelected;
    private boolean mClipValid;
    private float mStartY;
    private float mDistance;
    private float mFillRadius;
    private ValueAnimator mFillAnimator;

    public ProtectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFillPaint.setColor(0x30FFFFFF);
        setWillNotDraw(false);
    }

    void setCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPill = findViewById(R.id.pill);
        mRing = findViewById(R.id.ring);
        mLock = findViewById(R.id.lock);
        mLock.setOnTouchListener((v, event) -> onLockTouch(event));
    }

    private boolean onLockTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                updateRingBounds();
                mRing.setVisibility(View.INVISIBLE);
                mDistance = 0;
                mStartY = event.getRawY();
                animatePill(1f, PILL_ALPHA, false);
                mSelected = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mSelected) {
                    break;
                }
                mDistance = mStartY - event.getRawY();
                if (!isInsideRingColumn(event.getRawX())) {
                    release(false);
                    break;
                }
                mLock.setVisibility(isThresholdReached() ? View.INVISIBLE : View.VISIBLE);
                mFillRadius = mDistance + mRingBounds.height();
                updateClip();
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (mSelected) {
                    release(isThresholdReached() && isInsideRingColumn(event.getRawX()));
                }
                break;
        }
        return true;
    }

    private void release(boolean success) {
        animatePill(0f, 0f, true);
        animateFill(mDistance);
        mDistance = 0;
        mSelected = false;

        if (success) {
            if (mCallback != null) {
                mCallback.onDismissed();
            }
        } else {
            mLock.setVisibility(View.VISIBLE);
            mLock.setAlpha(1f);
        }
    }

    private boolean isThresholdReached() {
        return mDistance + mRingBounds.height() >= mPill.getHeight();
    }

    private boolean isInsideRingColumn(float rawX) {
        return rawX >= mRingBounds.left && rawX <= mRingBounds.right;
    }

    private void updateRingBounds() {
        mRing.getLocationOnScreen(mLocation);
        mRingBounds.set(mLocation[0], mLocation[1],
                mLocation[0] + mRing.getWidth(), mLocation[1] + mRing.getHeight());
    }

    private void updateClip() {
        getLocationOnScreen(mLocation);
        float left = mRingBounds.left - mLocation[0];
        float bottom = mRingBounds.bottom - mLocation[1];
        mClipPath.reset();
        mClipPath.addRoundRect(left, bottom - mPill.getHeight(), left + mRingBounds.width(),
                bottom, CLIP_RADIUS, CLIP_RADIUS, Path.Direction.CW);
        mClipValid = true;
    }

    private void animatePill(float scale, float alpha, boolean hideAtEnd) {
        mPill.animate().cancel();
        mPill.setVisibility(View.VISIBLE);
        mPill.animate()
                .scaleX(scale)
                .scaleY(scale)
                .alpha(alpha)
                .setDuration(PILL_ANIMATION_MS)
                .setListener(!hideAtEnd ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPill.setVisibility(View.INVISIBLE);
                        mRing.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void animateFill(float from) {
        if (mFillAnimator != null) {
            mFillAnimator.cancel();
        }
        mFillAnimator = ValueAnimator.ofFloat(from, 0f);
        mFillAnimator.setDuration(FILL_ANIMATION_MS);
        mFillAnimator.setInterpolator(t -> t * t * t);
        mFillAnimator.addUpdateListener(a -> {
            mFillRadius = (float) a.getAnimatedValue();
            invalidate();
        });
        mFillAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mClipValid || mFillRadius <= 0) {
            return;
        }
        getLocationOnScreen(mLocation);
        canvas.save();
        canvas.clipPath(mClipPath);
        canvas.drawCircle(mRingBounds.centerX() - mLocation[0], mRingBounds.bottom - mLocation[1],
                mFillRadius, mFillPaint);
        canvas.restore();
    }
}
