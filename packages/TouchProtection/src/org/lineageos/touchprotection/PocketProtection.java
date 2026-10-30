/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.PrintWriter;

/**
 * Decides on every screen-on whether the phone is in a pocket and, once something touches
 * the screen there, shows the protection overlay.
 *
 * The sensor hub's pocket verdict gives the proximity side, the light sensor ticks drive the
 * decision. Hub readings it can't classify fall back to the proximity clusters and then to
 * watching for palm or multi-finger touches in the dark.
 */
class PocketProtection implements SensorEventListener {

    private static final String TAG = "TouchProtection";

    private static final String SENSOR_POCKET = "com.samsung.sensor.pocket_mode";
    private static final String SENSOR_POCKET_POSITION = "com.samsung.sensor.pocket_pos_mode";
    private static final String SENSOR_LIGHT = "com.samsung.sensor.light_cct";

    // Pocket sensor values
    private static final int POCKET_STATE = 0;
    private static final int POCKET_REASON = 1;
    private static final int POCKET_PROXIMITY = 3;
    private static final int POCKET_LUX = 4;
    private static final int POCKET_MIN_RELEASE = 6;
    private static final int POCKET_MIN_RECOGNITION = 7;
    private static final int POCKET_LAST_OPEN_PROXIMITY = 8;
    private static final int POCKET_VERSION = 15;
    private static final int REASON_DEBUG = 7;

    private static final int MODE_PROXIMITY = 0;
    private static final int MODE_TOUCH = 1;

    private static final int BIT_DARK = 1;
    private static final int BIT_PROXIMITY = 2;
    private static final int BIT_PALM = 8;

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_NOT_IN_POCKET = 1;
    private static final int STATE_IN_POCKET = 2;
    private static final int STATE_TOUCHED = 3;
    private static final int STATE_SHOWING = 4;

    private static final long LIGHT_MONITORING_MS = 100;
    private static final long POPUP_GRACE_MS = 500;
    private static final long TOUCH_RECOGNITION_MS = 3000;

    private static final float LUX_DEFAULT = 10;
    private static final float LUX_UPSIDE_DOWN = 20;
    private static final float LUX_POCKET_POSITION = 150;
    private static final float ACCEL_Y_UPSIDE_DOWN = -6.5f;
    private static final int POCKET_POSITION_MIN_VERSION = 24081900;
    private static final int PALM_MIN_POINTERS = 3;

    private final SensorManager mSensorManager;
    private final Handler mHandler;
    private final Sensor mPocketSensor;
    private final Sensor mPositionSensor;
    private final Sensor mLightSensor;
    private final Sensor mAccelSensor;
    private final ProximityClusters mClusters = new ProximityClusters();
    private final TouchMonitor mTouchMonitor;
    private final ProtectionOverlay mOverlay;

    private boolean mScreenOn;
    private boolean mSensing;
    private int mMode = MODE_PROXIMITY;
    private int mState = STATE_UNKNOWN;
    private int mBits;
    private int mProximity = ProximityClusters.UNKNOWN;
    private int mLightTotal;
    private int mLightDark;
    private long mLightRegisterTime;
    private long mPopupShowTime;

    private boolean mPocketReceived;
    private float mHubLux;
    private int mHubVersion;
    private boolean mPositionReceived;
    private int mPositionState;
    private float mAccelY;

    private boolean mTouched;
    private boolean mWatchingPalm;

    private final Runnable mStopWatchingPalm = () -> mWatchingPalm = false;

    PocketProtection(Context context, Handler handler) {
        mSensorManager = context.getSystemService(SensorManager.class);
        mHandler = handler;
        mPocketSensor = Utils.findSensor(mSensorManager, SENSOR_POCKET);
        mPositionSensor = Utils.findSensor(mSensorManager, SENSOR_POCKET_POSITION);
        mLightSensor = Utils.findSensor(mSensorManager, SENSOR_LIGHT);
        mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mTouchMonitor = new TouchMonitor(this::onTouch);
        mOverlay = new ProtectionOverlay(context, this::onDismissed);
    }

