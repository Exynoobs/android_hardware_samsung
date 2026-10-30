/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-means clustering of the pocket sensor's proximity readings into an "open" and a
 * "covered" group, used to resolve readings the sensor hub itself could not classify.
 */
class ProximityClusters {

    static final int UNKNOWN = 0;
    static final int IN = 1;
    static final int OUT = 2;

    private static final int MIN_SAMPLES = 11;
    private static final int MAX_SAMPLES = 30;
    private static final int ITERATIONS = 5;
    private static final int MIN_CENTROID_DISTANCE = 400;

    private static class Sample {
        final int value;
        boolean closed;

        Sample(int value) {
            this.value = value;
        }
    }

    private final List<Sample> mSamples = new ArrayList<>();
    private int mOpen;
    private int mClosed;
    private boolean mValid;

    void add(int value) {
        for (Sample sample : mSamples) {
            if (sample.value == value) {
                return;
            }
        }

        Sample sample = new Sample(value);
        if (mValid) {
            sample.closed = isCloser(value);
            mSamples.add(sample);
            trim();
            updateCentroids();
        } else {
            mSamples.add(sample);
            if (mSamples.size() >= MIN_SAMPLES) {
                learn();
            }
            trim();
        }
    }

    int classify(int value) {
        if (!mValid) {
            return UNKNOWN;
        }
        return isCloser(value) ? IN : OUT;
    }

    void clear() {
        mSamples.clear();
        mOpen = 0;
        mClosed = 0;
        mValid = false;
    }

    @Override
    public String toString() {
        return "samples=" + mSamples.size() + " open=" + mOpen + " closed=" + mClosed
                + " valid=" + mValid;
    }

    private void learn() {
        mOpen = Integer.MAX_VALUE;
        mClosed = Integer.MIN_VALUE;
        for (Sample sample : mSamples) {
            mOpen = Math.min(mOpen, sample.value);
            mClosed = Math.max(mClosed, sample.value);
        }
        for (int i = 0; i < ITERATIONS; i++) {
            for (Sample sample : mSamples) {
                sample.closed = isCloser(sample.value);
            }
            updateCentroids();
        }
    }

    private boolean isCloser(int value) {
        return Math.abs(value - mClosed) < Math.abs(value - mOpen);
    }

    private void updateCentroids() {
        long openSum = 0, closedSum = 0;
        int openCount = 0, closedCount = 0;
        for (Sample sample : mSamples) {
            if (sample.closed) {
                closedSum += sample.value;
                closedCount++;
            } else {
                openSum += sample.value;
                openCount++;
            }
        }
        if (openCount > 0) {
            mOpen = (int) (openSum / openCount);
        }
        if (closedCount > 0) {
            mClosed = (int) (closedSum / closedCount);
        }
        mValid = openCount > 0 && closedCount > 0 && mClosed - mOpen >= MIN_CENTROID_DISTANCE;
    }

    private void trim() {
        while (mSamples.size() > MAX_SAMPLES) {
            mSamples.remove(0);
        }
    }
}
