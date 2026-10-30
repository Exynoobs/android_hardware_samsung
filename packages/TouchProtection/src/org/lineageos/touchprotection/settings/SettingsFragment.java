/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.touchprotection.settings;

import android.os.Bundle;

import androidx.preference.Preference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.lineageos.touchprotection.R;
import org.lineageos.touchprotection.Utils;

public class SettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_ENABLE = "touch_protection_enable";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.touch_protection_settings, rootKey);

        MainSwitchPreference switchBar = findPreference(KEY_ENABLE);
        switchBar.setChecked(Utils.isEnabled(getContext()));
        switchBar.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Utils.setEnabled(getContext(), (Boolean) newValue);
        return true;
    }
}
