/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.WindowManager;

class ProtectionOverlay {

    private static final long SCREEN_TIMEOUT_MS = 10000;

    private final Context mContext;
    private final KeyguardManager mKeyguardManager;
    private final PowerManager mPowerManager;
    private final WindowManager mWindowManager;
    private final ProtectionView.Callback mCallback;

    private ProtectionView mView;

    ProtectionOverlay(Context context, ProtectionView.Callback callback) {
        mContext = context;
        mCallback = callback;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    boolean isShowing() {
        return mView != null;
    }

    void show() {
        if (mView != null) {
            return;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_BOOT_PROGRESS,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("UnintentionalLcdOn");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        // Touches on the overlay must not keep the screen on.
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;

        if (mKeyguardManager.isKeyguardLocked()) {
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        } else {
            lp.userActivityTimeout = SCREEN_TIMEOUT_MS;
        }

        mView = (ProtectionView) LayoutInflater.from(mContext)
                .inflate(R.layout.protection_overlay, null);
        mView.setCallback(() -> {
            hide();
            mCallback.onDismissed();
        });
        mWindowManager.addView(mView, lp);
    }

    void hide() {
        if (mView == null) {
            return;
        }
        mWindowManager.removeViewImmediate(mView);
        mView = null;
    }
}
