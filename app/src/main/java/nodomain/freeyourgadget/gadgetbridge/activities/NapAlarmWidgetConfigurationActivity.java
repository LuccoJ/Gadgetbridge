/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.NapAlarmWidget;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.WidgetPreferenceStorage;

/**
 * Configures a {@link NapAlarmWidget}: first the device, then the amount of sleep to wait for.
 */
public class NapAlarmWidgetConfigurationActivity extends Activity implements GBActivity {
    private static final int[] DURATIONS_MINUTES = {10, 15, 20, 25, 30, 45, 60, 90};

    int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AbstractGBActivity.init(this, AbstractGBActivity.NO_ACTIONBAR);

        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            mAppWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        Intent resultValueCanceled = new Intent();
        resultValueCanceled.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_CANCELED, resultValueCanceled);

        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        // There is no capability for "fell asleep" events, so this is only an approximation of the
        // devices able to report them
        final List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices().stream()
                .filter(device -> {
                    final DeviceCoordinator coordinator = device.getDeviceCoordinator();
                    return coordinator.getAlarmSlotCount(device) > 0 && coordinator.supportsSleepMeasurement(device);
                }).collect(Collectors.toList());

        if (devices.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.appwidget_nap_alarm_widget_label)
                    .setMessage(R.string.nap_alarm_no_device)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }

        final String[] deviceNames = devices.stream().map(GBDevice::getAliasOrName).toArray(String[]::new);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.widget_settings_select_device_title)
                .setItems(deviceNames, (dialog, which) -> {
                    new WidgetPreferenceStorage().saveWidgetPrefs(getApplicationContext(),
                            String.valueOf(mAppWidgetId), devices.get(which).getAddress());
                    selectDuration();
                })
                .setCancelable(false)
                .show();
    }

    private void selectDuration() {
        final String[] labels = new String[DURATIONS_MINUTES.length];
        for (int i = 0; i < DURATIONS_MINUTES.length; i++) {
            labels[i] = getString(R.string.nap_alarm_duration_minutes, DURATIONS_MINUTES[i]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.nap_alarm_select_duration_title)
                .setItems(labels, (dialog, which) -> {
                    NapAlarmWidget.saveMinutes(mAppWidgetId, DURATIONS_MINUTES[which]);
                    NapAlarmWidget.updateAllWidgets(getApplicationContext());
                    Intent resultValueOk = new Intent();
                    resultValueOk.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
                    setResult(RESULT_OK, resultValueOk);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void setLanguage(Locale language, boolean invalidateLanguage) {
        AndroidUtils.setLanguage(this, language);
    }
}
