/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class TouchProtectionService extends Service {

    private static final String TAG = "TouchProtection";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private PocketProtection mPocketProtection;
    private SensorHubContext mSensorHubContext;
    private boolean mEnabled;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                    if (mEnabled) {
                        mSensorHubContext.onScreenOn();
                        mPocketProtection.onScreenOn();
                    }
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    if (mEnabled) {
                        mSensorHubContext.onScreenOff();
                        mPocketProtection.onScreenOff();
                    }
                    break;
                case Intent.ACTION_USER_SWITCHED:
                    update();
                    break;
            }
        }
    };

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            update();
        }
    };

    @Override
    public void onCreate() {
        mPocketProtection = new PocketProtection(this, mHandler);
        if (!mPocketProtection.isSupported()) {
            Log.e(TAG, "Pocket sensors not found");
            stopSelf();
            return;
        }
        mSensorHubContext = new SensorHubContext(this, mHandler);

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        registerReceiver(mReceiver, filter);
        getContentResolver().registerContentObserver(Settings.System.getUriFor(Utils.SETTING),
                false, mSettingObserver, UserHandle.USER_ALL);
        update();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mSensorHubContext == null) {
            return;
        }
        getContentResolver().unregisterContentObserver(mSettingObserver);
        unregisterReceiver(mReceiver);
        setEnabled(false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("TouchProtection enabled=" + mEnabled);
        if (mPocketProtection != null) {
            mPocketProtection.dump(pw);
        }
    }

    private void update() {
        setEnabled(Utils.isEnabled(this));
    }

    private void setEnabled(boolean enabled) {
        if (enabled == mEnabled) {
            return;
        }
        mEnabled = enabled;
        if (enabled) {
            mSensorHubContext.enable();
            mPocketProtection.enable();
        } else {
            mPocketProtection.disable();
            mSensorHubContext.disable();
        }
    }
}
