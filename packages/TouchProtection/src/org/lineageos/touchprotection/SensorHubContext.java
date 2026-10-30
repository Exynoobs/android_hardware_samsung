/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

/**
 * Tells the sensor hub about the display state. The hub's pocket algorithm only measures
 * after it has been told that the screen turned on.
 */
class SensorHubContext implements SensorEventListener, DisplayManager.DisplayListener {

    private static final String TAG = "TouchProtection";
    private static final String SENSOR_HUBS = "com.samsung.sensor.sensorhubs";

    private static final byte[] LCD_INTENT_ON = {(byte) 0xc1, 0x47, 0x00, (byte) 0xf1};
    private static final byte[] LCD_INTENT_OFF = {(byte) 0xc1, 0x47, 0x00, (byte) 0xf2};
    private static final byte[] LCD_PANEL_ON = {(byte) 0xc1, 0x47, 0x00, 0x01};
    private static final byte[] LCD_PANEL_OFF = {(byte) 0xc1, 0x47, 0x00, 0x00};
    private static final byte[] AP_WAKEUP = {(byte) 0xb4, 0x0d, (byte) 0xd1, 0x00};
    private static final byte[] AP_SLEEP = {(byte) 0xb4, 0x0d, (byte) 0xd2, 0x00};

    private final SensorManager mSensorManager;
    private final DisplayManager mDisplayManager;
    private final Handler mHandler;
    private final Sensor mSensor;

    private boolean mPanelOn = true;

    SensorHubContext(Context context, Handler handler) {
        mSensorManager = context.getSystemService(SensorManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mHandler = handler;
        mSensor = Utils.findSensor(mSensorManager, SENSOR_HUBS);
    }

    void enable() {
        if (mSensor == null) {
            Log.e(TAG, "No sensor hub context sensor");
            return;
        }
        // Context messages are only forwarded to the hub while this sensor is active.
        mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL,
                mHandler);
        mDisplayManager.registerDisplayListener(this, mHandler);
    }

    void disable() {
        if (mSensor == null) {
            return;
        }
        mDisplayManager.unregisterDisplayListener(this);
        mSensorManager.unregisterListener(this);
    }

    void onScreenOn() {
        send(LCD_INTENT_ON);
        send(AP_WAKEUP);
    }

    void onScreenOff() {
        send(AP_SLEEP);
        send(LCD_INTENT_OFF);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        boolean panelOn = mDisplayManager.getDisplay(displayId).getState() != Display.STATE_OFF;
        if (panelOn != mPanelOn) {
            mPanelOn = panelOn;
            send(panelOn ? LCD_PANEL_ON : LCD_PANEL_OFF);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void send(byte[] message) {
        if (mSensor == null) {
            return;
        }
        // The sensor HAL takes the message length followed by one value per byte.
        float[] data = new float[message.length + 1];
        data[0] = message.length;
        for (int i = 0; i < message.length; i++) {
            data[i + 1] = message[i] & 0xff;
        }
        mSensorManager.setOperationParameter(SensorAdditionalInfo.createCustomInfo(
                mSensor, SensorAdditionalInfo.TYPE_CUSTOM_INFO, data));
    }
}
