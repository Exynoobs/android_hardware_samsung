/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import android.hardware.input.InputManagerGlobal;
import android.os.Looper;
import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

/** Observes all touches on the default display without consuming them. */
class TouchMonitor {

    interface Listener {
        void onTouch(MotionEvent event);
    }

    private final Listener mListener;
    private InputMonitor mInputMonitor;
    private InputEventReceiver mReceiver;

    TouchMonitor(Listener listener) {
        mListener = listener;
    }

    void start() {
        if (mInputMonitor != null) {
            return;
        }
        mInputMonitor = InputManagerGlobal.getInstance()
                .monitorGestureInput("TouchProtection", Display.DEFAULT_DISPLAY);
        mReceiver = new InputEventReceiver(mInputMonitor.getInputChannel(),
                Looper.getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                if (event instanceof MotionEvent) {
                    mListener.onTouch((MotionEvent) event);
                }
                finishInputEvent(event, false);
            }
        };
    }

    void stop() {
        if (mInputMonitor == null) {
            return;
        }
        mReceiver.dispose();
        mReceiver = null;
        mInputMonitor.dispose();
        mInputMonitor = null;
    }
}
