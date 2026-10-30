/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.UserHandle;
import android.provider.Settings;

public final class Utils {

    public static final String SETTING = "screen_off_pocket";

    private Utils() {
    }

    public static boolean isEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(), SETTING, 1,
                UserHandle.USER_CURRENT) != 0;
    }

    public static void setEnabled(Context context, boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(), SETTING, enabled ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    static void startService(Context context) {
        context.startServiceAsUser(new Intent(context, TouchProtectionService.class),
                UserHandle.SYSTEM);
    }

    static Sensor findSensor(SensorManager sensorManager, String type) {
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (type.equals(sensor.getStringType())) {
                return sensor;
            }
        }
        return null;
    }
}