    boolean isSupported() {
        return mPocketSensor != null && mLightSensor != null;
    }

    void enable() {
        // The hub only reports on change, so the pocket sensors stay registered.
        register(mPocketSensor);
        register(mPositionSensor);
    }

    void disable() {
        onScreenOff();
        mSensorManager.unregisterListener(this);
    }

    void onScreenOn() {
        mScreenOn = true;
        register(mAccelSensor);
        startSensing();
    }

    void onScreenOff() {
        mScreenOn = false;
        stopSensing();
        mOverlay.hide();
        mTouched = false;
        mMode = MODE_PROXIMITY;
        mPocketReceived = false;
        mPositionReceived = false;
        mHubLux = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == mPocketSensor) {
            onPocketChanged(event.values);
        } else if (event.sensor == mPositionSensor) {
            mPositionState = (int) event.values[0];
            mPositionReceived = true;
        } else if (event.sensor == mLightSensor) {
            onLightChanged(event.values);
        } else if (event.sensor == mAccelSensor) {
            mAccelY = event.values[1];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    void dump(PrintWriter pw) {
        pw.println("  screenOn=" + mScreenOn + " sensing=" + mSensing + " mode=" + mMode
                + " state=" + mState + " bits=" + mBits + " proximity=" + mProximity);
        pw.println("  light=" + mLightDark + "/" + mLightTotal + " hubLux=" + mHubLux
                + " hubVersion=" + mHubVersion + " position=" + mPositionState
                + " touched=" + mTouched + " overlay=" + mOverlay.isShowing());
        pw.println("  clusters: " + mClusters);
    }

    private void onPocketChanged(float[] values) {
        int state = (int) values[POCKET_STATE];
        int reason = (int) values[POCKET_REASON];
        int proximity = (int) values[POCKET_PROXIMITY];
        Log.d(TAG, "Pocket state " + state + " reason " + reason + " proximity " + proximity
                + " lux " + values[POCKET_LUX]);

        // Each measurement is preceded by a debug copy that carries no usable data.
        if (reason % 10 == REASON_DEBUG) {
            return;
        }

        mPocketReceived = true;
        mHubLux = values[POCKET_LUX];
        mHubVersion = (int) values[POCKET_VERSION];

        if (proximity != 0) {
            mClusters.add(proximity);
        }
        if (state == ProximityClusters.UNKNOWN) {
            state = mClusters.classify(proximity);
        }

        switch (state) {
            case ProximityClusters.UNKNOWN:
                if (mSensing && mMode == MODE_PROXIMITY) {
                    Log.d(TAG, "Proximity unknown, watching touches");
                    mMode = MODE_TOUCH;
                    mWatchingPalm = true;
                    mHandler.postDelayed(mStopWatchingPalm, TOUCH_RECOGNITION_MS);
                }
                break;
            case ProximityClusters.IN:
                // The hub can't be trusted before it has calibrated itself.
                boolean uncalibrated = values[POCKET_MIN_RELEASE] == 0
                        && values[POCKET_MIN_RECOGNITION] == 0;
                if (uncalibrated || values[POCKET_LAST_OPEN_PROXIMITY] >= proximity) {
                    Log.d(TAG, "Pocket verdict rejected");
                    setProximity(ProximityClusters.OUT);
                    mHandler.post(this::resetPocketSensor);
                    stopSensing();
                } else {
                    setProximity(ProximityClusters.IN);
                }
                break;
            case ProximityClusters.OUT:
                setProximity(ProximityClusters.OUT);
                break;
        }
    }

    private void onLightChanged(float[] values) {
        if (!mSensing || (mMode == MODE_PROXIMITY && !mPocketReceived)) {
            return;
        }

        // While nothing is shown, the light sensor only paces the hub's own lux reading.
        float lux = mMode == MODE_TOUCH || mOverlay.isShowing() ? values[0] : mHubLux;
        if (lux < 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (mOverlay.isShowing() && now - mPopupShowTime < POPUP_GRACE_MS) {
            if (mMode == MODE_PROXIMITY && mProximity == ProximityClusters.OUT) {
                leavePocket();
            }
            return;
        }

        float threshold = getLuxThreshold();
        mLightTotal++;
        if (lux <= threshold) {
            mBits |= BIT_DARK;
            mLightDark++;
        } else {
            mBits &= ~(BIT_DARK | BIT_PALM);
            mLightDark = 0;
        }

        if (!mScreenOn || lux > threshold
                || (mMode == MODE_PROXIMITY && mProximity == ProximityClusters.OUT)) {
            Log.d(TAG, "Out of pocket, lux " + lux + " threshold " + threshold);
            leavePocket();
            return;
        }

        int inPocketBits = BIT_DARK | (mMode == MODE_PROXIMITY ? BIT_PROXIMITY : BIT_PALM);
        if (now - mLightRegisterTime >= LIGHT_MONITORING_MS && mLightDark == mLightTotal
                && mBits == inPocketBits) {
            enterPocket();
        }
    }

    private float getLuxThreshold() {
        if (mPositionReceived && mHubVersion >= POCKET_POSITION_MIN_VERSION
                && mPositionState == 1 && mProximity == ProximityClusters.IN) {
            return LUX_POCKET_POSITION;
        }
        if (mMode == MODE_PROXIMITY && mProximity == ProximityClusters.IN
                && mAccelY <= ACCEL_Y_UPSIDE_DOWN) {
            return LUX_UPSIDE_DOWN;
        }
        return LUX_DEFAULT;
    }

    private void enterPocket() {
        if (mState == STATE_UNKNOWN || mState == STATE_NOT_IN_POCKET) {
            Log.d(TAG, "In pocket");
            // A palm or several fingers already counts as the touch.
            mState = mMode == MODE_TOUCH ? STATE_TOUCHED : STATE_IN_POCKET;
        }
        if (mState == STATE_IN_POCKET && mTouched) {
            mState = STATE_TOUCHED;
        }
        if (mState == STATE_TOUCHED && mScreenOn) {
            Log.d(TAG, "Showing protection");
            mPopupShowTime = SystemClock.uptimeMillis();
            mOverlay.show();
            mState = STATE_SHOWING;
        }
    }

    private void leavePocket() {
        stopSensing();
        mState = STATE_NOT_IN_POCKET;
        mOverlay.hide();
    }

    private void onDismissed() {
        stopSensing();
        resetPocketSensor();
        mClusters.clear();
    }

    private void onTouch(MotionEvent event) {
        mTouched = true;
        if (mWatchingPalm && (event.getPointerCount() >= PALM_MIN_POINTERS || isPalm(event))) {
            mBits |= BIT_PALM;
        }
    }

    private static boolean isPalm(MotionEvent event) {
        if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_PALM) {
                return true;
            }
        }
        return false;
    }

    private void setProximity(int proximity) {
        mProximity = proximity;
        if (proximity == ProximityClusters.IN) {
            mBits |= BIT_PROXIMITY;
        } else {
            mBits &= ~BIT_PROXIMITY;
        }
    }

    private void startSensing() {
        if (mSensing) {
            return;
        }
        mSensing = true;
        mState = STATE_UNKNOWN;
        mBits = 0;
        mProximity = ProximityClusters.OUT;
        mLightTotal = 0;
        mLightDark = 0;
        mPopupShowTime = 0;
        register(mLightSensor);
        mLightRegisterTime = SystemClock.uptimeMillis();
        mTouchMonitor.start();
    }

    private void stopSensing() {
        if (!mSensing) {
            return;
        }
        mSensing = false;
        mSensorManager.unregisterListener(this, mLightSensor);
        mSensorManager.unregisterListener(this, mAccelSensor);
        mTouchMonitor.stop();
        mHandler.removeCallbacks(mStopWatchingPalm);
        mWatchingPalm = false;
        mBits = 0;
        mProximity = ProximityClusters.UNKNOWN;
    }

    private void resetPocketSensor() {
        mSensorManager.unregisterListener(this, mPocketSensor);
        register(mPocketSensor);
    }

    private void register(Sensor sensor) {
        if (sensor != null) {
            mSensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                    mHandler);
        }
    }
}
